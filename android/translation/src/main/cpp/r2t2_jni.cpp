#include <jni.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <algorithm>
#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

void initialize_llama();

namespace {
constexpr int batch_size = 256;
constexpr int sample_rate = 16000;

struct Recognizer {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
    std::unique_ptr<mtmd_context, decltype(&mtmd_free)> audio{nullptr, mtmd_free};
    std::atomic<bool> cancelled{false};
    std::atomic<bool> input_complete{false};
    bool final = false;
};

bool cancelled(void * data) {
    const auto & recognizer = *static_cast<Recognizer *>(data);
    return recognizer.cancelled.load(std::memory_order_relaxed) ||
        (!recognizer.final && recognizer.input_complete.load(std::memory_order_relaxed));
}

std::string bytes(JNIEnv * env, jbyteArray input) {
    std::string text(static_cast<size_t>(env->GetArrayLength(input)), '\0');
    env->GetByteArrayRegion(input, 0, static_cast<jsize>(text.size()), reinterpret_cast<jbyte *>(text.data()));
    return text;
}

void fail(JNIEnv * env, const char * message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

jbyteArray byte_array(JNIEnv * env, std::string_view text) {
    auto result = env->NewByteArray(static_cast<jsize>(text.size()));
    if (result && !text.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()),
                                reinterpret_cast<const jbyte *>(text.data()));
    }
    return result;
}

struct Preview {
    JNIEnv * env;
    jobject callback;
    jmethodID publish;
    size_t published = 0;

    bool update(std::string_view text) {
        if (!callback || text.size() <= published) return true;
        auto output = byte_array(env, text);
        if (!output) return false;
        env->CallVoidMethod(callback, publish, output);
        env->DeleteLocalRef(output);
        published = text.size();
        return !env->ExceptionCheck();
    }
};

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::string text(64, '\0');
    int size = llama_token_to_piece(vocab, token, text.data(), static_cast<int>(text.size()), 0, false);
    if (size < 0) {
        text.resize(-size);
        size = llama_token_to_piece(vocab, token, text.data(), static_cast<int>(text.size()), 0, false);
    }
    if (size < 0) throw std::runtime_error("R2T2 token could not be decoded");
    text.resize(size);
    return text;
}

std::string generate(Recognizer & recognizer, const std::vector<float> & samples,
                     const std::string & prefix, bool final, Preview & preview) {
    auto * ctx = recognizer.context.get();
    auto * audio = recognizer.audio.get();
    recognizer.final = final;
    if (cancelled(&recognizer)) return {};
    llama_memory_clear(llama_get_memory(ctx), true);
    const std::string prompt = std::string("<|im_start|>system\n<|im_end|>\n<|im_start|>user\n") +
        mtmd_default_marker() + "<|im_end|>\n<|im_start|>assistant\n" + prefix;
    const mtmd_input_text input{prompt.data(), prompt.size(), false, true};
    std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmap(
        mtmd_bitmap_init_from_audio(samples.size(), samples.data()), mtmd_bitmap_free);
    const mtmd_bitmap * media[] = {bitmap.get()};
    std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(
        mtmd_input_chunks_init(), mtmd_input_chunks_free);
    if (!bitmap || !chunks || mtmd_tokenize(audio, chunks.get(), &input, media, 1) != 0) {
        throw std::runtime_error("R2T2 audio could not be tokenized");
    }
    llama_pos position = 0;
    const size_t chunk_count = mtmd_input_chunks_size(chunks.get());
    for (size_t i = 0; i < chunk_count; ++i) {
        if (cancelled(&recognizer)) return {};
        auto * chunk = mtmd_input_chunks_get(chunks.get(), i);
        if (mtmd_helper_eval_chunk_single(audio, ctx, chunk, position, 0, batch_size,
                                          i + 1 == chunk_count, &position) != 0) {
            throw std::runtime_error("R2T2 could not encode audio or evaluate the prompt");
        }
    }

    const auto * vocab = llama_model_get_vocab(recognizer.model.get());
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_init_greedy(), llama_sampler_free);
    std::string text;
    size_t before_last_token = 0;
    constexpr int token_limit = 256;
    for (int i = 0; i < token_limit; ++i) {
        if (cancelled(&recognizer)) return {};
        auto token = llama_sampler_sample(sampler.get(), ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        before_last_token = text.size();
        text += token_piece(vocab, token);
        // The model appends a control marker when it needs more audio.
        const size_t wait = text.find('|');
        if (wait != std::string::npos) {
            text.resize(wait);
            if (!final) text.resize(std::min(text.size(), before_last_token));
            return text;
        }
        // Keep the newest token provisional until another token has been produced.
        if (!preview.update(std::string_view(text).substr(0, before_last_token))) return {};
        if (i + 1 == token_limit) throw std::runtime_error("R2T2 token limit exceeded");
        if (llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0) {
            throw std::runtime_error("R2T2 text decoding failed");
        }
    }
    if (!final) text.resize(before_last_token);
    return text;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_load(JNIEnv * env, jobject, jbyteArray model, jbyteArray projector) {
    try {
        initialize_llama();
        auto recognizer = std::make_unique<Recognizer>();
        // The speaker and translation backends share llama.cpp, but own separate weights and state.
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        recognizer->model.reset(llama_model_load_from_file(bytes(env, model).c_str(), params));
        if (!recognizer->model) throw std::runtime_error("R2T2 model could not be loaded");
        auto audio = mtmd_context_params_default();
        audio.use_gpu = false;
        audio.n_threads = 4;
        audio.print_timings = false;
        audio.warmup = false;
        recognizer->audio.reset(mtmd_init_from_file(bytes(env, projector).c_str(), recognizer->model.get(), audio));
        if (!recognizer->audio || !mtmd_support_audio(recognizer->audio.get()) ||
            mtmd_get_audio_sample_rate(recognizer->audio.get()) != sample_rate) {
            throw std::runtime_error("R2T2 audio projector could not be loaded");
        }
        auto config = llama_context_default_params();
        config.n_ctx = 2048;
        config.n_batch = batch_size;
        config.n_ubatch = batch_size;
        config.n_threads = 2;
        config.n_threads_batch = audio.n_threads;
        config.abort_callback = cancelled;
        config.abort_callback_data = recognizer.get();
        recognizer->context.reset(llama_init_from_model(recognizer->model.get(), config));
        if (!recognizer->context) throw std::runtime_error("R2T2 context could not be created");
        return reinterpret_cast<jlong>(recognizer.release());
    } catch (const std::exception & error) {
        fail(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_reset(JNIEnv *, jobject, jlong handle) {
    auto & recognizer = *reinterpret_cast<Recognizer *>(handle);
    recognizer.cancelled.store(false, std::memory_order_relaxed);
    recognizer.input_complete.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_cancel(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Recognizer *>(handle)->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_finishInput(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Recognizer *>(handle)->input_complete.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_decode(JNIEnv * env, jobject, jlong handle, jfloatArray pcm,
                                                 jbyteArray prefix, jboolean final, jobject callback) {
    auto & recognizer = *reinterpret_cast<Recognizer *>(handle);
    try {
        std::vector<float> samples(env->GetArrayLength(pcm));
        env->GetFloatArrayRegion(pcm, 0, static_cast<jsize>(samples.size()), samples.data());
        auto prompt_prefix = bytes(env, prefix);
        if (env->ExceptionCheck()) return nullptr;
        jmethodID publish = nullptr;
        if (callback) {
            auto type = env->GetObjectClass(callback);
            if (!type) return nullptr;
            publish = env->GetMethodID(type, "publish", "([B)V");
            env->DeleteLocalRef(type);
            if (!publish) return nullptr;
        }
        Preview preview{env, callback, publish};
        const auto text = generate(recognizer, samples, prompt_prefix, final, preview);
        if (env->ExceptionCheck() || cancelled(&recognizer)) return nullptr;
        return byte_array(env, text);
    } catch (const std::exception & error) {
        if (!cancelled(&recognizer)) fail(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_destroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Recognizer *>(handle);
}

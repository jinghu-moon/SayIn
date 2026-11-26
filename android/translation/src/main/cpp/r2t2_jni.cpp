#include <jni.h>
#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "r2t2_mtmd.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

void initialize_llama();

namespace {
constexpr int batch_size = 256;
constexpr int sample_rate = 16000;
using Clock = std::chrono::steady_clock;

enum class Stage {
    features, encode, audio_prefill, text_prefill, generate, tokens, reused_audio, reused_positions, count
};

struct Timings {
    bool enabled;
    jlong values[static_cast<size_t>(Stage::count)]{};

    Clock::time_point start() const { return enabled ? Clock::now() : Clock::time_point{}; }
    void record(Stage stage, Clock::time_point started) {
        if (enabled) {
            values[static_cast<size_t>(stage)] +=
                std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - started).count();
        }
    }
};

// One causal prompt unit: text tokens, or the mel and encoder output of one attention window.
struct PromptChunk {
    std::vector<llama_token> tokens;
    std::vector<float> mel;
    std::vector<std::vector<float>> conv_chunks; // per-100-frame conv_out output [tokens_c][conv_dim]
    std::vector<float> embedding;
    int frames = 0;
    llama_pos width = 0;
    llama_pos end_position = 0;

    bool same_input(const PromptChunk & other) const {
        return tokens == other.tokens && frames == other.frames && width == other.width &&
            mel.size() == other.mel.size() &&
            std::memcmp(mel.data(), other.mel.data(), mel.size() * sizeof(float)) == 0;
    }

    bool same_conv_subchunk(const PromptChunk & other, int subchunk, int bins) const {
        constexpr int chunk_frames = 100;
        const int off = subchunk * chunk_frames;
        const int len = std::clamp(frames - off, 0, chunk_frames);
        const int other_len = std::clamp(other.frames - off, 0, chunk_frames);
        if (len <= 0 || len != other_len) return false;
        if (static_cast<size_t>(subchunk) >= conv_chunks.size() || conv_chunks[subchunk].empty()) return false;
        for (int b = 0; b < bins; ++b) {
            const float * row_a = mel.data() + static_cast<size_t>(b) * frames + off;
            const float * row_b = other.mel.data() + static_cast<size_t>(b) * other.frames + off;
            if (std::memcmp(row_a, row_b, static_cast<size_t>(len) * sizeof(float)) != 0) return false;
        }
        return true;
    }
};

struct Recognizer {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
    std::unique_ptr<mtmd_context, decltype(&mtmd_free)> audio{nullptr, mtmd_free};
    std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch{nullptr, llama_batch_ext_free};
    std::vector<llama_token> head;
    std::vector<PromptChunk> prompt;
    std::vector<llama_token> evaluated_tail;
    llama_pos evaluated_tail_start = 0;
    size_t evaluated = 0;
    std::atomic<bool> cancelled{false};
    std::atomic<int> final_revision{0};
    int decode_revision = 0;
    bool final = false;

    void reset_prompt() {
        prompt.clear();
        evaluated_tail.clear();
        evaluated_tail_start = 0;
        evaluated = 0;
    }
};

bool cancelled(void * data) {
    const auto & recognizer = *static_cast<Recognizer *>(data);
    return recognizer.cancelled.load(std::memory_order_relaxed) ||
        (!recognizer.final && recognizer.final_revision.load(std::memory_order_relaxed) != recognizer.decode_revision);
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

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    const auto length = static_cast<int32_t>(text.size());
    const auto count = -llama_tokenize(vocab, text.data(), length, nullptr, 0, false, true);
    std::vector<llama_token> tokens(static_cast<size_t>(std::max(count, 0)));
    if (count <= 0 || llama_tokenize(vocab, text.data(), length, tokens.data(), count, false, true) != count) {
        throw std::runtime_error("R2T2 prompt could not be tokenized");
    }
    return tokens;
}

// The chat header, one chunk per encoder attention window, then the assistant turn with the stable prefix.
std::vector<PromptChunk> prompt_input(const Recognizer & recognizer, const std::vector<float> & samples,
                                      const std::string & prefix) {
    auto * audio = recognizer.audio.get();
    const int frames = r2t2_mel_frames(samples.size());
    const int bins = r2t2_mel_bins(audio);
    std::vector<float> mel(static_cast<size_t>(frames) * bins);
    if (!r2t2_log_mel(audio, samples.data(), samples.size(), mel.data())) {
        throw std::runtime_error("R2T2 audio features could not be computed");
    }
    std::vector<PromptChunk> chunks;
    chunks.emplace_back().tokens = recognizer.head;
    for (int offset = 0; offset < frames; offset += r2t2_window_frames()) {
        const int length = std::min(r2t2_window_frames(), frames - offset);
        auto & window = chunks.emplace_back();
        window.frames = length;
        window.width = r2t2_window_tokens(length);
        window.mel.resize(static_cast<size_t>(length) * bins);
        for (int bin = 0; bin < bins; ++bin) {
            const auto * row = mel.data() + static_cast<size_t>(bin) * frames + offset;
            std::copy(row, row + length, window.mel.begin() + static_cast<size_t>(bin) * length);
        }
    }
    chunks.emplace_back().tokens = tokenize(llama_model_get_vocab(recognizer.model.get()),
                                            "<|audio_end|><|im_end|>\n<|im_start|>assistant\n" + prefix);
    return chunks;
}

// Audio embeddings take the same position in every M-RoPE section.
int32_t decode_audio(Recognizer & recognizer, const PromptChunk & chunk, llama_pos & position) {
    auto * batch = recognizer.batch.get();
    const auto n_embd = static_cast<size_t>(llama_model_n_embd_inp(recognizer.model.get()));
    for (llama_pos offset = 0; offset < chunk.width; offset += batch_size) {
        llama_batch_ext_clear(batch);
        for (llama_pos i = offset; i < std::min<llama_pos>(chunk.width, offset + batch_size); ++i) {
            const auto index = llama_batch_ext_add_embd(batch, 0, {chunk.embedding.data() + i * n_embd, 1, n_embd});
            const llama_pos pos[] = {position, position, position, position};
            if (index < 0 || !llama_batch_ext_set_pos(batch, index, pos)) return -1;
            ++position;
        }
        if (const auto result = llama_process(recognizer.context.get(), LLAMA_PROCESS_TYPE_DECODE, batch)) return result;
    }
    return 0;
}

int32_t decode_text(Recognizer & recognizer, const std::vector<llama_token> & tokens, size_t start_index,
                    llama_pos & position, bool logits) {
    auto * batch = recognizer.batch.get();
    const auto count = static_cast<llama_pos>(tokens.size());
    for (llama_pos offset = static_cast<llama_pos>(start_index); offset < count; offset += batch_size) {
        llama_batch_ext_clear(batch);
        int32_t index = -1;
        for (llama_pos i = offset; i < std::min<llama_pos>(count, offset + batch_size); ++i) {
            index = llama_batch_ext_add_token(batch, 0, tokens[i]);
            if (index < 0 || !llama_batch_ext_set_pos(batch, index, &position)) return -1;
            ++position;
        }
        if (logits && offset + batch_size >= count && !llama_batch_ext_set_output_logits(batch, index, true)) return -1;
        if (const auto result = llama_process(recognizer.context.get(), LLAMA_PROCESS_TYPE_DECODE, batch)) return result;
    }
    return 0;
}

bool encode_audio_chunk(mtmd_context * audio, const PromptChunk & prev_opt, bool has_prev, PromptChunk & chunk,
                        std::vector<float> & embedding_out, const std::atomic<bool> & is_cancelled_flag,
                        bool (*abort_fn)(void *), void * abort_data) {
    constexpr int subchunk_frames = 100;
    const int bins = r2t2_mel_bins(audio);
    const int conv_dim = r2t2_conv_dim(audio);
    const int n_sub = (chunk.frames + subchunk_frames - 1) / subchunk_frames;
    chunk.conv_chunks.assign(static_cast<size_t>(n_sub), {});

    int first_dirty = n_sub;
    for (int c = 0; c < n_sub; ++c) {
        if (has_prev && prev_opt.same_conv_subchunk(chunk, c, bins)) {
            chunk.conv_chunks[c] = prev_opt.conv_chunks[c];
        } else {
            first_dirty = c;
            break;
        }
    }
    // Check if any chunks after first_dirty match too; run conv once on the contiguous dirty span [first_dirty .. last_dirty].
    int last_dirty = -1;
    if (first_dirty < n_sub) {
        last_dirty = first_dirty;
        for (int c = n_sub - 1; c > first_dirty; --c) {
            if (has_prev && prev_opt.same_conv_subchunk(chunk, c, bins)) {
                chunk.conv_chunks[c] = prev_opt.conv_chunks[c];
            } else {
                last_dirty = c;
                break;
            }
        }
        const int off = first_dirty * subchunk_frames;
        const int end_frame = std::min((last_dirty + 1) * subchunk_frames, chunk.frames);
        const int dirty_frames = end_frame - off;
        const int dirty_tokens = r2t2_window_tokens(dirty_frames);
        std::vector<float> dirty_mel(static_cast<size_t>(bins) * dirty_frames);
        for (int b = 0; b < bins; ++b) {
            const float * src = chunk.mel.data() + static_cast<size_t>(b) * chunk.frames + off;
            std::copy(src, src + dirty_frames, dirty_mel.begin() + static_cast<size_t>(b) * dirty_frames);
        }
        std::vector<float> dirty_conv(static_cast<size_t>(dirty_tokens) * conv_dim);
        if (!r2t2_conv_chunks(audio, dirty_mel.data(), dirty_frames, dirty_conv.data())) {
            return false;
        }
        int tok_off = 0;
        for (int c = first_dirty; c <= last_dirty; ++c) {
            const int c_frames = std::min(subchunk_frames, chunk.frames - c * subchunk_frames);
            const int c_tokens = r2t2_window_tokens(c_frames);
            const size_t floats = static_cast<size_t>(c_tokens) * conv_dim;
            chunk.conv_chunks[c].assign(dirty_conv.begin() + static_cast<size_t>(tok_off) * conv_dim,
                                        dirty_conv.begin() + static_cast<size_t>(tok_off) * conv_dim + floats);
            tok_off += c_tokens;
        }
    }
    if (is_cancelled_flag.load(std::memory_order_relaxed) || abort_fn(abort_data)) return false;

    std::vector<float> conv_all;
    conv_all.reserve(static_cast<size_t>(chunk.width) * conv_dim);
    for (int c = 0; c < n_sub; ++c) {
        conv_all.insert(conv_all.end(), chunk.conv_chunks[c].begin(), chunk.conv_chunks[c].end());
    }
    return r2t2_encode_conv(audio, conv_all.data(), static_cast<int32_t>(chunk.width), embedding_out.data());
}

std::string generate(Recognizer & recognizer, const std::vector<float> & samples,
                     const std::string & prefix, int token_budget, Preview & preview, Timings & timings) {
    auto * ctx = recognizer.context.get();
    auto * audio = recognizer.audio.get();
    const bool final = recognizer.final;
    if (cancelled(&recognizer)) return {};
    // Shorter than one mel frame, the segment carries no audio.
    if (r2t2_mel_frames(samples.size()) == 0) return {};
    const auto features_started = timings.start();
    auto chunks = prompt_input(recognizer, samples, prefix);
    timings.record(Stage::features, features_started);
    if (cancelled(&recognizer)) return {};

    auto & previous = recognizer.prompt;
    const size_t chunk_count = chunks.size();
    llama_pos position = 0;
    size_t keep = 0;
    // Never retain the final text chunk. It must produce logits for this round.
    for (; keep < recognizer.evaluated && keep + 1 < chunk_count && previous[keep].same_input(chunks[keep]); ++keep) {
        position = previous[keep].end_position;
    }
    auto * memory = llama_get_memory(ctx);
    size_t tail_keep = 0;
    if (keep == chunk_count - 1 && !recognizer.evaluated_tail.empty() && !chunks.back().tokens.empty()) {
        const auto & prev_tail = recognizer.evaluated_tail;
        const auto & cur_tail = chunks.back().tokens;
        const size_t max_common = std::min(prev_tail.size(), cur_tail.size() - 1);
        while (tail_keep < max_common && prev_tail[tail_keep] == cur_tail[tail_keep]) ++tail_keep;
        if (tail_keep > 0) position = recognizer.evaluated_tail_start + static_cast<llama_pos>(tail_keep);
    }
    if (keep == 0 || !llama_memory_seq_rm(memory, 0, position, -1)) {
        llama_memory_clear(memory, true);
        keep = 0;
        position = 0;
        tail_keep = 0;
    }
    if (tail_keep == 0) recognizer.evaluated_tail.clear();
    timings.values[static_cast<size_t>(Stage::reused_positions)] = position;
    // A window's encoder output depends on its own mel only; unchanged 100-frame conv chunks also carry over.
    const size_t shared_chunks = std::min(chunk_count, previous.size());
    std::vector<PromptChunk> prev_conv(shared_chunks);
    for (size_t i = 0; i < shared_chunks; ++i) {
        if (chunks[i].mel.empty()) continue;
        if (!previous[i].embedding.empty() && previous[i].same_input(chunks[i])) {
            chunks[i].embedding = std::move(previous[i].embedding);
            chunks[i].conv_chunks = std::move(previous[i].conv_chunks);
            ++timings.values[static_cast<size_t>(Stage::reused_audio)];
        } else if (!previous[i].conv_chunks.empty()) {
            prev_conv[i] = std::move(previous[i]);
        }
    }
    for (size_t i = 0; i < keep; ++i) chunks[i].end_position = previous[i].end_position;
    previous = std::move(chunks);
    recognizer.evaluated = keep;

    const auto n_embd = static_cast<size_t>(llama_model_n_embd_inp(recognizer.model.get()));
    for (size_t i = keep; i < chunk_count; ++i) {
        if (cancelled(&recognizer)) return {};
        auto & chunk = previous[i];
        const bool audio_chunk = !chunk.mel.empty();
        if (audio_chunk && chunk.embedding.empty()) {
            std::vector<float> embedding(static_cast<size_t>(chunk.width) * n_embd);
            const auto encode_started = timings.start();
            const bool has_prev = i < prev_conv.size() && !prev_conv[i].conv_chunks.empty();
            const bool encoded = encode_audio_chunk(
                audio, has_prev ? prev_conv[i] : PromptChunk{}, has_prev, chunk, embedding,
                recognizer.cancelled, cancelled, &recognizer);
            timings.record(Stage::encode, encode_started);
            if (!encoded) {
                if (cancelled(&recognizer)) return {};
                throw std::runtime_error("R2T2 could not encode audio");
            }
            chunk.embedding = std::move(embedding);
        }
        if (cancelled(&recognizer)) return {};
        const auto prefill_started = timings.start();
        const bool is_tail = i + 1 == chunk_count;
        int32_t decoded = 0;
        if (audio_chunk) {
            decoded = decode_audio(recognizer, chunk, position);
        } else {
            const size_t start_tok = is_tail ? tail_keep : 0;
            if (is_tail && tail_keep == 0) recognizer.evaluated_tail_start = position;
            decoded = decode_text(recognizer, chunk.tokens, start_tok, position, is_tail);
        }
        timings.record(audio_chunk ? Stage::audio_prefill : Stage::text_prefill, prefill_started);
        if (decoded != 0) {
            if (cancelled(&recognizer)) return {};
            throw std::runtime_error("R2T2 could not evaluate the prompt");
        }
        if (is_tail && !audio_chunk) {
            recognizer.evaluated_tail = chunk.tokens;
        }
        chunk.end_position = position;
        recognizer.evaluated = i + 1;
    }

    const auto generation_started = timings.start();
    const auto * vocab = llama_model_get_vocab(recognizer.model.get());
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_init_greedy(), llama_sampler_free);
    std::string text;
    size_t before_last_token = 0;
    for (int i = 0; i < token_budget; ++i) {
        if (cancelled(&recognizer)) break;
        auto token = llama_sampler_sample(sampler.get(), ctx, -1);
        ++timings.values[static_cast<size_t>(Stage::tokens)];
        if (llama_vocab_is_eog(vocab, token)) break;
        before_last_token = text.size();
        text += token_piece(vocab, token);
        // The model appends a control marker when it needs more audio.
        const size_t wait = text.find('|');
        if (wait != std::string::npos) {
            text.resize(wait);
            break;
        }
        // Keep the newest token provisional until another token has been produced.
        if (!preview.update(std::string_view(text).substr(0, before_last_token))) break;
        if (i + 1 == token_budget) {
            if (final) throw std::runtime_error("R2T2 token limit exceeded");
            break;
        }
        if (llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0) {
            if (cancelled(&recognizer)) break;
            throw std::runtime_error("R2T2 text decoding failed");
        }
    }
    timings.record(Stage::generate, generation_started);
    if (!final) text.resize(std::min(text.size(), before_last_token));
    return text;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_load(JNIEnv * env, jobject, jbyteArray model, jbyteArray projector,
                                                jint prefill_threads, jint encode_threads, jint gen_threads,
                                                jboolean gpu_decoder, jboolean gpu_projector) {
    try {
        if (prefill_threads < 1 || prefill_threads > 8 ||
            encode_threads < 1 || encode_threads > 8 ||
            gen_threads < 1 || gen_threads > 8 ||
            (gpu_projector && !gpu_decoder)) {
            throw std::runtime_error("Invalid R2T2 backend configuration");
        }
        initialize_llama();
        auto recognizer = std::make_unique<Recognizer>();
        // The speaker and translation backends share llama.cpp, but own separate weights and state.
        auto params = llama_model_default_params();
        ggml_backend_dev_t devices[] = {nullptr, nullptr};
        if (gpu_decoder) {
            devices[0] = ggml_backend_dev_by_name("Vulkan0");
            if (!devices[0]) throw std::runtime_error("R2T2 Vulkan device is unavailable");
        }
        params.devices = devices;
        params.n_gpu_layers = gpu_decoder ? -1 : 0;
        recognizer->model.reset(llama_model_load_from_file(bytes(env, model).c_str(), params));
        if (!recognizer->model) throw std::runtime_error("R2T2 model could not be loaded");
        auto audio = mtmd_context_params_default();
        audio.use_gpu = gpu_projector;
        audio.n_threads = encode_threads;
        audio.print_timings = false;
        audio.warmup = false;
        recognizer->audio.reset(mtmd_init_from_file(bytes(env, projector).c_str(), recognizer->model.get(), audio));
        if (!recognizer->audio || !mtmd_support_audio(recognizer->audio.get()) ||
            mtmd_get_audio_sample_rate(recognizer->audio.get()) != sample_rate) {
            throw std::runtime_error("R2T2 audio projector could not be loaded");
        }
        r2t2_set_encoder_threads(recognizer->audio.get(), encode_threads);
        r2t2_set_encoder_abort(recognizer->audio.get(), cancelled, recognizer.get());
        auto config = llama_context_default_params();
        config.n_ctx = 2048;
        config.n_batch = batch_size;
        config.n_ubatch = batch_size;
        config.n_threads = gen_threads;
        config.n_threads_batch = prefill_threads;
#ifdef SAYIN_R2T2_VULKAN_BENCHMARK
        if (gpu_decoder && std::getenv("SAYIN_R2T2_DISABLE_FLASH_ATTENTION")) {
            config.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        }
#endif
        config.abort_callback = cancelled;
        config.abort_callback_data = recognizer.get();
        recognizer->context.reset(llama_init_from_model(recognizer->model.get(), config));
        if (!recognizer->context) throw std::runtime_error("R2T2 context could not be created");
        recognizer->batch.reset(llama_batch_ext_init(recognizer->context.get()));
        if (!recognizer->batch || !mtmd_decode_use_mrope(recognizer->audio.get())) {
            throw std::runtime_error("R2T2 decoder batch could not be created");
        }
        recognizer->head = tokenize(llama_model_get_vocab(recognizer->model.get()),
                                    "<|im_start|>system\n<|im_end|>\n<|im_start|>user\n<|audio_start|>");
        return reinterpret_cast<jlong>(recognizer.release());
    } catch (const std::exception & error) {
        fail(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_reset(JNIEnv *, jobject, jlong handle) {
    auto & recognizer = *reinterpret_cast<Recognizer *>(handle);
    recognizer.reset_prompt();
    recognizer.cancelled.store(false, std::memory_order_relaxed);
    recognizer.final_revision.store(0, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_cancel(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Recognizer *>(handle)->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_prioritizeFinal(JNIEnv *, jobject, jlong handle, jint revision) {
    reinterpret_cast<Recognizer *>(handle)->final_revision.store(revision, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_decode(JNIEnv * env, jobject, jlong handle, jfloatArray pcm,
                                                 jbyteArray prefix, jboolean final, jint revision,
                                                 jint token_budget, jobject callback, jlongArray timing_output) {
    auto & recognizer = *reinterpret_cast<Recognizer *>(handle);
    recognizer.final = final;
    recognizer.decode_revision = revision;
    try {
        if (token_budget <= 0 || token_budget > 256) throw std::runtime_error("Invalid R2T2 token budget");
        const auto timing_count = static_cast<jsize>(Stage::count);
        if (timing_output && env->GetArrayLength(timing_output) != timing_count) {
            throw std::runtime_error("Invalid R2T2 timing buffer");
        }
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
        Timings timings{timing_output != nullptr};
        const auto text = generate(recognizer, samples, prompt_prefix, token_budget, preview, timings);
        if (final || env->ExceptionCheck()) recognizer.reset_prompt();
        if (timing_output && !env->ExceptionCheck()) env->SetLongArrayRegion(timing_output, 0, timing_count, timings.values);
        if (env->ExceptionCheck() || cancelled(&recognizer)) return nullptr;
        return byte_array(env, text);
    } catch (const std::exception & error) {
        recognizer.reset_prompt();
        if (!cancelled(&recognizer)) fail(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_seeyuer_sayin_translation_R2t2Bindings_destroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Recognizer *>(handle);
}

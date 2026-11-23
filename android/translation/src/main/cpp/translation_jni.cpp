#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
enum Status { Complete, Cancelled, Truncated, Error, InputTooLong, Timeout };
struct Session {
    std::atomic<bool> cancelled{false};
    llama_model * model = nullptr;
    int kind = -1;
    int threads = 4;
    ~Session() { if (model) llama_model_free(model); }
};
struct Events {
    JNIEnv * env;
    jobject target;
    jmethodID progress;
    jmethodID piece;
    jmethodID stage;
    bool failed = false;
    Events(JNIEnv * e, jobject t) : env(e), target(t) {
        auto cls = env->GetObjectClass(t);
        progress = env->GetMethodID(cls, "progress", "(F)V");
        piece = env->GetMethodID(cls, "piece", "([B)V");
        stage = env->GetMethodID(cls, "stage", "(I)V");
        env->DeleteLocalRef(cls);
    }
    void check() { if (env->ExceptionCheck()) { env->ExceptionClear(); failed = true; } }
};
struct Loading { Session * session; Events * events; };
bool load_progress(float value, void * opaque) {
    auto & state = *static_cast<Loading *>(opaque);
    if (state.session->cancelled.load()) return false;
    state.events->env->CallVoidMethod(state.events->target, state.events->progress, value);
    state.events->check();
    return !state.events->failed && !state.session->cancelled.load();
}
struct Abort { Session * session; Clock::time_point deadline; };
bool abort_decode(void * opaque) {
    auto & state = *static_cast<Abort *>(opaque);
    return state.session->cancelled.load() || Clock::now() >= state.deadline;
}
Session * session(jlong handle) { return reinterpret_cast<Session *>(handle); }
std::string bytes(JNIEnv * env, jbyteArray array) {
    const auto size = env->GetArrayLength(array);
    std::string out(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte *>(out.data()));
    return out;
}
jbyteArray byte_array(JNIEnv * env, const std::string & value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool special) {
    int count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, false, special);
    if (count == 0) return {};
    if (count > 0) throw std::runtime_error("tokenizer");
    std::vector<llama_token> result(static_cast<size_t>(-count));
    count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), result.data(), static_cast<int32_t>(result.size()), false, special);
    if (count < 0) throw std::runtime_error("tokenizer");
    result.resize(static_cast<size_t>(count));
    return result;
}
void append(std::vector<llama_token> & to, const std::vector<llama_token> & from) { to.insert(to.end(), from.begin(), from.end()); }
int64_t micros(Clock::time_point start) { return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count(); }
struct Result {
    int status = Error;
    std::string output;
    int source_tokens = 0, prompt_tokens = 0, generated_tokens = 0;
    int64_t prefill_us = 0, first_us = 0, total_us = 0;
};
jobject java_result(JNIEnv * env, const Result & r) {
    auto cls = env->FindClass("seeyuer/sayin/translation/NativeResult");
    auto ctor = env->GetMethodID(cls, "<init>", "(I[BIIIJJJ)V");
    auto output = byte_array(env, r.output);
    auto result = env->NewObject(cls, ctor, r.status, output, r.source_tokens, r.prompt_tokens, r.generated_tokens,
                                static_cast<jlong>(r.prefill_us), static_cast<jlong>(r.first_us), static_cast<jlong>(r.total_us));
    env->DeleteLocalRef(output);
    env->DeleteLocalRef(cls);
    return result;
}
// This model family uses scalar positions. Every request creates a new context and sampler.
void fill(llama_batch_ext * batch, const llama_token * tokens, int count, int offset) {
    llama_batch_ext_clear(batch);
    for (int i = 0; i < count; ++i) {
        const auto index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = offset + i;
        if (index < 0 || !llama_batch_ext_set_pos(batch, index, &pos)) throw std::runtime_error("batch");
    }
    if (!llama_batch_ext_set_output_logits(batch, count - 1, true)) throw std::runtime_error("logits");
}
Result translate(Session & s, const std::string & source, int limit, int timeout_ms, Events & events) {
    Result r;
    const auto start = Clock::now();
    Abort abort{&s, start + std::chrono::milliseconds(timeout_ms)};
    auto stopped = [&]() { return s.cancelled.load() ? Cancelled : (Clock::now() >= abort.deadline ? Timeout : Error); };
    if (!s.model || source.empty() || source.size() > 256000 || limit < 1 || limit > 2048 || timeout_ms < 1) return r;
    const auto * vocab = llama_model_get_vocab(s.model);
    // Never parse special tokens in user content. Only the fixed official framing is privileged.
    const auto content = tokenize(vocab, source, false);
    r.source_tokens = static_cast<int>(content.size());
    if (content.size() > 1024) { r.status = InputTooLong; return r; }
    std::vector<llama_token> prompt{llama_vocab_bos(vocab)};
    if (s.kind == 0) {
        append(prompt, tokenize(vocab, "<｜hy_User｜>将以下文本翻译成英语,注意只需要输出翻译后的结果,不要额外解释:\n\n", true));
        append(prompt, content);
        append(prompt, tokenize(vocab, "<｜hy_Assistant｜>", true));
    } else {
        append(prompt, tokenize(vocab, "<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>zh-hans<|plamo:msg|>", true));
        append(prompt, content);
        append(prompt, tokenize(vocab, "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>en<|plamo:msg|>", true));
    }
    r.prompt_tokens = static_cast<int>(prompt.size());
    if (prompt.size() + static_cast<size_t>(limit) > 4096) { r.status = InputTooLong; return r; }
    if (abort_decode(&abort)) { r.status = stopped(); return r; }
    auto params = llama_context_default_params();
    params.n_ctx = 4096; params.n_batch = 256; params.n_ubatch = 128;
    params.n_threads = s.threads; params.n_threads_batch = s.threads;
    params.abort_callback = abort_decode; params.abort_callback_data = &abort;
    params.no_perf = true;
    std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(s.model, params), llama_free);
    if (!context) return r;
    std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(context.get()), llama_batch_ext_free);
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    if (!batch || !sampler) return r;
    int pos = 0;
    events.env->CallVoidMethod(events.target, events.stage, 0); events.check();
    if (events.failed) return r;
    for (; pos < r.prompt_tokens; ) {
        if (abort_decode(&abort)) { r.status = stopped(); r.total_us = micros(start); return r; }
        const int count = std::min(256, r.prompt_tokens - pos);
        fill(batch.get(), prompt.data() + pos, count, pos);
        if (llama_process(context.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { r.status = stopped(); r.total_us = micros(start); return r; }
        pos += count;
    }
    r.prefill_us = micros(start);
    events.env->CallVoidMethod(events.target, events.stage, 1); events.check();
    if (events.failed) return r;
    r.status = Truncated;
    for (int i = 0; i < limit; ++i) {
        if (abort_decode(&abort)) { r.status = stopped(); break; }
        const auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) { r.status = Complete; break; }
        std::vector<char> piece(256);
        int count = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (count < 0) { piece.resize(static_cast<size_t>(-count)); count = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false); }
        if (count < 0) { r.status = Error; break; }
        if (r.output.size() + static_cast<size_t>(count) > 65536) break;
        r.output.append(piece.data(), static_cast<size_t>(count));
        ++r.generated_tokens;
        if (r.first_us == 0 && count > 0) r.first_us = micros(start);
        auto data = byte_array(events.env, std::string(piece.data(), static_cast<size_t>(count)));
        events.env->CallVoidMethod(events.target, events.piece, data);
        events.env->DeleteLocalRef(data); events.check();
        if (events.failed) { r.status = Error; break; }
        if (i + 1 < limit) {
            fill(batch.get(), &token, 1, pos++);
            if (llama_process(context.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { r.status = stopped(); break; }
        }
    }
    if (s.cancelled.load()) r.status = Cancelled;
    r.total_us = micros(start);
    return r;
}
} // namespace

void initialize_llama() {
    static std::once_flag init;
    std::call_once(init, [] { llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr); llama_backend_init(); });
}

extern "C" JNIEXPORT jlong JNICALL Java_seeyuer_sayin_translation_NativeBindings_create(JNIEnv *, jobject) {
    initialize_llama();
    try { return reinterpret_cast<jlong>(new Session()); } catch (...) { return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_seeyuer_sayin_translation_NativeBindings_reset(JNIEnv *, jobject, jlong h) { session(h)->cancelled.store(false); }
extern "C" JNIEXPORT void JNICALL Java_seeyuer_sayin_translation_NativeBindings_cancel(JNIEnv *, jobject, jlong h) { session(h)->cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL Java_seeyuer_sayin_translation_NativeBindings_destroy(JNIEnv *, jobject, jlong h) { delete session(h); }
extern "C" JNIEXPORT jint JNICALL Java_seeyuer_sayin_translation_NativeBindings_load(JNIEnv * env, jobject, jlong h, jbyteArray path, jint kind, jint threads, jobject callback) {
    auto & s = *session(h);
    if (s.model || kind < 0 || kind > 1 || threads < 1 || threads > 8) return Error;
    try {
        Events events(env, callback); Loading loading{&s, &events};
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.progress_callback = load_progress; params.progress_callback_user_data = &loading;
        const auto name = bytes(env, path);
        if (name.find('\0') != std::string::npos) return Error;
        s.model = llama_model_load_from_file(name.c_str(), params);
        if (!s.model) return s.cancelled.load() ? Cancelled : Error;
        char arch[64]{};
        llama_model_meta_val_str(s.model, "general.architecture", arch, sizeof(arch));
        if (std::string(arch) != (kind == 0 ? "hunyuan-dense" : "plamo3") || s.cancelled.load()) {
            llama_model_free(s.model); s.model = nullptr;
            return s.cancelled.load() ? Cancelled : Error;
        }
        s.kind = kind; s.threads = threads;
        return Complete;
    } catch (...) { if (s.model) { llama_model_free(s.model); s.model = nullptr; } return Error; }
}
// Load an uncompressed APK asset without extracting a second 1.13 GB copy.
// The FILE* starts at the asset data; llama reads absolute offsets from it.
// No mmap: the APK also contains unrelated assets, and mapping the whole APK
// would prefetch them and make tensor alignment depend on ZIP layout.
extern "C" JNIEXPORT jint JNICALL Java_seeyuer_sayin_translation_NativeBindings_loadFd(
        JNIEnv * env, jobject, jlong h, jint fd, jlong offset, jlong length, jint kind, jint threads, jobject callback) {
    auto & s = *session(h);
    if (s.model || kind < 0 || kind > 1 || threads < 1 || threads > 8 || fd < 0 || offset < 0 || length <= 0) return Error;
    // Borrow the caller's descriptor; Kotlin closes it after this call returns.
    const int duplicate = ::dup(fd);
    if (duplicate < 0) return Error;
    FILE * file = ::fdopen(duplicate, "rb");
    if (file == nullptr) { ::close(duplicate); return Error; }
    struct FileGuard {
        FILE * value;
        ~FileGuard() { if (value) std::fclose(value); }
    } guard{file};
    try {
        struct stat info{};
        const auto data = static_cast<uint64_t>(offset);
        const auto size = static_cast<uint64_t>(length);
        if (::fstat(::fileno(file), &info) != 0 || data + size < data ||
            data + size > static_cast<uint64_t>(info.st_size)) return Error;
        if (::fseeko(file, static_cast<off_t>(offset), SEEK_SET) != 0) return Error;
        Events events(env, callback); Loading loading{&s, &events};
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.progress_callback = load_progress; params.progress_callback_user_data = &loading;
        params.load_mode = LLAMA_LOAD_MODE_NONE;
        s.model = llama_model_load_from_file_ptr(file, params);
        if (!s.model) return s.cancelled.load() ? Cancelled : Error;
        char arch[64]{};
        llama_model_meta_val_str(s.model, "general.architecture", arch, sizeof(arch));
        if (std::string(arch) != (kind == 0 ? "hunyuan-dense" : "plamo3") || s.cancelled.load()) {
            llama_model_free(s.model); s.model = nullptr;
            return s.cancelled.load() ? Cancelled : Error;
        }
        s.kind = kind; s.threads = threads;
        return Complete;
    } catch (...) { if (s.model) { llama_model_free(s.model); s.model = nullptr; } return Error; }
}
extern "C" JNIEXPORT jobject JNICALL Java_seeyuer_sayin_translation_NativeBindings_run(JNIEnv * env, jobject, jlong h, jbyteArray source, jint limit, jint timeout_ms, jobject callback) {
    try { Events events(env, callback); return java_result(env, translate(*session(h), bytes(env, source), limit, timeout_ms, events)); }
    catch (...) { return java_result(env, Result{}); }
}

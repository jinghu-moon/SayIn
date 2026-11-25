#include "r2t2_mtmd.h"

namespace {
constexpr int32_t r2t2_chunk_frames = 100;     // n_window * 2
constexpr int32_t r2t2_chunk_tokens = 13;      // three stride-2 convolutions of a full chunk
constexpr int32_t r2t2_attention_frames = 800; // n_window_infer

bool r2t2_supported(mtmd_context * ctx) {
    return ctx && ctx->ctx_a && ctx->audio_preproc && ctx->proj_type_a() == PROJECTOR_TYPE_QWEN3A;
}

int32_t r2t2_conv_length(int32_t frames) {
    for (int i = 0; i < 3; ++i) frames = (frames - 1) / 2 + 1;
    return frames;
}

void * r2t2_cpu_proc(ggml_backend_t backend, const char * name) {
    const auto reg = ggml_backend_dev_backend_reg(ggml_backend_get_device(backend));
    return reg ? ggml_backend_reg_get_proc_address(reg, name) : nullptr;
}

// qwen3a graph restricted to the encoder outputs of real frames, the tail chunk is zero padded
// for the convolutions only, as in Qwen3ASRAudioEncoder.forward.
struct r2t2_encoder_graph : mtmd_graph {
    const int64_t n_valid;

    r2t2_encoder_graph(mtmd_subcontext * ctx, const mtmd_image_f32 & img, int64_t n_valid)
        : mtmd_graph(ctx, img), n_valid(n_valid) {}

    ggml_cgraph * build() override {
        ggml_tensor * inp = build_inp_raw(1);
        const int64_t n_frames = inp->ne[0];
        const int64_t n_mel = inp->ne[1];
        const int64_t n_chunks = n_frames / r2t2_chunk_frames;
        GGML_ASSERT(n_frames % r2t2_chunk_frames == 0);
        inp = ggml_view_4d(ctx0, inp, r2t2_chunk_frames, n_mel, 1, n_chunks,
            n_frames * (int64_t) sizeof(float), r2t2_chunk_frames * (int64_t) sizeof(float),
            r2t2_chunk_frames * (int64_t) sizeof(float), 0);
        inp = ggml_cont(ctx0, inp);
        auto conv_block = [&](ggml_tensor * x, ggml_tensor * w, ggml_tensor * b) {
            x = ggml_conv_2d(ctx0, w, x, 2, 2, 1, 1, 1, 1);
            if (b) x = ggml_add(ctx0, x, ggml_reshape_4d(ctx0, b, 1, 1, x->ne[2], 1));
            return ggml_gelu_erf(ctx0, x);
        };
        inp = conv_block(inp, model.conv2d_1_w, model.conv2d_1_b);
        inp = conv_block(inp, model.conv2d_2_w, model.conv2d_2_b);
        inp = conv_block(inp, model.conv2d_3_w, model.conv2d_3_b);
        GGML_ASSERT(inp->ne[0] == r2t2_chunk_tokens);
        inp = ggml_cont(ctx0, ggml_permute(ctx0, inp, 2, 0, 1, 3));
        inp = ggml_reshape_2d(ctx0, inp, inp->ne[0] * inp->ne[1], inp->ne[2] * inp->ne[3]);
        inp = ggml_mul_mat(ctx0, model.conv_out_w, inp);
        if (model.conv_out_b) inp = ggml_add(ctx0, inp, model.conv_out_b);
        const int64_t n_pos = inp->ne[1];
        ggml_tensor * positions = ggml_view_2d(ctx0, model.position_embeddings,
            model.position_embeddings->ne[0], r2t2_chunk_tokens, model.position_embeddings->nb[1], 0);
        ggml_tensor * target = ggml_new_tensor_2d(ctx0, GGML_TYPE_F32, model.position_embeddings->ne[0], n_pos);
        inp = ggml_add(ctx0, inp, ggml_repeat(ctx0, positions, target));
        // Outputs are ordered by chunk, so the padded positions of the tail chunk are the last columns.
        GGML_ASSERT(n_valid > 0 && n_valid <= n_pos);
        inp = ggml_view_2d(ctx0, inp, inp->ne[0], n_valid, inp->nb[1], 0);
        ggml_tensor * cur = build_vit(inp, n_valid, NORM_TYPE_NORMAL, hparams.ffn_op, nullptr, nullptr);
        cur = build_ffn(cur, model.mm_1_w, model.mm_1_b, nullptr, nullptr, model.mm_2_w, model.mm_2_b,
            FFN_GELU_ERF, -1);
        ggml_build_forward_expand(gf, cur);
        return gf;
    }
};
}

int32_t r2t2_mel_frames(size_t n_samples) {
    return static_cast<int32_t>(n_samples / 160);
}

int32_t r2t2_mel_bins(mtmd_context * ctx) {
    return r2t2_supported(ctx) ? static_cast<int32_t>(ctx->audio_preproc->mel_config.n_mel) : 0;
}

int32_t r2t2_window_frames(void) {
    return r2t2_attention_frames;
}

int32_t r2t2_window_tokens(int32_t frames) {
    if (frames <= 0 || frames > r2t2_attention_frames) return 0;
    const int32_t tail = frames % r2t2_chunk_frames;
    return frames / r2t2_chunk_frames * r2t2_chunk_tokens + (tail ? r2t2_conv_length(tail) : 0);
}

bool r2t2_log_mel(mtmd_context * ctx, const float * samples, size_t n_samples, float * out) {
    if (!r2t2_supported(ctx)) return false;
    auto config = ctx->audio_preproc->mel_config;
    const int64_t frames = r2t2_mel_frames(n_samples);
    const int64_t frame_size = (config.n_fft_bins - 1) * 2;
    if (frames <= 0 || config.hop_length != 160 || !config.cache ||
        config.cache->hann_window.size() != static_cast<size_t>(frame_size)) {
        return false;
    }
    // WhisperFeatureExtractor clamps the power at 1e-10 before log10.
    config.mel_floor = 1e-10f;
    config.mel_floor_add = false;
    // Centered STFT with reflection padding of n_fft / 2 samples.
    const int64_t pad = frame_size / 2;
    const auto n = static_cast<int64_t>(n_samples);
    std::vector<float> padded(n_samples + 2 * pad, 0.0f);
    for (int64_t i = 0; i < pad; ++i) {
        const int64_t head = pad - i;
        const int64_t tail = n - 2 - i;
        padded[i] = head < n ? samples[head] : 0.0f;
        padded[n + pad + i] = tail >= 0 ? samples[tail] : 0.0f;
    }
    std::copy(samples, samples + n_samples, padded.begin() + pad);
    {
        std::lock_guard<std::mutex> lock(ctx->compute_mutex);
        if (!mtmd_audio_log_mel(config, padded.data(), config.cache->hann_window.data(), frames, out)) return false;
    }
    const size_t count = static_cast<size_t>(frames) * config.n_mel;
    const float floor = *std::max_element(out, out + count) - 8.0f;
    for (size_t i = 0; i < count; ++i) out[i] = (std::max(out[i], floor) + 4.0f) / 4.0f;
    return true;
}

bool r2t2_encode_window(mtmd_context * ctx, const float * mel, int32_t frames, float * out) {
    const int32_t tokens = r2t2_window_tokens(frames);
    if (!r2t2_supported(ctx) || tokens == 0) return false;
    const auto bins = static_cast<int>(ctx->audio_preproc->mel_config.n_mel);
    const int padded = (frames + r2t2_chunk_frames - 1) / r2t2_chunk_frames * r2t2_chunk_frames;
    std::vector<float> input(static_cast<size_t>(padded) * bins, 0.0f);
    for (int bin = 0; bin < bins; ++bin) {
        std::copy(mel + static_cast<size_t>(bin) * frames, mel + static_cast<size_t>(bin + 1) * frames,
                  input.begin() + static_cast<size_t>(bin) * padded);
    }
    mtmd_image_f32_batch batch;
    batch.is_audio = true;
    batch.entries.emplace_back();
    auto & image = batch.entries.back();
    image.set_size({padded, bins}, false, true);
    image.cpy_buf(input);

    std::lock_guard<std::mutex> lock(ctx->compute_mutex);
    auto & encoder = *ctx->ctx_a;
    // Selects flash attention and reserves the compute buffers once.
    if (!encoder.is_allocated) encoder.warmup(batch);
    auto * sched = encoder.backends.sched.get();
    ggml_backend_sched_reset(sched);
    r2t2_encoder_graph graph(&encoder, image, tokens);
    ggml_cgraph * gf = graph.build();
    ggml_tensor * embeddings = ggml_graph_node(gf, -1);
    if (embeddings->ne[1] != tokens || embeddings->type != GGML_TYPE_F32 ||
        !ggml_backend_sched_alloc_graph(sched, gf)) {
        return false;
    }
    ggml_tensor * raw = ggml_graph_get_tensor(gf, "inp_raw");
    GGML_ASSERT(raw && ggml_nelements(raw) == static_cast<int64_t>(input.size()));
    ggml_backend_tensor_set(raw, input.data(), 0, ggml_nbytes(raw));
    if (const auto set_threads = reinterpret_cast<ggml_backend_set_n_threads_t>(
            r2t2_cpu_proc(encoder.backends.backend_cpu, "ggml_backend_set_n_threads"))) {
        set_threads(encoder.backends.backend_cpu, ctx->n_threads);
    }
    if (ggml_backend_sched_graph_compute(sched, gf) != GGML_STATUS_SUCCESS) return false;
    ggml_backend_tensor_get(embeddings, out, 0, ggml_nbytes(embeddings));
    return true;
}

void r2t2_set_encoder_abort(mtmd_context * ctx, bool (*abort)(void *), void * data) {
    const auto backend = ctx->ctx_a->backends.backend_cpu;
    const auto set_abort = reinterpret_cast<ggml_backend_set_abort_callback_t>(
        r2t2_cpu_proc(backend, "ggml_backend_set_abort_callback"));
    if (set_abort) set_abort(backend, abort, data);
}

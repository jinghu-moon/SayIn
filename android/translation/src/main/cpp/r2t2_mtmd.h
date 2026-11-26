#pragma once
#include "mtmd.h"
#include <stddef.h>
#include <stdint.h>

// Qwen3-ASR audio front end as in the reference encoder. The pinned mtmd path adds one STFT frame
// and feeds the zero-padded tail positions of each window to the encoder and decoder.

// Log-mel frames of a segment, matching WhisperFeatureExtractor: the last STFT frame is dropped.
MTMD_API int32_t r2t2_mel_frames(size_t n_samples);
MTMD_API int32_t r2t2_mel_bins(mtmd_context * ctx);
// Mel frames per encoder attention window.
MTMD_API int32_t r2t2_window_frames(void);
// Encoder output positions of one window with the given number of valid mel frames.
MTMD_API int32_t r2t2_window_tokens(int32_t frames);
// Hidden width of the audio encoder output before ViT projection.
MTMD_API int32_t r2t2_conv_dim(mtmd_context * ctx);
// Normalized log-mel of the whole segment, out has the layout [bins][frames].
MTMD_API bool r2t2_log_mel(mtmd_context * ctx, const float * samples, size_t n_samples, float * out);
// Runs the 3-layer conv frontend + conv_out on [bins][frames], out receives [tokens][conv_dim].
MTMD_API bool r2t2_conv_chunks(mtmd_context * ctx, const float * mel, int32_t frames, float * out);
// Runs ViT + multimodal projector on conv_features [tokens][conv_dim], out receives [tokens][n_embd].
MTMD_API bool r2t2_encode_conv(mtmd_context * ctx, const float * conv_features, int32_t tokens, float * out);
// Encodes one window, mel has the layout [bins][frames] and out receives [tokens][n_embd].
MTMD_API bool r2t2_encode_window(mtmd_context * ctx, const float * mel, int32_t frames, float * out);
MTMD_API void r2t2_set_encoder_abort(mtmd_context * ctx, bool (*abort)(void *), void * data);
MTMD_API void r2t2_set_encoder_threads(mtmd_context * ctx, int32_t n_threads);

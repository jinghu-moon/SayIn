"""Run real X-ASR inference on host WAVs; does not measure Android/microphone behavior."""
import argparse
import json
from pathlib import Path
import sys
import time
import wave


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('wavs', nargs='+', type=Path)
    parser.add_argument('--runtime', type=Path, help='Optional isolated pip --target directory')
    parser.add_argument('--model', type=Path, default=Path('android/local-asr/assets/models/x-asr-480ms'))
    parser.add_argument('--output', type=Path, default=Path('docs/research/31-x-asr-host-inference.json'))
    args = parser.parse_args()
    if args.runtime:
        sys.path.insert(0, str(args.runtime.resolve()))
    import numpy as np
    import sherpa_onnx

    started = time.monotonic()
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(args.model / 'tokens.txt'), encoder=str(args.model / 'encoder.onnx'),
        decoder=str(args.model / 'decoder.onnx'), joiner=str(args.model / 'joiner.onnx'),
        sample_rate=16000, feature_dim=80, model_type='zipformer2',
        num_threads=2, provider='cpu', decoding_method='greedy_search',
        enable_endpoint_detection=True, rule2_min_trailing_silence=1.4,
    )
    results = {'sherpa_onnx': sherpa_onnx.__version__, 'platform': sys.platform,
               'model_load_seconds': time.monotonic() - started, 'files': []}
    for path in args.wavs:
        with wave.open(str(path)) as wav:
            assert wav.getnchannels() == 1 and wav.getsampwidth() == 2 and wav.getframerate() == 16000
            samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype='<i2').astype(np.float32) / 32768
        stream = recognizer.create_stream()
        committed = ''
        partials = []

        def update(endpoint=False):
            nonlocal committed
            text = recognizer.get_result(stream).strip()
            space = bool(committed and text and committed[-1].isascii() and committed[-1].isalnum()
                         and text[0].isascii() and text[0].isalnum())
            combined = committed + (' ' if space else '') + text
            if not partials or partials[-1] != combined:
                partials.append(combined)
            if endpoint:
                committed = combined
                recognizer.reset(stream)
            return combined

        started = time.monotonic()
        for offset in range(0, len(samples), 1600):
            stream.accept_waveform(16000, samples[offset:offset + 1600])
            while recognizer.is_ready(stream):
                recognizer.decode_stream(stream)
            update(recognizer.is_endpoint(stream))
        stream.accept_waveform(16000, np.zeros(12800, dtype=np.float32))
        stream.input_finished()
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
        final = update()
        assert final, f'Empty recognition for speech sample: {path}'
        result = {'wav': str(path), 'audio_seconds': len(samples) / 16000,
                  'host_inference_seconds': time.monotonic() - started,
                  'text': final, 'partial_revisions': partials}
        results['files'].append(result)
        print(json.dumps(result, ensure_ascii=True), flush=True)
    # Silence must not hallucinate a sendable transcript.
    stream = recognizer.create_stream()
    for _ in range(30):
        stream.accept_waveform(16000, np.zeros(1600, dtype=np.float32))
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
    stream.accept_waveform(16000, np.zeros(12800, dtype=np.float32))
    stream.input_finished()
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)
    results['silence_text'] = recognizer.get_result(stream).strip()
    assert not results['silence_text'], 'Silence unexpectedly produced text'
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()

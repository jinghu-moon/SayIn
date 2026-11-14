"""Prepare pinned Android ASR artifacts; never execute code from model archives."""
import hashlib
import argparse
import json
from pathlib import Path
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / 'android/local-asr'
PINNED = json.loads((ROOT / 'android/asr-artifacts.json').read_text(encoding='utf-8'))
MODEL = PINNED['model']
ARCHIVE_SHA = PINNED['archive_sha256']
AAR_SHA = PINNED['aar_sha256']
FILES = set(PINNED['files'])


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    if args.verify_only:
        expected = {OUTPUT / f"sherpa-onnx-{PINNED['sherpa_version']}.aar": AAR_SHA}
        expected.update({OUTPUT / 'assets/models/x-asr-480ms' / name: sha for name, sha in PINNED['files'].items()})
        fixture = PINNED['test_fixture']
        expected[ROOT / 'android' / fixture['file']] = fixture['sha256']
        for path, sha in expected.items():
            if not path.is_file() or digest(path) != sha:
                raise ValueError(f'Missing or invalid ASR artifact: {path}. Run python scripts/prepare_x_asr.py')
        print('Verified pinned AAR, model assets and test fixture SHA-256.')
        return
    OUTPUT.mkdir(parents=True, exist_ok=True)
    aar = OUTPUT / f"sherpa-onnx-{PINNED['sherpa_version']}.aar"
    if not aar.exists() or digest(aar) != AAR_SHA:
        temporary = aar.with_suffix('.download')
        url = PINNED['aar_url']
        urllib.request.urlretrieve(url, temporary)
        if digest(temporary) != AAR_SHA:
            raise ValueError('Official sherpa-onnx AAR SHA-256 mismatch')
        temporary.replace(aar)
    archive = ROOT / 'assets/models' / (MODEL + '.tar.bz2')
    if digest(archive) != ARCHIVE_SHA:
        raise ValueError('X-ASR archive SHA-256 mismatch')
    destination = OUTPUT / 'assets/models/x-asr-480ms'
    manifest = OUTPUT / 'model-manifest.json'
    if manifest.exists():
        previous = json.loads(manifest.read_text(encoding='utf-8'))
        if previous.get('archive_sha256') == ARCHIVE_SHA and all(
            (destination / name).is_file() and digest(destination / name) == PINNED['files'][name]
            for name in FILES
        ):
            print('Pinned sherpa-onnx AAR and X-ASR assets verified; no extraction needed.')
            return
    destination.mkdir(parents=True, exist_ok=True)
    hashes = {}
    with tarfile.open(archive, 'r|bz2') as tar:
        for member in tar:
            relative = member.name.removeprefix('./')
            if relative not in {f'{MODEL}/{name}' for name in FILES}:
                continue
            if not member.isfile():
                raise ValueError('Expected regular model file')
            name = Path(relative).name
            target = destination / name
            temporary = target.with_suffix(target.suffix + '.partial')
            with tar.extractfile(member) as source, temporary.open('wb') as output:
                shutil.copyfileobj(source, output)
            temporary.replace(target)
            hashes[name] = digest(target)
            if hashes[name] != PINNED['files'][name]:
                raise ValueError(f'Extracted model digest mismatch: {name}')
            print(f'Prepared {name}: {target.stat().st_size:,} bytes', flush=True)
    if set(hashes) != FILES:
        raise ValueError('Incomplete X-ASR archive')
    manifest.write_text(json.dumps({'archive_sha256': ARCHIVE_SHA, 'aar_sha256': AAR_SHA,
                                   'files': hashes}, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()

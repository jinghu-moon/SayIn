# Third-party notices

SayIn's own source code and documentation are licensed under the root [MIT license](LICENSE). Third-party code and assets retain their original licenses; the root license does not replace them.

## Tabler Icons

- Upstream: <https://github.com/tabler/tabler-icons>, v3.51.0.
- License: MIT, copyright Paweł Kuna.
- Android SVGs and generated vectors: [license](android/design/tabler/LICENSE), [source hashes](android/design/tabler/provenance.json).
- HTML prototype icons: [license](docs/demos/Tabler-LICENSE), [source hashes](docs/demos/voicelink-desktop.assets.json).
- Desktop attribution: [license](desktop/assets/LICENSE.tabler). Runtime component icons are provided by Yue.

## Gradle Wrapper

The wrapper scripts and JAR in `android/` originate from Gradle and retain the Apache License 2.0. See [license](licenses/Apache-2.0.txt), the copyright notices in `android/gradlew` and `android/gradlew.bat`, and <https://github.com/gradle/gradle>.

## Local translation

llama.cpp / ggml is pinned to `32bdc3a89e6528d3e419024c833c5dabf32c7541` under MIT. The selected Hy-MT2-1.8B Q4_K_M weight is Apache-2.0 at the revision in `android/translation-artifacts.json`; no weight is bundled in the APK. PLaMo's Community License applies to the separately evaluated PLaMo files, not the selected product download. Full license texts and contribution/runtime notices are in [translation notices](docs/licenses/translation/THIRD-PARTY-NOTICES.txt) and included in generated Android assets. Model evaluation evidence is in [implementation record](docs/50-android-local-translation-implementation.md).

## Build dependencies

Rust and Android dependencies, including Yue/GPUI, Miuix, AndroidX, Kotlin, OkHttp, ZXing and the shared Rust pairing dependencies, retain their respective licenses. Versions and sources are recorded in `desktop/Cargo.lock`, `desktop/yue-source.json` and `android/gradle/libs.versions.toml`. Dependency source code and locally cloned reference projects are not redistributed in this repository. Distribution of compiled applications requires retaining the notices applicable to their bundled dependencies.

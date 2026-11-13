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

## Build dependencies

Rust and Android dependencies, including Yue/GPUI, Miuix, AndroidX, Kotlin, OkHttp, ZXing and the shared Rust pairing dependencies, retain their respective licenses. Versions and sources are recorded in `desktop/Cargo.lock`, `desktop/yue-source.json` and `android/gradle/libs.versions.toml`. Dependency source code and locally cloned reference projects are not redistributed in this repository. Distribution of compiled applications requires retaining the notices applicable to their bundled dependencies.

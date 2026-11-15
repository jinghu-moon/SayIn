# SayIn Root Helper engineering rules

Inherit the repository AGENTS.md. User selected C++ for the independent collector.
Use the pinned Android NDK 29.0.14206865, CMake and CTest. Do not add to the App build.

Replace the Kotlin diagnostic scaffold instead of maintaining two runtimes. The initial
native Binder backend supports Android 13/API 33 only; unknown layouts fail closed.
Do not claim clipboard access, App readiness or ROM support from host tests alone.

Before introducing a persistent runtime, implement and validate the gates in docs/56:
atomic instance ownership; process identity; authenticated fixed-operation IPC; independent
opt-in switches; restart budget; user-stop handling; fail-closed unlock state; private storage;
and update/disable/uninstall acknowledgement. Replace the inactive lifecycle hooks together.
An empty signing allowlist denies every client; never turn it into a trust-on-first-use path.

Package only explicitly listed files. Never ship local logs, runtime state, credentials,
private data or build caches. No automatic Root installation or device permission changes.
Changes must pass the native build, CTest, package verifier and relevant isolated device tests.

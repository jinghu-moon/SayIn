# This app intentionally has no Activity; its public workload lives in the separate test APK.
# Preserve that test entry surface and Kotlin runtime used by the external test runner.
# Product app release uses the library's narrow consumer JNI rules, not these benchmark rules.
-keep class seeyuer.sayin.translation.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Shared by AndroidX test runner, absent from this Activity-free benchmark app usage.
-keep class androidx.tracing.Trace { *; }

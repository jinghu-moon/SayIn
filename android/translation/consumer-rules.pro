# JNI constructs this result and invokes these callbacks. Never keep the application wholesale.
-keep class seeyuer.sayin.translation.NativeBindings { *; }
-keep class seeyuer.sayin.translation.R2t2Bindings { *; }
-keep interface seeyuer.sayin.translation.R2t2Preview { *; }
-keep class seeyuer.sayin.translation.NativeResult { *; }
-keep interface seeyuer.sayin.translation.NativeEvents { *; }
-keepclassmembers class * implements seeyuer.sayin.translation.NativeEvents { *; }

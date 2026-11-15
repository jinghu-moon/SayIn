# Device-generated Parcel samples

`clip-api37-plain.hex` and `clip-api37-sensitive.hex` were generated on the connected
Xiaomi 25102RKBEC, Android 17/API 37, HyperOS OS4.0.6.0.XPMCNXM. They contain only
`ClipData.newPlainText("fixture", "SAYIN_TEST_platform_中文🙂")`, a synthetic
timestamp 12345, and (for the second file) the sensitive flag. No system clipboard
was read to create these files.

Generator: `tests/device/PlatformProbe.java`, built into the isolated fixture APK.
Run through `CLASSPATH=<fixture.apk> app_process /system/bin
seeyuer.sayin.helperfixture.PlatformProbe`. It prints interface signatures and
Base64-encoded generated Parcel bytes. Decode the `PARCEL` lines and format bytes
as hexadecimal to reproduce the inputs.

Unlike AOSP, this ROM's BaseBundle writes an additional `mHasIntent` Boolean after
every non-empty bundle. The sensitive sample captures this difference. Production
selection is explicit; unknown formats fail closed. These samples are not evidence
that every API 37 ROM shares this layout.

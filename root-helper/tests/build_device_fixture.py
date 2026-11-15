"""Build an isolated synthetic clipboard writer. Never installs or runs it."""
from pathlib import Path
import subprocess
import zipfile
import os

repo = Path(__file__).resolve().parents[2]
sdk = Path(os.environ.get("ANDROID_HOME", "C:/Users/seeyuer/AppData/Local/Android/Sdk"))
java = Path("C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot/bin")
tools = sdk / "build-tools/37.0.0"
android = sdk / "platforms/android-37.0/android.jar"
source = repo / "root-helper/tests/device"
output = repo / "root-helper/build/device-fixture"
output.mkdir(parents=True, exist_ok=True)
classes = output / "classes"
classes.mkdir(exist_ok=True)

def run(*arguments):
    subprocess.run([str(a) for a in arguments], check=True)

run(java / "javac.exe", "--release", "17", "-classpath", android, "-d", classes, *sorted(source.glob("*.java")))
jar = output / "fixture.jar"
with zipfile.ZipFile(jar, "w") as z:
    for p in classes.rglob("*.class"):
        z.write(p, p.relative_to(classes).as_posix())
dex = output / "dex"
dex.mkdir(exist_ok=True)
run(java / "java.exe", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "33", "--lib", android, "--output", dex, jar)
apk = output / "unsigned.apk"
run(tools / "aapt2.exe", "link", "-I", android, "--manifest", source / "AndroidManifest.xml", "-o", apk)
with zipfile.ZipFile(apk, "a") as z:
    z.write(dex / "classes.dex", "classes.dex")
key = output / "fixture.keystore"
if not key.exists():
    run(java / "keytool.exe", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android", "-alias", "fixture", "-dname", "CN=SayIn isolated clipboard fixture", "-keyalg", "RSA", "-validity", "1000")
target = output / "fixture.apk"
run(java / "java.exe", "-jar", tools / "lib/apksigner.jar", "sign", "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", target, apk)
print(target)

# An allowlisted package name signed by the UNtrusted fixture key. Isolated App only.
impostor_manifest = output / "impostor-manifest.xml"
impostor_manifest.write_text("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="seeyuer.sayin.imagetest">
<uses-sdk android:minSdkVersion="33" android:targetSdkVersion="33" />
<application android:label="SayIn untrusted signature fixture" android:allowBackup="false" />
<instrumentation android:name="seeyuer.sayin.helperfixture.SocketProbe" android:targetPackage="seeyuer.sayin.imagetest" />
</manifest>""", encoding="utf-8")
impostor_unsigned = output / "impostor-unsigned.apk"
run(tools / "aapt2.exe", "link", "-I", android, "--manifest", impostor_manifest, "-o", impostor_unsigned)
with zipfile.ZipFile(impostor_unsigned, "a") as z: z.write(dex / "classes.dex", "classes.dex")
run(java / "java.exe", "-jar", tools / "lib/apksigner.jar", "sign", "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--v3-signing-enabled", "false", "--v4-signing-enabled", "false", "--out", output / "impostor.apk", impostor_unsigned)

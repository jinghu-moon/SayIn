package seeyuer.sayin.helperfixture;

import android.content.ClipData;
import android.os.IBinder;
import java.lang.reflect.Method;

/** Writes generated lock-screen samples as real adb shell; never reads the clipboard. */
public final class ShellClipboardWriter {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000 || args.length != 1 || !args[0].startsWith("SAYIN_TEST_") || args[0].length() > 80) throw new IllegalArgumentException();
        IBinder binder = (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "clipboard");
        Object api = Class.forName("android.content.IClipboard$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        Method write = Class.forName("android.content.IClipboard").getMethod("setPrimaryClip", ClipData.class, String.class, String.class, int.class, int.class);
        for (int i = 1; i <= 3; i++) {
            write.invoke(api, ClipData.newPlainText("fixture", args[0] + i + " 中文🙂"), "com.android.shell", null, 0, 0);
            Thread.sleep(300);
        }
        System.out.println("samples_written=3");
    }
}

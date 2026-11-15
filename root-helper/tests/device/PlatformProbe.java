package seeyuer.sayin.helperfixture;

import android.content.ClipData;
import android.os.Parcel;
import android.os.PersistableBundle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;

/** One-shot system-layout diagnostic. Serializes generated data; never reads the clipboard. */
public final class PlatformProbe {
    public static void main(String[] args) throws Exception {
        for (String name : new String[]{"android.content.IClipboard", "android.app.IActivityManager", "android.app.trust.ITrustManager", "android.view.IWindowManager", "android.os.IPowerManager"}) {
            Class<?> api = Class.forName(name);
            Class<?> stub = Class.forName(name + "$Stub");
            for (Method method : api.getDeclaredMethods()) {
                String key = method.getName();
                if (!(key.contains("PrimaryClip") || key.equals("getCurrentUserId") || key.equals("isDeviceLocked") || key.equals("isKeyguardLocked") || key.equals("isInteractive"))) continue;
                Field transaction = stub.getDeclaredField("TRANSACTION_" + key);
                transaction.setAccessible(true);
                System.out.println("METHOD " + name + " " + transaction.getInt(null) + " " + method.toString());
            }
        }
        for (boolean sensitive : new boolean[]{false, true}) {
            ClipData clip = ClipData.newPlainText("fixture", "SAYIN_TEST_platform_中文🙂");
            Method timestamp = clip.getDescription().getClass().getDeclaredMethod("setTimestamp", long.class);
            timestamp.setAccessible(true); timestamp.invoke(clip.getDescription(), 12345L);
            if (sensitive) {
                PersistableBundle extra = new PersistableBundle();
                extra.putBoolean("android.content.extra.IS_SENSITIVE", true);
                clip.getDescription().setExtras(extra);
            }
            Parcel parcel = Parcel.obtain();
            parcel.writeInt(1); clip.writeToParcel(parcel, 0);
            System.out.println("PARCEL " + sensitive + " " + Base64.getEncoder().encodeToString(parcel.marshall()));
            parcel.recycle();
        }
    }
}

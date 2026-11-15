package seeyuer.sayin.helperfixture;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.PersistableBundle;

/** Isolated writer of generated samples only. Never reads the user's clipboard. */
public final class ClipboardFixture extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        try {
            String prefix = arguments.getString("prefix", "");
            if (!prefix.startsWith("SAYIN_TEST_") || prefix.length() > 80) throw new IllegalArgumentException();
            String mode = arguments.getString("mode", "normal");
            int count = Integer.parseInt(arguments.getString("count", "3"));
            int delay = Integer.parseInt(arguments.getString("delay", "300"));
            if (count < 1 || count > 80 || delay < 0 || delay > 2000) throw new IllegalArgumentException();
            ClipboardManager clipboard = (ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);
            for (int i = 1; i <= count; i++) {
                ClipData clip;
                if (mode.equals("uri")) clip = ClipData.newRawUri("fixture", android.net.Uri.parse("content://sayin.invalid/test"));
                else if (mode.equals("oversize")) clip = ClipData.newPlainText("fixture", prefix + "x".repeat(20000));
                else clip = ClipData.newPlainText("fixture", prefix + i + " 中文🙂");
                if (mode.equals("sensitive")) {
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                    clip.getDescription().setExtras(extras);
                }
                clipboard.setPrimaryClip(clip);
                if (delay > 0) Thread.sleep(delay);
            }
            Bundle result = new Bundle(); result.putString("samples_written", Integer.toString(count));
            finish(0, result);
        } catch (Exception error) {
            Bundle result = new Bundle(); result.putString("fixture_error", error.getClass().getSimpleName());
            finish(1, result);
        }
    }
}

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
        savedArguments = arguments;
        start();
    }
    @Override public void onStart() {
        Bundle arguments = savedArguments;
        runSamples(arguments);
    }
    private Bundle savedArguments;
    private void runSamples(Bundle arguments) {
        android.app.Activity activity = null;
        try {
            String prefix = arguments.getString("prefix", "");
            if (!prefix.startsWith("SAYIN_TEST_") || prefix.length() > 80) throw new IllegalArgumentException();
            String mode = arguments.getString("mode", "normal");
            int count = Integer.parseInt(arguments.getString("count", "3"));
            int delay = Integer.parseInt(arguments.getString("delay", "300"));
            if (count < 1 || count > 80 || delay < 0 || delay > 2000) throw new IllegalArgumentException();
            long deadline = android.os.SystemClock.uptimeMillis() + 10000;
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                activity = ClipboardFixtureActivity.current;
                if (activity != null && activity.hasWindowFocus()) break;
                Thread.sleep(50);
            }
            if (activity == null || !activity.hasWindowFocus()) throw new IllegalStateException("fixture_has_no_focus");
            ClipboardManager clipboard = (ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            for (int i = 1; i <= count; i++) {
                ClipData clip;
                if (mode.equals("uri")) clip = ClipData.newRawUri("fixture", android.net.Uri.parse("content://sayin.invalid/test"));
                else if (mode.equals("html")) clip = ClipData.newHtmlText("fixture", prefix + i, "<b>synthetic</b>");
                else if (mode.equals("styled")) {
                    android.text.SpannableString text = new android.text.SpannableString(prefix + i);
                    text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, text.length(), 0);
                    clip = ClipData.newPlainText("fixture", text);
                }
                else if (mode.equals("oversize")) clip = ClipData.newPlainText("fixture", prefix + "x".repeat(20000));
                else clip = ClipData.newPlainText("fixture", prefix + i + " 中文🙂");
                if (mode.equals("sensitive") || mode.equals("metadata")) {
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean(mode.equals("sensitive") ? "android.content.extra.IS_SENSITIVE" : "sayin.test", true);
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
        } finally {
            if (activity != null) {
                android.app.Activity owned = activity;
                runOnMainSync(owned::finish);
            }
        }
    }
}

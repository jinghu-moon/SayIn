package seeyuer.sayin.helperfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Gives synthetic clipboard writes real foreground focus without changing AppOps. */
public final class ClipboardFixtureActivity extends Activity {
    public static volatile ClipboardFixtureActivity current;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("SayIn clipboard verification\nGenerated test content only");
        label.setPadding(32, 64, 32, 32);
        setContentView(label);
        current = this;
    }
    @Override public void onDestroy() { if (current == this) current = null; super.onDestroy(); }
}

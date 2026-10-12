package es.inhouse.gmsprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Embedded microG component probe\n\nExperimental harness only.\nGoogle login, YouTube playback and push have not been validated.");
        text.setTextSize(20);
        text.setPadding(32, 64, 32, 32);
        setContentView(text);
    }
}

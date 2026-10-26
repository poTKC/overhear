package jp.tkc.st15iplayer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class PlayerActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText(R.string.app_name);
        text.setTextSize(24);
        setContentView(text);
    }
}

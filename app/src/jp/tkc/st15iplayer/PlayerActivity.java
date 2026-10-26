package jp.tkc.st15iplayer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import jp.tkc.st15iplayer.audio.AudioSink;

public class PlayerActivity extends Activity {
    // Temporary: the audio sink moves into the player service later.
    private AudioSink audioSink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText(R.string.app_name);
        text.setTextSize(24);
        setContentView(text);
        audioSink = new AudioSink(this, null);
        audioSink.start();
    }

    @Override
    protected void onDestroy() {
        audioSink.stop();
        super.onDestroy();
    }
}

package jp.tkc.st15iplayer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import jp.tkc.st15iplayer.audio.AudioSink;
import jp.tkc.st15iplayer.runtime.ProgressListener;
import jp.tkc.st15iplayer.runtime.RuntimeInstaller;
import jp.tkc.st15iplayer.runtime.SoloistProcess;

public class PlayerActivity extends Activity {
    // Temporary: the audio sink moves into the player service later.
    private AudioSink audioSink;

    // TEMPORARY debug wiring for Task 6 verification; Task 8 removes it
    // (the player service and setup screen take over).
    private static final String DEV_KEY_FILE = "dev_api_key";
    /**
     * run-as cannot read packages.list on this CM10.2 build, so the dev key
     * may instead be pushed here as a short-lived 0644 file (adb deletes it
     * after the import; the app cannot write /data/local/tmp).
     */
    private static final String DEV_KEY_TMP = "/data/local/tmp/st15i_dev_key";
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView logView;
    private RuntimeInstaller installer;
    private SoloistProcess soloist;
    // TEMPORARY: without a multicast lock the Wi-Fi driver drops the mDNS
    // queries Spotify sends, so the phone never sees "ST15i". Task 7's
    // player service takes this over.
    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.net.wifi.WifiManager wifi =
                (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("st15i-player-mdns");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        root.addView(title);

        Button start = new Button(this);
        start.setText("Debug: start Soloist");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                debugStart();
            }
        });
        root.addView(start);
        Button showLog = new Button(this);
        showLog.setText("Debug: show log");
        showLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showLog();
            }
        });
        root.addView(showLog);
        Button check = new Button(this);
        check.setText("Debug: check key exposure");
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                checkExposure();
            }
        });
        root.addView(check);
        status = new TextView(this);
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(9);
        scroll.addView(logView);
        root.addView(scroll);
        setContentView(root);

        audioSink = new AudioSink(this, null);
        audioSink.start();
        installer = new RuntimeInstaller(this);
        soloist = new SoloistProcess(installer, new SoloistProcess.Listener() {
            @Override public void onExit(int exitCode, boolean expired, boolean requested) {
                setStatus("Soloist exited: " + exitCode + (expired ? " (expired)" : ""));
            }
        });
    }

    private void debugStart() {
        if (soloist.isRunning()) {
            setStatus("Soloist is already running");
            return;
        }
        setStatus("Setting up...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Prefs prefs = new Prefs(PlayerActivity.this);
                    importDevKey(prefs);
                    if (!prefs.hasApiKey()) {
                        setStatus("No API key (push files/" + DEV_KEY_FILE + ")");
                        return;
                    }
                    setStatus("Installing runtime...");
                    installer.ensureBundle();
                    setStatus("Checking Soloist...");
                    RuntimeInstaller.Result r = installer.ensureSoloist(false, new ProgressListener() {
                        @Override public void onProgress(int percent) {
                            setStatus("Downloading Soloist " + percent + "%");
                        }

                        @Override public void onExtracting() {
                            setStatus("Extracting Soloist...");
                        }
                    });
                    if (!r.ok) {
                        setStatus("Soloist download failed: " + r.message);
                        return;
                    }
                    // After onDestroy's release() this throws instead of
                    // starting, so the check and the start are atomic.
                    soloist.start(prefs.getDeviceName(), prefs.getApiKey(), false);
                    setStatus("Soloist running");
                } catch (IOException e) {
                    setStatus("Setup failed: " + e.getMessage());
                } catch (RuntimeException e) {
                    setStatus("Setup failed: " + e);
                }
            }
        }, "debug-setup").start();
    }

    /**
     * Moves files/dev_api_key (pushed with run-as) into Prefs and deletes it;
     * else imports DEV_KEY_TMP, which adb must delete.
     */
    private void importDevKey(Prefs prefs) throws IOException {
        File f = new File(getFilesDir(), DEV_KEY_FILE);
        if (f.isFile()) {
            boolean saved = prefs.setApiKey(new String(readBytes(f), "UTF-8"));
            if (!f.delete() || !saved) throw new IOException("could not import " + DEV_KEY_FILE);
            return;
        }
        File tmp = new File(DEV_KEY_TMP);
        if (tmp.isFile() && tmp.canRead()) {
            if (!prefs.setApiKey(new String(readBytes(tmp), "UTF-8"))) {
                throw new IOException("could not import " + DEV_KEY_TMP);
            }
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * For each of our processes running libargkey.so, counts key occurrences
     * in /proc/<pid>/cmdline and /proc/<pid>/environ (the latter is readable
     * only by this uid). Shows counts only, never the key.
     */
    private void checkExposure() {
        final String key = new Prefs(this).getApiKey();
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                File[] procs = new File("/proc").listFiles();
                if (procs != null && !key.isEmpty()) {
                    for (File p : procs) {
                        if (!p.getName().matches("[0-9]+")) continue;
                        try {
                            String cmdline = new String(readBytes(new File(p, "cmdline")), "UTF-8");
                            if (!cmdline.contains("libargkey.so")) continue;
                            String environ = new String(readBytes(new File(p, "environ")), "UTF-8");
                            sb.append("pid ").append(p.getName())
                                    .append(": cmdline key=").append(count(cmdline, key))
                                    .append(" '-k'=").append(count(cmdline, "\0-k\0"))
                                    .append(" environ key=").append(count(environ, key))
                                    .append(" ST15I_SOLOIST_KEY=").append(count(environ, "ST15I_SOLOIST_KEY"))
                                    .append(" environ bytes=").append(environ.length())
                                    .append('\n');
                        } catch (IOException e) {
                            sb.append("pid ").append(p.getName()).append(": ").append(e.getMessage()).append('\n');
                        }
                    }
                }
                setStatus(sb.length() == 0 ? "no libargkey process found" : sb.toString());
            }
        }, "debug-check").start();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    private void showLog() {
        List<String> lines = soloist.getLog();
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - 60); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        logView.setText(sb);
    }

    private void setStatus(final String text) {
        main.post(new Runnable() {
            @Override public void run() {
                status.setText(text);
            }
        });
    }

    @Override
    protected void onDestroy() {
        installer.cancelDownload();
        soloist.release();
        audioSink.stop();
        multicastLock.release();
        super.onDestroy();
    }
}

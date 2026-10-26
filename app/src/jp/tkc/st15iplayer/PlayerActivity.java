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

/**
 * TEMPORARY debug screen (Task 8 replaces it): starts and stops
 * PlayerService, which owns the audio sink and Soloist, and shows its state
 * and Soloist's log. Closing it leaves playback running.
 */
public class PlayerActivity extends Activity {
    private static final String DEV_KEY_FILE = "dev_api_key";
    /**
     * run-as cannot read packages.list on this CM10.2 build (and needs a
     * debuggable build), so the dev key may instead be pushed here as a
     * short-lived 0644 file (adb deletes it after the import; the app cannot
     * write /data/local/tmp).
     */
    private static final String DEV_KEY_TMP = "/data/local/tmp/st15i_dev_key";
    private static final long REFRESH_MS = 1000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView serviceState;
    private TextView logView;
    private boolean resumed;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            PlayerService s = PlayerService.current();
            serviceState.setText(s == null ? "service not running" : s.describe());
            if (resumed) main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        root.addView(title);

        addButton(root, "Debug: import key + start service", new View.OnClickListener() {
            @Override public void onClick(View v) {
                importAndStart();
            }
        });
        addButton(root, "Debug: stop service", new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlayerService.stop(PlayerActivity.this);
                setStatus("Service stopped");
            }
        });
        addButton(root, "Debug: restart Soloist (audio trace on/off)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlayerService s = PlayerService.current();
                boolean trace = s != null && !s.isTracingAudio();
                startService(new android.content.Intent(PlayerActivity.this, PlayerService.class)
                        .setAction(PlayerService.ACTION_RESTART_SOLOIST)
                        .putExtra(PlayerService.EXTRA_TRACE_AUDIO, trace));
                setStatus("Restarting Soloist, audio trace " + (trace ? "on" : "off"));
            }
        });
        addButton(root, "Debug: kill Soloist (crash test)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                killSoloist();
            }
        });
        addButton(root, "Debug: show log", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showLog();
            }
        });
        addButton(root, "Debug: check key exposure", new View.OnClickListener() {
            @Override public void onClick(View v) {
                checkExposure();
            }
        });
        status = new TextView(this);
        root.addView(status);
        serviceState = new TextView(this);
        serviceState.setTextSize(11);
        root.addView(serviceState);
        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(9);
        scroll.addView(logView);
        root.addView(scroll);
        setContentView(root);

        Prefs prefs = new Prefs(this);
        if (prefs.hasApiKey() && prefs.isAutoStart()) PlayerService.start(this);
    }

    private void addButton(LinearLayout root, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        root.addView(b);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        main.post(refresh);
    }

    @Override
    protected void onPause() {
        resumed = false;
        main.removeCallbacks(refresh);
        super.onPause();
    }

    private void importAndStart() {
        setStatus("Importing key...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Prefs prefs = new Prefs(PlayerActivity.this);
                    importDevKey(prefs);
                    if (!prefs.hasApiKey()) {
                        setStatus("No API key (push " + DEV_KEY_TMP + ")");
                        return;
                    }
                    setStatus("Starting service");
                    PlayerService.start(PlayerActivity.this);
                } catch (IOException e) {
                    setStatus("Key import failed: " + e.getMessage());
                }
            }
        }, "debug-import").start();
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

    /** SIGKILLs our Soloist children (same uid), to exercise the restart backoff. */
    private void killSoloist() {
        int self = android.os.Process.myPid();
        StringBuilder sb = new StringBuilder();
        File[] procs = new File("/proc").listFiles();
        if (procs != null) {
            for (File p : procs) {
                if (!p.getName().matches("[0-9]+")) continue;
                try {
                    String cmdline = new String(readBytes(new File(p, "cmdline")), "UTF-8");
                    if (!cmdline.contains("libargkey.so")) continue;
                    String stat = new String(readBytes(new File(p, "stat")), "UTF-8");
                    String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                    if (Integer.parseInt(f[1]) != self) continue;
                    android.os.Process.sendSignal(Integer.parseInt(p.getName()), 9);
                    sb.append("killed ").append(p.getName()).append(' ');
                } catch (IOException e) {
                    // gone
                } catch (RuntimeException e) {
                    // unparsable stat
                }
            }
        }
        setStatus(sb.length() == 0 ? "no Soloist child found" : sb.toString());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    private void showLog() {
        PlayerService s = PlayerService.current();
        if (s == null) {
            logView.setText("service not running");
            return;
        }
        List<String> lines = s.getLog();
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - 60); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        logView.setText(sb);
        // Also to logcat for adb (lines are key-redacted; debug screen only).
        for (String line : lines) android.util.Log.i("ST15iDebug", line);
    }

    private void setStatus(final String text) {
        main.post(new Runnable() {
            @Override public void run() {
                status.setText(text);
            }
        });
    }
}

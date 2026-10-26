package jp.tkc.st15iplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import jp.tkc.st15iplayer.soloist.PlayerState;
import jp.tkc.st15iplayer.soloist.SoloistClient;

/**
 * The player: status line, artwork in the middle, track text, seek bar,
 * controls and volume, and a queue panel that slides up over it. State comes
 * from PlayerService (bound with flags 0, so the screen never starts or keeps
 * the service); commands go through the service's SoloistClient.
 *
 * The launcher entry: without an API key it hands over to SetupActivity.
 * Closing it leaves playback running.
 */
public class PlayerActivity extends Activity {
    private static final long TICK_MS = 500;
    /** A seek's target is shown until Soloist reports a newer position, at most this long. */
    private static final long SEEK_HOLD_MS = 3000;
    private static final int VOLUME_STEP = 5;
    private static final int QUEUE_SHOWN = 10;
    private static final int ON_COLOR = 0xff33b5e5;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final VolumeControl volume = new VolumeControl();
    private Prefs prefs;
    private ArtworkLoader<Bitmap> artwork;
    private int artPx;
    private int thumbPx;

    private TextView status;
    private ImageView art;
    private TextView title;
    private TextView subtitle;
    private TextView elapsed;
    private TextView duration;
    private SeekBar seek;
    private ImageButton shuffle;
    private ImageButton prev;
    private ImageButton playPause;
    private ImageButton next;
    private ImageButton repeat;
    private SeekBar volumeBar;
    private View queuePanel;
    private ListView queueList;
    private TextView queueEmpty;
    private QueueAdapter queueAdapter;

    /** The service we listen to (PlayerService.current() when it runs). */
    private PlayerService service;
    private boolean bound;
    /** Between onResume and onPause. */
    private boolean started;
    private PlayerState state = new PlayerState();
    private boolean connected;
    private PlayerText.Action statusAction = PlayerText.Action.NONE;
    /** The cover URL asked for the artwork view ("" = none). */
    private String artUrl;
    private boolean seeking;
    private long seekTarget = -1;
    private long seekAt;
    private long seekStateTs;
    private boolean queueOpen;
    private boolean volumeKeyDown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        if (!prefs.hasApiKey()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_player);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        float density = getResources().getDisplayMetrics().density;
        artPx = Math.round(240 * density);
        thumbPx = Math.round(40 * density);
        artwork = Artwork.loader(this);

        status = (TextView) findViewById(R.id.status);
        art = (ImageView) findViewById(R.id.artwork);
        title = (TextView) findViewById(R.id.title);
        subtitle = (TextView) findViewById(R.id.subtitle);
        elapsed = (TextView) findViewById(R.id.elapsed);
        duration = (TextView) findViewById(R.id.duration);
        seek = (SeekBar) findViewById(R.id.seek);
        shuffle = (ImageButton) findViewById(R.id.shuffle);
        prev = (ImageButton) findViewById(R.id.prev);
        playPause = (ImageButton) findViewById(R.id.play_pause);
        next = (ImageButton) findViewById(R.id.next);
        repeat = (ImageButton) findViewById(R.id.repeat);
        volumeBar = (SeekBar) findViewById(R.id.volume);
        queuePanel = findViewById(R.id.queue_panel);
        queueList = (ListView) findViewById(R.id.queue_list);
        queueEmpty = (TextView) findViewById(R.id.queue_empty);
        queueAdapter = new QueueAdapter();
        queueList.setAdapter(queueAdapter);
        title.setSelected(true); // marquee

        wireControls();
        wireGestures();
        if (PlayerService.current() == null && prefs.isAutoStart()) PlayerService.start(this);
    }

    private void wireControls() {
        status.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onStatusTapped();
            }
        });
        if (isDebuggable(this)) {
            status.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showDebugMenu();
                    return true;
                }
            });
        }
        findViewById(R.id.settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openSettings();
            }
        });
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (service != null) service.togglePlayPause();
            }
        });
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SoloistClient c = client();
                if (c != null) c.prev();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SoloistClient c = client();
                if (c != null) c.next();
            }
        });
        shuffle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SoloistClient c = client();
                if (c != null) c.setShuffle(!state.isShuffle());
            }
        });
        repeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SoloistClient c = client();
                if (c == null) return;
                // off -> context -> track -> off, like Spotify.
                switch (state.getRepeat()) {
                    case OFF:
                        c.setRepeat(PlayerState.Repeat.CONTEXT);
                        break;
                    case CONTEXT:
                        c.setRepeat(PlayerState.Repeat.TRACK);
                        break;
                    default:
                        c.setRepeat(PlayerState.Repeat.OFF);
                        break;
                }
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) setText(elapsed, PlayerText.time(progress));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                SoloistClient c = client();
                if (c == null) return;
                c.seek(bar.getProgress());
                seekTarget = bar.getProgress();
                seekAt = System.currentTimeMillis();
                seekStateTs = state.getPositionTimestampMs();
            }
        });
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) changeVolume(volume.change(progress, SystemClock.uptimeMillis()));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                volume.setDragging(true);
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                volume.setDragging(false);
                main.removeCallbacks(sendVolume);
                sendVolume.run();
            }
        });
        findViewById(R.id.queue_button).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (queueOpen) closeQueue();
                else openQueue();
            }
        });
        findViewById(R.id.queue_close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                closeQueue();
            }
        });
    }

    private void wireGestures() {
        final GestureDetector openGesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (vy < 0 && Math.abs(vy) > Math.abs(vx)) {
                    openQueue();
                    return true;
                }
                return false;
            }
        });
        art.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                return openGesture.onTouchEvent(e);
            }
        });
        final GestureDetector closeGesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (vy > 0 && Math.abs(vy) > Math.abs(vx) && listAtTop()) {
                    closeQueue();
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.queue_header).setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                return closeGesture.onTouchEvent(e);
            }
        });
        View.OnTouchListener watch = new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                closeGesture.onTouchEvent(e);
                return false; // the list still scrolls
            }
        };
        queueList.setOnTouchListener(watch);
        queueEmpty.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                return closeGesture.onTouchEvent(e);
            }
        });
    }

    private boolean listAtTop() {
        if (queueList.getVisibility() != View.VISIBLE || queueList.getChildCount() == 0) return true;
        return queueList.getFirstVisiblePosition() == 0 && queueList.getChildAt(0).getTop() >= 0;
    }

    // --- lifecycle ---

    // Resumed, not started: with the screen off the activity is only paused,
    // and the service's wake lock would keep a 500 ms tick running all night.

    @Override
    protected void onResume() {
        super.onResume();
        started = true;
        // Flags 0: connects when the service runs, never creates or keeps it.
        bound = bindService(new Intent(this, PlayerService.class), connection, 0);
        attach();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        started = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(sendVolume);
        sendVolume.run();
        detach();
        if (bound) unbindService(connection);
        bound = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (artwork != null) artwork.cancelAll();
        super.onDestroy();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            attach();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            attach();
        }
    };

    private final Runnable onServiceChanged = new Runnable() {
        @Override public void run() {
            render();
        }
    };

    /** Follows PlayerService.current(): it may have stopped or been recreated. */
    private void attach() {
        PlayerService s = started ? PlayerService.current() : null;
        if (s != service) {
            if (service != null) service.removeUiListener(onServiceChanged);
            service = s;
            if (s != null) s.addUiListener(onServiceChanged);
        }
        render();
    }

    private void detach() {
        if (service != null) service.removeUiListener(onServiceChanged);
        service = null;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (service != PlayerService.current()) attach();
            updatePosition();
            main.postDelayed(this, TICK_MS);
        }
    };

    private SoloistClient client() {
        return service != null && connected ? service.client() : null;
    }

    // --- rendering ---

    private void render() {
        if (service != null) {
            state = service.getPlayerState();
            connected = service.isClientConnected();
        } else {
            state = new PlayerState();
            connected = false;
        }
        PlayerText.Status st = service == null
                ? PlayerText.status(false, null, null, null, false, state, prefs.getDeviceName())
                : PlayerText.status(true, service.getSupervisorState(), service.getStatus(),
                        service.getAuthError(), connected, state, prefs.getDeviceName());
        setText(status, st.text);
        statusAction = st.action;

        boolean hasTrack = connected && state.getTitle() != null;
        setText(title, hasTrack ? state.getTitle() : getString(R.string.no_track));
        setText(subtitle, hasTrack ? PlayerText.subtitle(state.getArtists(), state.getAlbum()) : "");
        showArtwork(hasTrack ? state.getMediumCoverUrl() : null);

        boolean enabled = PlayerText.controlsEnabled(connected, state);
        PlayerState.Status ps = state.getStatus();
        boolean playing = ps == PlayerState.Status.PLAYING || ps == PlayerState.Status.BUFFERING;
        playPause.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        setEnabled(playPause, enabled);
        setEnabled(prev, enabled);
        setEnabled(next, enabled);
        setEnabled(shuffle, enabled);
        setEnabled(repeat, enabled);
        setToggle(shuffle, state.isShuffle());
        repeat.setImageResource(state.getRepeat() == PlayerState.Repeat.TRACK ? R.drawable.ic_repeat_one
                : R.drawable.ic_repeat);
        setToggle(repeat, state.getRepeat() != PlayerState.Repeat.OFF);
        seek.setEnabled(enabled && state.getDurationMs() > 0);
        volumeBar.setEnabled(enabled && state.getVolume() >= 0);

        volume.onServer(state.getVolume());
        updateVolumeBar();

        List<PlayerState.QueueItem> q = connected ? state.getQueue()
                : Collections.<PlayerState.QueueItem>emptyList();
        queueAdapter.setItems(q.size() > QUEUE_SHOWN ? q.subList(0, QUEUE_SHOWN) : q);
        queueList.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        queueEmpty.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);

        updatePosition();
    }

    private void updatePosition() {
        long dur = connected ? state.getDurationMs() : 0;
        if (seek.getMax() != (int) dur) seek.setMax((int) dur);
        setText(duration, dur > 0 ? PlayerText.time(dur) : "");
        if (seeking) return; // the user's thumb wins
        if (!connected || state.getTitle() == null) {
            seek.setProgress(0);
            setText(elapsed, "");
            return;
        }
        long now = System.currentTimeMillis();
        long pos;
        if (seekTarget >= 0 && now - seekAt < SEEK_HOLD_MS && state.getPositionTimestampMs() == seekStateTs) {
            PlayerState.Status ps = state.getStatus();
            boolean advancing = ps == PlayerState.Status.PLAYING;
            pos = seekTarget + (advancing ? now - seekAt : 0);
            if (dur > 0) pos = Math.min(pos, dur);
        } else {
            seekTarget = -1;
            pos = state.positionAt(now);
        }
        seek.setProgress((int) pos);
        setText(elapsed, PlayerText.time(pos));
    }

    private void updateVolumeBar() {
        int v = volume.display(SystemClock.uptimeMillis());
        if (v >= 0 && volumeBar.getProgress() != v) volumeBar.setProgress(v);
    }

    private void showArtwork(String url) {
        String u = url == null ? "" : url;
        if (u.equals(artUrl)) return;
        artUrl = u;
        if (url == null) {
            artwork.cancel(art);
            art.setImageResource(R.drawable.art_placeholder);
            return;
        }
        // The old cover stays until the new one is here (or failed).
        artwork.load(art, url, artPx, new ArtworkLoader.Callback<Bitmap>() {
            @Override public void onArtwork(String loaded, Bitmap image) {
                if (image != null) art.setImageBitmap(image);
                else art.setImageResource(R.drawable.art_placeholder);
            }
        });
    }

    private static void setText(TextView v, String text) {
        if (!text.contentEquals(v.getText())) v.setText(text);
    }

    private static void setEnabled(View v, boolean enabled) {
        if (v.isEnabled() == enabled) return;
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.35f);
    }

    private static void setToggle(ImageButton b, boolean on) {
        if (on) b.setColorFilter(ON_COLOR, PorterDuff.Mode.SRC_IN);
        else b.clearColorFilter();
    }

    // --- volume ---

    private final Runnable sendVolume = new Runnable() {
        @Override public void run() {
            int v = volume.flush(SystemClock.uptimeMillis());
            SoloistClient c = client();
            if (v >= 0 && c != null) c.setVolume(v);
        }
    };

    /** wait from VolumeControl.change/step: send now, or once the interval is up. */
    private void changeVolume(long wait) {
        if (wait < 0) return;
        updateVolumeBar();
        main.removeCallbacks(sendVolume);
        if (wait == 0) sendVolume.run();
        else main.postDelayed(sendVolume, wait);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Soloist's volume while it plays here; the system's otherwise.
            if (PlayerText.controlsEnabled(connected, state) && state.getVolume() >= 0) {
                int delta = keyCode == KeyEvent.KEYCODE_VOLUME_UP ? VOLUME_STEP : -VOLUME_STEP;
                changeVolume(volume.step(delta, SystemClock.uptimeMillis()));
                volumeKeyDown = true;
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_MENU) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && volumeKeyDown) {
            volumeKeyDown = false;
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // --- queue panel ---

    private void openQueue() {
        if (queueOpen) return;
        queueOpen = true;
        if (connected && service != null) service.client().refreshQueue();
        queuePanel.setVisibility(View.VISIBLE);
        queuePanel.post(new Runnable() {
            @Override public void run() {
                if (!queueOpen) return;
                queuePanel.setTranslationY(queuePanel.getHeight());
                queuePanel.animate().translationY(0).setDuration(220);
            }
        });
    }

    private void closeQueue() {
        if (!queueOpen) return;
        queueOpen = false;
        queuePanel.animate().translationY(queuePanel.getHeight()).setDuration(180).withEndAction(new Runnable() {
            @Override public void run() {
                if (!queueOpen) queuePanel.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (queueOpen) {
            closeQueue();
            return;
        }
        super.onBackPressed();
    }

    private final class QueueAdapter extends BaseAdapter {
        private List<PlayerState.QueueItem> items = Collections.emptyList();

        void setItems(List<PlayerState.QueueItem> list) {
            if (sameItems(items, list)) return;
            items = list;
            notifyDataSetChanged();
        }

        @Override public int getCount() {
            return items.size();
        }

        @Override public Object getItem(int position) {
            return items.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public boolean isEnabled(int position) {
            return false; // display only: there is no command to jump to an entry
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(PlayerActivity.this).inflate(R.layout.item_queue, parent, false);
            PlayerState.QueueItem item = items.get(position);
            ((TextView) v.findViewById(R.id.item_title)).setText(item.title == null ? "" : item.title);
            ((TextView) v.findViewById(R.id.item_artists)).setText(PlayerText.subtitle(item.artists, null));
            final ImageView thumb = (ImageView) v.findViewById(R.id.thumb);
            final String url = item.smallCoverUrl;
            thumb.setTag(url);
            Bitmap hit = url == null ? null : artwork.get(url, thumbPx);
            if (hit != null) {
                artwork.cancel(thumb);
                thumb.setImageBitmap(hit);
            } else {
                thumb.setImageResource(R.drawable.art_placeholder);
                if (url == null) {
                    artwork.cancel(thumb);
                } else {
                    artwork.load(thumb, url, thumbPx, new ArtworkLoader.Callback<Bitmap>() {
                        @Override public void onArtwork(String loaded, Bitmap image) {
                            if (image != null && loaded.equals(thumb.getTag())) thumb.setImageBitmap(image);
                        }
                    });
                }
            }
            return v;
        }
    }

    private static boolean sameItems(List<PlayerState.QueueItem> a, List<PlayerState.QueueItem> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    // --- status line, settings, debug ---

    private void onStatusTapped() {
        switch (statusAction) {
            case START:
                PlayerService.start(this);
                break;
            case RETRY:
                if (service != null) service.retry();
                break;
            case SETUP:
                openSettings();
                break;
            default:
                break;
        }
    }

    private void openSettings() {
        startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_EDIT, true));
    }

    /** Debuggable builds only: long-press on the status line. */
    private void showDebugMenu() {
        final String[] items = {
            getString(R.string.debug_show_log),
            getString(R.string.debug_restart),
            getString(R.string.debug_restart_trace),
            getString(R.string.debug_kill),
            getString(R.string.debug_stop_service),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.debug_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                showLog();
                                break;
                            case 1:
                            case 2:
                                startService(new Intent(PlayerActivity.this, PlayerService.class)
                                        .setAction(PlayerService.ACTION_RESTART_SOLOIST)
                                        .putExtra(PlayerService.EXTRA_TRACE_AUDIO, which == 2));
                                break;
                            case 3:
                                killSoloist();
                                break;
                            default:
                                PlayerService.stop(PlayerActivity.this);
                                break;
                        }
                    }
                })
                .show();
    }

    private void showLog() {
        StringBuilder sb = new StringBuilder();
        PlayerService s = PlayerService.current();
        if (s == null) {
            sb.append("service not running");
        } else {
            sb.append(s.describe()).append("\n\n");
            List<String> lines = s.getLog();
            for (int i = Math.max(0, lines.size() - 80); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
            // Also to logcat for adb (lines are key-redacted; debuggable builds only).
            for (String line : lines) android.util.Log.i("ST15iDebug", line);
        }
        TextView tv = new TextView(this);
        tv.setTextSize(9);
        tv.setTextIsSelectable(true);
        tv.setText(sb);
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        new AlertDialog.Builder(this).setView(scroll).setPositiveButton(android.R.string.ok, null).show();
    }

    /** SIGKILLs our Soloist children (same uid), to exercise the restart backoff. */
    private void killSoloist() {
        int self = android.os.Process.myPid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        for (File p : procs) {
            if (!p.getName().matches("[0-9]+")) continue;
            try {
                String cmdline = new String(readBytes(new File(p, "cmdline")), "UTF-8");
                if (!cmdline.contains("libargkey.so")) continue;
                String stat = new String(readBytes(new File(p, "stat")), "UTF-8");
                String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                if (Integer.parseInt(f[1]) != self) continue;
                android.os.Process.sendSignal(Integer.parseInt(p.getName()), 9);
            } catch (IOException e) {
                // gone
            } catch (RuntimeException e) {
                // unparsable stat
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

    static boolean isDebuggable(Context c) {
        return (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}

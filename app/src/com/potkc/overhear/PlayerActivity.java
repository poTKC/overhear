package com.potkc.overhear;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.BatteryManager;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import com.potkc.overhear.soloist.PlayerState;
import com.potkc.overhear.soloist.SoloistClient;

/**
 * The player: in full screen a top strip with the time and battery, artwork
 * in the middle (rounded corners), track text, seek bar and controls, and at
 * the bottom the status line (left; English) with the library (opens
 * LibraryActivity at the page of what plays now, as Back does once the queue
 * is closed), queue and
 * settings buttons (right). The queue panel
 * slides up over the player, under the bottom row. State comes from
 * PlayerService (bound with flags 0, so the screen never starts or keeps
 * the service); commands go through the service's SoloistClient.
 *
 * Full screen (Prefs.isFullscreen, default on): FLAG_FULLSCREEN hides the
 * status bar; set before the first layout and re-read on every resume, so a
 * change in setup applies on return. The top strip (time, battery) shows
 * only in full screen; the weighted spacers absorb the difference.
 *
 * There is no volume control on screen: the volume keys adjust the media
 * volume (with the system panel), which PlayerService keeps in step with the
 * phone's Spotify slider.
 *
 * The launcher entry: without an API key it hands over to SetupActivity.
 * Closing it leaves playback running; the menu (Menu key or long-press on
 * settings) stops the service.
 */
public class PlayerActivity extends Activity {
    private static final long TICK_MS = 500;
    private static final long BACKDROP_FADE_MS = 500;
    private static final int QUEUE_SHOWN = 10;
    /** The played part of the seek bar while it is dragged (white otherwise). */
    private static final int SEEK_DRAG_COLOR = 0xff1ed760;
    /** Rubik Regular's digits (subset, tools/make_fonts.py), loaded once. */
    private static Typeface digits;
    private static final int MENU_SETTINGS = 1;
    private static final int MENU_SERVICE = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private ArtworkLoader<Artwork.Cover> artwork;
    private int artPx;
    private int thumbPx;

    private TextView status;
    private View topStrip;
    private TextView clock;
    private TextView battery;
    private GradientDrawable batteryDot;
    private float artRadius;
    private float thumbRadius;
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
    private final SeekHold seekHold = new SeekHold();
    private boolean queueOpen;
    /** The root's background: the cover colour fading into ArtColor.BACKGROUND (ArtColor.gradient). */
    private GradientDrawable backdrop;
    /** The colour at the top of the backdrop now, and the one it is fading to. */
    private int backdropColor = ArtColor.BACKGROUND;
    private int backdropTarget = ArtColor.BACKGROUND;
    private ValueAnimator backdropFade;
    /** The service's finished-jump count when this screen started one; -1 when none is awaited. */
    private int awaitedJump = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        if (!prefs.hasApiKey()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        applyFullscreen(); // before the first layout: no jump at launch
        setContentView(R.layout.activity_player);
        // 8 bits per channel: the dark gradient bands visibly in RGB 565.
        getWindow().setFormat(PixelFormat.RGBA_8888);
        backdrop = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                ArtColor.gradient(ArtColor.BACKGROUND, ArtColor.BACKGROUND));
        backdrop.setDither(true);
        findViewById(R.id.root).setBackground(backdrop);
        setVolumeControlStream(AudioManager.STREAM_MUSIC); // the keys always change the media volume
        float density = getResources().getDisplayMetrics().density;
        artPx = Math.round(240 * density);
        thumbPx = Math.round(40 * density);
        artRadius = 6 * density;
        thumbRadius = 3 * density;
        artwork = Artwork.loader(this);

        status = (TextView) findViewById(R.id.status);
        topStrip = findViewById(R.id.top_strip);
        clock = (TextView) findViewById(R.id.clock);
        battery = (TextView) findViewById(R.id.battery);
        batteryDot = new GradientDrawable();
        batteryDot.setShape(GradientDrawable.OVAL);
        batteryDot.setColor(StatusStrip.UNKNOWN);
        findViewById(R.id.battery_dot).setBackground(batteryDot);
        art = (ImageView) findViewById(R.id.artwork);
        title = (TextView) findViewById(R.id.title);
        subtitle = (TextView) findViewById(R.id.subtitle);
        elapsed = (TextView) findViewById(R.id.elapsed);
        duration = (TextView) findViewById(R.id.duration);
        // Clock, battery number and seek times: digits and ':' only (StatusStrip.DIGITS_FONT_CHARS).
        Typeface d = digitsTypeface(this);
        if (d != null) {
            clock.setTypeface(d);
            battery.setTypeface(d);
            elapsed.setTypeface(d);
            duration.setTypeface(d);
        }
        seek = (SeekBar) findViewById(R.id.seek);
        shuffle = (ImageButton) findViewById(R.id.shuffle);
        prev = (ImageButton) findViewById(R.id.prev);
        playPause = (ImageButton) findViewById(R.id.play_pause);
        next = (ImageButton) findViewById(R.id.next);
        repeat = (ImageButton) findViewById(R.id.repeat);
        queuePanel = findViewById(R.id.queue_panel);
        queueList = (ListView) findViewById(R.id.queue_list);
        queueEmpty = (TextView) findViewById(R.id.queue_empty);
        queueAdapter = new QueueAdapter();
        queueList.setAdapter(queueAdapter);
        title.setSelected(true); // marquee
        art.setImageDrawable(RoundedArt.placeholder(getResources(), artRadius));

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
        findViewById(R.id.settings).setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showMenu();
                return true;
            }
        });
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (service != null) service.togglePlayPause();
            }
        });
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SoloistClient c = control();
                if (c != null) c.prev();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SoloistClient c = control();
                if (c != null) c.next();
            }
        });
        shuffle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SoloistClient c = control();
                if (c != null) c.setShuffle(!state.isShuffle());
            }
        });
        repeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SoloistClient c = control();
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
                setSeekProgressColor(SEEK_DRAG_COLOR);
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                setSeekProgressColor(0);
                SoloistClient c = control();
                if (c == null) return;
                c.seek(bar.getProgress());
                seekHold.seeked(bar.getProgress(), System.currentTimeMillis(), state.getPositionTimestampMs());
            }
        });
        findViewById(R.id.library_button).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openLibrary();
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
        queueList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                jumpTo(position);
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
        applyFullscreen(); // setup may have changed it
        started = true;
        // Only while resumed: the strip is not seen otherwise.
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockReceiver, time);
        updateBattery(registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        updateClock();
        // Flags 0: connects when the service runs, never creates or keeps it.
        bound = bindService(new Intent(this, PlayerService.class), connection, 0);
        attach();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        started = false;
        unregisterReceiver(clockReceiver);
        unregisterReceiver(batteryReceiver);
        main.removeCallbacks(tick);
        detach();
        if (bound) unbindService(connection);
        bound = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (artwork != null) artwork.cancelAll(Artwork.viewsOf(this));
        if (backdropFade != null) backdropFade.cancel();
        super.onDestroy();
    }

    private void applyFullscreen() {
        int full = WindowManager.LayoutParams.FLAG_FULLSCREEN;
        boolean want = prefs.isFullscreen();
        if (topStrip != null) {
            boolean strip = StatusStrip.visible(want);
            topStrip.setVisibility(strip ? View.VISIBLE : View.GONE);
            // The open queue leaves the strip (time, battery) visible.
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) queuePanel.getLayoutParams();
            int top = strip ? topStrip.getLayoutParams().height : 0;
            if (lp.topMargin != top) {
                lp.topMargin = top;
                queuePanel.setLayoutParams(lp);
            }
        }
        if (((getWindow().getAttributes().flags & full) != 0) == want) return;
        if (want) getWindow().addFlags(full);
        else getWindow().clearFlags(full);
    }

    // --- top strip ---

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    private void updateClock() {
        // The system 12/24 h setting, but no AM/PM: the digits font has no letters.
        Calendar now = Calendar.getInstance();
        setText(clock, StatusStrip.clockText(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(this)));
    }

    /** The digits typeface from assets, or null if it cannot be read (the default font then). */
    private static Typeface digitsTypeface(Context c) {
        if (digits == null) {
            try {
                digits = Typeface.createFromAsset(c.getAssets(), "fonts/overhear-digits.ttf");
            } catch (RuntimeException e) {
                android.util.Log.w("OverhearPlayer", "digits font: " + e);
            }
        }
        return digits;
    }

    /** From the sticky ACTION_BATTERY_CHANGED (null if none yet). */
    private void updateBattery(Intent i) {
        if (i == null) return;
        int pct = StatusStrip.percent(i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                i.getIntExtra(BatteryManager.EXTRA_SCALE, -1));
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING;
        setText(battery, StatusStrip.batteryText(pct));
        batteryDot.setColor(StatusStrip.batteryColor(pct, charging));
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

    /** client() for a control the user pressed: it ends a queue jump first. */
    private SoloistClient control() {
        if (service != null) service.userInput();
        return client();
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
                ? PlayerText.status(false, null, false, null, 0, null, false, state, prefs.getDeviceName())
                : PlayerText.status(true, service.getSupervisorState(), service.needsSetup(), service.getPhase(),
                        service.getPhaseValue(), service.getAuthError(), connected, state, prefs.getDeviceName());
        setText(status, st.text);
        statusAction = st.action;

        boolean hasTrack = connected && state.getTitle() != null;
        setText(title, hasTrack ? state.getTitle() : getString(R.string.no_track));
        setText(subtitle, hasTrack ? PlayerText.subtitle(state.getArtists(), state.getAlbum()) : "");
        showArtwork(hasTrack ? state.getMediumCoverUrl() : null);

        QueueJump jump = service == null ? null : service.getJump();
        boolean jumping = jump != null;
        // A Web API jump only blocks the queue: a control pressed meanwhile ends the wait (PlayerService.userInput),
        // though a play request already sent may still land afterwards (Spotify gives no way to cancel it).
        boolean webJumping = service != null && service.isWebJumping();
        // While a jump skips, the controls rest (and the queue takes no other tap).
        boolean enabled = PlayerText.controlsEnabled(connected, state) && !jumping;
        PlayerState.Status ps = state.getStatus();
        boolean playing = ps == PlayerState.Status.PLAYING || ps == PlayerState.Status.BUFFERING;
        setImage(playPause, playing ? R.drawable.ic_pause_button : R.drawable.ic_play_button);
        setEnabled(playPause, enabled);
        setEnabled(prev, enabled);
        setEnabled(next, enabled);
        // Faded and untappable where Soloist does not allow them (radio ...);
        // an option that is on stays green, just faded.
        setEnabled(shuffle, PlayerText.shuffleEnabled(connected, state) && !jumping);
        setEnabled(repeat, PlayerText.repeatEnabled(connected, state) && !jumping);
        shuffle.setActivated(state.isShuffle()); // ic_shuffle_state: green with a dot
        repeat.setImageLevel(PlayerText.repeatLevel(state.getRepeat())); // ic_repeat_levels
        setEnabled(seek, enabled && state.getDurationMs() > 0);

        List<PlayerState.QueueItem> q = connected ? state.getQueue()
                : Collections.<PlayerState.QueueItem>emptyList();
        List<PlayerState.QueueItem> shown = q.size() > QUEUE_SHOWN ? q.subList(0, QUEUE_SHOWN) : q;
        boolean tappable = PlayerText.queueTappable(connected, state, jumping || webJumping);
        if (webJumping) {
            // With a uid the row is found by it alone, so it does not drift when the list shifts.
            String uid = service.getWebJumpUid();
            queueAdapter.setItems(shown, tappable, uid, service.getWebJumpUri(),
                    uid != null ? -1 : service.getWebJumpIndex());
        } else if (jump != null) {
            queueAdapter.setItems(shown, tappable, jump.getUid(), jump.getUri(), jump.getIndex() - jump.getSteps());
        } else {
            queueAdapter.setItems(shown, tappable, null, null, -1);
        }
        queueList.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        queueEmpty.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
        if (awaitedJump >= 0 && (service == null || service.getJumpsFinished() > awaitedJump)) {
            // A successful jump closes the queue; a failed one leaves it open to try again.
            if (service != null && service.lastJumpOk()) closeQueue();
            awaitedJump = -1;
        }

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
        long pos = seekHold.position(state, System.currentTimeMillis());
        seek.setProgress((int) pos);
        setText(elapsed, PlayerText.time(pos));
    }

    private void showArtwork(String url) {
        String u = url == null ? "" : url;
        if (u.equals(artUrl)) return;
        artUrl = u;
        if (url == null) {
            artwork.cancel(art);
            art.setImageDrawable(RoundedArt.placeholder(getResources(), artRadius));
            setBackdrop(ArtColor.BACKGROUND);
            return;
        }
        // The old cover stays until the new one is here (or failed).
        artwork.load(art, url, artPx, true, new ArtworkLoader.Callback<Artwork.Cover>() {
            @Override public void onArtwork(String loaded, Artwork.Cover image) {
                if (image != null) {
                    art.setImageDrawable(new RoundedArt(image.bitmap, artRadius));
                    setBackdrop(image.color);
                } else {
                    art.setImageDrawable(RoundedArt.placeholder(getResources(), artRadius));
                    setBackdrop(ArtColor.BACKGROUND);
                }
            }
        });
    }

    /**
     * Cross-fades the backdrop's top colour to color over BACKDROP_FADE_MS
     * (ArtColor.BACKGROUND: back to the plain background).
     */
    private void setBackdrop(int color) {
        if (color == backdropTarget) return;
        backdropTarget = color;
        if (backdropFade != null) backdropFade.cancel(); // from wherever it got to
        backdropFade = ValueAnimator.ofObject(new ArgbEvaluator(), backdropColor, color);
        backdropFade.setDuration(BACKDROP_FADE_MS);
        backdropFade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                backdropColor = (Integer) a.getAnimatedValue();
                backdrop.setColors(ArtColor.gradient(backdropColor, ArtColor.BACKGROUND));
            }
        });
        backdropFade.start();
    }

    private static void setText(TextView v, String text) {
        if (!text.contentEquals(v.getText())) v.setText(text);
    }

    private static void setEnabled(View v, boolean enabled) {
        if (v.isEnabled() == enabled) return;
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.4f);
    }

    /**
     * Tints the played part of the seek bar (0: back to its white). A colour
     * filter on the progress layer: on API 18 a SeekBar ignores state lists
     * in its progress drawable, and swapping the drawable resets its bounds.
     */
    private void setSeekProgressColor(int color) {
        Drawable d = seek.getProgressDrawable();
        Drawable progress = d instanceof LayerDrawable
                ? ((LayerDrawable) d).findDrawableByLayerId(android.R.id.progress) : null;
        if (progress == null) return;
        if (color == 0) progress.clearColorFilter();
        else progress.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        seek.invalidate();
    }

    /** Sets the image only when it changes (the resource id is kept in the tag). */
    private static void setImage(ImageButton b, int res) {
        if (Integer.valueOf(res).equals(b.getTag())) return;
        b.setTag(res);
        b.setImageResource(res);
    }

    // --- keys (the volume keys are left to the system: media volume) ---

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // --- queue panel ---

    /** A tap on queue entry position: jump there (PlayerService: the Web API or QueueJump). */
    private void jumpTo(int position) {
        if (service == null || !connected || position >= queueAdapter.getCount()) return;
        int before = service.getJumpsFinished();
        if (service.jumpTo(position, (PlayerState.QueueItem) queueAdapter.getItem(position))) {
            awaitedJump = before;
        }
        render();
    }

    private void openQueue() {
        if (queueOpen) return;
        queueOpen = true;
        SoloistClient c = client();
        if (c != null) c.refreshQueue();
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
        // Back goes to the library, as its ♫ comes back here; Home leaves the app.
        openLibrary();
    }

    /**
     * Opens the library at the page of what plays now: Soloist's context as
     * of the last render (none while no player is connected), which
     * LibraryActivity turns into its stack.
     */
    private void openLibrary() {
        Intent i = new Intent(this, LibraryActivity.class);
        // Without a track (as render shows it; Soloist idle after a restart, say) nothing plays,
        // whatever the context says.
        String uri = connected && state.getTitle() != null ? state.getContextUri() : null;
        if (uri != null) {
            String name = state.getContextName();
            String cover = null;
            // Right after a play the track (and the name) may still be the last context's.
            if (uri.equals(state.getAlbumUri())) {
                if (name == null || name.isEmpty()) name = state.getAlbum(); // the name lags a play a moment
                cover = state.getSmallCoverUrl() != null ? state.getSmallCoverUrl() : state.getCoverUrl();
            }
            i.putExtra(LibraryActivity.EXTRA_CONTEXT_URI, uri)
                    .putExtra(LibraryActivity.EXTRA_CONTEXT_NAME, name)
                    .putExtra(LibraryActivity.EXTRA_CONTEXT_COVER, cover);
        }
        startActivity(i);
    }

    private final class QueueAdapter extends BaseAdapter {
        private List<PlayerState.QueueItem> items = Collections.emptyList();
        private boolean tappable;
        /** The entry a jump is heading for (by uid; by place before the first skip without one). */
        private String busyUid;
        private String busyUri;
        private int busyIndex = -1;

        /** uid, uri, index: the jump's target (uri null: none), index its current place in list. */
        void setItems(List<PlayerState.QueueItem> list, boolean tap, String uid, String uri, int index) {
            if (sameItems(items, list) && tap == tappable && eq(uid, busyUid) && eq(uri, busyUri)
                    && index == busyIndex) {
                return;
            }
            items = list;
            tappable = tap;
            busyUid = uid;
            busyUri = uri;
            busyIndex = index;
            notifyDataSetChanged();
        }

        private boolean isBusy(int position) {
            if (busyUri == null) return false;
            PlayerState.QueueItem item = items.get(position);
            if (busyUid != null) return busyUid.equals(item.uid);
            return position == busyIndex && busyUri.equals(item.uri);
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

        @Override public boolean areAllItemsEnabled() {
            return tappable;
        }

        @Override public boolean isEnabled(int position) {
            return tappable; // a tap skips there; none while a jump runs or the controls are off
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(PlayerActivity.this).inflate(R.layout.item_queue, parent, false);
            PlayerState.QueueItem item = items.get(position);
            ((TextView) v.findViewById(R.id.item_title)).setText(item.title == null ? "" : item.title);
            ((TextView) v.findViewById(R.id.item_artists)).setText(PlayerText.subtitle(item.artists, null));
            final ImageView thumb = (ImageView) v.findViewById(R.id.thumb);
            boolean busy = isBusy(position);
            v.findViewById(R.id.busy).setVisibility(busy ? View.VISIBLE : View.GONE);
            thumb.setAlpha(busy ? 0.3f : 1f);
            final String url = item.smallCoverUrl;
            thumb.setTag(url);
            Artwork.Cover hit = url == null ? null : artwork.get(url, thumbPx);
            if (hit != null) {
                artwork.cancel(thumb);
                thumb.setImageDrawable(new RoundedArt(hit.bitmap, thumbRadius));
            } else {
                thumb.setImageDrawable(RoundedArt.placeholder(getResources(), thumbRadius));
                if (url == null) {
                    artwork.cancel(thumb);
                } else {
                    artwork.load(thumb, url, thumbPx, false, new ArtworkLoader.Callback<Artwork.Cover>() {
                        @Override public void onArtwork(String loaded, Artwork.Cover image) {
                            if (image != null && loaded.equals(thumb.getTag())) {
                                thumb.setImageDrawable(new RoundedArt(image.bitmap, thumbRadius));
                            }
                        }
                    });
                }
            }
            return v;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
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
        run(statusAction);
    }

    private void run(PlayerText.Action action) {
        switch (action) {
            case STOP:
                // onDestroy stops Soloist and releases the locks; the tick
                // notices the service is gone and shows TAP_TO_START.
                PlayerService.stop(this);
                break;
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

    /** Menu key or long-press on settings: settings, and stop (or start) the service. */
    private void showMenu() {
        final boolean up = PlayerService.current() != null;
        PopupMenu menu = new PopupMenu(this, findViewById(R.id.settings));
        menu.getMenu().add(Menu.NONE, MENU_SETTINGS, 0, R.string.menu_settings);
        menu.getMenu().add(Menu.NONE, MENU_SERVICE, 1, PlayerText.serviceMenuLabel(up));
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == MENU_SETTINGS) openSettings();
                else run(PlayerText.serviceMenuAction(up));
                return true;
            }
        });
        menu.show();
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
            for (String line : lines) android.util.Log.i("OverhearDebug", line);
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

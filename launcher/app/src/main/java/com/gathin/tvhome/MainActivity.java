package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    /** How far the Now playing card lifts when focused; less than a tile, since it is much wider. */
    private static final float NOW_PLAYING_SCALE = 1.04f;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private View btnInputs;
    private TextView btnWifi;
    private TextView shamsiDate;
    private boolean shamsi;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    /** Between onResume and onPause; package broadcasts while paused only mark the tiles stale. */
    private boolean resumed;
    /**
     * The tiles no longer match the TV: never built, a package changed while Home was away, or the
     * drawables were dropped under memory pressure. Otherwise a return to Home keeps the tiles as they are.
     */
    private boolean tilesStale = true;
    /** The grid's entry animation has played; it plays once per build from scratch, not on every return. */
    private boolean tilesShown;
    /** PackageManager's change counter at the last check (getChangedPackages). */
    private int packageSeq;
    private int backgroundColor;
    private File wallpaperFile;
    private android.widget.ImageView wallView;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** The Inputs, Settings or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    private TextView btnSettings;
    /** Now playing card (NowPlaying): the media session of an app playing in the background. */
    private boolean nowPlayingOn;
    private View nowPlaying;
    private ImageView nowPlayingArt;
    private ImageView nowPlayingState;
    private TextView nowPlayingTitle;
    private TextView nowPlayingSubtitle;
    private MediaSessionManager sessions;
    private ComponentName sessionListener;
    private final List<MediaController> watched = new ArrayList<>();
    private MediaController shownSession;
    /** A session to show; the card is on screen when there is one, room for it, and the grid is at its top. */
    private boolean nowPlayingHasSession;
    private boolean nowPlayingVisible;
    private Bitmap nowPlayingSource;
    private String nowPlayingPkg;
    private final Runnable nowPlayingRefresh = () -> NowPlaying.run(() -> {
        refreshNowPlaying();
        return null;
    }, null);
    private final MediaController.Callback sessionCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            scheduleNowPlaying();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            scheduleNowPlaying();
        }

        @Override
        public void onSessionDestroyed() {
            scheduleNowPlaying();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> NowPlaying.run(() -> {
        watch(list);
        return null;
    }, null);
    /** self_update (config.xml or a theme's config.json): off, manual or weekly. */
    private String selfUpdate;
    private Dialog updatePrompt;
    // Static so they survive the activity being recreated: the newer version the last check found,
    // and whether the owner still has to be asked about it in this process.
    private static Updater.Feed offered;
    private static boolean updatePromptDue;
    /** Bitcoin line (Ticker): on from btc_ticker, or from the Settings menu, which wins once used. */
    private TextView btcTicker;
    private boolean btcOn;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    private static final String PREFS = "home";
    private static final String PREF_BTC = "btc_ticker";
    private static final String PREF_GALLERY = "gallery";
    private static final int REQUEST_PHOTOS = 7;
    /** Gallery (Gallery.java): on from gallery, or from the Settings menu, which wins once used. */
    private boolean galleryOn;
    /** Two photos for the crossfade and the scrim over them, under everything; null until a photo shows. */
    private FrameLayout galleryLayer;
    private ImageView galleryFront;
    private ImageView galleryBack;
    private long galleryShownAt;
    private boolean galleryLoading;
    // Static so a recreated activity doesn't list the folder again or start a new round of photos.
    private static volatile List<String> galleryPhotos = Collections.emptyList();
    private static volatile long galleryListedAt;
    private static final Gallery.ShuffleBag galleryBag = new Gallery.ShuffleBag(new java.util.Random());
    private final Runnable galleryTick = new Runnable() {
        @Override
        public void run() {
            nextPhoto();
            handler.postDelayed(this, Gallery.INTERVAL_MS);
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            if (!resumed) {
                tilesStale = true;   // rebuilt when Home comes back
                return;
            }
            reloadApps(false, true);
        }
    };
    private final Runnable btcTick = new Runnable() {
        @Override
        public void run() {
            fetchTicker();
            handler.postDelayed(this, Ticker.REFRESH_MS);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // The root (or the wallpaper) paints every pixel; a window background under it is a wasted full-screen fill.
        getWindow().setBackgroundDrawable(null);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        COLUMNS = cfg.columns;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        backgroundColor = cfg.backgroundColor;
        root.setBackgroundColor(backgroundColor);
        wallpaperFile = cfg.wallpaper;
        if (wallpaperFile != null) showWallpaper(wallpaperFile);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, COLUMNS, density)), density);
            int dockRow = itemH + (int) ((6 + 12) * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            int want = Math.max(0, h - dockRow - gridRows - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        btnInputs = findViewById(R.id.btnInputs);
        btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });
        selfUpdate = cfg.selfUpdate;
        greetingName = cfg.greetingName;
        btcTicker = findViewById(R.id.btcTicker);
        btcOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_BTC, cfg.btcTicker);
        galleryOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_GALLERY, cfg.gallery);
        // The first start after a self-update says so (and puts accessibility services back if it can).
        int started = UpdateFeature.run(() -> Updater.afterStart(this), Updater.STARTED_PLAIN);
        if (started != Updater.STARTED_PLAIN) {
            Toast.makeText(this, getString(started == Updater.STARTED_UPDATED ? R.string.updated_to : R.string.updated_services_off,
                    BuildConfig.VERSION_NAME), Toast.LENGTH_LONG).show();
        }

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // The left end is Inputs, or Wi-Fi on a box without inputs.
                return v.getId() == R.id.btnInputs
                        || (v.getId() == R.id.btnWifi && btnInputs.getVisibility() != View.VISIBLE);
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        setUpNowPlaying(cfg.nowPlaying);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && (newF.getId() == R.id.card || newF.getId() == R.id.nowPlaying);
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        // Registered for the activity's life, not just while resumed: a package that changes while
        // another app is in front marks the tiles stale, so returning Home rebuilds only when needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            wallView = wall;
            updateBackdrop();
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        tilesReady = false;
        promptsPaused = false;
        if (wallpaperFile != null && wallView == null) showWallpaper(wallpaperFile);   // dropped by onTrimMemory
        // Both checks run every time: packagesChanged() also moves the counter on.
        boolean changed = packagesChanged();
        reloadApps(true, changed || tilesStale);
        updateGreeting();
        updateWifiLabel();
        updateInputsButton();
        handler.postDelayed(minuteTick, MINUTE_MS);
        startTicker();
        startNowPlaying();
        startGallery();
        Updater.installListener = () -> handler.post(() -> {
            if (updatePrompt != null) updatePrompt.dismiss();
        });
        updateUpdateDot();
        checkWeekly();
    }

    /**
     * True when a package was added, removed or updated since the last call (or the platform can't say).
     * One cheap call; when it says nothing changed, coming back Home skips the app and card scan.
     */
    private boolean packagesChanged() {
        try {
            ChangedPackages cp = getPackageManager().getChangedPackages(packageSeq);
            if (cp == null) return false;
            packageSeq = cp.getSequenceNumber();
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Puts focus back where it was, after rebuilding the tiles (and the module cards) when reload is set.
     * resume: Home is coming to the front, so the glass and the hero fade back in on the returning tile.
     */
    private void reloadApps(boolean resume, boolean reload) {
        final boolean animate = resume && reload && !tilesShown;
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = resume || grid.hasFocus();
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        if (reload) reloadTiles();
        if (animate) grid.scheduleLayoutAnimation();
        tilesShown = true;

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // One LEANBACK query for both the tiles and the card checks.
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), leanback);
        tilesStale = false;
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(btcTick);
        handler.removeCallbacks(galleryTick);
        stopNowPlaying();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (updatePrompt != null) updatePrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
        Updater.installListener = null;
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork and
     * the wallpaper, and rebuild them when Home comes back. Not at UI_HIDDEN, the ordinary trip to an
     * app, where keeping them is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is hidden when an accessibility service keeps
        // the process in the foreground; never blank the screen the owner is looking at.
        boolean due = level >= TRIM_MEMORY_BACKGROUND
                || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL;
        if (!due || resumed) return;
        adapter.clear();
        tilesStale = true;
        tilesShown = false;
        if (wallView != null) {
            wallView.setImageDrawable(null);
            root.removeView(wallView);
            wallView = null;
        }
        dropGallery();   // the next return to Home shows a new photo
        updateBackdrop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        Rect r = glassTo(card, AppAdapter.FOCUS_SCALE, AppAdapter.cornerRadius(card.getWidth(), density));
        float pad = 6f * density;        // matches GlassView's glow inset
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    /**
     * Springs the glass highlight onto {@code target} as it looks scaled by {@code scale}, with the
     * target's unscaled corner {@code radius}. Returns the target's rect in root coordinates, from
     * layout positions (ignores the scale animation).
     */
    private Rect glassTo(View target, float scale, float radius) {
        Rect r = new Rect(0, 0, target.getWidth(), target.getHeight());
        root.offsetDescendantRectToMyCoords(target, r);
        // The Now playing card is placed with setY (placeNowPlaying), which the layout rect leaves out.
        if (target == nowPlaying) r.offset(Math.round(target.getTranslationX()), Math.round(target.getTranslationY()));
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = target.getWidth() * scale + 2 * pad;
        float h = target.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
        }
        glass.setRadius(radius * scale + pad);
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            // The spring works in grid content coordinates; a target outside the grid simply doesn't scroll.
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        return r;
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(ambientAlpha()).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f * ambientAlpha()).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(ambientAlpha()).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    /** The app-coloured glow stays off over Gallery photos: tinting family photos red or blue looks wrong. */
    private float ambientAlpha() {
        return galleryOn ? 0f : 1f;
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return position % COLUMNS == 0;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return position % COLUMNS == COLUMNS - 1 || position == count - 1;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {
                if (!nowPlayingVisible) return false;      // dock row: let focus move to the top bar
                // With the Now playing card up, only the last dock tile goes to it; the others go to
                // the top button above them, as without the card.
                if (position == COLUMNS - 1) nowPlaying.requestFocus();
                else topButtonAbove(position).requestFocus();
                return true;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    /** The visible top button nearest horizontally to the dock tile at {@code position}. */
    private View topButtonAbove(int position) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        float x = 0;
        if (vh != null) {
            Rect r = new Rect(0, 0, vh.itemView.getWidth(), vh.itemView.getHeight());
            root.offsetDescendantRectToMyCoords(vh.itemView, r);
            x = r.exactCenterX();
        }
        View best = btnSettings;
        float bestD = Float.MAX_VALUE;
        for (View b : new View[]{btnInputs, btnWifi, btnSettings}) {
            if (b.getVisibility() != View.VISIBLE) continue;
            Rect r = new Rect(0, 0, b.getWidth(), b.getHeight());
            root.offsetDescendantRectToMyCoords(b, r);
            float d = Math.abs(r.exactCenterX() - x);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(greetingName.isEmpty() ? getString(res) : getString(res) + ", " + greetingName);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        dropIn(popup, panel, anchor, Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- module cards ----------------------------------------------------------------------------

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        showCardPromptIfDue();
        showUpdatePrompt();   // waits for every card prompt to be answered
    }

    private void showCardPromptIfDue() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = glassDialog(panel);
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** A centred glass panel over the dimmed home screen: the card and update prompts. */
    private Dialog glassDialog(View panel) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        return dialog;
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }

    // ---- Now playing ----------------------------------------------------------------------------

    private void setUpNowPlaying(boolean on) {
        nowPlayingOn = on;
        nowPlaying = findViewById(R.id.nowPlaying);
        nowPlayingArt = findViewById(R.id.nowPlayingArt);
        nowPlayingState = findViewById(R.id.nowPlayingState);
        nowPlayingTitle = findViewById(R.id.nowPlayingTitle);
        nowPlayingSubtitle = findViewById(R.id.nowPlayingSubtitle);
        sessionListener = new ComponentName(this, NowPlayingListener.class);
        heroName.setEllipsize(TextUtils.TruncateAt.END);
        nowPlaying.setContentDescription(getString(R.string.now_playing_description));
        // Focus looks like a tile's: the card lifts and the glass highlight springs onto it.
        nowPlaying.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? NOW_PLAYING_SCALE : 1.0f;
            v.animate().scaleX(s).scaleY(s)
                    .setDuration(focused ? 260 : 180)
                    .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                    .start();
            if (focused) glassTo(v, NOW_PLAYING_SCALE, 24 * density);
        });
        final float artRadius = 14 * density;
        nowPlayingArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), artRadius);
            }
        });
        nowPlayingArt.setClipToOutline(true);
        nowPlaying.setOnClickListener(v -> NowPlaying.run(() -> {
            MediaController c = shownSession;
            if (c == null) return null;
            PlaybackState st = c.getPlaybackState();
            if (st != null && NowPlaying.isActive(st.getState())) c.getTransportControls().pause();
            else c.getTransportControls().play();
            return null;
        }, null));
        nowPlaying.setOnLongClickListener(v -> {
            NowPlaying.run(() -> {
                openNowPlayingApp();
                return null;
            }, null);
            return true;
        });
        nowPlaying.setOnKeyListener((v, keyCode, event) -> {
            boolean left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
            if (!left && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusTile(COLUMNS - 1);                      // back to the tile it is entered from
                return true;
            }
            if (event.getRepeatCount() > 0) return true;   // one skip per press, not a run of them
            NowPlaying.run(() -> {
                MediaController c = shownSession;
                if (c == null) return null;
                if (left) c.getTransportControls().skipToPrevious();
                else c.getTransportControls().skipToNext();
                return null;
            }, null);
            nowPlayingArt.animate().translationX((left ? -6 : 6) * density).setDuration(90)
                    .withEndAction(() -> nowPlayingArt.animate().translationX(0f).setDuration(160).start()).start();
            return true;
        });
        // The card sits in the space above the dock: keep it there as the layout settles, and out of the
        // way while the grid is scrolled up over that space.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> grid.post(this::placeNowPlaying));
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (nowPlayingHasSession) placeNowPlaying();
            }
        });
    }

    private void startNowPlaying() {
        if (!nowPlayingOn || !NowPlaying.working()) return;
        NowPlaying.run(() -> {
            if (!listenerGranted()) {
                nowPlayingHasSession = false;
                placeNowPlaying();
                return null;
            }
            if (sessions == null) sessions = getSystemService(MediaSessionManager.class);
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, sessionListener, handler);
            watch(sessions.getActiveSessions(sessionListener));
            return null;
        }, null);
        if (!NowPlaying.working()) {
            nowPlayingHasSession = false;
            placeNowPlaying();
        }
    }

    private void stopNowPlaying() {
        handler.removeCallbacks(nowPlayingRefresh);
        NowPlaying.run(() -> {
            if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            unwatch();
            return null;
        }, null);
    }

    /** Whether the notification listener is enabled, without which Android lists no sessions. */
    private boolean listenerGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(sessionListener);
        }
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && Arrays.asList(enabled.split(":")).contains(sessionListener.flattenToString());
    }

    /** Follows every active session, so whichever one starts playing can take the card. */
    private void watch(List<MediaController> list) {
        unwatch();
        if (list != null) {
            for (MediaController c : list) {
                c.registerCallback(sessionCallback, handler);
                watched.add(c);
            }
        }
        refreshNowPlaying();
    }

    private void unwatch() {
        for (MediaController c : watched) {
            try {
                c.unregisterCallback(sessionCallback);
            } catch (Exception ignored) {
                // A session that died already has nothing to unregister.
            }
        }
        watched.clear();
    }

    /** Callbacks can come in bursts (state, then metadata); draw once after them. */
    private void scheduleNowPlaying() {
        handler.removeCallbacks(nowPlayingRefresh);
        handler.postDelayed(nowPlayingRefresh, 120);
    }

    private void refreshNowPlaying() {
        int n = watched.size();
        int[] states = new int[n];
        String[] packages = new String[n];
        for (int i = 0; i < n; i++) {
            PlaybackState st = watched.get(i).getPlaybackState();
            states[i] = st != null ? st.getState() : NowPlaying.STATE_NONE;
            packages[i] = watched.get(i).getPackageName();
        }
        int pick = NowPlaying.choose(states, packages, getPackageName());
        shownSession = pick >= 0 ? watched.get(pick) : null;
        nowPlayingHasSession = shownSession != null;
        if (nowPlayingHasSession) bindNowPlaying(shownSession, states[pick]);
        placeNowPlaying();
    }

    private void bindNowPlaying(MediaController c, int state) {
        String pkg = c.getPackageName();
        String appLabel = pkg;
        try {
            appLabel = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {
            // A package we can't see keeps its package name.
        }
        MediaMetadata md = c.getMetadata();
        String title = NowPlaying.title(text(md, MediaMetadata.METADATA_KEY_TITLE),
                text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE), appLabel);
        nowPlayingTitle.setText(title);
        String sub = NowPlaying.subtitle(text(md, MediaMetadata.METADATA_KEY_ARTIST),
                text(md, MediaMetadata.METADATA_KEY_ALBUM), appLabel, title);
        nowPlayingSubtitle.setText(sub);
        nowPlayingSubtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        nowPlayingState.setImageResource(NowPlaying.isActive(state) ? R.drawable.ic_pause : R.drawable.ic_play);

        Bitmap art = null;
        if (md != null) {
            for (String key : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                art = md.getBitmap(key);
                if (art != null) break;
            }
        }
        // Metadata comes again with every state change; decode a new picture only when it changed.
        if (art != null && (art != nowPlayingSource || !pkg.equals(nowPlayingPkg))) {
            nowPlayingArt.setImageBitmap(squareThumb(art, (int) (72 * density)));
        } else if (art == null && (nowPlayingSource != null || !pkg.equals(nowPlayingPkg))) {
            Drawable icon = null;
            try {
                // The square icon: a wide banner cropped to a square cuts the app's name off.
                icon = getPackageManager().getApplicationIcon(pkg);
            } catch (Exception ignored) {
                // No picture: the card shows the title alone.
            }
            nowPlayingArt.setImageDrawable(icon);
        }
        nowPlayingSource = art;
        nowPlayingPkg = pkg;
    }

    private static String text(MediaMetadata md, String key) {
        if (md == null) return null;
        CharSequence t = md.getText(key);
        return t != null ? t.toString() : null;
    }

    /** A centre square of the artwork at the card's size, so a 1000 px cover isn't drawn at 72 dp. */
    private static Bitmap squareThumb(Bitmap src, int size) {
        int side = Math.min(src.getWidth(), src.getHeight());
        if (side <= 0) return src;
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        Bitmap square = side == src.getWidth() && side == src.getHeight() ? src : Bitmap.createBitmap(src, x, y, side, side);
        return side > size ? Bitmap.createScaledBitmap(square, size, size, true) : square;
    }

    /**
     * Shows the card right-aligned just above the dock when there is a session, room under the top
     * bar, and the grid sits at its top; otherwise fades it out. The top shelf name is shortened so
     * it never runs under the card.
     */
    private void placeNowPlaying() {
        boolean want = nowPlayingOn && nowPlayingHasSession && NowPlaying.working()
                && !grid.canScrollVertically(-1) && grid.getHeight() > 0;
        float top = 0;
        if (want) {
            if (nowPlaying.getVisibility() != View.VISIBLE) {
                nowPlaying.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.AT_MOST));
            }
            int h = nowPlaying.getVisibility() == View.VISIBLE && nowPlaying.getHeight() > 0
                    ? nowPlaying.getHeight() : nowPlaying.getMeasuredHeight();
            Rect g = new Rect(0, 0, grid.getWidth(), grid.getHeight());
            root.offsetDescendantRectToMyCoords(grid, g);
            Rect bar = new Rect(0, 0, btnSettings.getWidth(), btnSettings.getHeight());
            root.offsetDescendantRectToMyCoords(btnSettings, bar);
            float dockTop = g.top + grid.getPaddingTop();
            top = dockTop - 22 * density - h;
            want = top >= bar.bottom + 14 * density;   // no room with rows under the dock: leave it out
        }
        if (want) {
            nowPlaying.setY(top);
            if (!nowPlayingVisible) {
                nowPlayingVisible = true;
                nowPlaying.setVisibility(View.VISIBLE);
                nowPlaying.animate().alpha(1f).setDuration(240).start();
            }
            nowPlaying.post(() -> {
                int cardLeft = root.getWidth() - (int) (56 * density) - nowPlaying.getWidth();
                heroName.setMaxWidth(Math.max(0, cardLeft - heroName.getLeft() - (int) (24 * density)));
            });
        } else if (nowPlayingVisible) {
            nowPlayingVisible = false;
            if (nowPlaying.hasFocus()) focusTile(lastFocused < COLUMNS ? lastFocused : 0);
            nowPlaying.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (!nowPlayingVisible) nowPlaying.setVisibility(View.GONE);
            }).start();
            heroName.setMaxWidth(Integer.MAX_VALUE);
        }
        // The card is reached only from Settings (Down) and the last dock tile (Up, onTileVerticalKey).
        btnWifi.setNextFocusDownId(R.id.grid);
        btnSettings.setNextFocusDownId(nowPlayingVisible ? R.id.nowPlaying : R.id.grid);
    }

    /** Hold OK on the card: the app's own player screen, or failing that its launcher entry. */
    private void openNowPlayingApp() {
        MediaController c = shownSession;
        if (c == null) return;
        PendingIntent pi = c.getSessionActivity();
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    // Android 14 lets a foreground app start another app's PendingIntent only when it says so.
                    ActivityOptions o = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    pi.send(this, 0, null, null, null, null, o.toBundle());
                } else {
                    pi.send();
                }
                return;
            } catch (PendingIntent.CanceledException ignored) {
                // Fall back to the app's launcher entry.
            }
        }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i != null) open(i);
    }

    // ---- Gallery -----------------------------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PHOTOS) {
            galleryListedAt = 0;
            startGallery();
        }
    }

    private boolean hasPhotoPermission() {
        return checkSelfPermission(Gallery.permission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** The Gallery row's status: Off, On, or On with what's missing. */
    private String galleryState() {
        if (!galleryOn) return getString(R.string.settings_menu_off);
        if (!hasPhotoPermission()) return getString(R.string.gallery_no_permission);
        if (galleryListedAt > 0 && galleryPhotos.isEmpty()) return getString(R.string.gallery_empty);
        return getString(R.string.settings_menu_on);
    }

    /** Shows a photo now if none is up or the current one is five minutes old, then one every five minutes. */
    private void startGallery() {
        handler.removeCallbacks(galleryTick);
        if (!galleryOn || !Gallery.working() || !hasPhotoPermission()) {
            dropGallery();
            updateBackdrop();
            return;
        }
        handler.postDelayed(galleryTick, Gallery.delayUntilNext(galleryShownAt, System.currentTimeMillis()));
    }

    /** Lists the folder when due, picks the next photo and decodes it on the gallery thread. */
    private void nextPhoto() {
        if (galleryLoading || !Gallery.working()) return;
        galleryLoading = true;
        final Context app = getApplicationContext();
        final int w = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        final int h = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
        Gallery.EXECUTOR.execute(() -> {
            Bitmap bmp = Gallery.run(() -> {
                long now = System.currentTimeMillis();
                if (Gallery.relistDue(galleryListedAt, now)) {
                    galleryPhotos = Gallery.list(app);
                    galleryListedAt = now;
                }
                // A photo that can't be read is skipped; give up after a few so a folder of bad files can't spin.
                for (int tries = 0; tries < 5; tries++) {
                    String item = galleryBag.next(galleryPhotos);
                    if (item == null) return null;
                    Bitmap b = Gallery.decode(app, item, w, h);
                    if (b != null) return b;
                }
                return null;
            }, null);
            handler.post(() -> {
                galleryLoading = false;
                if (isDestroyed() || !galleryOn) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp != null) Gallery.run(() -> {
                    showPhoto(bmp);
                    return null;
                }, null);
            });
        });
    }

    /** Crossfades to a new photo over about a second; the one it replaces is let go once covered. */
    private void showPhoto(Bitmap bmp) {
        if (galleryLayer == null) {
            galleryLayer = new FrameLayout(this);
            FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            galleryFront = new ImageView(this);
            galleryBack = new ImageView(this);
            for (ImageView v : new ImageView[] {galleryFront, galleryBack}) {
                v.setScaleType(ImageView.ScaleType.CENTER_CROP);
                galleryLayer.addView(v, new FrameLayout.LayoutParams(full));
            }
            // Darker at the top and bottom so the clock, the header and the dock stay readable on bright photos.
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0x8C000000, 0x1A000000, 0x26000000, 0xB3000000}));
            galleryLayer.addView(scrim, new FrameLayout.LayoutParams(full));
            root.addView(galleryLayer, wallView != null ? 1 : 0, full);
        }
        final ImageView incoming = galleryBack;
        final ImageView outgoing = galleryFront;
        incoming.animate().cancel();
        incoming.setImageBitmap(bmp);
        incoming.setAlpha(0f);
        galleryLayer.removeView(incoming);
        galleryLayer.addView(incoming, 1);   // above the current photo, under the scrim
        galleryFront = incoming;
        galleryBack = outgoing;
        galleryShownAt = System.currentTimeMillis();
        // The wallpaper (or colour) stays under the first photo until it has faded in.
        incoming.animate().alpha(1f).setDuration(1000).withEndAction(() -> {
            if (galleryBack == outgoing) release(outgoing);
            updateBackdrop();
        }).start();
    }

    /** Lets go of a photo. Not recycle(): a frame already recorded may still draw it; the collector frees it. */
    private static void release(ImageView v) {
        v.setImageDrawable(null);
    }

    /** Removes the photos (Gallery off, or memory trimmed); the theme wallpaper or colour shows again. */
    private void dropGallery() {
        if (galleryLayer == null) return;
        galleryFront.animate().cancel();
        galleryBack.animate().cancel();
        release(galleryFront);
        release(galleryBack);
        root.removeView(galleryLayer);
        galleryLayer = null;
        galleryFront = null;
        galleryBack = null;
        galleryShownAt = 0;
    }

    /**
     * Exactly one full-screen layer paints the background: the gallery, else the theme wallpaper, else the
     * root's colour. The ones underneath would be overdraw.
     */
    private void updateBackdrop() {
        boolean photos = galleryLayer != null;
        if (wallView != null) wallView.setVisibility(photos ? View.GONE : View.VISIBLE);
        if (photos || wallView != null) root.setBackground(null);
        else root.setBackgroundColor(backgroundColor);
    }

    // ---- Bitcoin line ----------------------------------------------------------------------------

    /** Shows the last reading at once, then refreshes now if it's over a minute old and every five minutes after. */
    private void startTicker() {
        if (!btcOn) {
            btcTicker.setVisibility(View.GONE);
            return;
        }
        showTicker(Ticker.last);
        Ticker.Quote q = Ticker.last;
        long age = q == null ? Long.MAX_VALUE : System.currentTimeMillis() - q.at;
        handler.postDelayed(btcTick, age >= Ticker.STALE_MS ? 0 : Math.max(0, Ticker.REFRESH_MS - age));
    }

    /** One refresh on the ticker thread; the result is shown if the line is still on. */
    private void fetchTicker() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null || cm.getActiveNetwork() == null) return;
        if (!Ticker.busy.compareAndSet(false, true)) return;
        Ticker.EXECUTOR.execute(() -> {
            try {
                Ticker.last = Ticker.fetch(Ticker.last);
            } catch (Exception e) {
                // A failed refresh keeps the last reading on screen.
            } finally {
                Ticker.busy.set(false);
            }
            handler.post(() -> {
                if (!isDestroyed() && btcOn) showTicker(Ticker.last);
            });
        });
    }

    /** "₿ $81,002  ▼ 0.3%  BLOCK 972,026"; hidden until there is a price. */
    private void showTicker(Ticker.Quote q) {
        if (q == null || !q.hasPrice()) {
            btcTicker.setVisibility(View.GONE);
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        // Older TVs' fonts may lack U+20BF; spell it out there.
        String sign = btcTicker.getPaint().hasGlyph("\u20BF") ? "\u20BF" : "BTC";
        append(sb, sign, 0xFFF7931A);
        sb.append("  ").append(Ticker.formatPrice(q.price));
        if (!Double.isNaN(q.changePct)) {
            sb.append("   ");
            append(sb, Ticker.formatChange(q.changePct), q.changePct < 0 ? 0xFFFF6B6B : 0xFF4CD787);
        }
        if (q.height >= 0) {
            sb.append("   ");
            append(sb, getString(R.string.btc_block, Ticker.formatHeight(q.height)), 0xB3FFFFFF);
        }
        btcTicker.setText(sb);
        btcTicker.setVisibility(View.VISIBLE);
    }

    private static void append(SpannableStringBuilder sb, String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---- self-update -----------------------------------------------------------------------------

    /** Hold OK on Settings: Module cards, and Check for updates with the installed version. */
    private void showSettingsMenu() {
        View anchor = btnSettings;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (340 * density));

        String cardsState = !moduleCards || !CardFeature.working() ? getString(R.string.settings_menu_off)
                : cards.isEmpty() ? getString(R.string.no_module_cards)
                : getString(R.string.settings_menu_cards_count, cards.size());
        View cardsRow = addMenuRow(rows, getString(R.string.settings_menu_cards), cardsState);
        cardsRow.setOnClickListener(v -> {
            popup.dismiss();
            showModuleCards();
        });

        View btcRow = addMenuRow(rows, getString(R.string.settings_menu_btc),
                getString(btcOn ? R.string.settings_menu_on : R.string.settings_menu_off));
        final TextView btcStatus = btcRow.findViewById(R.id.status);
        btcRow.setOnClickListener(v -> {
            btcOn = !btcOn;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_BTC, btcOn).apply();
            btcStatus.setText(btcOn ? R.string.settings_menu_on : R.string.settings_menu_off);
            handler.removeCallbacks(btcTick);
            startTicker();
        });

        View galleryRow = addMenuRow(rows, getString(R.string.settings_menu_gallery), galleryState());
        final TextView galleryStatus = galleryRow.findViewById(R.id.status);
        galleryRow.setOnClickListener(v -> {
            galleryOn = !galleryOn;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_GALLERY, galleryOn).apply();
            if (heroShown) ambient.animate().alpha(ambientAlpha()).setDuration(300).start();
            galleryStatus.setText(galleryState());
            if (galleryOn && !hasPhotoPermission()) {
                popup.dismiss();
                requestPermissions(new String[] {Gallery.permission()}, REQUEST_PHOTOS);
                return;
            }
            galleryListedAt = 0;   // look at the folder again: the owner may just have added photos
            startGallery();
        });

        View checkRow = addMenuRow(rows, getString(R.string.update_menu_check, BuildConfig.VERSION_NAME), updateState());
        final TextView status = checkRow.findViewById(R.id.status);
        checkRow.setOnClickListener(v -> {
            String state = updateState();
            if (!updatesOn()) {
                status.setText(state);
                return;
            }
            status.setText(R.string.update_checking);
            runCheck(status, popup);
        });

        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private View addMenuRow(LinearLayout rows, String title, String status) {
        View row = getLayoutInflater().inflate(R.layout.item_menu_row, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView s = row.findViewById(R.id.status);
        s.setText(status);
        rows.addView(row);
        return row;
    }

    /** The Check for updates row before a check: why there are no updates, or the version already found. */
    private String updateState() {
        if (Updater.feedUrl().isEmpty()) return getString(R.string.update_self_built);
        if (!updatesOn()) return getString(R.string.update_off);
        Updater.Feed f = offered;
        return f != null ? getString(R.string.update_available, f.versionName) : "";
    }

    /** A build with a feed, self_update not off, and the updater hasn't failed in this process. */
    private boolean updatesOn() {
        return !Updater.feedUrl().isEmpty() && !Updater.MODE_OFF.equals(selfUpdate) && UpdateFeature.working();
    }

    /** Weekly mode: check when the last check is a week old (a day after a failure) and the TV is online. */
    private void checkWeekly() {
        if (!updatesOn() || !Updater.MODE_WEEKLY.equals(selfUpdate)) return;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null || cm.getActiveNetwork() == null) return;
        boolean due = UpdateFeature.run(() -> Updater.checkDue(Updater.load(Updater.stateFile(this)), System.currentTimeMillis()), false);
        if (due) runCheck(null, null);
    }

    /** One check on the update thread. status and popup are the Settings menu's, for a check the owner asked for. */
    private void runCheck(final TextView status, final PopupWindow popup) {
        if (!Updater.busy.compareAndSet(false, true)) return;   // a check or a download is already running
        final Context app = getApplicationContext();
        Updater.EXECUTOR.execute(() -> {
            Updater.Outcome o;
            try {
                o = UpdateFeature.run(() -> Updater.check(app), null);
            } finally {
                Updater.busy.set(false);
            }
            final Updater.Outcome outcome = o;
            handler.post(() -> {
                if (!isDestroyed()) UpdateFeature.run(() -> {
                    checked(outcome, status, popup);
                    return null;
                }, null);
            });
        });
    }

    private void checked(Updater.Outcome o, TextView status, PopupWindow popup) {
        boolean manual = status != null;
        if (o == null) {
            if (manual) status.setText(R.string.update_off);
            return;
        }
        switch (o.kind) {
            case Updater.AVAILABLE:
                offered = o.feed;
                // The weekly check doesn't ask again about a skipped version; the owner's own check does.
                if (manual || o.feed.versionCode != Updater.load(Updater.stateFile(this)).skipped) updatePromptDue = true;
                if (manual) status.setText(getString(R.string.update_available, o.feed.versionName));
                break;
            case Updater.UP_TO_DATE:
                offered = null;
                if (manual) status.setText(R.string.update_up_to_date);
                break;
            case Updater.TOO_OLD:
                offered = null;
                if (manual) status.setText(getString(R.string.update_too_old, o.feed.versionName));
                break;
            case Updater.Outcome.INVALID:
                if (manual) status.setText(R.string.update_invalid);
                break;
            default:
                if (manual) status.setText(R.string.update_unreachable);
                break;
        }
        // The prompt follows straight away; the menu makes room for it.
        if (manual && updatePromptDue && popup != null && popup.isShowing()) popup.dismiss();
        updateUpdateDot();
        showUpdatePrompt();
    }

    /** A dot on the Settings pill while a newer version is known and not skipped. */
    private void updateUpdateDot() {
        boolean waiting = updatesOn() && UpdateFeature.run(() -> {
            Updater.State st = Updater.load(Updater.stateFile(this));
            return st.availableCode > BuildConfig.VERSION_CODE && st.availableCode != st.skipped;
        }, false);
        // drawableTint covers the dot too, so it follows the pill's text colour as the icon does.
        btnSettings.setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_settings), null,
                waiting ? getDrawable(R.drawable.update_dot) : null, null);
    }

    /**
     * Asks about the version the last check found: once its check is done, over a laid out grid with
     * window focus, after every module card prompt has been answered. Nothing installs without Update.
     */
    private void showUpdatePrompt() {
        if (!updatePromptDue || offered == null || updatePrompt != null || cardPrompt != null || openPopup != null) return;
        if (!tilesReady || !hasWindowFocus() || isFinishing() || !updatesOn()) return;
        if (moduleCards && !promptsPaused && !cards.isEmpty() && CardFeature.working()
                && CardFeature.run(() -> Cards.firstPending(cards), null) != null) return;
        UpdateFeature.run(() -> {
            showUpdatePromptNow();
            return null;
        }, null);
    }

    private void showUpdatePromptNow() {
        final Updater.Feed feed = offered;
        View panel = getLayoutInflater().inflate(R.layout.dialog_update_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.update_prompt_title, feed.versionName));
        ((TextView) panel.findViewById(R.id.body)).setText(getString(R.string.update_prompt_body, BuildConfig.VERSION_NAME));
        TextView notes = panel.findViewById(R.id.notes);
        if (feed.notes.isEmpty()) notes.setVisibility(View.GONE);
        else notes.setText(feed.notes);
        final TextView status = panel.findViewById(R.id.status);
        final TextView later = panel.findViewById(R.id.btnLater);
        final TextView update = panel.findViewById(R.id.btnUpdate);
        final TextView skip = panel.findViewById(R.id.btnSkip);

        final Dialog dialog = glassDialog(panel);
        final AtomicBoolean cancel = new AtomicBoolean();
        later.setOnClickListener(v -> {
            updatePromptDue = false;   // the dot stays; the next weekly check asks again
            dialog.dismiss();
        });
        skip.setOnClickListener(v -> {
            updatePromptDue = false;
            UpdateFeature.run(() -> {
                File file = Updater.stateFile(this);
                Updater.State st = Updater.load(file);
                st.skipped = feed.versionCode;
                return Updater.save(file, st);
            }, false);
            dialog.dismiss();
            updateUpdateDot();
        });
        update.setOnClickListener(v -> UpdateFeature.run(() -> {
            startUpdate(feed, dialog, cancel, status, later, update, skip);
            return null;
        }, null));
        dialog.setOnCancelListener(d -> later.performClick());   // Back means Later, and cancels a download
        dialog.setOnDismissListener(d -> {
            cancel.set(true);
            if (updatePrompt == dialog) updatePrompt = null;
        });
        // Left and Right move between the buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (update.getVisibility() != View.VISIBLE) return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnLater;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSkip;
        };
        later.setOnKeyListener(keys);
        update.setOnKeyListener(keys);
        skip.setOnKeyListener(keys);
        updatePrompt = dialog;
        dialog.show();
        later.requestFocus();
    }

    /** Update pressed: the install permission first, then download, verify and install on the update thread. */
    private void startUpdate(Updater.Feed feed, Dialog dialog, AtomicBoolean cancel, TextView status,
                             View later, View update, View skip) {
        status.setVisibility(View.VISIBLE);
        if (!getPackageManager().canRequestPackageInstalls()) {
            // The prompt comes back when Home does, and the owner presses Update again.
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, R.string.update_permission, Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException | SecurityException e) {
                status.setText(R.string.update_permission_adb);
            }
            return;
        }
        if (!Updater.busy.compareAndSet(false, true)) {
            status.setText(R.string.update_checking);   // a weekly check is still running; try again in a moment
            return;
        }
        update.setVisibility(View.GONE);
        skip.setVisibility(View.GONE);
        later.requestFocus();   // Later now cancels the download
        status.setText(getString(R.string.update_downloading, 0));
        final Context app = getApplicationContext();
        Updater.EXECUTOR.execute(() -> {
            int result;
            try {
                result = UpdateFeature.run(() -> Updater.update(app, feed, cancel, (stage, percent) -> handler.post(() -> {
                    if (cancel.get()) return;
                    status.setText(stage == Updater.Progress.DOWNLOADING ? getString(R.string.update_downloading, percent)
                            : getString(stage == Updater.Progress.VERIFYING ? R.string.update_verifying : R.string.update_installing));
                })), Updater.FAILED_INSTALL);
            } finally {
                Updater.busy.set(false);
            }
            final int done = result;
            handler.post(() -> {
                if (done == Updater.DONE) {
                    // Android takes it from here; declining its confirmation counts as Later.
                    updatePromptDue = false;
                    return;
                }
                if (done == Updater.CANCELLED || isDestroyed() || !dialog.isShowing()) return;
                status.setText(done == Updater.FAILED_DOWNLOAD ? R.string.update_download_failed
                        : done == Updater.REJECTED ? R.string.update_rejected : R.string.update_install_failed);
            });
        });
    }
}

package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds the launchable apps and prepares their tiles on a background thread. Tiles are kept for the life of the
 * process, so returning Home costs one package query instead of decoding every banner again; an app is only
 * rebuilt when the system reports it installed, updated, enabled, disabled or removed.
 *
 * Module cards are not handled here: the adapter puts them over these tiles on the main thread (see
 * {@link AppAdapter#setTiles}), so the card code keeps running where its guard and decision file expect it.
 */
final class AppLoader {

    /** What to load: changes to any of these rebuild every tile. */
    static final class Request {
        final Set<String> hidden;
        final boolean showHomeSettings;
        /** Tile size from {@link AppAdapter#tileWidthPx} for the configured columns. */
        final int tileWidth, tileHeight;

        Request(Set<String> hidden, boolean showHomeSettings, int tileWidth, int tileHeight) {
            this.hidden = Collections.unmodifiableSet(new HashSet<>(hidden));
            this.showHomeSettings = showHomeSettings;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
        }

        boolean sameAs(Request o) {
            return o != null && hidden.equals(o.hidden) && showHomeSettings == o.showHomeSettings
                    && tileWidth == o.tileWidth && tileHeight == o.tileHeight;
        }
    }

    /** One load: the app tiles in package-manager order, and the optional "Default home" tile. */
    static final class Snapshot {
        final List<AppAdapter.Entry> apps;
        final AppAdapter.Entry homeSettings;

        Snapshot(List<AppAdapter.Entry> apps, AppAdapter.Entry homeSettings) {
            this.apps = Collections.unmodifiableList(apps);
            this.homeSettings = homeSettings;
        }

        /** Cached tiles are reused while their app is unchanged, so identity is enough. */
        boolean sameAs(Snapshot o) {
            if (o == null || o.homeSettings != homeSettings || o.apps.size() != apps.size()) return false;
            for (int i = 0; i < apps.size(); i++) {
                if (o.apps.get(i) != apps.get(i)) return false;
            }
            return true;
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Packages changed since their tiles were built. Added on the main thread, drained on the worker. */
    private static final Set<String> STALE = Collections.synchronizedSet(new HashSet<>());

    // Worker thread only.
    private static final Map<String, AppAdapter.Entry> CACHE = new HashMap<>();
    private static AppAdapter.Entry homeTile;
    /** Labels are localised and tiles are drawn for one request: a change in either rebuilds them all. */
    private static String cacheLocale;
    private static Request cacheRequest;

    /** The last snapshot handed out; written on the worker, read anywhere. */
    private static volatile Snapshot last;

    // Main thread only.
    private static Request request;
    private static Runnable active;
    private static boolean watching;

    private AppLoader() {
    }

    /** Tiles from the last load, or null before the first one finishes. */
    static Snapshot cached() {
        return last;
    }

    /**
     * Keeps the tiles fresh while Home is resumed: loads now, and again whenever a package changes.
     * {@code onChanged} runs on the main thread, only when the tiles differ from {@link #cached()}.
     */
    static void start(Context ctx, Request req, Runnable onChanged) {
        Context app = ctx.getApplicationContext();
        watch(app);
        request = req;
        active = onChanged;
        refresh(app);
    }

    static void stop() {
        active = null;
    }

    private static void refresh(Context app) {
        final Request req = request;
        final Runnable callback = active;
        if (req == null || callback == null) return;
        WORKER.execute(() -> {
            Snapshot fresh = load(app, req);
            if (fresh.sameAs(last)) return;
            last = fresh;
            MAIN.post(callback);
        });
    }

    /**
     * Registered once for the whole process, not per resume: an app updated while another app is in front
     * must not keep its old banner when Home comes back.
     */
    private static void watch(Context app) {
        if (watching) return;
        watching = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                        && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                Uri data = intent.getData();
                if (data != null) STALE.add(data.getSchemeSpecificPart());
                refresh(app);
            }
        };
        // Package broadcasts come from the system, so a non-exported receiver still gets them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    private static Snapshot load(Context app, Request req) {
        Resources res = app.getResources();
        String locale = res.getConfiguration().getLocales().toLanguageTags();
        if (!locale.equals(cacheLocale) || !req.sameAs(cacheRequest)) {
            CACHE.clear();
            homeTile = null;
            cacheLocale = locale;
            cacheRequest = req;
        }
        // Banners are drawn at the focused size, so the zoomed tile stays sharp.
        int w = Math.round(req.tileWidth * AppAdapter.FOCUS_SCALE);
        int h = Math.round(req.tileHeight * AppAdapter.FOCUS_SCALE);

        Set<String> stale;
        synchronized (STALE) {
            stale = new HashSet<>(STALE);
            STALE.clear();
        }
        PackageManager pm = app.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<AppAdapter.Entry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(app.getPackageName()) || req.hidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            String key = component.flattenToShortString();
            seen.add(key);
            AppAdapter.Entry e = stale.contains(pkg) ? null : CACHE.get(key);
            if (e == null) {
                e = build(app, pm, ri, component, w, h);
                CACHE.put(key, e);
            }
            apps.add(e);
        }
        CACHE.keySet().retainAll(seen);

        // Optional "Default home" tile (config.xml), only when this TV actually has a home-screen picker.
        AppAdapter.Entry home = null;
        if (req.showHomeSettings && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            if (homeTile == null) {
                Drawable icon = app.getDrawable(R.drawable.ic_home_settings);
                homeTile = new AppAdapter.Entry(AppAdapter.Entry.HOME_SETTINGS, app.getString(R.string.home_settings),
                        new ComponentName(app, HomeSettingsActivity.class), null, icon, null,
                        vividColor(render(icon, 12, 12, false)));
            }
            home = homeTile;
        }
        return new Snapshot(apps, home);
    }

    private static AppAdapter.Entry build(Context app, PackageManager pm, ResolveInfo ri, ComponentName component,
                                          int w, int h) {
        // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
        // redraw the tile name as something else (#117).
        String label = Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), component.getPackageName());
        Drawable banner = loadBanner(app, pm, ri);
        if (banner != null) {
            Bitmap tile = render(banner, w, h, true);
            int ambient = vividColor(tile);
            return new AppAdapter.Entry(AppAdapter.Entry.APP, label, component, toHardware(tile), null, null, ambient);
        }
        Drawable icon = ri.loadIcon(pm);
        int ambient = icon != null ? vividColor(render(icon, 12, 12, false)) : 0xFF444444;
        return new AppAdapter.Entry(AppAdapter.Entry.APP, label, component, null, icon, null, ambient);
    }

    private static Drawable loadBanner(Context app, PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, app.getResources().getDisplayMetrics().densityDpi);
                return resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.activityInfo.loadBanner(pm);
    }

    /** Draws a drawable into a bitmap of exactly this size, centre-cropped like the tile's ImageView. */
    private static Bitmap render(Drawable d, int w, int h, boolean crop) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (crop && dw > 0 && dh > 0) {
            float s = Math.max((float) w / dw, (float) h / dh);
            int sw = Math.round(dw * s), sh = Math.round(dh * s);
            int l = (w - sw) / 2, t = (h - sh) / 2;
            d.setBounds(l, t, l + sw, t + sh);
        } else {
            d.setBounds(0, 0, w, h);
        }
        d.draw(new Canvas(bmp));
        return bmp;
    }

    /** GPU-only copy: no second copy in the app's memory and no upload on first draw. */
    private static Bitmap toHardware(Bitmap bmp) {
        Bitmap hw = bmp.copy(Bitmap.Config.HARDWARE, false);
        if (hw == null) return bmp;
        bmp.recycle();
        return hw;
    }

    /** A vivid colour sampled from the banner (or icon), for the glow behind the top shelf. */
    private static int vividColor(Bitmap src) {
        int n = 12;
        Bitmap bmp = src.getWidth() == n && src.getHeight() == n ? src : Bitmap.createScaledBitmap(src, n, n, true);
        int[] px = new int[n * n];
        bmp.getPixels(px, 0, n, 0, 0, n, n);
        if (bmp != src) bmp.recycle();
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            r += Color.red(p);
            g += Color.green(p);
            b += Color.blue(p);
            Color.colorToHSV(p, hsv);
            float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / px.length), (int) (g / px.length), (int) (b / px.length));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }
}

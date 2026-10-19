package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Self-update (docs/specs/self-update.md): reads the release feed, downloads and verifies the APK and
 * hands it to the package installer. No UI code here; MainActivity asks the owner first, and nothing is
 * installed without their Update. The feed URL and the release signer are fixed at build time
 * (BuildConfig.UPDATE_FEED, UPDATE_SIGNER); with an empty feed the updater never touches the network.
 *
 * The feed parser, the state file format and the next-check rule are plain Java, tested on the JVM.
 */
final class Updater {
    static final String TAG = "TvHomeUpdate";
    static final String PACKAGE = "com.gathin.tvhome";
    static final String STATE_FILE = "update.json";

    static final String MODE_OFF = "off";
    static final String MODE_MANUAL = "manual";
    static final String MODE_WEEKLY = "weekly";

    static final int MAX_FEED_BYTES = 4 * 1024;
    static final long MAX_APK_BYTES = 20L * 1024 * 1024;
    static final int MAX_REDIRECTS = 5;
    static final int MAX_NOTES = 200;
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 15_000;
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final long WEEK_MS = 7 * DAY_MS;

    /** Where GitHub serves releases from, redirects included; the feed's own host is added to these. */
    static final List<String> HOSTS = Arrays.asList(
            "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com");

    private static final Pattern VERSION_NAME = Pattern.compile("[0-9A-Za-z.\\-]{1,16}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** One check or download at a time, off the main thread. */
    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static final AtomicBoolean busy = new AtomicBoolean();
    /** Set by the home screen while it is in front: told when an install fails, so it can close its prompt. */
    static volatile Runnable installListener;
    private static boolean started;

    private Updater() {
    }

    /** A feed rejected, or a download that failed its checks, for a stated reason (logged under {@link #TAG}). */
    static final class UpdateException extends Exception {
        UpdateException(String reason) {
            super(reason);
        }
    }

    // ---- feed ------------------------------------------------------------------------------------

    static final class Feed {
        final int versionCode;
        final String versionName;
        final int minSdk;
        final String apkUrl;
        final long apkSize;
        final String sha256;
        final String notes;

        Feed(int versionCode, String versionName, int minSdk, String apkUrl, long apkSize, String sha256, String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.minSdk = minSdk;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
            this.sha256 = sha256;
            this.notes = notes;
        }
    }

    static final int UP_TO_DATE = 0;
    static final int AVAILABLE = 1;
    static final int TOO_OLD = 2;

    /**
     * Reads update.json, schema 1. Every rule of the spec is checked here except the comparison with
     * the installed version and the TV's SDK ({@link #classify}). Rejects, never guesses.
     */
    static Feed parseFeed(String text, Set<String> hosts) throws UpdateException {
        if (text == null) throw new UpdateException("empty feed");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FEED_BYTES) throw new UpdateException("feed larger than " + MAX_FEED_BYTES + " bytes");
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (JSONException e) {
            throw new UpdateException("feed is not a JSON object");
        }
        Long schema = whole(o.opt("schema"));
        if (schema == null || schema != 1) throw new UpdateException("schema is not 1");
        if (!PACKAGE.equals(o.opt("package"))) throw new UpdateException("package is not " + PACKAGE);
        Long code = whole(o.opt("version_code"));
        if (code == null || code < 1 || code > Integer.MAX_VALUE) throw new UpdateException("version_code is missing or out of range");
        Object name = o.opt("version_name");
        if (!(name instanceof String) || !VERSION_NAME.matcher((String) name).matches()) throw new UpdateException("version_name is missing or not allowed");
        Long minSdk = whole(o.opt("min_sdk"));
        if (minSdk == null || minSdk < 1 || minSdk > 1000) throw new UpdateException("min_sdk is missing or out of range");
        Object url = o.opt("apk_url");
        if (!(url instanceof String) || ((String) url).length() > 512 || !allowed((String) url, hosts)) {
            throw new UpdateException("apk_url is not https on an allowed host");
        }
        Long size = whole(o.opt("apk_size"));
        if (size == null || size < 1 || size > MAX_APK_BYTES) throw new UpdateException("apk_size is missing or out of range");
        Object sha = o.opt("apk_sha256");
        if (!(sha instanceof String) || !SHA256.matcher((String) sha).matches()) throw new UpdateException("apk_sha256 is not 64 lower-case hex characters");
        Object notes = o.opt("notes");
        String cleanNotes = "";
        if (notes != null && notes != JSONObject.NULL) {
            if (!(notes instanceof String) || ((String) notes).length() > MAX_NOTES) throw new UpdateException("notes are not text of at most " + MAX_NOTES + " characters");
            cleanNotes = clean((String) notes);
        }
        return new Feed((int) (long) code, (String) name, (int) (long) minSdk, (String) url, size, (String) sha, cleanNotes);
    }

    /** A JSON integer, from either JSON library's number types; null for anything else, fractions included. */
    private static Long whole(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        return null;
    }

    /** Plain text: line breaks and tabs become spaces, other control and format characters go. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') b.append(' ');
            else if (Character.getType(c) != Character.CONTROL && Character.getType(c) != Character.FORMAT) b.append(c);
        }
        return b.toString().trim();
    }

    /** Whether the feed offers a newer version this TV can run. */
    static int classify(Feed feed, int installedCode, int sdk) {
        if (feed.versionCode <= installedCode) return UP_TO_DATE;
        return feed.minSdk > sdk ? TOO_OLD : AVAILABLE;
    }

    // ---- hosts -----------------------------------------------------------------------------------

    /** The allowlist: GitHub's release hosts plus the feed's own host (a fork's feed may live elsewhere). */
    static Set<String> hosts(String feedUrl) {
        Set<String> out = new HashSet<>(HOSTS);
        try {
            String host = new URI(feedUrl).getHost();
            if (host != null) out.add(host.toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** https, on a listed host, default port, no user name or password in the URL. */
    static boolean allowed(String url, Set<String> hosts) {
        try {
            URI u = new URI(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
                    && hosts.contains(u.getHost().toLowerCase(Locale.ROOT))
                    && u.getRawUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- configuration ---------------------------------------------------------------------------

    /** self_update from config.xml or config.json: off, manual or weekly; anything else means manual. */
    static String mode(String raw) {
        String m = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return MODE_OFF.equals(m) || MODE_WEEKLY.equals(m) ? m : MODE_MANUAL;
    }

    static String feedUrl() {
        return BuildConfig.UPDATE_FEED;
    }

    // ---- state (files/update.json) ---------------------------------------------------------------

    static final class State {
        long lastCheck;
        long lastFailure;
        /** The version the owner chose to skip, 0 for none. */
        int skipped;
        /** The newer version the last successful check found, 0 for none. */
        int availableCode;
        String availableName = "";
        /** The version handed to the installer, until the next start sees whether it landed. */
        int installing;
        /** The launcher's accessibility services that were on before that install, ':' separated. */
        String services = "";
    }

    /** Weekly: 7 days after the last successful check, or 24 hours after a failed one. A time in the future is ignored. */
    static boolean checkDue(State s, long now) {
        long last = sane(s.lastCheck, now);
        long failed = sane(s.lastFailure, now);
        if (failed > last) return now - failed >= DAY_MS;
        return last == 0 || now - last >= WEEK_MS;
    }

    /** A time more than a day ahead means the TV's clock was wrong when it was written: treat it as never. */
    private static long sane(long t, long now) {
        return t > now + DAY_MS ? 0 : t;
    }

    static String encode(State s) {
        try {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            o.put("last_check", s.lastCheck);
            o.put("last_failure", s.lastFailure);
            o.put("skipped", s.skipped);
            o.put("available_code", s.availableCode);
            o.put("available_name", s.availableName);
            o.put("installing", s.installing);
            o.put("services", s.services);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The state as {@link #encode} writes it, or null when anything is missing, mistyped or out of range. */
    static State decode(String text) {
        if (text == null || text.length() > 4096) return null;
        try {
            JSONObject o = new JSONObject(text);
            Long version = whole(o.opt("version"));
            if (version == null || version != 1) return null;
            State s = new State();
            Long v;
            if ((v = whole(o.opt("last_check"))) == null || v < 0) return null;
            s.lastCheck = v;
            if ((v = whole(o.opt("last_failure"))) == null || v < 0) return null;
            s.lastFailure = v;
            if ((v = whole(o.opt("skipped"))) == null || v < 0 || v > Integer.MAX_VALUE) return null;
            s.skipped = (int) (long) v;
            if ((v = whole(o.opt("available_code"))) == null || v < 0 || v > Integer.MAX_VALUE) return null;
            s.availableCode = (int) (long) v;
            if ((v = whole(o.opt("installing"))) == null || v < 0 || v > Integer.MAX_VALUE) return null;
            s.installing = (int) (long) v;
            Object name = o.opt("available_name");
            if (!(name instanceof String)) return null;
            if (!((String) name).isEmpty() && !VERSION_NAME.matcher((String) name).matches()) return null;
            s.availableName = (String) name;
            Object services = o.opt("services");
            if (!(services instanceof String) || ((String) services).length() > 1024) return null;
            s.services = (String) services;
            return s;
        } catch (JSONException e) {
            return null;
        }
    }

    static File stateFile(Context ctx) {
        return new File(ctx.getFilesDir(), STATE_FILE);
    }

    /** The state, or a fresh one. A file that does not decode is moved to update.json.bad, as cards.json is. */
    static State load(File file) {
        if (!file.exists()) return new State();
        String text;
        try {
            text = new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file.getName() + ": " + e);
            return new State();
        }
        State s = decode(text);
        if (s == null) {
            Log.w(TAG, file.getName() + " is not valid; moved to " + STATE_FILE + ".bad");
            Cards.quarantine(file);
            return new State();
        }
        return s;
    }

    static boolean save(File file, State s) {
        AtomicFile af = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            out.write(encode(s).getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + file.getName() + ": " + e);
            if (out != null) af.failWrite(out);
            return false;
        }
    }

    // ---- network ---------------------------------------------------------------------------------

    /**
     * GET with redirects followed by hand, each one checked against the allowlist. Returns a
     * connection with a 200 response; the caller reads and disconnects it.
     */
    static HttpURLConnection open(String url, Set<String> hosts) throws IOException, UpdateException {
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            if (!allowed(url, hosts)) throw new UpdateException("not an allowed https URL: " + url);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "CleanYourTV/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept-Encoding", "identity");   // sizes are checked on the bytes as sent
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IOException("redirect without a Location");
                url = new URL(new URL(url), location).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            return c;
        }
        throw new UpdateException("more than " + MAX_REDIRECTS + " redirects");
    }

    static String fetchFeed(String url, Set<String> hosts) throws IOException, UpdateException {
        HttpURLConnection c = open(url, hosts);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_FEED_BYTES) throw new UpdateException("feed larger than " + MAX_FEED_BYTES + " bytes");
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** The result of one check, for the home screen. */
    static final class Outcome {
        static final int UNREACHABLE = 10;
        static final int INVALID = 11;

        /** UP_TO_DATE, AVAILABLE, TOO_OLD, UNREACHABLE or INVALID. */
        final int kind;
        final Feed feed;

        Outcome(int kind, Feed feed) {
            this.kind = kind;
            this.feed = feed;
        }
    }

    /** One check against the feed, recorded in the state file. Runs on {@link #EXECUTOR}. */
    static Outcome check(Context ctx) {
        File file = stateFile(ctx);
        State s = load(file);
        long now = System.currentTimeMillis();
        Outcome out;
        try {
            Feed feed = parseFeed(fetchFeed(feedUrl(), hosts(feedUrl())), hosts(feedUrl()));
            int kind = classify(feed, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT);
            out = new Outcome(kind, feed);
            s.lastCheck = now;
            s.availableCode = kind == AVAILABLE ? feed.versionCode : 0;
            s.availableName = kind == AVAILABLE ? feed.versionName : "";
        } catch (UpdateException e) {
            Log.w(TAG, "feed rejected: " + e.getMessage());
            out = new Outcome(Outcome.INVALID, null);
            s.lastFailure = now;
        } catch (IOException e) {
            Log.w(TAG, "check failed: " + e);
            out = new Outcome(Outcome.UNREACHABLE, null);
            s.lastFailure = now;
        }
        save(file, s);
        return out;
    }

    // ---- download, verify, install ---------------------------------------------------------------

    interface Progress {
        int DOWNLOADING = 0;
        int VERIFYING = 1;
        int INSTALLING = 2;

        void at(int stage, int percent);
    }

    static final int DONE = 0;
    static final int CANCELLED = 1;
    static final int FAILED_DOWNLOAD = 2;
    static final int REJECTED = 3;
    static final int FAILED_INSTALL = 4;

    static File apkFile(Context ctx) {
        return new File(new File(ctx.getCacheDir(), "update"), "cleanyourtv.apk");
    }

    /**
     * Download, verify and hand to the installer. DONE means the session was committed: Android now
     * asks the owner (or not, on Android 12+ after an earlier self-update) and replaces the process.
     */
    static int update(Context ctx, Feed feed, AtomicBoolean cancel, Progress progress) {
        File apk = apkFile(ctx);
        try {
            download(feed, apk, cancel, progress);
            if (cancel.get()) return discard(apk, CANCELLED);
            progress.at(Progress.VERIFYING, 100);
            verify(ctx, apk, feed);
        } catch (UpdateException e) {
            Log.w(TAG, "update rejected: " + e.getMessage());
            return discard(apk, cancel.get() ? CANCELLED : REJECTED);
        } catch (IOException e) {
            Log.w(TAG, "download failed: " + e);
            return discard(apk, cancel.get() ? CANCELLED : FAILED_DOWNLOAD);
        } catch (Exception e) {
            Log.w(TAG, "update rejected: " + e);
            return discard(apk, REJECTED);
        }
        if (cancel.get()) return discard(apk, CANCELLED);
        progress.at(Progress.INSTALLING, 100);
        File file = stateFile(ctx);
        State s = load(file);
        s.installing = feed.versionCode;
        s.services = ourServices(Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), ctx.getPackageName());
        save(file, s);
        try {
            install(ctx, apk);
            return DONE;
        } catch (Exception e) {
            Log.w(TAG, "install session failed: " + e);
            installFailed(ctx);
            return FAILED_INSTALL;
        }
    }

    private static int discard(File apk, int result) {
        if (apk.exists() && !apk.delete()) Log.w(TAG, "cannot delete " + apk);
        return result;
    }

    /** Streams the APK to the cache, stopping at the feed's size, and checks its SHA-256 on the way. */
    static void download(Feed feed, File dest, AtomicBoolean cancel, Progress progress) throws IOException, UpdateException {
        File dir = dest.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        HttpURLConnection c = open(feed.apkUrl, hosts(feedUrl()));
        try {
            long length = c.getContentLengthLong();
            if (length > feed.apkSize) throw new UpdateException("server announces " + length + " bytes, feed says " + feed.apkSize);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long total = 0;
            int lastPercent = -1;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    if (cancel.get()) throw new IOException("cancelled");
                    total += n;
                    if (total > feed.apkSize) throw new UpdateException("download is larger than apk_size");
                    sha.update(chunk, 0, n);
                    out.write(chunk, 0, n);
                    int percent = (int) (total * 100 / feed.apkSize);
                    if (percent != lastPercent) progress.at(Progress.DOWNLOADING, lastPercent = percent);
                }
            }
            if (total != feed.apkSize) throw new UpdateException("download is " + total + " bytes, feed says " + feed.apkSize);
            if (!Cards.hex(sha.digest()).equals(feed.sha256)) throw new UpdateException("SHA-256 does not match the feed");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
    }

    /** Package, version and signer of the downloaded file, before anything is handed to Android. */
    @SuppressWarnings("deprecation")
    static void verify(Context ctx, File apk, Feed feed) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(), Cards.signerFlags());
        if (pi == null) throw new UpdateException("not a readable APK");
        if (!ctx.getPackageName().equals(pi.packageName)) throw new UpdateException("APK is " + pi.packageName);
        long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
        if (code != feed.versionCode) throw new UpdateException("APK is version " + code + ", feed says " + feed.versionCode);
        String signer = Cards.digest(pi);
        if (!signer.equals(BuildConfig.UPDATE_SIGNER)) throw new UpdateException("APK signer " + signer + " is not the release key");
        if (!signer.equals(Cards.digest(pm, ctx.getPackageName()))) throw new UpdateException("APK signer differs from the installed launcher's");
    }

    static void install(Context ctx, File apk) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        // The owner already said Update; Android skips its own dialog when it can (we installed the last version).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("cleanyourtv.apk", 0, apk.length())) {
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(ctx, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(ctx, id, result, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            try {
                installer.abandonSession(id);
            } catch (RuntimeException ignored) {
            }
            throw e;
        }
    }

    /** The install did not happen: forget it and drop the file. */
    static void installFailed(Context ctx) {
        File file = stateFile(ctx);
        State s = load(file);
        s.installing = 0;
        s.services = "";
        save(file, s);
        discard(apkFile(ctx), 0);
    }

    static final int STARTED_PLAIN = 0;
    static final int STARTED_UPDATED = 1;
    static final int STARTED_UPDATED_SERVICES_OFF = 2;

    /**
     * Once per process: did the last install land? If it did, puts the launcher's accessibility services
     * back when Android switched them off and the install script granted WRITE_SECURE_SETTINGS.
     */
    static int afterStart(Context ctx) {
        if (started) return STARTED_PLAIN;
        started = true;
        File file = stateFile(ctx);
        if (!file.exists()) return STARTED_PLAIN;
        State s = load(file);
        if (s.installing == 0) return STARTED_PLAIN;
        boolean landed = s.installing == BuildConfig.VERSION_CODE;
        boolean servicesOn = !landed || restoreServices(ctx, s.services);
        s.installing = 0;
        s.services = "";
        if (landed && s.availableCode <= BuildConfig.VERSION_CODE) {
            s.availableCode = 0;
            s.availableName = "";
        }
        save(file, s);
        discard(apkFile(ctx), 0);
        if (!landed) return STARTED_PLAIN;
        return servicesOn ? STARTED_UPDATED : STARTED_UPDATED_SERVICES_OFF;
    }

    /** True when nothing needed restoring or the restore worked. */
    private static boolean restoreServices(Context ctx, String ours) {
        if (ours.isEmpty()) return true;
        String now = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String wanted = withServices(now, ours);
        if (wanted.equals(now == null ? "" : now)) return true;
        if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, wanted);
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "cannot restore accessibility services: " + e);
            return false;
        }
    }

    /** The entries of enabled_accessibility_services that belong to this package, ':' separated. */
    static String ourServices(String enabled, String pkg) {
        if (enabled == null) return "";
        List<String> out = new ArrayList<>();
        for (String s : enabled.split(":")) if (s.startsWith(pkg + "/")) out.add(s);
        return String.join(":", out);
    }

    /** enabled_accessibility_services with our entries added where missing, others kept in order. */
    static String withServices(String enabled, String ours) {
        List<String> list = new ArrayList<>();
        if (enabled != null && !enabled.isEmpty() && !"null".equals(enabled)) {
            for (String s : enabled.split(":")) if (!s.isEmpty()) list.add(s);
        }
        for (String s : ours.split(":")) if (!s.isEmpty() && !list.contains(s)) list.add(s);
        return String.join(":", list);
    }
}

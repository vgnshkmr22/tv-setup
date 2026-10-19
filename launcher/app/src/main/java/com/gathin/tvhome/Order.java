package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The owner's tile arrangement: {@code order.json} in the app's files directory, written when a
 * move is placed (hold OK on a tile → Move). The file lists the dock (the first
 * {@code columns} tiles, kept as package names like config.xml's dock_apps) and the grid below it
 * (kept as component keys, {@link android.content.ComponentName#flattenToShortString()}), in display
 * order. It is the user's own data: a theme's config.json and {@code tools/theme.sh --reset} never
 * touch it. To go back to the default arrangement, remove the file and restart the launcher:
 * {@code tools/tv.py 'rm /data/data/com.gathin.tvhome/files/order.json'} then force-stop Home.
 *
 * <p>The file format is strict (it must be exactly what {@link #encode} writes); anything else is
 * quarantined to {@code order.json.bad} and the launcher continues with the default order. The
 * codec and {@link #sequence} are pure Java so the JVM tests cover them; only {@link #load},
 * {@link #save} and {@link #storeFile} touch Android.
 */
final class Order {
    private static final String TAG = "TvHome.Order";
    static final String STORE_FILE = "order.json";
    static final int MAX_KEYS = 256;

    /** Grid order below the dock: component keys (or package names), as the owner arranged them. */
    final List<String> grid;
    /** Dock order: package names, as many as the dock has slots. */
    final List<String> dock;

    Order(List<String> dock, List<String> grid) {
        this.dock = Collections.unmodifiableList(new ArrayList<>(dock));
        this.grid = Collections.unmodifiableList(new ArrayList<>(grid));
    }

    /** One placeable app: its tile key (the component) and its package. */
    static final class App {
        final String key;
        final String pkg;

        App(String key, String pkg) {
            this.key = key;
            this.pkg = pkg;
        }
    }

    // ---- decision file (order.json) ---------------------------------------------------------------

    static File storeFile(Context ctx) {
        return new File(ctx.getFilesDir(), STORE_FILE);
    }

    /** The saved order, or the empty order when there is none (or it does not decode). */
    static Order load(File file) {
        if (!file.exists()) return new Order(Collections.emptyList(), Collections.emptyList());
        String text;
        try {
            text = new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file.getName() + ": " + e);
            return new Order(Collections.emptyList(), Collections.emptyList());
        }
        Order order = decode(text);
        if (order == null) {
            Log.w(TAG, file.getName() + " is not valid; moved to " + STORE_FILE + ".bad");
            quarantine(file);
            return new Order(Collections.emptyList(), Collections.emptyList());
        }
        return order;
    }

    /** Writes the order atomically. False (and a log line) when the write failed. */
    static boolean save(File file, Order order) {
        AtomicFile af = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            out.write(encode(order.dock, order.grid).getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "cannot write " + file.getName() + ": " + e);
            if (out != null) af.failWrite(out);
            return false;
        }
    }

    /** Moves a bad order file to {@code order.json.bad}, replacing an older .bad. */
    static boolean quarantine(File file) {
        File bad = new File(file.getParentFile(), file.getName() + ".bad");
        if (bad.exists() && !bad.delete()) return false;
        return file.renameTo(bad) || file.delete();
    }

    // ---- codec ------------------------------------------------------------------------------------

    /**
     * order.json: one line in the exact layout {@link #encode} writes, keys quoted and comma
     * separated: {@code {"grid":["pkg/.Activity"],"dock":["pkg"]}}. Anything else, including a key
     * listed twice or more than {@link #MAX_KEYS} keys, is invalid (null).
     */
    static Order decode(String text) {
        if (text == null || text.length() > 32 * 1024) return null;
        int[] at = {0};
        if (!take(text, at, "{\"grid\":[")) return null;
        List<String> grid = decodeList(text, at);
        if (grid == null || !take(text, at, ",\"dock\":[")) return null;
        List<String> dock = decodeList(text, at);
        if (dock == null || !take(text, at, "}")) return null;
        if (at[0] < text.length() && text.charAt(at[0]) == '\n') at[0]++;   // a trailing newline is fine
        if (at[0] != text.length()) return null;
        Set<String> seen = new HashSet<>();
        for (String k : grid) if (!isKey(k) || !seen.add(k)) return null;
        for (String k : dock) if (!isKey(k) || !seen.add(k)) return null;
        if (seen.size() > MAX_KEYS) return null;
        return new Order(dock, grid);
    }

    /** The comma separated run of quoted keys up to the next ']', which is consumed here. */
    private static List<String> decodeList(String s, int[] at) {
        List<String> out = new ArrayList<>();
        if (take(s, at, "]")) return out;
        while (true) {
            if (!take(s, at, "\"")) return null;
            String key = until(s, at, '"');
            if (key == null || !take(s, at, "\"")) return null;
            out.add(key);
            if (take(s, at, "]")) return out;
            if (!take(s, at, ",")) return null;
        }
    }

    /** Consumes the literal at the cursor, or returns false. */
    private static boolean take(String s, int[] at, String literal) {
        if (!s.startsWith(literal, at[0])) return false;
        at[0] += literal.length();
        return true;
    }

    /** The text from the cursor up to the next stop character, which stays for the next literal; null when there is none. */
    private static String until(String s, int[] at, char stop) {
        int end = s.indexOf(stop, at[0]);
        if (end < 0) return null;
        String out = s.substring(at[0], end);
        at[0] = end;
        return out;
    }

    /** The order file text for these keys. Keys are validated first, so no JSON escaping is ever needed. */
    static String encode(List<String> dock, List<String> grid) {
        if (dock.size() + grid.size() > MAX_KEYS) throw new IllegalArgumentException("more than " + MAX_KEYS + " keys");
        Set<String> seen = new HashSet<>();
        StringBuilder b = new StringBuilder("{\"grid\":[");
        appendKeys(b, grid, seen);
        b.append("],\"dock\":[");
        appendKeys(b, dock, seen);
        return b.append("]}\n").toString();
    }

    private static void appendKeys(StringBuilder b, List<String> keys, Set<String> seen) {
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (!isKey(k)) throw new IllegalArgumentException("not an app key: " + k);
            if (!seen.add(k)) throw new IllegalArgumentException("listed twice: " + k);
            if (i > 0) b.append(',');
            b.append('"').append(k).append('"');
        }
    }

    /**
     * A saved key: a package name (what the dock keeps), or a component
     * {@code package/class} (what the grid keeps, flattened by ComponentName). The set is kept
     * narrow on purpose, so the file needs no escaping and a corrupt one is always caught.
     */
    static boolean isKey(String s) {
        if (s == null || s.isEmpty() || s.length() > 255) return false;
        int slash = s.indexOf('/');
        if (slash < 0) return Cards.isPackageName(s);
        if (slash != s.lastIndexOf('/')) return false;
        String cls = s.substring(slash + 1);
        if (!Cards.isPackageName(s.substring(0, slash)) || cls.isEmpty() || cls.length() > 192) return false;
        for (int i = 0; i < cls.length(); i++) {
            char c = cls.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '$' || c == '.')) return false;
        }
        return true;
    }

    // ---- merge ------------------------------------------------------------------------------------

    /**
     * The display order for the installed apps: the owner's dock first (filled up to
     * {@code columns} slots from {@code defaultDock}), then the owner's grid, then every app the
     * owner never listed, in the default order (cards then alphabetical). A saved key matches an
     * app by component key or package name; an app that is gone is skipped and one app is never
     * placed twice (the dock wins over the grid). An app the owner put in the grid is never pulled
     * back into the dock by the fill.
     *
     * @param defaultOrder the installed apps in the default sort order
     * @return every index of {@code defaultOrder} exactly once, in display order
     */
    static List<Integer> sequence(List<App> defaultOrder, List<String> userDock, List<String> userGrid,
                                  List<String> defaultDock, int columns) {
        int n = defaultOrder.size();
        boolean[] placed = new boolean[n];
        List<Integer> out = new ArrayList<>(n);
        // The owner's dock, in the saved order.
        for (String k : userDock) place(out, placed, defaultOrder, k);
        // Fill the dock up to its slot count from the configured dock, skipping apps the owner
        // unpinned into the grid: their place is the grid, whatever config.xml says.
        for (String pkg : defaultDock) {
            if (out.size() >= columns) break;
            int i = find(defaultOrder, placed, pkg);
            if (i < 0 || names(userGrid, defaultOrder.get(i))) continue;
            placed[i] = true;
            out.add(i);
        }
        // The owner's grid, in the saved order, then everything new in the default order.
        for (String k : userGrid) place(out, placed, defaultOrder, k);
        for (int i = 0; i < n; i++) if (!placed[i]) out.add(i);
        return out;
    }

    private static void place(List<Integer> out, boolean[] placed, List<App> apps, String key) {
        int i = find(apps, placed, key);
        if (i >= 0) {
            placed[i] = true;
            out.add(i);
        }
    }

    /** The index of the not yet placed app this key names (by component key or package), or -1. */
    private static int find(List<App> apps, boolean[] placed, String key) {
        if (key == null) return -1;
        for (int i = 0; i < apps.size(); i++) {
            App a = apps.get(i);
            if (!placed[i] && (key.equals(a.key) || key.equals(a.pkg))) return i;
        }
        return -1;
    }

    /** Whether any of these keys names the app (by component key or package). */
    private static boolean names(List<String> keys, App app) {
        for (String k : keys) if (k.equals(app.key) || k.equals(app.pkg)) return true;
        return false;
    }
}

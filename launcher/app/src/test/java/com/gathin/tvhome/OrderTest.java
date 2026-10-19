package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plain JVM tests for the tile order file (order.json) and the merge that applies it. */
public class OrderTest {

    private static final String KEY_A = "io.example.a/.MainActivity";
    private static final String KEY_B = "io.example.b/.MainActivity";
    private static final String KEY_C = "io.example.c/.MainActivity";
    private static final String KEY_D = "io.example.d/.MainActivity";
    private static final String KEY_E = "io.example.e/.MainActivity";

    private static Order.App app(String key) {
        return new Order.App(key, key.substring(0, key.indexOf('/')));
    }

    private static List<Order.App> apps(String... keys) {
        List<Order.App> out = new ArrayList<>();
        for (String k : keys) out.add(app(k));
        return out;
    }

    private static List<String> strings(String... s) {
        return Arrays.asList(s);
    }

    // ---- codec (order.json) -----------------------------------------------------------------------

    @Test
    public void encodeWritesTheExactLayout() {
        assertEquals("{\"grid\":[],\"dock\":[]}\n", Order.encode(Collections.emptyList(), Collections.emptyList()));
        assertEquals("{\"grid\":[\"" + KEY_A + "\"],\"dock\":[\"io.example.b\"]}\n",
                Order.encode(strings("io.example.b"), strings(KEY_A)));
    }

    @Test
    public void storeRoundTrip() {
        List<String> dock = strings("io.example.b", "io.example.a");
        List<String> grid = strings(KEY_C, KEY_D);
        Order out = Order.decode(Order.encode(dock, grid));
        assertNotNull(out);
        assertEquals(dock, out.dock);
        assertEquals(grid, out.grid);
    }

    @Test
    public void emptyStoreRoundTrip() {
        Order out = Order.decode(Order.encode(Collections.emptyList(), Collections.emptyList()));
        assertNotNull(out);
        assertTrue(out.dock.isEmpty());
        assertTrue(out.grid.isEmpty());
    }

    @Test
    public void decodeAcceptsTheTrailingNewlineAndNothingAfter() {
        String good = Order.encode(strings("io.example.a"), strings(KEY_A));
        assertEquals(KEY_A, Order.decode(good).grid.get(0));                               // encode's newline is fine
        assertEquals(KEY_A, Order.decode(good.substring(0, good.length() - 1)).grid.get(0)); // and so is none
        assertNull(Order.decode(good + "\n"));                                             // two cannot come from encode
    }

    @Test
    public void decodeRejectsAnythingElse() {
        String good = Order.encode(strings("io.example.a", "io.example.b"), strings(KEY_A));
        assertNull(Order.decode(null));
        assertNull(Order.decode(""));
        assertNull(Order.decode("{}"));
        assertNull(Order.decode("[]"));
        assertNull(Order.decode(good + "garbage"));
        assertNull(Order.decode(good.substring(1)));
        assertNull(Order.decode(good.replace("\"grid\"", "\"order\"")));          // unknown key
        assertNull(Order.decode(good.replace("\"dock\":[\"io.example.a\"", "\"dock\":[]")));  // a list went missing
        assertNull(Order.decode("{\"dock\":[],\"grid\":[]}"));                    // the lists swapped
        assertNull(Order.decode(good.replace("io.example.a\",", "io.example.a"))); // missing comma
        assertNull(Order.decode(good.replace("io.example.b", "io.example.a")));   // listed twice
        assertNull(Order.decode(good.replace("\"io.example.b\"", "\"io.example.b")));  // unclosed quote
        assertNull(Order.decode(good.replace("\n", " \n")));                      // not the exact layout
        assertNull(Order.decode(good.replace("io.example.a\",", "io.example.a\\\"x\",")));  // a quote inside
        assertNull(Order.decode(good.replace("]}", "]")));                        // unclosed
        char[] huge = new char[33 * 1024];
        Arrays.fill(huge, ' ');
        assertNull(Order.decode(new String(huge)));
    }

    @Test
    public void encodeRefusesInvalidKeysAndDuplicates() {
        try {
            Order.encode(strings("io.example.a"), strings("io.example.a\"x"));
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            Order.encode(strings("io.example.a"), strings("io.example.a"));   // the same key in both lists
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            Order.encode(strings("io/example"), Collections.emptyList());     // not a package or component
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void keysArePackagesOrComponents() {
        assertTrue(Order.isKey("io.example.a"));
        assertTrue(Order.isKey("io.example.a/.MainActivity"));
        assertTrue(Order.isKey("com.netflix.ninja/com.netflix.tv.TvActivity"));
        assertTrue(Order.isKey("io.example.a/com.example.ui.MainActivity$Inner"));   // $ in a class name
        assertTrue(Order.isKey("io.example.a/com.example.MainActivity"));
        assertFalse(Order.isKey(null));
        assertFalse(Order.isKey(""));
        assertFalse(Order.isKey("example"));                       // no dot
        assertFalse(Order.isKey("io.example.a/"));                 // no class
        assertFalse(Order.isKey("io.example.a//MainActivity"));    // two slashes
        assertFalse(Order.isKey("io.example.a/My Class"));         // a space
        assertFalse(Order.isKey("io.example.a/My\"Class"));        // a quote needs escaping, so it is invalid
        assertFalse(Order.isKey("io.example.a/.Main\u202E"));      // bidi override
    }

    // ---- merge (Order.sequence) -------------------------------------------------------------------

    /** With no saved order the configured dock fills the dock and everything else keeps the default order. */
    @Test
    public void sequenceWithoutAStoreIsTheDefaultOrder() {
        List<Order.App> def = apps(KEY_A, KEY_B, KEY_C, KEY_D, KEY_E);   // already sorted: dock apps first
        List<Integer> out = Order.sequence(def, Collections.emptyList(), Collections.emptyList(),
                strings("io.example.a", "io.example.b", "io.example.c", "io.example.d"), 4);
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), out);
    }

    /** The owner's dock wins over the configured one, and the owner's grid order is kept. */
    @Test
    public void sequenceUserOrderWins() {
        List<Order.App> def = apps(KEY_A, KEY_B, KEY_C, KEY_D, KEY_E);
        List<Integer> out = Order.sequence(def,
                strings("io.example.d", "io.example.e"),                 // the owner pinned d and e
                strings(KEY_C, KEY_A),                                   // and moved c and a down, in that order
                strings("io.example.a", "io.example.b", "io.example.c", "io.example.d"), 4);
        // dock: d, e and the configured fill b (c is in the owner's grid and must not be pulled back);
        // then the owner's grid; b is listed once even though config names it too.
        assertEquals(Arrays.asList(3, 4, 1, 2, 0), out);
    }

    /** The fill tops the dock up from the configured dock after apps were uninstalled. */
    @Test
    public void sequenceFillsTheDockAfterAnUninstall() {
        // The owner's dock was a, b, c, d; b and c are gone now (not in def).
        List<Order.App> def = apps(KEY_A, KEY_D, KEY_E);
        List<Integer> out = Order.sequence(def,
                strings("io.example.a", "io.example.b", "io.example.c", "io.example.d"),
                strings(KEY_E),
                strings("io.example.a", "io.example.b", "io.example.c", "io.example.d", "io.example.e"), 4);
        // a and d from the saved dock; the rest are gone or listed in the grid, so nothing fills up
        // and the order file never names apps that do not exist.
        assertEquals(Arrays.asList(0, 1, 2), out);
    }

    /** An app the owner pinned out of the dock stays in the grid, however the fill feels about it. */
    @Test
    public void sequenceNeverPullsAGridAppBackIntoTheDock() {
        List<Order.App> def = apps(KEY_A, KEY_B, KEY_C, KEY_D);   // a..d are all configured dock apps
        List<Integer> out = Order.sequence(def,
                Collections.emptyList(),
                strings(KEY_A, KEY_B),                             // the owner moved a and b into the grid
                strings("io.example.a", "io.example.b", "io.example.c", "io.example.d"), 4);
        // c, d fill the dock (a and b are the owner's choice now), then the owner's grid, then nothing new.
        assertEquals(Arrays.asList(2, 3, 0, 1), out);
    }

    /** A saved key matches an app by package or by component, in either list. */
    @Test
    public void sequenceMatchesByPackageOrComponent() {
        List<Order.App> def = apps(KEY_A, KEY_B, KEY_C);
        List<Integer> out = Order.sequence(def,
                strings(KEY_A),                                    // a component key in the dock list
                strings("io.example.c"),                           // a package in the grid list
                Collections.emptyList(), 4);
        assertEquals(Arrays.asList(0, 2, 1), out);
    }

    /** Apps that are new, or that the file never listed, follow the listed ones in the default order. */
    @Test
    public void sequencePutsNewAppsAfterTheListedOnes() {
        List<Order.App> def = apps(KEY_A, KEY_B, KEY_C, KEY_D, KEY_E);
        List<Integer> out = Order.sequence(def,
                Collections.emptyList(),
                strings(KEY_E),                                    // the owner moved e to the top of the grid
                strings("io.example.a", "io.example.b", "io.example.c", "io.example.d"), 4);
        // a..d fill the dock (the configured order), then the listed e, then nothing else is new.
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), out);
        // With c and d uninstalled instead, e is listed and the new a follow it.
        List<Order.App> fewer = apps(KEY_E, KEY_A, KEY_B);
        List<Integer> out2 = Order.sequence(fewer,
                Collections.emptyList(),
                strings(KEY_E),
                strings("io.example.a", "io.example.b", "io.example.c", "io.example.d"), 4);
        assertEquals(Arrays.asList(1, 2, 0), out2);
    }

    /** Whatever is saved, every app is placed exactly once and the dock keeps its size. */
    @Test
    public void sequenceIsAPermutation() {
        List<Order.App> def = apps(KEY_A, KEY_B, KEY_C, KEY_D, KEY_E);
        List<String> dock = strings("io.example.b", "io.example.c");
        List<String> grid = strings(KEY_A, KEY_D, KEY_E, "io.example.ghost");
        List<Integer> out = Order.sequence(def, dock, grid,
                strings("io.example.a", "io.example.b"), 4);
        assertEquals(def.size(), out.size());
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2, 3, 4)), new HashSet<>(out));
        Set<String> seen = new HashSet<>();
        for (int i : out) assertTrue(seen.add(def.get(i).key));
    }

    // ---- decision file (order.json) ---------------------------------------------------------------

    @Test
    public void quarantineMovesTheBadFileAside() throws Exception {
        File dir = File.createTempFile("order", "dir");
        assertTrue(dir.delete() && dir.mkdir());
        File store = new File(dir, Order.STORE_FILE);
        try (FileOutputStream out = new FileOutputStream(store)) {
            out.write("not json".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(Order.quarantine(store));
        File bad = new File(dir, Order.STORE_FILE + ".bad");
        assertFalse(store.exists());
        assertTrue(bad.exists());
        // A second bad file replaces the first; the evidence is the latest one.
        try (FileOutputStream out = new FileOutputStream(store)) {
            out.write("still not json".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(Order.quarantine(store));
        assertEquals(14, bad.length());
        assertTrue(bad.delete() && dir.delete());
    }
}

package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plain JVM tests for the self-update feed parser and its limits, the next-check rule and the state file. */
public class UpdaterTest {

    private static final String FEED_URL = "https://github.com/Gathin23/cleanyourtv/releases/latest/download/update.json";
    private static final Set<String> HOSTS = Updater.hosts(FEED_URL);
    private static final String SHA = "9f2c000000000000000000000000000000000000000000000000000000000abc";

    private static JSONObject feed() throws Exception {
        JSONObject o = new JSONObject();
        o.put("schema", 1);
        o.put("package", "com.gathin.tvhome");
        o.put("version_code", 3);
        o.put("version_name", "3.0");
        o.put("min_sdk", 26);
        o.put("apk_url", "https://github.com/Gathin23/cleanyourtv/releases/download/v3.0.0/cleanyourtv-3.0.apk");
        o.put("apk_size", 251904);
        o.put("apk_sha256", SHA);
        o.put("notes", "Self-update. Faster grid on 1 GB TVs.");
        return o;
    }

    private static void rejected(JSONObject o) {
        rejected(o.toString());
    }

    private static void rejected(String text) {
        try {
            Updater.parseFeed(text, HOSTS);
            fail("accepted: " + text);
        } catch (Updater.UpdateException expected) {
        }
    }

    // ---- feed ------------------------------------------------------------------------------------

    @Test
    public void aGoodFeedParses() throws Exception {
        Updater.Feed f = Updater.parseFeed(feed().toString(), HOSTS);
        assertEquals(3, f.versionCode);
        assertEquals("3.0", f.versionName);
        assertEquals(26, f.minSdk);
        assertEquals(251904, f.apkSize);
        assertEquals(SHA, f.sha256);
        assertEquals("Self-update. Faster grid on 1 GB TVs.", f.notes);
    }

    @Test
    public void notesAreOptionalAndCleaned() throws Exception {
        JSONObject o = feed();
        o.remove("notes");
        assertEquals("", Updater.parseFeed(o.toString(), HOSTS).notes);
        o.put("notes", "Line one\nline two\u0007‮.");
        assertEquals("Line one line two.", Updater.parseFeed(o.toString(), HOSTS).notes);
        o.put("notes", repeat('a', Updater.MAX_NOTES));
        assertEquals(Updater.MAX_NOTES, Updater.parseFeed(o.toString(), HOSTS).notes.length());
        o.put("notes", repeat('a', Updater.MAX_NOTES + 1));
        rejected(o);
        o.put("notes", 5);
        rejected(o);
    }

    @Test
    public void anotherSchemaOrPackageIsIgnored() throws Exception {
        rejected(feed().put("schema", 2));
        rejected(feed().put("schema", "1"));
        rejected(feed().put("schema", 1.5));
        rejected(feed().put("package", "com.example.evil"));
        JSONObject o = feed();
        o.remove("schema");
        rejected(o);
        rejected("not json");
        rejected("[1, 2]");
        rejected((String) null);
    }

    @Test
    public void numbersMustBeWholeAndInRange() throws Exception {
        rejected(feed().put("version_code", 0));
        rejected(feed().put("version_code", "3"));
        rejected(feed().put("version_code", 3.5));
        rejected(feed().put("version_code", 1L + Integer.MAX_VALUE));
        rejected(feed().put("min_sdk", 0));
        rejected(feed().put("apk_size", 0));
        rejected(feed().put("apk_size", Updater.MAX_APK_BYTES + 1));
        assertEquals(Updater.MAX_APK_BYTES, Updater.parseFeed(feed().put("apk_size", Updater.MAX_APK_BYTES).toString(), HOSTS).apkSize);
    }

    @Test
    public void versionNameAndHashAreStrict() throws Exception {
        rejected(feed().put("version_name", ""));
        rejected(feed().put("version_name", "3.0 beta"));
        rejected(feed().put("version_name", repeat('1', 17)));
        assertNotNull(Updater.parseFeed(feed().put("version_name", "3.0-rc.1").toString(), HOSTS));
        rejected(feed().put("apk_sha256", SHA.toUpperCase()));
        rejected(feed().put("apk_sha256", SHA.substring(1)));
        rejected(feed().put("apk_sha256", SHA.replace('a', 'g')));
    }

    @Test
    public void theApkComesFromAnAllowedHttpsHost() throws Exception {
        rejected(feed().put("apk_url", "http://github.com/x.apk"));
        rejected(feed().put("apk_url", "https://evil.example/x.apk"));
        rejected(feed().put("apk_url", "https://github.com.evil.example/x.apk"));
        rejected(feed().put("apk_url", "https://user@github.com/x.apk"));
        rejected(feed().put("apk_url", "https://github.com:8443/x.apk"));
        rejected(feed().put("apk_url", "file:///sdcard/x.apk"));
        rejected(feed().put("apk_url", "https://github.com/" + repeat('a', 512)));
        assertNotNull(Updater.parseFeed(feed().put("apk_url", "https://objects.githubusercontent.com/x.apk").toString(), HOSTS));
        assertNotNull(Updater.parseFeed(feed().put("apk_url", "https://GitHub.com:443/x.apk").toString(), HOSTS));
    }

    @Test
    public void aForkFeedHostIsAllowedToo() {
        Set<String> hosts = Updater.hosts("https://updates.example.org/update.json");
        assertTrue(Updater.allowed("https://updates.example.org/cleanyourtv.apk", hosts));
        assertTrue(Updater.allowed("https://github.com/x.apk", hosts));
        assertFalse(Updater.allowed("https://example.org/x.apk", hosts));
    }

    @Test
    public void aFeedOverFourKilobytesIsRejected() throws Exception {
        JSONObject o = feed();
        o.put("padding", repeat(' ', Updater.MAX_FEED_BYTES));
        rejected(o);
    }

    @Test
    public void classifyComparesVersionAndSdk() throws Exception {
        Updater.Feed f = Updater.parseFeed(feed().toString(), HOSTS);
        assertEquals(Updater.AVAILABLE, Updater.classify(f, 2, 26));
        assertEquals(Updater.UP_TO_DATE, Updater.classify(f, 3, 34));
        assertEquals(Updater.UP_TO_DATE, Updater.classify(f, 4, 34));   // a lower version is never offered
        Updater.Feed newer = Updater.parseFeed(feed().put("min_sdk", 30).toString(), HOSTS);
        assertEquals(Updater.TOO_OLD, Updater.classify(newer, 2, 28));
        assertEquals(Updater.AVAILABLE, Updater.classify(newer, 2, 30));
    }

    @Test
    public void modeDefaultsToManual() {
        assertEquals(Updater.MODE_OFF, Updater.mode("off"));
        assertEquals(Updater.MODE_WEEKLY, Updater.mode(" Weekly "));
        assertEquals(Updater.MODE_MANUAL, Updater.mode("manual"));
        assertEquals(Updater.MODE_MANUAL, Updater.mode("daily"));
        assertEquals(Updater.MODE_MANUAL, Updater.mode(""));
        assertEquals(Updater.MODE_MANUAL, Updater.mode(null));
    }

    // ---- next check ------------------------------------------------------------------------------

    private static final long NOW = 1_800_000_000_000L;

    private static Updater.State state(long lastCheck, long lastFailure) {
        Updater.State s = new Updater.State();
        s.lastCheck = lastCheck;
        s.lastFailure = lastFailure;
        return s;
    }

    @Test
    public void neverCheckedIsDue() {
        assertTrue(Updater.checkDue(state(0, 0), NOW));
    }

    @Test
    public void weeklyAfterASuccess() {
        assertFalse(Updater.checkDue(state(NOW - Updater.WEEK_MS + 1, 0), NOW));
        assertTrue(Updater.checkDue(state(NOW - Updater.WEEK_MS, 0), NOW));
    }

    @Test
    public void aDayAfterAFailure() {
        long last = NOW - 3 * Updater.DAY_MS;
        assertFalse(Updater.checkDue(state(last, NOW - Updater.DAY_MS + 1), NOW));
        assertTrue(Updater.checkDue(state(last, NOW - Updater.DAY_MS), NOW));
        // A failure older than the last success doesn't shorten the week.
        assertFalse(Updater.checkDue(state(NOW - Updater.DAY_MS, NOW - 2 * Updater.DAY_MS), NOW));
    }

    @Test
    public void timesFarInTheFutureCountAsNever() {
        assertTrue(Updater.checkDue(state(NOW + 30 * Updater.DAY_MS, 0), NOW));
        assertTrue(Updater.checkDue(state(0, NOW + 30 * Updater.DAY_MS), NOW));
        // Within a day ahead is clock drift, not a wrong clock: not due.
        assertFalse(Updater.checkDue(state(NOW + Updater.DAY_MS / 2, 0), NOW));
    }

    // ---- state file ------------------------------------------------------------------------------

    @Test
    public void stateRoundTrips() {
        Updater.State s = new Updater.State();
        s.lastCheck = NOW;
        s.lastFailure = NOW - 5;
        s.skipped = 4;
        s.availableCode = 5;
        s.availableName = "5.0";
        s.installing = 5;
        s.services = "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService";
        Updater.State back = Updater.decode(Updater.encode(s));
        assertNotNull(back);
        assertEquals(s.lastCheck, back.lastCheck);
        assertEquals(s.lastFailure, back.lastFailure);
        assertEquals(4, back.skipped);
        assertEquals(5, back.availableCode);
        assertEquals("5.0", back.availableName);
        assertEquals(5, back.installing);
        assertEquals(s.services, back.services);
        assertNotNull(Updater.decode(Updater.encode(new Updater.State())));
    }

    @Test
    public void badStateIsRejected() throws Exception {
        JSONObject good = new JSONObject(Updater.encode(new Updater.State()));
        assertNull(Updater.decode("garbage"));
        assertNull(Updater.decode(null));
        assertNull(Updater.decode(new JSONObject(good.toString()).put("version", 2).toString()));
        assertNull(Updater.decode(new JSONObject(good.toString()).put("last_check", -1).toString()));
        assertNull(Updater.decode(new JSONObject(good.toString()).put("skipped", "4").toString()));
        assertNull(Updater.decode(new JSONObject(good.toString()).put("available_name", "5 0").toString()));
        assertNull(Updater.decode(new JSONObject(good.toString()).put("services", repeat('a', 1025)).toString()));
        JSONObject missing = new JSONObject(good.toString());
        missing.remove("installing");
        assertNull(Updater.decode(missing.toString()));
        assertNull(Updater.decode(repeat(' ', 4097)));
    }

    // ---- accessibility services ------------------------------------------------------------------

    @Test
    public void ourServicesAreRememberedAndPutBack() {
        String pkg = "com.gathin.tvhome";
        String other = "com.example/.Reader";
        String home = "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService";
        String input = "com.gathin.tvhome/.InputKeyService";
        assertEquals(home + ":" + input, Updater.ourServices(other + ":" + home + ":" + input, pkg));
        assertEquals("", Updater.ourServices(null, pkg));
        assertEquals("", Updater.ourServices("com.gathin.tvhomex/.Evil", pkg));

        assertEquals(other + ":" + home, Updater.withServices(other, home));
        assertEquals(other + ":" + home, Updater.withServices(other + ":" + home, home));   // nothing to add
        assertEquals(home + ":" + input, Updater.withServices(null, home + ":" + input));
        assertEquals(home, Updater.withServices("null", home));
        assertEquals(other, Updater.withServices(other, ""));
    }

    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }
}

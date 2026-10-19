package com.gathin.tvhome;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the remote key to app map (key_launch_map) parsing. */
public class InputKeyServiceTest {

    // ---- parsing (key_launch_map) ----------------------------------------------------------------

    @Test
    public void emptySpecMeansTheFeatureIsOff() {
        assertTrue(InputKeyService.parseKeyLaunchMap(null).isEmpty());
        assertTrue(InputKeyService.parseKeyLaunchMap("").isEmpty());
        assertTrue(InputKeyService.parseKeyLaunchMap("   ").isEmpty());
        assertTrue(InputKeyService.parseKeyLaunchMap(" ; ; ").isEmpty());
    }

    @Test
    public void oneEntryWithAKeycodeName() {
        Map<String, String> map = InputKeyService.parseKeyLaunchMap("KEYCODE_MEDIA_PLAY=com.zattoo.player");
        assertEquals(1, map.size());
        assertEquals("com.zattoo.player", map.get("KEYCODE_MEDIA_PLAY"));
    }

    @Test
    public void keycodeNamesAreCaseInsensitive() {
        Map<String, String> map = InputKeyService.parseKeyLaunchMap(" keycode_media_play = com.zattoo.player ");
        assertEquals("com.zattoo.player", map.get("KEYCODE_MEDIA_PLAY"));
    }

    @Test
    public void numericKeycodesAreAccepted() {
        Map<String, String> map = InputKeyService.parseKeyLaunchMap("126=com.zattoo.player");
        assertEquals("com.zattoo.player", map.get("126"));
    }

    @Test
    public void severalEntriesAreSeparatedBySemicolons() {
        Map<String, String> map = InputKeyService.parseKeyLaunchMap(
                "KEYCODE_MEDIA_PLAY=com.zattoo.player;126=com.netflix.ninja;KEYCODE_TV=com.a.b");
        assertEquals(3, map.size());
        assertEquals("com.zattoo.player", map.get("KEYCODE_MEDIA_PLAY"));
        assertEquals("com.netflix.ninja", map.get("126"));
        assertEquals("com.a.b", map.get("KEYCODE_TV"));
    }

    @Test
    public void malformedEntriesAreSkipped() {
        Map<String, String> map = InputKeyService.parseKeyLaunchMap(
                "KEYCODE_MEDIA_PLAY;=com.a.b;KEYCODE_TV=; =com.c.d;KEYCODE_CAPS_LOCK=com.ok.app;;");
        assertEquals(1, map.size());
        assertEquals("com.ok.app", map.get("KEYCODE_CAPS_LOCK"));
    }

    @Test
    public void laterDuplicatesWin() {
        Map<String, String> map = InputKeyService.parseKeyLaunchMap(
                "KEYCODE_MEDIA_PLAY=com.first.app;KEYCODE_MEDIA_PLAY=com.second.app");
        assertEquals(1, map.size());
        assertEquals("com.second.app", map.get("KEYCODE_MEDIA_PLAY"));
    }

    @Test
    public void valuesKeepEverythingAfterTheFirstEqualsSign() {
        Map<String, String> map = InputKeyService.parseKeyLaunchMap("KEYCODE_MEDIA_PLAY=com.a.b=x");
        assertEquals("com.a.b=x", map.get("KEYCODE_MEDIA_PLAY"));
    }
}

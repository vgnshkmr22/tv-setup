package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Which session the Now playing card shows, what its two lines say, and its guard. */
public class NowPlayingTest {
    private static final String SELF = "com.gathin.tvhome";

    @After
    public void tearDown() {
        NowPlaying.reset();
    }

    @Test
    public void playingBeatsPausedWhateverTheOrder() {
        assertEquals(1, NowPlaying.choose(new int[]{NowPlaying.STATE_PAUSED, NowPlaying.STATE_PLAYING},
                new String[]{"a", "b"}, SELF));
        assertEquals(0, NowPlaying.choose(new int[]{NowPlaying.STATE_BUFFERING, NowPlaying.STATE_PLAYING},
                new String[]{"a", "b"}, SELF));
    }

    @Test
    public void firstPausedWhenNothingPlays() {
        assertEquals(1, NowPlaying.choose(new int[]{NowPlaying.STATE_STOPPED, NowPlaying.STATE_PAUSED, NowPlaying.STATE_PAUSED},
                new String[]{"a", "b", "c"}, SELF));
    }

    @Test
    public void nothingToShow() {
        assertEquals(-1, NowPlaying.choose(new int[0], new String[0], SELF));
        assertEquals(-1, NowPlaying.choose(new int[]{NowPlaying.STATE_NONE, NowPlaying.STATE_STOPPED, NowPlaying.STATE_ERROR},
                new String[]{"a", "b", "c"}, SELF));
    }

    @Test
    public void skipsTheLauncherItself() {
        assertEquals(1, NowPlaying.choose(new int[]{NowPlaying.STATE_PLAYING, NowPlaying.STATE_PAUSED},
                new String[]{SELF, "b"}, SELF));
    }

    @Test
    public void activeStates() {
        assertTrue(NowPlaying.isActive(NowPlaying.STATE_SKIPPING_TO_NEXT));
        assertFalse(NowPlaying.isActive(NowPlaying.STATE_PAUSED));
        assertFalse(NowPlaying.isActive(NowPlaying.STATE_ERROR));
    }

    @Test
    public void titleFallsBack() {
        assertEquals("Song", NowPlaying.title(" Song ", "Display", "YouTube Music"));
        assertEquals("Display", NowPlaying.title("", "Display", "YouTube Music"));
        assertEquals("YouTube Music", NowPlaying.title(null, " ", "YouTube Music"));
    }

    @Test
    public void subtitleJoinsArtistAndApp() {
        assertEquals("Artist · YouTube Music", NowPlaying.subtitle("Artist", "Album", "YouTube Music", "Song"));
        assertEquals("Album · Spotify", NowPlaying.subtitle(null, "Album", "Spotify", "Song"));
        assertEquals("Spotify", NowPlaying.subtitle("", null, "Spotify", "Song"));
        assertEquals("Artist", NowPlaying.subtitle("Artist", null, "Spotify", "Spotify"));
        assertEquals("", NowPlaying.subtitle(null, null, "Spotify", "Spotify"));
    }

    @Test
    public void guardSwitchesOffOnce() {
        List<Throwable> reported = new ArrayList<>();
        NowPlaying.reporter = reported::add;
        assertEquals("fallback", NowPlaying.run(() -> {
            throw new SecurityException("no listener");
        }, "fallback"));
        assertFalse(NowPlaying.working());
        assertEquals("fallback", NowPlaying.run(() -> "value", "fallback"));
        assertEquals(1, reported.size());
    }
}

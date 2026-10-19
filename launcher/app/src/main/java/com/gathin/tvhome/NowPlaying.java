package com.gathin.tvhome;

import android.util.Log;

/**
 * The Now playing card's logic that doesn't need Android: which media session to show and what the
 * two lines say, plus the guard that switches the card off for the session on the first failure, as
 * {@link CardFeature} does for module cards. MainActivity does the media session calls.
 */
final class NowPlaying {
    static final String TAG = "TvHomeNowPlaying";

    // android.media.session.PlaybackState values, repeated so this stays testable on the JVM.
    static final int STATE_NONE = 0;
    static final int STATE_STOPPED = 1;
    static final int STATE_PAUSED = 2;
    static final int STATE_PLAYING = 3;
    static final int STATE_FAST_FORWARDING = 4;
    static final int STATE_REWINDING = 5;
    static final int STATE_BUFFERING = 6;
    static final int STATE_ERROR = 7;
    static final int STATE_CONNECTING = 8;
    static final int STATE_SKIPPING_TO_PREVIOUS = 9;
    static final int STATE_SKIPPING_TO_NEXT = 10;
    static final int STATE_SKIPPING_TO_QUEUE_ITEM = 11;

    private static volatile boolean broken;
    /** Where the one failure line goes; tests replace it, since android.util.Log is not available there. */
    static CardFeature.Reporter reporter = t -> Log.w(TAG, "now playing switched off for this session: " + t);

    private NowPlaying() {
    }

    /** Playing, or about to: the card shows these first and draws the pause glyph. */
    static boolean isActive(int state) {
        switch (state) {
            case STATE_PLAYING:
            case STATE_BUFFERING:
            case STATE_FAST_FORWARDING:
            case STATE_REWINDING:
            case STATE_CONNECTING:
            case STATE_SKIPPING_TO_PREVIOUS:
            case STATE_SKIPPING_TO_NEXT:
            case STATE_SKIPPING_TO_QUEUE_ITEM:
                return true;
            default:
                return false;
        }
    }

    /**
     * The session to show, as an index into the lists Android returns (most recently active first):
     * the first one playing, else the first one paused; -1 when nothing is playing or paused. The
     * launcher's own package is skipped.
     */
    static int choose(int[] states, String[] packages, String self) {
        int paused = -1;
        for (int i = 0; i < states.length; i++) {
            if (self.equals(packages[i])) continue;
            if (isActive(states[i])) return i;
            if (states[i] == STATE_PAUSED && paused < 0) paused = i;
        }
        return paused;
    }

    /** The first line: the track's title, else the display title, else the app's name. */
    static String title(String title, String displayTitle, String appLabel) {
        if (!blank(title)) return title.trim();
        if (!blank(displayTitle)) return displayTitle.trim();
        return appLabel;
    }

    /**
     * The second line, "artist · app": the artist (or the album when there is none) and the app's
     * name, leaving out what's missing and the app's name when the first line already is it.
     */
    static String subtitle(String artist, String album, String appLabel, String shownTitle) {
        String who = !blank(artist) ? artist.trim() : !blank(album) ? album.trim() : "";
        boolean appInTitle = appLabel.equals(shownTitle);
        if (who.isEmpty()) return appInTitle ? "" : appLabel;
        return appInTitle ? who : who + " · " + appLabel;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static boolean working() {
        return !broken;
    }

    /** Runs one step and returns its value, or the fallback once the card is off or the step fails. */
    static <T> T run(CardFeature.Step<T> step, T fallback) {
        if (broken) return fallback;
        try {
            return step.run();
        } catch (Exception | LinkageError e) {
            broken = true;
            try {
                reporter.failed(e);
            } catch (Exception ignored) {
            }
            return fallback;
        }
    }

    /** For tests only: forget a failure. */
    static void reset() {
        broken = false;
    }
}

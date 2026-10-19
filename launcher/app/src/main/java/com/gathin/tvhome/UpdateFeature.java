package com.gathin.tvhome;

import android.util.Log;

/**
 * The guard around self-update, as {@link CardFeature} is around module cards: the first failure of
 * any kind that is safe to catch is logged once and switches updates off for the rest of the process,
 * so Home behaves as with self_update off. Called from the main thread and the update thread.
 */
final class UpdateFeature {

    private static volatile boolean broken;
    /** Where the one failure line goes; tests replace it, since android.util.Log is not available there. */
    static CardFeature.Reporter reporter = t -> Log.w(Updater.TAG, "self-update switched off for this session: " + t);

    private UpdateFeature() {
    }

    static boolean working() {
        return !broken;
    }

    /** Runs one step and returns its value, or the fallback once the feature is off or the step fails. */
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

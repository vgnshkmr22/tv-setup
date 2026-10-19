package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps remote keys to apps and to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for the Input button; on Google TV the stock launcher handles that key, so with the stock
 * launcher disabled the button does nothing. KEYCODE_TV always goes to the input switcher; it is not remappable.
 *
 * Any other key can be remapped to open an app with the {@code key_launch_map} config key
 * ({@link Config}), e.g. {@code KEYCODE_MEDIA_PLAY=com.zattoo.player} for a remote's dedicated streaming button.
 * Keys not in the map pass through untouched. Every key event is logged under the {@code KeyRemap} tag so the
 * real keycode of an unknown remote button can be read off `logcat`.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    private static final String TAG = "KeyRemap";

    private Map<String, String> keyLaunchMap = Collections.emptyMap();

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        keyLaunchMap = parseKeyLaunchMap(Config.load(this).keyLaunchMap);
        Log.i(TAG, "service connected, key_launch_map=" + keyLaunchMap);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        Log.i(TAG, code + " " + KeyEvent.keyCodeToString(code)
                + " action=" + (event.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up")
                + " repeat=" + event.getRepeatCount());

        if (code == KeyEvent.KEYCODE_TV) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                try {
                    startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ignored) {
                }
            }
            return true;
        }

        String pkg = keyLaunchMap.get(KeyEvent.keyCodeToString(code));
        if (pkg == null) pkg = keyLaunchMap.get(Integer.toString(code));
        if (pkg == null) return false;

        // Not installed (or nothing to launch): leave the key exactly as the system would have been.
        Intent launch = launchIntent(pkg);
        if (launch == null) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            try {
                startActivity(launch);
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    /** The intent that opens {@code pkg}, or null when the package is missing or has no launch entry point. */
    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            // Some TV apps only declare a leanback launcher entry.
            intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            if (pm.resolveActivity(intent, 0) == null) return null;
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * Parses {@code key_launch_map}: {@code KEYCODE=package} entries separated by {@code ;}, where KEYCODE is an
     * Android keycode name (matched case-insensitively) or its decimal number. Malformed entries are skipped,
     * later duplicates win, and an empty spec gives an empty map (the feature off).
     */
    static Map<String, String> parseKeyLaunchMap(String spec) {
        if (spec == null || spec.trim().isEmpty()) return Collections.emptyMap();
        Map<String, String> out = new HashMap<>();
        for (String entry : spec.split(";")) {
            int eq = entry.indexOf('=');
            if (eq < 0) continue;
            String key = entry.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String value = entry.substring(eq + 1).trim();
            if (key.isEmpty() || value.isEmpty()) continue;
            out.put(key, value);
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}

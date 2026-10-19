package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Gallery: the owner's photos from Pictures/Launcher on the TV as the Home background, one at random
 * every five minutes. Off unless switched on (gallery, or the Settings menu). Listing, order and timing
 * are plain Java so they can be tested on the JVM; decoding runs on {@link #EXECUTOR}, never on the main
 * thread. Like {@link CardFeature}, the first failure switches Gallery off for the session.
 */
final class Gallery {
    static final String TAG = "TvHomeGallery";
    /** Under the shared Pictures folder: /sdcard/Pictures/Launcher. */
    static final String FOLDER = "Launcher";
    static final long INTERVAL_MS = 5 * 60_000L;
    /** How often the folder is listed again on a return to Home. */
    static final long RELIST_MS = 5 * 60_000L;
    static final int MAX_FILES = 2000;

    /** One thread that ends when idle, so a TV that never uses Gallery keeps no thread for it. */
    static final ThreadPoolExecutor EXECUTOR =
            new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    private static volatile boolean broken;
    static CardFeature.Reporter reporter = t -> Log.w(TAG, "gallery switched off for this session: " + t);

    private Gallery() {
    }

    /** A photo the launcher shows: jpg, jpeg, png or webp, not hidden (".thumb.jpg", "._IMG.jpg" from macOS). */
    static boolean isPhoto(String name) {
        if (name == null || name.isEmpty() || name.startsWith(".")) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    /** Photo file names from a directory listing, sorted, capped at {@link #MAX_FILES}. */
    static List<String> photos(String[] names) {
        List<String> out = new ArrayList<>();
        if (names == null) return out;
        for (String n : names) {
            if (isPhoto(n)) out.add(n);
        }
        Collections.sort(out);
        return out.size() > MAX_FILES ? new ArrayList<>(out.subList(0, MAX_FILES)) : out;
    }

    /** Milliseconds until the next photo is due: now when none is shown yet, else five minutes after the last. */
    static long delayUntilNext(long shownAt, long now) {
        if (shownAt <= 0 || shownAt > now) return 0;
        return Math.max(0, shownAt + INTERVAL_MS - now);
    }

    /** True when the folder listing is older than {@link #RELIST_MS} (or there is none). */
    static boolean relistDue(long listedAt, long now) {
        return listedAt <= 0 || listedAt > now || now - listedAt >= RELIST_MS;
    }

    /** Every photo once, in random order, then a new round; the last of a round never opens the next. */
    static final class ShuffleBag {
        private final Random random;
        private final List<String> left = new ArrayList<>();
        private String last;

        ShuffleBag(Random random) {
            this.random = random;
        }

        /** The next photo out of {@code all}, or null when there are none. Photos removed meanwhile are skipped. */
        String next(List<String> all) {
            if (all.isEmpty()) return null;
            Set<String> present = new HashSet<>(all);
            left.retainAll(present);
            if (left.isEmpty()) {
                left.addAll(all);
                Collections.shuffle(left, random);
                if (left.size() > 1 && left.get(0).equals(last)) {
                    Collections.swap(left, 0, left.size() - 1);
                }
            }
            last = left.remove(0);
            return last;
        }
    }

    static boolean working() {
        return !broken;
    }

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

    // ---- Android side, on the executor ------------------------------------------------------------

    static String permission() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
    }

    /**
     * The photos to choose from: file paths from the folder, or content URIs from MediaStore when the
     * folder lists nothing (a file the media store knows can be invisible to a plain listing, and the
     * other way round, depending on how it got there).
     */
    static List<String> list(Context ctx) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER);
        List<String> out = new ArrayList<>();
        for (String name : photos(dir.list())) out.add(new File(dir, name).getPath());
        if (!out.isEmpty()) return out;
        if (Build.VERSION.SDK_INT < 29) return out;
        ContentResolver cr = ctx.getContentResolver();
        Uri images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
        try (Cursor c = cr.query(images, cols, MediaStore.Images.Media.RELATIVE_PATH + "=?",
                new String[] {Environment.DIRECTORY_PICTURES + "/" + FOLDER + "/"}, MediaStore.Images.Media.DISPLAY_NAME)) {
            while (c != null && c.moveToNext() && out.size() < MAX_FILES) {
                if (isPhoto(c.getString(1))) out.add(ContentUris.withAppendedId(images, c.getLong(0)).toString());
            }
        }
        return out;
    }

    /**
     * One photo scaled to cover a w x h screen: bounds first, then a power-of-two sample so the bitmap
     * is about screen size, RGB_565, turned upright by its EXIF orientation. Null when it can't be read.
     */
    static Bitmap decode(Context ctx, String item, int w, int h) {
        try {
            int degrees = rotation(ctx, item);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            decodeStream(ctx, item, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            int pw = degrees % 180 == 0 ? opts.outWidth : opts.outHeight;
            int ph = degrees % 180 == 0 ? opts.outHeight : opts.outWidth;
            int sample = 1;
            while (pw / (sample * 2) >= w && ph / (sample * 2) >= h) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = decodeStream(ctx, item, opts);
            if (bmp == null || degrees == 0) return bmp;
            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap turned = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (turned != bmp) bmp.recycle();
            return turned;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "skipped " + item + ": " + e);
            return null;
        }
    }

    private static Bitmap decodeStream(Context ctx, String item, BitmapFactory.Options opts) throws IOException {
        if (!item.startsWith("content:")) return BitmapFactory.decodeFile(item, opts);
        try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(item))) {
            return in == null ? null : BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private static int rotation(Context ctx, String item) {
        try {
            ExifInterface exif;
            if (item.startsWith("content:")) {
                try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(item))) {
                    if (in == null) return 0;
                    exif = new ExifInterface(in);
                }
            } else {
                exif = new ExifInterface(item);
            }
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;   // no EXIF (PNG, WebP) or unreadable: as stored
        }
    }
}

package com.gathin.tvhome;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Bitcoin line above the top buttons: spot price, change since 24 hours ago and the latest block
 * height. Off unless the owner turns it on (btc_ticker, or the Settings menu). Three fixed HTTPS
 * requests, no redirects, no identifiers; only while Home is in front, at most every five minutes.
 * Parsing and formatting are plain Java so they can be tested on the JVM.
 */
final class Ticker {
    private static final String TAG = "TvHomeTicker";

    static final String SPOT_URL = "https://api.coinbase.com/v2/prices/BTC-USD/spot";
    /** 24 hour open and last trade; the change is last price against open. */
    static final String STATS_URL = "https://api.exchange.coinbase.com/products/BTC-USD/stats";
    static final String HEIGHT_URL = "https://mempool.space/api/blocks/tip/height";

    /** How often the line refreshes while Home is in front, and how old a value may be on return. */
    static final long REFRESH_MS = 5 * 60_000L;
    static final long STALE_MS = 60_000L;
    private static final int MAX_BYTES = 8 * 1024;

    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static final AtomicBoolean busy = new AtomicBoolean(false);

    /** One reading. Any part can be missing (NaN or -1) when its request failed. */
    static final class Quote {
        final double price;
        final double changePct;
        final long height;
        final long at;

        Quote(double price, double changePct, long height, long at) {
            this.price = price;
            this.changePct = changePct;
            this.height = height;
            this.at = at;
        }

        boolean hasPrice() {
            return !Double.isNaN(price);
        }
    }

    /** The last reading in this process, so a recreated activity shows it at once. */
    static volatile Quote last;

    private Ticker() {
    }

    /** Fetches all three values; a failed part keeps the previous reading's value for that part. */
    static Quote fetch(Quote previous) {
        double price = Double.NaN;
        double open = Double.NaN;
        long height = -1;
        try {
            price = parseSpot(get(SPOT_URL));
        } catch (Exception e) {
            Log.w(TAG, "spot: " + e);
        }
        try {
            open = parseOpen(get(STATS_URL));
        } catch (Exception e) {
            Log.w(TAG, "stats: " + e);
        }
        try {
            height = parseHeight(get(HEIGHT_URL));
        } catch (Exception e) {
            Log.w(TAG, "height: " + e);
        }
        if (Double.isNaN(price) && previous != null) price = previous.price;
        double change = change(price, open);
        if (Double.isNaN(change) && previous != null) change = previous.changePct;
        if (height < 0 && previous != null) height = previous.height;
        return new Quote(price, change, height, System.currentTimeMillis());
    }

    /** {"data":{"amount":"81002.12","base":"BTC","currency":"USD"}} */
    static double parseSpot(String body) throws Exception {
        JSONObject data = new JSONObject(body).getJSONObject("data");
        if (!"BTC".equals(data.optString("base")) || !"USD".equals(data.optString("currency"))) {
            throw new IllegalArgumentException("not BTC-USD");
        }
        return positive(Double.parseDouble(data.getString("amount")));
    }

    /** {"open":"81245.1","high":…,"low":…,"last":…,"volume":…} */
    static double parseOpen(String body) throws Exception {
        return positive(Double.parseDouble(new JSONObject(body).getString("open")));
    }

    /** A bare decimal number, the height of the latest block. */
    static long parseHeight(String body) {
        String s = body.trim();
        if (s.isEmpty() || s.length() > 9 || !s.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("not a block height");
        }
        return Long.parseLong(s);
    }

    static double change(double price, double open) {
        if (Double.isNaN(price) || Double.isNaN(open) || open <= 0) return Double.NaN;
        return (price - open) / open * 100;
    }

    private static double positive(double v) {
        if (!(v > 0) || Double.isInfinite(v)) throw new IllegalArgumentException("not a price: " + v);
        return v;
    }

    /** "$81,002" */
    static String formatPrice(double price) {
        return "$" + NumberFormat.getIntegerInstance(Locale.US).format(Math.round(price));
    }

    /** "▼ 0.3%" or "▲ 1.2%"; a change that rounds to zero shows as "0.0%" with no arrow. */
    static String formatChange(double pct) {
        String n = String.format(Locale.US, "%.1f%%", Math.abs(pct));
        if (n.equals("0.0%")) return n;
        return (pct < 0 ? "▼ " : "▲ ") + n;
    }

    /** "972,026" */
    static String formatHeight(long height) {
        return NumberFormat.getIntegerInstance(Locale.US).format(height);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "CleanYourTV/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("response too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}

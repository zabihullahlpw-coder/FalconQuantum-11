package com.falcon.quantum;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One record per position, naming the stop and target that guard it.
 *
 * A time-based rate limit only slows a runaway down. This makes duplication
 * structurally impossible: a position either has a protection record or it
 * does not, and the record survives a restart. It also gives us the thing
 * needed at the other end - knowing which orders to cancel when the
 * position disappears, so conditional orders cannot accumulate forever.
 *
 * Key format: ENV|SYMBOL|SIDE|ENTRY, rounded so tiny mark-price drift does
 * not mint a new identity for the same position.
 */
public final class ProtectionLedger {

    private static final String PREFS = "falcon_protection";
    private static final String INDEX = "index";

    private ProtectionLedger() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Stable identity for a position. */
    public static String positionId(String env, String symbol, String side, double entry) {
        // 6 significant-ish digits: enough to distinguish two genuinely
        // different entries, coarse enough to survive rounding noise.
        long stamp = Math.round(entry * 1_000_000d);
        return env + "|" + symbol.toUpperCase() + "|" + side.toUpperCase() + "|" + stamp;
    }

    public static final class Record {
        public String id;
        public long stopId;
        public long targetId;
        public long placedAt;

        public boolean hasStop() {
            return stopId > 0;
        }

        public boolean hasTarget() {
            return targetId > 0;
        }

        public boolean complete() {
            return hasStop() && hasTarget();
        }
    }

    public static Record get(Context ctx, String id) {
        SharedPreferences p = prefs(ctx);
        String raw = p.getString(id, null);
        if (raw == null) return null;
        Record r = new Record();
        r.id = id;
        String[] parts = raw.split(",");
        try {
            r.stopId = Long.parseLong(parts[0]);
            r.targetId = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            r.placedAt = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
        } catch (Exception e) {
            return null;
        }
        return r;
    }

    public static void put(Context ctx, String id, long stopId, long targetId) {
        SharedPreferences p = prefs(ctx);
        Record existing = get(ctx, id);
        long stop = stopId > 0 ? stopId : (existing == null ? 0 : existing.stopId);
        long target = targetId > 0 ? targetId : (existing == null ? 0 : existing.targetId);

        Set<String> index = new HashSet<String>(p.getStringSet(INDEX, new HashSet<String>()));
        index.add(id);
        p.edit()
                .putString(id, stop + "," + target + "," + System.currentTimeMillis())
                .putStringSet(INDEX, index)
                .apply();
    }

    public static void remove(Context ctx, String id) {
        SharedPreferences p = prefs(ctx);
        Set<String> index = new HashSet<String>(p.getStringSet(INDEX, new HashSet<String>()));
        index.remove(id);
        p.edit().remove(id).putStringSet(INDEX, index).apply();
    }

    /** Every record currently held, used to find ones with no position left. */
    public static List<Record> all(Context ctx) {
        List<Record> out = new ArrayList<Record>();
        Set<String> index = prefs(ctx).getStringSet(INDEX, new HashSet<String>());
        for (String id : index) {
            Record r = get(ctx, id);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** Drop every record for an environment, e.g. after a manual cleanup. */
    public static void clear(Context ctx, String env) {
        SharedPreferences p = prefs(ctx);
        Set<String> index = new HashSet<String>(p.getStringSet(INDEX, new HashSet<String>()));
        SharedPreferences.Editor e = p.edit();
        Set<String> keep = new HashSet<String>();
        for (String id : index) {
            if (id.startsWith(env + "|")) {
                e.remove(id);
            } else {
                keep.add(id);
            }
        }
        e.putStringSet(INDEX, keep).apply();
    }

    public static String symbolOf(String id) {
        String[] parts = id.split("\\|");
        return parts.length > 1 ? parts[1] : "";
    }

    public static String envOf(String id) {
        String[] parts = id.split("\\|");
        return parts.length > 0 ? parts[0] : "";
    }
}

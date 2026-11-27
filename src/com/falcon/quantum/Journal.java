package com.falcon.quantum;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Durable record of what actually happened: every position opened and
 * closed, and every error.
 *
 * Errors used to arrive as notifications and then vanish. That made
 * repeated failures impossible to review and buried the one notification
 * that matters - a position opening or closing. Errors are written here
 * instead, and only trade events reach the notification shade.
 */
public final class Journal extends SQLiteOpenHelper {

    private static final String DB = "falcon_journal.db";
    private static final int VERSION = 1;

    public Journal(Context ctx) {
        super(ctx, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trades ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "env TEXT, symbol TEXT, side TEXT, leverage INTEGER, margin_mode TEXT,"
                + "entry REAL, exit REAL, qty REAL, notional REAL,"
                + "sl REAL, tp REAL, pnl REAL, protection TEXT,"
                + "opened_at INTEGER, closed_at INTEGER, status TEXT, note TEXT)");
        db.execSQL("CREATE INDEX idx_trades_env ON trades(env, opened_at DESC)");

        db.execSQL("CREATE TABLE errors ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "at INTEGER, env TEXT, symbol TEXT, stage TEXT, detail TEXT, context TEXT)");
        db.execSQL("CREATE INDEX idx_errors_at ON errors(at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS trades");
        db.execSQL("DROP TABLE IF EXISTS errors");
        onCreate(db);
    }

    // ------------------------------------------------------------ trades

    public static final class Trade {
        public long id, openedAt, closedAt;
        public String env, symbol, side, marginMode, status, protection, note;
        public int leverage;
        public double entry, exit, qty, notional, sl, tp, pnl;
    }

    /** Record an opened position. Returns its row id. */
    public long recordOpen(String env, String symbol, String side, int leverage,
                           String marginMode, double entry, double qty, double notional,
                           double sl, double tp, String protection) {
        ContentValues v = new ContentValues();
        v.put("env", env);
        v.put("symbol", symbol);
        v.put("side", side);
        v.put("leverage", leverage);
        v.put("margin_mode", marginMode);
        v.put("entry", entry);
        v.put("qty", qty);
        v.put("notional", notional);
        v.put("sl", sl);
        v.put("tp", tp);
        v.put("protection", protection);
        v.put("opened_at", System.currentTimeMillis());
        v.put("status", "OPEN");
        return getWritableDatabase().insert("trades", null, v);
    }

    /** Close the most recent open row for a symbol. */
    public void recordClose(String env, String symbol, double exit, double pnl, String reason) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM trades WHERE env=? AND symbol=? AND status='OPEN' "
                        + "ORDER BY id DESC LIMIT 1", new String[]{env, symbol});
        long id = c.moveToFirst() ? c.getLong(0) : -1;
        c.close();
        if (id < 0) return;
        ContentValues v = new ContentValues();
        v.put("exit", exit);
        v.put("pnl", pnl);
        v.put("closed_at", System.currentTimeMillis());
        v.put("status", reason == null ? "CLOSED" : reason);
        getWritableDatabase().update("trades", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** True when this symbol already has an open row, used to avoid doubles. */
    public boolean hasOpen(String env, String symbol) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM trades WHERE env=? AND symbol=? AND status='OPEN' LIMIT 1",
                new String[]{env, symbol});
        boolean has = c.moveToFirst();
        c.close();
        return has;
    }

    public List<Trade> history(String env, int limit) {
        List<Trade> out = new ArrayList<Trade>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,env,symbol,side,leverage,margin_mode,entry,exit,qty,notional,"
                        + "sl,tp,pnl,protection,opened_at,closed_at,status,note FROM trades "
                        + "WHERE env=? ORDER BY id DESC LIMIT " + Math.max(1, limit),
                new String[]{env});
        while (c.moveToNext()) {
            Trade t = new Trade();
            t.id = c.getLong(0);
            t.env = c.getString(1);
            t.symbol = c.getString(2);
            t.side = c.getString(3);
            t.leverage = c.getInt(4);
            t.marginMode = c.getString(5);
            t.entry = c.getDouble(6);
            t.exit = c.getDouble(7);
            t.qty = c.getDouble(8);
            t.notional = c.getDouble(9);
            t.sl = c.getDouble(10);
            t.tp = c.getDouble(11);
            t.pnl = c.getDouble(12);
            t.protection = c.getString(13);
            t.openedAt = c.getLong(14);
            t.closedAt = c.getLong(15);
            t.status = c.getString(16);
            t.note = c.getString(17);
            out.add(t);
        }
        c.close();
        return out;
    }

    /** Totals across closed trades: {wins, losses, netPnl}. */
    public double[] summary(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(CASE WHEN pnl>0 THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN pnl<=0 THEN 1 ELSE 0 END),"
                        + " COALESCE(SUM(pnl),0) FROM trades WHERE env=? AND status!='OPEN'",
                new String[]{env});
        double w = 0, l = 0, net = 0;
        if (c.moveToFirst()) {
            w = c.getInt(0);
            l = c.getInt(1);
            net = c.getDouble(2);
        }
        c.close();
        return new double[]{w, l, net};
    }

    // ------------------------------------------------------------ errors

    public static final class Problem {
        public long at;
        public String env, symbol, stage, detail, context;
    }

    /**
     * Record a failure. Deliberately never notifies: errors belong in a
     * list the operator can review, not in the notification shade.
     */
    public void recordError(String env, String symbol, String stage,
                            String detail, String context) {
        ContentValues v = new ContentValues();
        v.put("at", System.currentTimeMillis());
        v.put("env", env == null ? "" : env);
        v.put("symbol", symbol == null ? "" : symbol);
        v.put("stage", stage == null ? "" : stage);
        v.put("detail", detail == null ? "" : detail);
        v.put("context", context == null ? "" : context);
        getWritableDatabase().insert("errors", null, v);
        prune();
    }

    public List<Problem> errors(int limit) {
        List<Problem> out = new ArrayList<Problem>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT at,env,symbol,stage,detail,context FROM errors "
                        + "ORDER BY id DESC LIMIT " + Math.max(1, limit), null);
        while (c.moveToNext()) {
            Problem p = new Problem();
            p.at = c.getLong(0);
            p.env = c.getString(1);
            p.symbol = c.getString(2);
            p.stage = c.getString(3);
            p.detail = c.getString(4);
            p.context = c.getString(5);
            out.add(p);
        }
        c.close();
        return out;
    }

    public int errorCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM errors", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** Net realised P/L since midnight, for the daily-loss stop. */
    public double todayPnl(String env) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(pnl),0) FROM trades WHERE env=? AND status!='OPEN' "
                        + "AND closed_at>=?",
                new String[]{env, String.valueOf(cal.getTimeInMillis())});
        double p = c.moveToFirst() ? c.getDouble(0) : 0;
        c.close();
        return p;
    }

    /** How many losses in a row, most recent first. */
    public int consecutiveLosses(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT pnl FROM trades WHERE env=? AND status!='OPEN' ORDER BY id DESC LIMIT 20",
                new String[]{env});
        int run = 0;
        while (c.moveToNext()) {
            if (c.getDouble(0) < 0) run++;
            else break;
        }
        c.close();
        return run;
    }

    public void clearErrors() {
        getWritableDatabase().delete("errors", null, null);
    }

    /** Keep the log useful rather than unbounded. */
    private void prune() {
        getWritableDatabase().execSQL(
                "DELETE FROM errors WHERE id NOT IN "
                        + "(SELECT id FROM errors ORDER BY id DESC LIMIT 500)");
    }

    public static String when(long millis) {
        if (millis <= 0) return "—";
        return new SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(new Date(millis));
    }
}

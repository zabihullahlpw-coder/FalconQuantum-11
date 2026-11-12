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
    private static final int VERSION = 2;

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
        addEvidenceSchema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) addEvidenceSchema(db);
    }

    private void addEvidenceSchema(SQLiteDatabase db) {
        String[] columns = {
            "entry_order_id INTEGER DEFAULT 0", "client_id TEXT DEFAULT ''",
            "position_side TEXT DEFAULT ''", "account_key TEXT DEFAULT ''",
            "stop_order_id INTEGER DEFAULT 0", "target_order_id INTEGER DEFAULT 0",
            "gross_pnl REAL", "commission REAL", "funding REAL", "fee_assets TEXT DEFAULT ''",
            "cost_state TEXT DEFAULT 'LEGACY_UNVERIFIED'", "close_reason TEXT DEFAULT 'UNKNOWN'",
            "close_detail TEXT DEFAULT ''", "exit_order_ids TEXT DEFAULT ''",
            "initial_risk REAL DEFAULT 0", "strategy_key TEXT DEFAULT ''",
            "signal_price REAL DEFAULT 0", "requested_close_id INTEGER DEFAULT 0",
            "requested_close_reason TEXT DEFAULT ''", "requested_close_client TEXT DEFAULT ''"
        };
        for (String col : columns) db.execSQL("ALTER TABLE trades ADD COLUMN " + col);
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, trade_id INTEGER,"
                + "at INTEGER, env TEXT, symbol TEXT, kind TEXT, detail TEXT)");
        db.execSQL("CREATE INDEX idx_events_trade ON events(trade_id,at)");
    }

    /** Persist an intent BEFORE sending any order. Uncertain outcomes stay pending. */
    public long recordIntent(Settings cfg, Engine.Row row, double qty, String clientId,
                             String accountKey, String strategyKey) {
        long id = recordOpen(cfg.env, row.symbol, row.side, cfg.leverage, cfg.marginMode,
                row.entry, qty, qty * row.entry, row.stop, row.target, "PENDING");
        ContentValues v = new ContentValues();
        v.put("status", "ENTRY_PENDING"); v.put("client_id", clientId);
        v.put("account_key", accountKey); v.put("strategy_key", strategyKey);
        v.put("signal_price", row.entry); v.put("initial_risk", Math.abs(row.entry-row.stop));
        v.put("cost_state", "PENDING"); v.put("note", row.reason+" | "+row.reasons+" | "+row.warnings); update(id, v);
        event(id, cfg.env, row.symbol, "ENTRY_INTENT", row.reason + " | " + row.reasons);
        return id;
    }

    public void update(long id, ContentValues values) {
        if (getWritableDatabase().update("trades", values, "id=?",
                new String[]{String.valueOf(id)}) != 1) throw new IllegalStateException("Missing trade " + id);
    }

    public void event(long id, String env, String symbol, String kind, String detail) {
        ContentValues v = new ContentValues();
        v.put("trade_id", id); v.put("at", System.currentTimeMillis()); v.put("env", env);
        v.put("symbol", symbol); v.put("kind", kind); v.put("detail", detail);
        if (getWritableDatabase().insert("events", null, v) < 0)
            throw new IllegalStateException("Could not persist trade evidence");
    }

    public String events(long id) {
        StringBuilder out = new StringBuilder();
        Cursor c = getReadableDatabase().rawQuery("SELECT at,kind,detail FROM events WHERE trade_id=? ORDER BY id",
                new String[]{String.valueOf(id)});
        try { while (c.moveToNext()) out.append(when(c.getLong(0))).append("  ")
                .append(c.getString(1)).append("\n").append(c.getString(2)).append("\n\n"); }
        finally { c.close(); }
        return out.toString();
    }

    public Trade active(String env, String symbol, String side) {
        for (Trade t : history(env, 10000)) if (symbol.equals(t.symbol) && side.equals(t.side)
                && t.closedAt == 0 && !"ENTRY_REJECTED".equals(t.status)) return t;
        return null;
    }

    // ------------------------------------------------------------ trades

    public static final class Trade {
        public long id, openedAt, closedAt;
        public String env, symbol, side, marginMode, status, protection, note;
        public int leverage;
        public double entry, exit, qty, notional, sl, tp, pnl;
        public long entryOrderId, stopOrderId, targetOrderId, requestedCloseId;
        public String clientId, positionSide, accountKey, costState, closeReason, closeDetail,
                exitOrderIds, feeAssets, strategyKey, requestedCloseReason, requestedCloseClient;
        public double grossPnl, commission, funding, initialRisk, signalPrice;
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
        long id = getWritableDatabase().insert("trades", null, v);
        if (id < 0) throw new IllegalStateException("Could not record trade");
        return id;
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
        v.put("status", "RECONCILE_PENDING");
        v.put("close_reason", reason == null ? "UNKNOWN" : reason);
        v.put("cost_state", "UNVERIFIED");
        v.put("close_detail", "Legacy close estimate; requires exchange fills");
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
                        + "sl,tp,pnl,protection,opened_at,closed_at,status,note,"
                        + "entry_order_id,client_id,position_side,account_key,stop_order_id,target_order_id,"
                        + "gross_pnl,commission,funding,fee_assets,cost_state,close_reason,close_detail,"
                        + "exit_order_ids,initial_risk,strategy_key,signal_price,requested_close_id,requested_close_reason,requested_close_client FROM trades "
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
            t.entryOrderId=c.getLong(18); t.clientId=c.getString(19); t.positionSide=c.getString(20);
            t.accountKey=c.getString(21); t.stopOrderId=c.getLong(22); t.targetOrderId=c.getLong(23);
            t.grossPnl=c.isNull(24)?Double.NaN:c.getDouble(24);
            t.commission=c.isNull(25)?Double.NaN:c.getDouble(25);
            t.funding=c.isNull(26)?Double.NaN:c.getDouble(26); t.feeAssets=c.getString(27);
            t.costState=c.getString(28); t.closeReason=c.getString(29); t.closeDetail=c.getString(30);
            t.exitOrderIds=c.getString(31); t.initialRisk=c.getDouble(32); t.strategyKey=c.getString(33);
            t.signalPrice=c.getDouble(34); t.requestedCloseId=c.getLong(35); t.requestedCloseReason=c.getString(36);
            t.requestedCloseClient=c.getString(37);
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
                        + " COALESCE(SUM(pnl),0) FROM trades WHERE env=? AND closed_at>0 AND cost_state IN ('CONFIRMED','SIMULATED')",
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
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(pnl),0) FROM trades WHERE env=? AND closed_at>0 AND cost_state IN ('CONFIRMED','SIMULATED') "
                        + "AND closed_at>=?",
                new String[]{env, String.valueOf(cal.getTimeInMillis())});
        double p = c.moveToFirst() ? c.getDouble(0) : 0;
        c.close();
        return p;
    }

    /** How many losses in a row, most recent first. */
    public int consecutiveLosses(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT pnl FROM trades WHERE env=? AND closed_at>0 AND cost_state IN ('CONFIRMED','SIMULATED') ORDER BY id DESC LIMIT 20",
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

    public String exportEvidence(String env) throws Exception {
        org.json.JSONObject report=new org.json.JSONObject();
        report.put("version",Readiness.MODEL_VERSION);report.put("environment",env);
        report.put("exported_at",System.currentTimeMillis());
        report.put("note","Unverified historical rows are not proof of P/L or exit cause. No API keys are exported.");
        for(String table:new String[]{"trades","events","errors"}) {
            org.json.JSONArray rows=new org.json.JSONArray();
            Cursor c=getReadableDatabase().rawQuery("SELECT * FROM "+table+" WHERE env=? ORDER BY id",new String[]{env});
            try{while(c.moveToNext()) {
                org.json.JSONObject row=new org.json.JSONObject();
                for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?org.json.JSONObject.NULL:c.getString(i));
                rows.put(row);
            }}finally{c.close();}
            report.put(table,rows);
        }
        return report.toString(2);
    }

    public static String when(long millis) {
        if (millis <= 0) return "—";
        return new SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(new Date(millis));
    }
}

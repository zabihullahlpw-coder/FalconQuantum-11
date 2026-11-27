package com.falcon.quantum;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulated trading account, one completely separate ledger per environment.
 *
 * Supports both market types:
 *   SPOT     - buy only, no leverage, position sized from free balance.
 *   FUTURES  - long or short, leverage applied to notional, isolated or
 *              cross margin, with a liquidation price enforced locally.
 *
 * This is the account the phone trades against. Real exchange orders are
 * handled separately by {@link Trader}, which is off unless the operator
 * explicitly arms it.
 */
public final class Paper extends SQLiteOpenHelper {

    private static final String DB = "falcon_accounts.db";
    private static final int VERSION = 3;
    public static final double STARTING_BALANCE = 1000.0;

    public Paper(Context ctx) {
        super(ctx, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trades ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "env TEXT, market TEXT, symbol TEXT, side TEXT,"
                + "entry REAL, stop REAL, target REAL, liq REAL,"
                + "qty REAL, notional REAL, margin REAL, leverage INTEGER, margin_mode TEXT,"
                + "opened_at INTEGER, closed_at INTEGER, exit REAL, pnl REAL,"
                + "status TEXT, score REAL)");
        db.execSQL("CREATE TABLE account (env TEXT PRIMARY KEY, balance REAL)");
        for (int i = 0; i < Settings.ENVIRONMENTS.length; i++) {
            ContentValues v = new ContentValues();
            v.put("env", Settings.ENVIRONMENTS[i]);
            v.put("balance", STARTING_BALANCE);
            db.insert("account", null, v);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Schema changed shape between previews; rebuild rather than migrate
        // a simulated ledger that carries no real value.
        db.execSQL("DROP TABLE IF EXISTS trades");
        db.execSQL("DROP TABLE IF EXISTS account");
        onCreate(db);
    }

    public static final class Trade {
        public long id;
        public String env, market, symbol, side, status, marginMode;
        public double entry, stop, target, liq, qty, notional, margin, exit, pnl, score;
        public int leverage;
        public long openedAt, closedAt;
    }

    public double balance(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT balance FROM account WHERE env=?", new String[]{env});
        double b = STARTING_BALANCE;
        if (c.moveToFirst()) b = c.getDouble(0);
        else {
            ContentValues v = new ContentValues();
            v.put("env", env);
            v.put("balance", STARTING_BALANCE);
            getWritableDatabase().insertWithOnConflict("account", null, v,
                    SQLiteDatabase.CONFLICT_IGNORE);
        }
        c.close();
        return b;
    }

    private void setBalance(String env, double b) {
        ContentValues v = new ContentValues();
        v.put("env", env);
        v.put("balance", b);
        getWritableDatabase().insertWithOnConflict("account", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int openCount(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM trades WHERE status='OPEN' AND env=?", new String[]{env});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public boolean hasOpen(String env, String symbol) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM trades WHERE status='OPEN' AND env=? AND symbol=? LIMIT 1",
                new String[]{env, symbol});
        boolean has = c.moveToFirst();
        c.close();
        return has;
    }

    /**
     * Liquidation price for a leveraged position.
     *
     * Isolated margin is backed only by the margin committed to this trade,
     * so it liquidates at roughly a 1/leverage adverse move. Cross margin is
     * backed by the whole free balance, so it can absorb a larger move
     * before liquidating.
     */
    public static double liquidationPrice(boolean isLong, double entry, int leverage,
                                          boolean cross) {
        // 0 is the agreed "no liquidation" marker for both directions. An
        // unleveraged position cannot be liquidated, and returning
        // Double.MAX_VALUE here previously leaked into the UI as a 300-digit
        // number.
        if (leverage <= 1) return 0;
        double buffer = cross ? 1.35 : 0.95;
        double move = entry * (buffer / leverage);
        if (move >= entry) move = entry * 0.95;
        double liq = isLong ? entry - move : entry + move;
        return liq < 0 ? 0 : liq;
    }

    /**
     * Open a simulated position under the given environment's rules.
     * Returns null on success, or a human-readable reason for refusal.
     */
    public String open(Settings cfg, Engine.Row row, Strategy strategy) {
        String env = cfg.env;
        // Only PAPER keeps a simulated ledger. DEMO and LIVE must reflect
        // the real exchange account, never a local invention of one.
        if (!"PAPER".equals(env)) return "simulated ledger is PAPER only";
        if (hasOpen(env, row.symbol)) return "already holding " + row.symbol;
        if (openCount(env) >= cfg.maxPositions) return "max positions reached";

        boolean isLong = "LONG".equals(row.side);
        if (isLong && !cfg.allowLong) return "LONG disabled for " + env;
        if (!isLong && !cfg.allowShort) return "SHORT disabled for " + env;
        if (!isLong && !cfg.isFutures()) return "shorting needs FUTURES";

        double bal = balance(env);
        if (bal <= 0 || row.price <= 0) return "no balance";

        // Margin committed is a fixed slice of the account; leverage then
        // multiplies it into position notional.
        double margin = bal * (cfg.riskPercent / 100.0) * 5.0;
        if (margin > bal * 0.5) margin = bal * 0.5;
        if (margin < 5) return "account too small for this risk setting";

        double notional = margin * cfg.notionalMultiplier();
        double qty = notional / row.price;

        ContentValues v = new ContentValues();
        v.put("env", env);
        v.put("market", cfg.tradingMode);
        v.put("symbol", row.symbol);
        v.put("side", row.side);
        v.put("entry", row.entry);
        v.put("stop", row.stop);
        v.put("target", row.target);
        v.put("liq", cfg.isFutures()
                ? liquidationPrice(isLong, row.entry, cfg.leverage, "CROSS".equals(cfg.marginMode))
                : 0);
        v.put("qty", qty);
        v.put("notional", notional);
        v.put("margin", margin);
        v.put("leverage", cfg.isFutures() ? cfg.leverage : 1);
        v.put("margin_mode", cfg.isFutures() ? cfg.marginMode : "NONE");
        v.put("opened_at", System.currentTimeMillis());
        v.put("status", "OPEN");
        v.put("score", row.score);

        if (getWritableDatabase().insert("trades", null, v) <= 0) return "could not record trade";
        // Margin is locked out of free balance until the position closes.
        setBalance(env, bal - margin);
        return null;
    }

    public List<Trade> trades(String env, String status, int limit) {
        List<Trade> out = new ArrayList<Trade>();
        StringBuilder q = new StringBuilder(
                "SELECT id,env,market,symbol,side,entry,stop,target,liq,qty,notional,margin,"
                        + "leverage,margin_mode,opened_at,closed_at,exit,pnl,status,score"
                        + " FROM trades WHERE env=?");
        List<String> args = new ArrayList<String>();
        args.add(env);
        if (status != null) {
            q.append(" AND status=?");
            args.add(status);
        }
        q.append(" ORDER BY id DESC LIMIT ").append(limit);
        Cursor c = getReadableDatabase().rawQuery(q.toString(),
                args.toArray(new String[args.size()]));
        while (c.moveToNext()) {
            Trade t = new Trade();
            t.id = c.getLong(0);
            t.env = c.getString(1);
            t.market = c.getString(2);
            t.symbol = c.getString(3);
            t.side = c.getString(4);
            t.entry = c.getDouble(5);
            t.stop = c.getDouble(6);
            t.target = c.getDouble(7);
            t.liq = c.getDouble(8);
            t.qty = c.getDouble(9);
            t.notional = c.getDouble(10);
            t.margin = c.getDouble(11);
            t.leverage = c.getInt(12);
            t.marginMode = c.getString(13);
            t.openedAt = c.getLong(14);
            t.closedAt = c.getLong(15);
            t.exit = c.getDouble(16);
            t.pnl = c.getDouble(17);
            t.status = c.getString(18);
            t.score = c.getDouble(19);
            out.add(t);
        }
        c.close();
        return out;
    }

    public double unrealised(Trade t, double price) {
        boolean isLong = "LONG".equals(t.side);
        return (isLong ? (price - t.entry) : (t.entry - price)) * t.qty;
    }

    /**
     * Mark a position to market and close it if stop, target or liquidation
     * is reached. Liquidation is checked first: it is the hard boundary.
     */
    public String markToMarket(Trade t, double price) {
        boolean isLong = "LONG".equals(t.side);
        double pnl = unrealised(t, price);

        if (t.leverage > 1 && t.liq > 0) {
            boolean liquidated = isLong ? price <= t.liq : price >= t.liq;
            if (liquidated) {
                // A liquidation loses the committed margin, not more.
                close(t, price, -t.margin, "LIQUIDATED");
                return "LIQUIDATED";
            }
        }
        boolean hitStop = isLong ? price <= t.stop : price >= t.stop;
        boolean hitTarget = isLong ? price >= t.target : price <= t.target;
        if (hitStop || hitTarget) {
            close(t, price, pnl, hitTarget ? "TARGET" : "STOP");
            return hitTarget ? "TARGET" : "STOP";
        }
        return "OPEN";
    }

    public void close(Trade t, double price, double pnl, String reason) {
        // A position can never lose more than the margin behind it.
        if (pnl < -t.margin) pnl = -t.margin;
        ContentValues v = new ContentValues();
        v.put("closed_at", System.currentTimeMillis());
        v.put("exit", price);
        v.put("pnl", pnl);
        v.put("status", reason);
        getWritableDatabase().update("trades", v, "id=?", new String[]{String.valueOf(t.id)});
        // Release the margin and settle the result.
        setBalance(t.env, balance(t.env) + t.margin + pnl);
    }

    public double realisedPnl(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(pnl),0) FROM trades WHERE status!='OPEN' AND env=?",
                new String[]{env});
        double p = c.moveToFirst() ? c.getDouble(0) : 0;
        c.close();
        return p;
    }

    public int[] winLoss(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(CASE WHEN pnl>0 THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN pnl<=0 THEN 1 ELSE 0 END)"
                        + " FROM trades WHERE status!='OPEN' AND env=?", new String[]{env});
        int w = 0, l = 0;
        if (c.moveToFirst()) {
            w = c.getInt(0);
            l = c.getInt(1);
        }
        c.close();
        return new int[]{w, l};
    }

    public void resetAccount(String env) {
        getWritableDatabase().delete("trades", "env=?", new String[]{env});
        setBalance(env, STARTING_BALANCE);
    }
}

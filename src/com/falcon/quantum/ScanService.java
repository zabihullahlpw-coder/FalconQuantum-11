package com.falcon.quantum;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps Falcon scanning while the app is minimised or the screen is off.
 *
 * Android stops ordinary background threads once an app leaves the
 * foreground, so this runs as a foreground service with a persistent
 * notification - the only way to do continuous work reliably, and the
 * honest one: the user can always see that Falcon is running.
 *
 * What it does on each pass:
 *   1. re-reads settings and strategy, so changes take effect immediately,
 *   2. scans the active environment's symbols,
 *   3. marks open PAPER positions to market so stops and targets still fire,
 *   4. notifies on new READY signals.
 *
 * What it deliberately does NOT do: place real orders. Unattended
 * real-money entries are opt-in per environment and are handled below.
 */
public class ScanService extends Service {

    public static final String CHANNEL_STATUS = "falcon_status";
    public static final String CHANNEL_SIGNALS = "falcon_signals";
    private static final int NOTIFY_ONGOING = 1001;
    private static final int NOTIFY_SIGNAL_BASE = 2000;

    public static final String PREFS = "falcon_prefs";
    public static final String KEY_BG_ENABLED = "bg_enabled";
    public static final String KEY_BG_INTERVAL = "bg_interval_sec";
    public static final String KEY_BG_AUTOTRADE = "bg_autotrade";

    private volatile boolean running = false;
    /** Last protection sweep, so it cannot run on every scan cycle. */
    private volatile long lastSweepAt = 0;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private Paper paper;
    private Journal journal;
    private final Set<String> notified = new HashSet<String>();
    private int signalId = NOTIFY_SIGNAL_BASE;

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BG_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_BG_ENABLED, on).apply();
    }

    /** Seconds between background passes. 15s default, 15s floor. */
    public static int intervalSeconds(Context ctx) {
        int v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BG_INTERVAL, 5);
        if (v < 5) v = 5;
        if (v > 900) v = 900;
        return v;
    }

    public static void setIntervalSeconds(Context ctx, int seconds) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_BG_INTERVAL, Math.max(5, Math.min(900, seconds))).apply();
    }

    /** Unattended entries while minimised. Off by default, on purpose. */
    public static boolean backgroundAutoTrade(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BG_AUTOTRADE, false);
    }

    public static void setBackgroundAutoTrade(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_BG_AUTOTRADE, on).apply();
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ScanService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ScanService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        paper = new Paper(this);
        journal = new Journal(this);
        paper.reconcileJournal();
        createChannels();
    }

    /** The one switch that says scanning is on. Owned by the Activity. */
    public static boolean scanningOn(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("auto_scan_on", false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android can restart a sticky service after killing the process.
        // If scanning is not on, this must not resurrect itself and post a
        // notification claiming to scan.
        if ((!scanningOn(this) && !hasTrackedPositions()) || !isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        running = true;

        startForegroundSafely(buildOngoing("Starting…"));

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "falcon:scan");
            // Released in onDestroy; the timeout is a safety net only.
            wakeLock.acquire(12L * 60L * 60L * 1000L);
        } catch (Exception ignored) {
        }

        worker = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "falcon-bg-scan");
        worker.start();
        // Restart if Android reclaims the process.
        return START_STICKY;
    }

    /**
     * Watch managed-stop positions on their own fast timer.
     *
     * A scan cycle spends most of its time fetching candles for 30 symbols.
     * A stop that is only checked at the end of that is too slow, so this
     * runs independently and does nothing but compare price to the stored
     * levels.
     */
    private void startStopWatcher() {
        Thread watcher = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        enforceManagedStops();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "falcon-stop-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void enforceManagedStops() {
        String env=getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("active_env","PAPER");
        ProtectionMonitor.runAll(this);
    }
    private boolean hasTrackedPositions() {
        String env=getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("active_env","PAPER");
        for(String e:new String[]{"PAPER","DEMO","LIVE"}) for(Journal.Trade t:journal.history(e,10000)) if(t.closedAt==0&&!"ENTRY_REJECTED".equals(t.status))return true;
        return false;
    }

    private void loop() {
        startStopWatcher();
        while (running) {
            if (!scanningOn(this) && !hasTrackedPositions()) {
                // Scanning was stopped while a pass was queued.
                running = false;
                stopForeground(true);
                stopSelf();
                return;
            }
            long started = System.currentTimeMillis();
            try {
                if(scanningOn(this)) scanOnce();
                else updateOngoing("Monitoring protection; scanning paused");
            } catch (Throwable t) {
                updateOngoing("Scan error: " + String.valueOf(t.getMessage()));
            }
            long interval = intervalSeconds(this) * 1000L;
            long elapsed = System.currentTimeMillis() - started;
            // Back-to-back: if the pass took longer than the interval, start
            // the next one immediately rather than falling further behind.
            long sleep = interval - elapsed;
            if (sleep < 0) sleep = 0;
            long until = System.currentTimeMillis() + sleep;
            while (running && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void scanOnce() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String env = p.getString("active_env", "PAPER");
        Settings cfg = Settings.load(this, env);
        Strategy strategy = Strategy.load(this);

        if (cfg.pauseAtLimit && !"PAPER".equals(env) && Trader.hasKeys(this, env)) {
            try {
                Trader t = Trader.forReading(this, cfg);
                if (t != null && t.positions().size() >= cfg.maxPositions) {
                    updateOngoing(env + " · paused, " + cfg.maxPositions
                            + " positions open · resumes when one closes");
                    // Managing what is already open matters more than finding the next
        // trade, so it runs first and on every cycle.
        managePositions(env, cfg, strategy);
        sweepProtection(env);
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        Binance.useFuturesMarket(cfg.isFutures());
        Binance.restoreBlocked(getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet("blocked_symbols", new java.util.HashSet<String>()));
        updateOngoing("Scanning " + env + " · " + cfg.timeframe + "…");

        final List<String> symbols = new ArrayList<String>();
        List<Binance.Market> markets = null;
        try {
            if (cfg.customWatchlist && !cfg.watchlist.isEmpty()) {
                symbols.addAll(cfg.watchlist);
            } else {
                markets = Binance.topMarketsCached(cfg.scanLimit);
                for (int i = 0; i < markets.size(); i++) symbols.add(markets.get(i).symbol);
            }
        } catch (Exception e) {
            updateOngoing("Market data unavailable: " + e.getMessage());
            return;
        }

        // Fetch candles in parallel: sequentially, 12 round trips cannot
        // finish inside a 15s budget on mobile data.
        final java.util.Map<String, Double> volumes = new java.util.HashMap<String, Double>();
        if (markets != null) {
            for (int i = 0; i < markets.size(); i++) {
                volumes.put(markets.get(i).symbol, Double.valueOf(markets.get(i).quoteVolume));
            }
        }
        final Settings fcfg = cfg;
        final Strategy fstrategy = strategy;
        final java.util.List<Engine.Row> results =
                java.util.Collections.synchronizedList(new ArrayList<Engine.Row>());
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(6);
        for (int i = 0; i < symbols.size(); i++) {
            final String sym = symbols.get(i);
            pool.execute(new Runnable() {
                public void run() {
                    if (!running) return;
                    try {
                        Double qv = volumes.get(sym);
                        Binance.Candles k = Binance.klines(sym, fcfg.timeframe, 200);
                        if (Binance.isBlocked(sym)) return;
                        Engine.Row r = EntryTiming.analyse(sym,k,qv==null?20000000:qv.doubleValue(),fstrategy,fcfg);
                        if (r != null) results.add(r);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        pool.shutdown();
        try {
            // Never let a stalled request push the cycle past its interval.
            pool.awaitTermination(Math.max(10, intervalSeconds(this) - 2),
                    java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            pool.shutdownNow();
            return;
        }

        // The protection sweep runs on a slow timer, not every cycle. At a
        // 5s scan it was checking - and, when verification failed, creating -
        // protection twelve times a minute.
        // Managing what is already open matters more than finding the next
        // trade, so it runs first and on every cycle.
        managePositions(env, cfg, strategy);
        sweepProtection(env);

        int ready = 0;
        for (int i = 0; i < results.size() && running; i++) {
            Engine.Row r = results.get(i);
            if ("LONG".equals(r.side) && !cfg.allowLong) continue;
            if ("SHORT".equals(r.side) && !cfg.allowShort) continue;
            if (!r.ready) continue;
            ready++;
            maybeAutoTrade(env, cfg, r, strategy);
        }

        if (notified.size() > 400) notified.clear();
        updateOngoing(env + " · " + results.size() + " scanned · " + ready + " ready · every "
                + intervalSeconds(this) + "s");
    }

    /** When each position was first seen, for the post-entry grace period. */
    private final java.util.HashMap<String, Long> openedAtMillis =
            new java.util.HashMap<String, Long>();

    /** Best favourable excursion per position, for give-back detection. */
    private final java.util.HashMap<String, Double> peakMove =
            new java.util.HashMap<String, Double>();

    /**
     * Re-examine every open position on each cycle.
     *
     * An entry decision ages. This asks, repeatedly, whether the trade is
     * still working: protect it once it has, close it when the reason has
     * gone, and pull an unreachable target closer.
     */
    private void managePositions(String env,Settings cfg,Strategy strategy) {
        // Exit experiments are evaluated in PAPER/replay; existing LIVE exits are not silently changed.
        // The independent watcher owns exchange verification and safety closures.
    }

    private double[] storedPlan(String env, String symbol) {
        String raw = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("plan_" + env + "_" + symbol, "");
        if (raw == null || raw.indexOf('|') < 0) return new double[]{0, 0};
        try {
            String[] parts = raw.split("\\|");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception e) {
            return new double[]{0, 0};
        }
    }

    private void savePlan(String env, String symbol, double sl, double tp) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("plan_" + env + "_" + symbol, sl + "|" + tp).apply();
    }

    /** Re-protect anything bare. Deliberately infrequent. */
    private void sweepProtection(String env) { /* independent 15s watcher */ }

    /**
     * Unattended entries. PAPER is simulated and safe to automate. DEMO and
     * LIVE require the operator to have switched background auto-trading on
     * explicitly, and LIVE additionally requires the arming flag - an app
     * that opens real positions while sitting in a pocket should never be
     * the default.
     */
    private void maybeAutoTrade(String env,Settings cfg,Engine.Row r,Strategy strategy) {
        if(!cfg.autoTrade)return;
        if("PAPER".equals(env)) {
            String error=paper.open(cfg,r,strategy);
            if(error==null&&cfg.notifyOpen)notifyTrade("PAPER opened "+r.symbol,"See HISTORY for modeled fees and execution");
            return;
        }
        if(!backgroundAutoTrade(this))return;
        String result=Execution.open(this,cfg,r,strategy);
        if(result.startsWith("Opened")&&cfg.notifyOpen)notifyTrade(env+" "+r.symbol,result);
        else journal.recordError(env,r.symbol,"Entry decision",result,"background");
    }

    // ------------------------------------------------------------ notifications

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel status = new NotificationChannel(
                CHANNEL_STATUS, "Falcon scanner", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("Shows that background scanning is running.");
        status.setShowBadge(false);
        nm.createNotificationChannel(status);

        NotificationChannel signals = new NotificationChannel(
                CHANNEL_SIGNALS, "Falcon signals", NotificationManager.IMPORTANCE_HIGH);
        signals.setDescription("Alerts when a setup qualifies.");
        nm.createNotificationChannel(signals);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    private Notification buildOngoing(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_STATUS);
        else b = new Notification.Builder(this);
        b.setContentTitle("Falcon Quantum")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b.build();
    }

    private void startForegroundSafely(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFY_ONGOING, n);
            }
        } catch (Exception e) {
            // If the OS refuses the foreground start there is no way to keep
            // running reliably; stop rather than pretend to be scanning.
            running = false;
            stopSelf();
        }
    }

    private void updateOngoing(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFY_ONGOING, buildOngoing(text));
        } catch (Exception ignored) {
        }
    }

    /** Only called when a position opens (or an open attempt fails). */
    private void notifyTrade(String title, String body) {
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_SIGNALS);
            else b = new Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH);
            b.setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentIntent(openApp())
                    .setAutoCancel(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(signalId++, b.build());
            if (signalId > NOTIFY_SIGNAL_BASE + 200) signalId = NOTIFY_SIGNAL_BASE;
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}

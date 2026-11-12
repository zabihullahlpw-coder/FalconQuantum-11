package com.falcon.quantum;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

/** Local permission/transport tests. Every HTTPS connection is intercepted; no exchange is contacted. */
public final class LiveControlTests {
    private static int passed, writes;
    private static Runnable beforeConnection;
    private interface Action { void run() throws Exception; }
    private static void ok(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
        passed++;
    }
    private static void denied(Action action,String message) throws Exception {
        int before=writes;
        try { action.run(); throw new AssertionError(message+": allowed"); }
        catch(Trader.EntryNotSubmittedException expected) { ok(writes==before,message); }
    }
    private static String transport(Trader client,String path,boolean opening) throws Exception {
        Method method=Trader.class.getDeclaredMethod("callHostOnce",String.class,String.class,String.class,String.class,boolean.class);
        method.setAccessible(true);
        try { return (String)method.invoke(client,Trader.FUT_LIVE,"POST",path,"symbol=TESTUSDT",opening); }
        catch(InvocationTargetException ex) {
            if(ex.getCause() instanceof Exception) throw (Exception)ex.getCause();
            throw new AssertionError(ex.getCause());
        }
    }
    private static String ordinaryWrite(Trader client,String path) throws Exception {
        Method method=Trader.class.getDeclaredMethod("call",String.class,String.class,String.class);
        method.setAccessible(true);
        try { return (String)method.invoke(client,"POST",path,"symbol=TESTUSDT&reduceOnly=true"); }
        catch(InvocationTargetException ex) {
            if(ex.getCause() instanceof Exception) throw (Exception)ex.getCause();
            throw new AssertionError(ex.getCause());
        }
    }
    private static final class LocalConnection extends HttpURLConnection {
        LocalConnection(URL url) { super(url); }
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
        public int getResponseCode() { return 200; }
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[]{'{','}'}); }
        public OutputStream getOutputStream() { writes++;return new ByteArrayOutputStream(); }
    }
    /** Simulate a reference to a client created by an older LIVE-enabled build. */
    private static Trader legacyEntryClient(Context ctx) throws Exception {
        java.lang.reflect.Constructor<Trader> ctor=Trader.class.getDeclaredConstructor(
            String.class,String.class,boolean.class,boolean.class);
        ctor.setAccessible(true);
        Trader client=ctor.newInstance("test-live-key","test-secret",true,false);
        for(String field:new String[]{"writeAllowed","environment","appContext"}) {
            java.lang.reflect.Field f=Trader.class.getDeclaredField(field);f.setAccessible(true);
            if("writeAllowed".equals(field))f.setBoolean(client,true);
            if("environment".equals(field))f.set(client,"LIVE");
            if("appContext".equals(field))f.set(client,ctx);
        }
        return client;
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {
            public URLStreamHandler createURLStreamHandler(String protocol) {
                if("file".equals(protocol)||"jar".equals(protocol)||"jrt".equals(protocol)) return null;
                if(!"https".equals(protocol)&&!"http".equals(protocol)) throw new AssertionError("Unexpected network protocol "+protocol);
                return new URLStreamHandler() {
                    protected URLConnection openConnection(URL url) {
                        if(!"https".equals(url.getProtocol())) throw new AssertionError("No HTTP connections allowed");
                        if(!"fapi.binance.com".equals(url.getHost())&&!"demo-fapi.binance.com".equals(url.getHost()))
                            throw new AssertionError("Unexpected host "+url.getHost());
                        if(beforeConnection!=null) {Runnable callback=beforeConnection;beforeConnection=null;callback.run();}
                        return new LocalConnection(url);
                    }
                };
            }
        });
        final Context ctx=new Context();
        final Settings live=Settings.load(ctx,"LIVE");
        final Settings demo=Settings.load(ctx,"DEMO");
        ok(!Trader.liveArmed(ctx),"new installation starts disarmed");
        ok(Trader.entryBlockReason(ctx,live).contains("safety preview"),"LIVE entries unconditionally blocked");
        ok(Trader.forEnvironment(ctx,live)==null,"LIVE has no new-entry client");
        Trader.setLiveArmed(ctx,true);
        ok(!Trader.liveArmed(ctx),"old LIVE checkbox preference cannot arm the bot");
        Trader.saveKeys(ctx,"LIVE","local-test-live-key","local-test-secret");
        Trader.saveKeys(ctx,"DEMO","local-test-demo-key","local-test-secret");
        ok(Trader.hasKeys(ctx,"LIVE"),"LIVE credentials available for existing-position management");
        ok(Trader.entryBlockReason(ctx,live).contains("disabled"),"LIVE stays blocked with credentials");
        ok(Trader.forEnvironment(ctx,live)==null,"LIVE new-entry client not constructed even with keys");
        ok(Execution.open(ctx,live,null,new Strategy()).contains("disabled"),"shared execution path refuses LIVE before signal checks");
        final Trader stale=legacyEntryClient(ctx);
        denied(new Action(){public void run(){stale.requireEntryPermission();}},"old LIVE entry clients remain blocked");
        denied(new Action(){public void run()throws Exception{stale.enter("TESTUSDT",true,1,"test-id");}},"enter blocks before order request");
        denied(new Action(){public void run()throws Exception{stale.setLeverage("TESTUSDT",2);}},"old LIVE client cannot change entry leverage");
        denied(new Action(){public void run()throws Exception{stale.setMarginMode("TESTUSDT","ISOLATED");}},"old LIVE client cannot change margin mode");
        denied(new Action(){public void run()throws Exception{transport(stale,"/fapi/v1/order",true);}},"LIVE transport refuses entry before opening connection");
        ok(writes==0,"blocked LIVE entry emits no request bytes");
        final Trader manager=Trader.forManagement(ctx,live);
        ok(manager!=null,"existing LIVE positions retain management capability");
        ok("{}".equals(ordinaryWrite(manager,Trader.ALGO_ORDER_PATH)),"existing LIVE protection write permitted");
        ok("{}".equals(ordinaryWrite(manager,"/fapi/v1/order")),"existing LIVE protective close permitted");
        denied(new Action(){public void run(){manager.requireEntryPermission();}},"management client cannot open position");
        final Trader reader=Trader.forReading(ctx,live);
        denied(new Action(){public void run(){reader.requireEntryPermission();}},"read-only client cannot open position");
        int before=writes;
        try{ordinaryWrite(reader,Trader.ALGO_ORDER_PATH);throw new AssertionError("read-only mutation allowed");}
        catch(IllegalStateException expected){ok(writes==before,"read-only mutation blocked before transport");}
        live.tradingMode="SPOT";
        ok(Trader.entryBlockReason(ctx,live).contains("SPOT"),"unsupported LIVE spot cannot open");
        ok(Trader.entryBlockReason(ctx,Settings.load(ctx,"PAPER"))!=null,"PAPER cannot open exchange positions");
        ok(Trader.entryBlockReason(ctx,Settings.load(ctx,"INVALID"))!=null,"unknown environment cannot enable exchange positions");
        ok(Trader.entryBlockReason(ctx,demo)==null,"DEMO testnet remains available");
        final Trader demoClient=Trader.forEnvironment(ctx,demo);
        demoClient.requireEntryPermission();ok(true,"DEMO testnet entry permitted");
        ok("{}".equals(transport(demoClient,"/fapi/v1/order",true)),"DEMO entry reaches mocked HTTPS transport");
        ok(!demo.autoTrade&&!live.autoTrade,"automatic entries remain opt-in");
        ok(writes==3,"only management writes and DEMO write reached mocked transport");
        Trader.setLiveArmed(ctx,false);
        ok(!Trader.liveArmed(ctx),"safety lock cannot be switched off through preference");
        System.out.println("PASS "+passed+" safety-entry control checks (mock HTTPS only; no Binance requests)");
    }
}

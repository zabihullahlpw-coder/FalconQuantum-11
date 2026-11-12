package com.falcon.quantum;
import android.content.Context;
import java.util.List;

/** Point-in-time exchange verification while the app/service has network access. */
public final class ProtectionMonitor {
    private ProtectionMonitor() {}
    public static void runAll(Context ctx) {
        Journal j=new Journal(ctx);
        try {
            for(String env:new String[]{"DEMO","LIVE"}) {
                if(!Trader.hasKeys(ctx,env))continue;
                boolean needed=false;
                for(Journal.Trade t:j.history(env,10000))if(t.closedAt==0&&!"ENTRY_REJECTED".equals(t.status)){needed=true;break;}
                if(needed)run(ctx,Settings.load(ctx,env));
            }
        }finally{j.close();}
        Paper.monitor(ctx);
    }
    public static void run(Context ctx,Settings cfg) {
        if("PAPER".equals(cfg.env))return;
        synchronized(Trader.ORDER_LOCK) {
            Journal j=new Journal(ctx);
            try {
                Trader t=Trader.forManagement(ctx,cfg);if(t==null)return;
                List<Trader.Position> held=t.positions();Execution.reconcile(ctx,t,cfg,held);
                for(Trader.Position p:held) {
                    Journal.Trade row=j.active(cfg.env,p.symbol,p.side);
                    if(row==null||!t.accountKey().equals(row.accountKey))continue;
                    String problem=t.ensureProtection(p,row.sl,row.tp);
                    if(problem!=null) {
                        j.event(row.id,cfg.env,p.symbol,"PROTECTION_NOT_VERIFIED",problem);
                        j.recordError(cfg.env,p.symbol,"Protection monitoring",problem,"trade "+row.id);
                        // Act on a confirmed breach, not a guessed/network-failure price.
                        if(!"CLOSE_PENDING".equals(row.status)&&(problem.startsWith("STOP_BREACHED")||problem.startsWith("TARGET_BREACHED")))
                            Execution.close(ctx,t,cfg,p,problem.startsWith("STOP")?"SAFETY_STOP":"SAFETY_TARGET",problem);
                    }
                }
                t.reconcileProtection();
            }catch(Exception e){j.recordError(cfg.env,"","Monitor error",e.toString(),"Last verification is stale; no success assumed");}
            finally{j.close();}
        }
    }
}

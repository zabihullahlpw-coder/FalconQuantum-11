package com.falcon.quantum;

import android.content.Context;

/** No network/exchange used: pure finalized-plan and preference regression tests. */
public final class ReverseTradeDirectionTests {
    private static int checks;
    private static void ok(boolean condition,String label) {
        if(!condition) throw new AssertionError(label);
        checks++;
    }
    private static void eq(double actual,double expected,String label) {
        ok(Math.abs(actual-expected)<1e-8,label+": expected "+expected+", got "+actual);
    }
    private static Engine.Row row(String side) {
        Engine.Row r=new Engine.Row();
        r.side=side;r.symbol="BTCUSDT";r.entry=100;
        r.stop="LONG".equals(side)?98:102;
        r.target="LONG".equals(side)?104:96;
        r.ready=true;r.status="READY";r.grade="A";r.reason="READY";
        return r;
    }
    public static void main(String[] args) {
        Context ctx=new Context();
        Settings paper=Settings.load(ctx,"PAPER");
        ok(!paper.reverseTradeDirection,"new installs have reversal OFF");
        Engine.Row standard=row("LONG");
        TradeDirection.apply(standard,paper);
        ok("LONG".equals(standard.side)&&!standard.directionReversed,"checkbox OFF is unchanged");
        eq(standard.stop,98,"normal LONG stop unchanged");
        eq(standard.target,104,"normal LONG target unchanged");

        Strategy strategy=new Strategy();
        String oldKey=Readiness.key(paper,strategy);
        paper.reverseTradeDirection=true;
        ok(!oldKey.equals(Readiness.key(paper,strategy)),"reverse settings invalidate old scans");
        paper.save(ctx);
        ok(Settings.load(ctx,"PAPER").reverseTradeDirection,"PAPER reverse preference survives reload");
        ok(!Settings.load(ctx,"DEMO").reverseTradeDirection,"DEMO stays independent");
        ok(!Settings.load(ctx,"LIVE").reverseTradeDirection,"LIVE stays independent");
        ok(Readiness.key(Settings.load(ctx,"PAPER"),strategy).equals(Readiness.key(paper,strategy)),
                "reverse setting survives fingerprint refresh");

        Engine.Row fromLong=row("LONG");
        TradeDirection.apply(fromLong,paper);
        ok("SHORT".equals(fromLong.side)&&"LONG".equals(fromLong.signalSide),"LONG signal opens SHORT");
        eq(fromLong.stop,102,"reversed SHORT stop is above entry");
        eq(fromLong.target,96,"reversed SHORT target is below entry");
        ok(fromLong.ready&&fromLong.directionReversed,"eligible reversed SHORT stays READY");
        ok(!fromLong.warnings.isEmpty(),"reversal is clearly labelled experimental");
        TradeDirection.apply(fromLong,paper);
        ok("SHORT".equals(fromLong.side)&&fromLong.stop==102&&fromLong.target==96,
                "second application never double-inverts");

        Engine.Row fromShort=row("SHORT");
        TradeDirection.apply(fromShort,paper);
        ok("LONG".equals(fromShort.side)&&"SHORT".equals(fromShort.signalSide),"SHORT signal opens LONG");
        eq(fromShort.stop,98,"reversed LONG stop is below entry");
        eq(fromShort.target,104,"reversed LONG target is above entry");
        ok(fromShort.ready,"eligible reversed LONG stays READY");

        paper.allowShort=false;paper.allowLong=true;
        Engine.Row banned=row("LONG");TradeDirection.apply(banned,paper);
        ok(!banned.ready&&"WAIT".equals(banned.status),"cannot bypass SHORT permission");
        paper.allowShort=true;
        paper.allowLong=false;
        banned=row("SHORT");TradeDirection.apply(banned,paper);
        ok(!banned.ready,"cannot bypass LONG permission");
        paper.allowLong=true;

        paper.tradingMode="SPOT";paper.validated();
        Engine.Row spot=row("LONG");TradeDirection.apply(spot,paper);
        ok(!spot.ready&&spot.reason.contains("SPOT"),"SPOT cannot open inverted SHORT");
        paper.tradingMode="FUTURES";paper.validated();paper.allowShort=true;

        Engine.Row bad=row("LONG");bad.target=97;TradeDirection.apply(bad,paper);
        ok(!bad.ready&&!bad.directionReversed,"invalid input bracket is blocked");
        bad=row("LONG");bad.entry=2;bad.stop=1;bad.target=5;
        TradeDirection.apply(bad,paper);
        ok(!bad.ready&&!bad.directionReversed,"inverted negative target blocked");
        ok(!Trader.liveArmed(ctx),"reversal does not enable LIVE trading");
        System.out.println("PASS "+checks+" reverse-direction and settings checks");
    }
}

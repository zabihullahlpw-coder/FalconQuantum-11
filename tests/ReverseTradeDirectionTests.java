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
        r.ready=true;r.status="READY";r.grade="A";r.reason="READY";r.riskReward=2.0;
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
        eq(fromLong.stop,104,"reversed SHORT stop is EXACT original TP");
        eq(fromLong.target,98,"reversed SHORT target is EXACT original SL");
        eq(fromLong.riskReward,0.5,"reversed RR reflects swapped prices");
        ok(fromLong.ready&&fromLong.directionReversed,"eligible reversed SHORT stays READY");
        ok(!fromLong.warnings.isEmpty(),"reversal is clearly labelled experimental");
        TradeDirection.apply(fromLong,paper);
        ok("SHORT".equals(fromLong.side)&&fromLong.stop==104&&fromLong.target==98,
                "second application never double-inverts");

        Engine.Row fromShort=row("SHORT");
        TradeDirection.apply(fromShort,paper);
        ok("LONG".equals(fromShort.side)&&"SHORT".equals(fromShort.signalSide),"SHORT signal opens LONG");
        eq(fromShort.stop,96,"reversed LONG stop is EXACT original TP");
        eq(fromShort.target,102,"reversed LONG target is EXACT original SL");
        eq(fromShort.riskReward,0.5,"reversed LONG RR reflects swapped prices");
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
        ok(bad.ready&&bad.directionReversed,"small-price plans use exact swaps, never mirrored negatives");
        eq(bad.stop,5,"old TP 5 becomes exact SL");
        eq(bad.target,1,"old SL 1 becomes exact TP");
        Engine.Row exact=row("LONG");
        exact.entry=103.6371;exact.stop=99.4189;exact.target=117.6397;
        final long oldSL=Double.doubleToLongBits(exact.stop);
        final long oldTP=Double.doubleToLongBits(exact.target);
        TradeDirection.apply(exact,paper);
        ok(Double.doubleToLongBits(exact.target)==oldSL &&
                Double.doubleToLongBits(exact.stop)==oldTP,"no price rounding or recalculation");
        eq(exact.riskReward,(103.6371-99.4189)/(117.6397-103.6371),
                "effective RR computed from exact swapped prices");
        ok(!Trader.liveArmed(ctx),"reversal does not enable LIVE trading");
        System.out.println("PASS "+checks+" reverse-direction and settings checks");
    }
}

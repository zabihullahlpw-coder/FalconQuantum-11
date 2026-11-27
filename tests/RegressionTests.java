package com.falcon.quantum;
import org.json.*;
import android.content.Context;

public final class RegressionTests {
 private static int passed;
 private static void ok(boolean x,String name){if(!x)throw new AssertionError(name);passed++;}
 private static void eq(double a,double b,String name){ok(Math.abs(a-b)<1e-8,name+" expected "+b+" got "+a);}
 private static JSONObject order(){return new JSONObject().put("algoId",1).put("orderType","STOP_MARKET").put("algoStatus","NEW")
   .put("symbol","BTCUSDT").put("side","SELL").put("positionSide","LONG").put("closePosition",true).put("workingType","MARK_PRICE").put("triggerPrice",99);}
 private static boolean guards(JSONObject o){return ProtectionRules.matches(o,"BTCUSDT","LONG",true,1,true,99,.01);}
 private static JSONObject fill(long id,long order,String side,double qty,double price,double gross,double fee,long time){return new JSONObject()
   .put("id",id).put("orderId",order).put("symbol","BTCUSDT").put("side",side).put("positionSide","BOTH")
   .put("qty",qty).put("price",price).put("realizedPnl",gross).put("commission",fee).put("commissionAsset","USDT").put("time",time);}
 private static Journal.Trade trade(){Journal.Trade t=new Journal.Trade();t.entryOrderId=10;t.symbol="BTCUSDT";t.side="LONG";t.qty=1;t.positionSide="BOTH";return t;}
 private static Binance.Candles candles(int count,long start){Binance.Candles c=new Binance.Candles(count);for(int i=0;i<count;i++){
   c.openTime[i]=start+i*60000L;c.closeTime[i]=c.openTime[i]+59999;c.open[i]=100+i*.01;c.high[i]=c.open[i]+.5;c.low[i]=c.open[i]-.5;c.close[i]=c.open[i]+.1;c.volume[i]=100000;}return c;}
 public static void main(String[] a)throws Exception {
  eq(RiskMath.net(.05,.1,-.02),-.07,"fees turn gross winner into net loser");
  double q=RiskMath.size(1000,1000,100,98,.5,25,10,5,2,100);
  ok(q*RiskMath.perUnit(100,98,5,2)<=5.0000001,"size bounded by monetary stop loss");
  ok(q*100/10+q*100*.0005<=250,"margin and fee cap");
  double wider=RiskMath.size(1000,1000,100,95,.5,25,10,5,2,100);ok(wider<q,"wider stop gets smaller size");
  eq(RiskMath.size(1000,1000,100,98,.5,25,10,5,2,0),0,"daily exhausted => no entry");
  double daily=RiskMath.size(1000,1000,100,98,.5,25,10,5,2,1);ok(daily*RiskMath.perUnit(100,98,5,2)<=1.0000001,"reserve remaining daily budget");
  ok(RiskMath.adverseFill(100,true,2)>100&&RiskMath.adverseFill(100,false,2)<100,"adverse fill both sides");
  eq(RiskMath.utcDay(86400001L),86400000,"UTC midnight");
  ok(guards(order()),"documented orderType recognized");
  ok(!guards(order().put("algoStatus","CANCELED")),"cancelled ID is not protection");
  ok(!guards(order().put("algoStatus","TRIGGERED")),"triggered order not working stop");
  ok(!guards(order().put("symbol","ETHUSDT")),"exact symbol");
  ok(!guards(order().put("positionSide","SHORT")),"exact hedge book");
  ok(!guards(order().put("side","BUY")),"closing direction");
  ok(!guards(order().put("orderType","TAKE_PROFIT_MARKET")),"target is not stop");
  ok(!guards(order().put("workingType","CONTRACT_PRICE")),"required mark trigger");
  ok(!guards(order().put("priceProtect",true)),"delayed trigger protection not silently accepted");
  ok(!guards(order().put("closePosition",false).put("quantity",.5)),"insufficient quantity");
  ok(guards(order().put("closePosition",false).put("quantity",1)),"hedge reducing quantity accepted");
  ok(!ProtectionRules.matches(order().put("positionSide","BOTH").put("closePosition",false).put("quantity",1),"BTCUSDT","BOTH",true,1,true,99,.01),"one-way must reduce");
  ok(!guards(order().put("triggerPrice",98)),"wrong trigger not accepted");
  Context ctx=new Context();String id=ProtectionLedger.positionId("DEMO:accountA","BTCUSDT","LONG",100);
  ProtectionLedger.exact(ctx,id,10,20,99,102,"VERIFIED");
  ok(ProtectionLedger.get(ctx,id).complete(),"ledger roundtrip");
  ProtectionLedger.exact(ctx,id,10,0,99,102,"STOP_VERIFIED");ok(!ProtectionLedger.get(ctx,id).complete(),"absent target cleared explicitly");
  ProtectionLedger.state(ctx,id,"UNKNOWN",0);ok(ProtectionLedger.get(ctx,id).verifiedAt==0,"unknown verification becomes stale");
  ok(!id.equals(ProtectionLedger.positionId("LIVE:accountA","BTCUSDT","LONG",100)),"environment isolation");
  ok(!id.equals(ProtectionLedger.positionId("DEMO:accountB","BTCUSDT","LONG",100)),"account isolation");
  ProtectionLedger.remove(ctx,id);ok(ProtectionLedger.get(ctx,id)==null,"remove owned ledger");
  Settings cfg=Settings.load(ctx,"PAPER");eq(cfg.riskPercent,.25,"conservative risk migration default");
  cfg.riskPercent=Double.NaN;cfg.feeBps=Double.NaN;cfg.validated();eq(cfg.riskPercent,.25,"NaN risk rejected");eq(cfg.feeBps,5,"NaN costs rejected");
  Settings demoDefault=Settings.load(ctx,"DEMO");
  ok(demoDefault.leverage==1&&"ISOLATED".equals(demoDefault.marginMode),"DEMO defaults to 1x isolated");
  ok(demoDefault.maxPositions==1&&demoDefault.maxConsecutiveLosses==3,"DEMO limits exposure and losses by default");
  demoDefault.leverage=20;demoDefault.marginMode="CROSS";demoDefault.riskPercent=10;demoDefault.dailyLossPercent=10;demoDefault.sizePercent=100;
  demoDefault.validated();
  for (String mode : new String[]{"PAPER","DEMO","LIVE"}) {
      Settings setting = Settings.load(ctx,mode);
      for (int lev=1; lev<=150; lev++) {
          setting.leverage=lev;
          setting.maxPositions=1+(lev-1)%10;
          setting.save(ctx);
          Settings restored=Settings.load(ctx,mode);
          ok(restored.leverage==lev && restored.maxPositions==setting.maxPositions,
                  mode+" restores selected futures leverage and position limit");
      }
      setting.leverage=151; setting.maxPositions=11; setting.validated();
      ok(setting.leverage==150 && setting.maxPositions==10,"upper bounds");
      setting.leverage=0; setting.maxPositions=0; setting.validated();
      ok(setting.leverage==1 && setting.maxPositions==1,"lower bounds");
      setting.tradingMode="SPOT"; setting.leverage=150; setting.validated();
      ok(setting.leverage==1,"Spot stays at 1x");
  }
  ok("ISOLATED".equals(demoDefault.marginMode),"testnet disallows cross margin");
  eq(demoDefault.riskPercent,.5,"maximum risk per trade capped at 0.5 percent");
  eq(demoDefault.dailyLossPercent,2,"maximum daily loss capped at 2 percent");
  ok(demoDefault.sizePercent==25,"testnet margin share capped at 25 percent");
  double r=Execution.dailyRemaining(ctx,cfg,"test",1000,0,0,100000);eq(r,10,"daily default1pct");
  eq(Execution.dailyRemaining(ctx,cfg,"test",2000,0,0,100001),10,"deposit cannot increase daily budget");
  eq(Execution.dailyRemaining(ctx,cfg,"test",980,0,-20,100002),0,"daily loss latches");
  eq(Execution.dailyRemaining(ctx,cfg,"test",1010,0,10,100003),0,"recovery cannot reset same-day latch");
  ok(Execution.dailyRemaining(ctx,cfg,"test",1000,0,0,86400001L)>0,"new UTC day resets");
  eq(Execution.dailyRemaining(ctx,cfg,"withdrawal",1000,0,0,100000),10,"initial withdrawal test budget");
  eq(Execution.dailyRemaining(ctx,cfg,"withdrawal",500,0,0,100001),5,"withdrawal lowers daily baseline");
  eq(Execution.dailyRemaining(ctx,cfg,"withdrawal",1000,0,0,100002),5,"redeposit cannot regrow reduced baseline");
  Strategy keyStrategy=new Strategy();String originalKey=Readiness.key(cfg,keyStrategy);
  cfg.allowShort=false;ok(!originalKey.equals(Readiness.key(cfg,keyStrategy)),"direction changes split evidence cohorts");cfg.allowShort=true;
  cfg.sizePercent=50;ok(!originalKey.equals(Readiness.key(cfg,keyStrategy)),"margin changes split evidence cohorts");cfg.sizePercent=25;
  cfg.riskPercent=.7;cfg.validated();keyStrategy.entryScore=78.1;
  String savedKey=Readiness.key(cfg,keyStrategy);cfg.save(ctx);keyStrategy.save(ctx);
  ok(savedKey.equals(Readiness.key(Settings.load(ctx,"PAPER"),Strategy.load(ctx))),"preference float storage preserves settings fingerprint");
  ok(!Trader.liveArmed(ctx),"LIVE default remains off");
  Trader.setLiveArmed(ctx,true);ok(Trader.liveArmed(ctx),"explicit permission enables LIVE");
  Trader.setLiveArmed(ctx,false);ok(!Trader.liveArmed(ctx),"unchecked LIVE preference persists");
  JSONArray base=new JSONArray().put(fill(1,9,"SELL",1,90,-10,.05,90000))
   .put(fill(2,10,"BUY",1,100,0,.05,100000)).put(fill(3,11,"SELL",1,100.05,.05,.05,200000));
  JSONArray funding=new JSONArray().put(new JSONObject().put("incomeType","FUNDING_FEE").put("tranId",1).put("asset","USDT").put("income",-.02).put("time",150000));
  TradeAccounting.Result ar=TradeAccounting.calculate(trade(),base,funding);ok(ar.complete,"complete fills");eq(ar.gross,.05,"prior losing roundtrip excluded");eq(ar.net,-.07,"actual fees/funding reconciled");eq(ar.exit,100.05,"weighted exit from actual fills");
  ok(ar.exitOrders.contains(11L)&&!ar.exitOrders.contains(9L),"exit identity not nearest price");
  base.put(fill(3,11,"SELL",1,100.05,.05,.05,200000));eq(TradeAccounting.calculate(trade(),base,funding).net,-.07,"deduplicate fills");
  JSONArray foreign=new JSONArray().put(fill(2,10,"BUY",1,100,0,.05,100000)).put(fill(3,11,"SELL",1,101,1,.00001,200000).put("commissionAsset","BNB"));
  ok(!TradeAccounting.calculate(trade(),foreign,new JSONArray()).complete,"non-USDT fees cannot be treated as USDT");
  JSONArray partial=new JSONArray().put(fill(2,10,"BUY",1,100,0,.05,100000)).put(fill(3,11,"SELL",.5,101,.5,.025,200000));
  ok(!TradeAccounting.calculate(trade(),partial,new JSONArray()).complete,"partial close not final P/L");
  ok(!TradeAccounting.calculate(trade(),new JSONArray(),new JSONArray()).complete,"missing fills stay unverified");
  partial.put(fill(4,12,"BUY",1,100,0,.05,180000));ok(!TradeAccounting.calculate(trade(),partial,new JSONArray()).complete,"external scale-in rejected");
  JSONArray split=new JSONArray().put(fill(2,10,"BUY",.4,100,0,.02,100000)).put(fill(3,10,"BUY",.6,102,0,.0306,100001))
    .put(fill(4,11,"SELL",1,103,1.8,.0515,200000));
  eq(TradeAccounting.calculate(trade(),split,new JSONArray()).entry,101.2,"partial entry fills weighted correctly");
  Binance.Candles minute=candles(31,0),fifteen=CandleSeries.aggregate(minute,900000);
  ok(fifteen.size==2,"incomplete aggregation bucket excluded");eq(fifteen.open[0],100,"bucket opens correctly");
  ok(CandleSeries.closedBy(fifteen,899999)==0&&CandleSeries.closedBy(fifteen,900000)==1,"only closed candles visible");
  Binance.Candles slice=CandleSeries.window(minute,15,10);ok(slice.size==10&&slice.closeTime[9]==minute.closeTime[14],"no future candles in window");
  minute.openTime[8]+=1000;ok(CandleSeries.aggregate(minute,900000).size==1,"missing minute cannot fabricate HTF confirmation");
  Engine.Row row=new Engine.Row();row.ready=true;row.entry=100;row.stop=98;row.target=104;row.side="LONG";row.signalCloseTime=100000;
  ok(EntryTiming.check(row,100,100001)==null,"fresh acceptable entry");
  ok(EntryTiming.check(row,100.6,100001)!=null,"chase larger than .25R blocked");
  ok(EntryTiming.check(row,100,220001)!=null,"stale signal blocked");
  ok(EntryTiming.check(row,100,99999)!=null,"future signal blocked");
  ok(EntryTiming.check(row,97,100001)!=null,"crossed original stop blocked");
  ok(EntryTiming.pullback(row,candles(10,0),candles(10,0))!=null,"missing HTF history => no trade");
  PositionManager.Verdict fixed=ExitPolicy.review("FIXED",true,100,99,98,104,2,3,null,false,false,5,2);
  ok(!fixed.closes()&&!fixed.retargets(),"fixed exits unchanged by momentum");
  ok(ExitPolicy.review("EARLY",true,100,99,98,104,2,3,null,false,false,5,2).closes(),"early experiment isolated");
  PositionManager.Verdict trailing=ExitPolicy.review("TRAIL",true,100,103,100.2,106,2,3,null,true,true,5,2);
  eq(trailing.newStop,101.6,"trailing uses original R, not tightened stop");
  ok(ExitPolicy.review("TRAIL",true,100,103,102,106,2,3,null,true,true,5,2).newStop==0,"trailing never loosens");
  eq(ExitPolicy.review("TRAIL",false,100,97,99.8,94,2,3,null,true,true,5,2).newStop,98.4,"short trailing symmetry");
  ok("STOP".equals(Replay.hit(true,98,104,105,97)),"both levels => stop first long");
  ok("STOP".equals(Replay.hit(false,102,96,103,95)),"both levels => stop first short");
  eq(Replay.stopFill(true,98,95,95),95,"long gap slippage");eq(Replay.stopFill(false,102,105,105),105,"short gap slippage");
  Strategy st=new Strategy();Strategy copy=st.copy();copy.enabled[0]=false;ok(st.enabled[0],"scanner strategy not shared mutable arrays");
  System.out.println("PASS "+passed+" financial/control regression assertions");
 }
}

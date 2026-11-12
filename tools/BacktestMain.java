package com.falcon.quantum;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Offline comparison runner; CSV inputs required. Never sends exchange requests. */
public final class BacktestMain {
 private static String required(Map<String,String> a,String k){if(!a.containsKey(k))throw new IllegalArgumentException("Required --"+k);return a.get(k);}
 private static double num(Map<String,String>a,String k,double d){double x=Double.parseDouble(a.containsKey(k)?a.get(k):String.valueOf(d));if(!RiskMath.positive(x))throw new IllegalArgumentException(k);return x;}
 private static String json(String x){return "\""+x.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
 private static String sha(Path path)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[8192];for(int n;(n=in.read(b))>0;)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format("%02x",b&255));return s.toString();}
 public static Binance.Candles candles(Path file)throws Exception {
  List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8);
  if(lines.isEmpty()||!lines.get(0).equals("open_time,open,high,low,close,volume"))throw new IllegalArgumentException("Invalid OHLCV header: "+file);
  Binance.Candles c=new Binance.Candles(lines.size()-1);
  for(int i=0;i<c.size;i++){
   String[] p=lines.get(i+1).split(",",-1);if(p.length!=6)throw new IllegalArgumentException("Invalid candle row "+(i+2));
   c.openTime[i]=Long.parseLong(p[0]);c.closeTime[i]=c.openTime[i]+59999;
   if(c.openTime[i]%60000!=0||(i>0&&c.openTime[i]!=c.openTime[i-1]+60000))throw new IllegalArgumentException("Missing/duplicate/unsorted minute at "+c.openTime[i]);
   c.open[i]=Double.parseDouble(p[1]);c.high[i]=Double.parseDouble(p[2]);c.low[i]=Double.parseDouble(p[3]);c.close[i]=Double.parseDouble(p[4]);c.volume[i]=Double.parseDouble(p[5]);
   if(!RiskMath.positive(c.open[i])||!RiskMath.positive(c.high[i])||!RiskMath.positive(c.low[i])||!RiskMath.positive(c.close[i])||!Double.isFinite(c.volume[i])||c.volume[i]<0
    ||c.high[i]<Math.max(c.open[i],c.close[i])||c.low[i]>Math.min(c.open[i],c.close[i]))throw new IllegalArgumentException("Invalid prices/volume at "+c.openTime[i]);
  }return c;
 }
 public static List<Replay.Funding> funding(Path file)throws Exception {
  List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8);List<Replay.Funding> out=new ArrayList<Replay.Funding>();
  if(lines.isEmpty()||!lines.get(0).equals("funding_time,funding_rate,mark_price"))throw new IllegalArgumentException("Invalid funding header");
  long last=-1;
  for(int i=1;i<lines.size();i++){String[] p=lines.get(i).split(",",-1);Replay.Funding f=new Replay.Funding();f.at=Long.parseLong(p[0]);f.rate=Double.parseDouble(p[1]);f.mark=Double.parseDouble(p[2]);
   if(p.length!=3||f.at<=last||!Double.isFinite(f.rate)||!RiskMath.positive(f.mark))throw new IllegalArgumentException("Invalid/unsorted funding");last=f.at;out.add(f);}
  if(out.isEmpty())throw new IllegalArgumentException("Funding history is empty; cannot claim cost-complete replay");return out;
 }
 public static void main(String[] args)throws Exception {
  Map<String,String>a=new LinkedHashMap<String,String>();for(int i=0;i<args.length;i+=2){if(i+1>=args.length||!args[i].startsWith("--"))throw new IllegalArgumentException("Use --key value pairs");a.put(args[i].substring(2),args[i+1]);}
  Set<String> allowed=new HashSet<String>(Arrays.asList("contract","mark","funding","out","symbol","step","min-qty","min-notional","leverage","fee-bps","slippage-bps","initial-equity","risk-pct","daily-pct","stage","entry","exit"));
  for(String k:a.keySet())if(!allowed.contains(k))throw new IllegalArgumentException("Unknown option --"+k);
  String stage=a.containsKey("stage")?a.get("stage"):"development";
  if(!"development".equals(stage)&&!"holdout".equals(stage))throw new IllegalArgumentException("Stage must be development or holdout");
  String[] entries={"BASELINE","PULLBACK"},exits={"LEGACY","FIXED","EARLY","TRAIL"};
  if("holdout".equals(stage)) {
   String entry=required(a,"entry"),exit=required(a,"exit");
   if(!Arrays.asList(entries).contains(entry)||!Arrays.asList(exits).contains(exit))throw new IllegalArgumentException("Choose a valid preselected holdout entry/exit");
   entries=new String[]{entry};exits=new String[]{exit};
  }else if(a.containsKey("entry")||a.containsKey("exit"))throw new IllegalArgumentException("Development compares all candidates; entry/exit are for the preselected holdout only");
  Path cp=Paths.get(required(a,"contract")),mp=Paths.get(required(a,"mark")),fp=Paths.get(required(a,"funding")),out=Paths.get(required(a,"out"));
  String symbol=required(a,"symbol").toUpperCase(Locale.US);if(!symbol.matches("[A-Z0-9]{5,20}"))throw new IllegalArgumentException("Symbol");
  Binance.Candles c=candles(cp),m=candles(mp);List<Replay.Funding>fund=funding(fp);
  if(c.size<30*24*60)throw new IllegalArgumentException("At least 30 contiguous days needed (10-day higher-timeframe warm-up plus split windows)");
  long start=c.openTime[0]+10L*86400000,end=c.closeTime[c.size-1]+1;
  if(fund.get(0).at>start||fund.get(fund.size()-1).at<end-12*3600000L)throw new IllegalArgumentException("Funding history does not cover evaluation window");
  // Missing funding intervals cannot be inferred; require inspection of downloaded provenance too.
  long span=end-start,b1=(start+(long)(span*.6))/900000*900000,b2=(start+(long)(span*.8))/900000*900000;
  long[] boundaries={start,b1,b2,end};String[] sets={"development","validation","holdout"};
  Files.createDirectories(out);
  try(java.util.stream.Stream<Path> existing=Files.list(out)){if(existing.findAny().isPresent())throw new IllegalArgumentException("Output directory must be empty; preserve prior evidence");}
  StringBuilder summary=new StringBuilder("sample,entry,exit,cost_scenario,trades,win_rate,net_pnl,profit_factor,minute_close_drawdown,fees,funding,ambiguous_bars,skipped_risk,skipped_timing\n");
  Strategy strategy=new Strategy();
  int count=0;
  for(int split="holdout".equals(stage)?2:0;split<("holdout".equals(stage)?3:2);split++)for(String entry:entries)for(String exit:exits)for(int stress=1;stress<=2;stress++) {
   Replay.Config cfg=new Replay.Config();cfg.entry=entry;cfg.exit=exit;
   cfg.step=num(a,"step",.001);cfg.minQty=num(a,"min-qty",.001);cfg.minNotional=num(a,"min-notional",5);
   double requestedLeverage=num(a,"leverage",3);cfg.leverage=(int)requestedLeverage;
   if(cfg.leverage<1||cfg.leverage>5||cfg.leverage!=requestedLeverage)throw new IllegalArgumentException("Use integer leverage 1 to 5; liquidation tiers not modeled");
   cfg.feeBps=num(a,"fee-bps",5)*stress;cfg.slipBps=num(a,"slippage-bps",2)*stress;
   cfg.equity=num(a,"initial-equity",1000);cfg.riskPercent=num(a,"risk-pct",.5);cfg.dailyLossPercent=num(a,"daily-pct",2);
   if(cfg.riskPercent>2||cfg.dailyLossPercent>5)throw new IllegalArgumentException("Risk cap 2% per trade / daily 5%");
   Replay.Report r=Replay.run(symbol,c,m,fund,strategy,cfg,boundaries[split],boundaries[split+1]);
   String name=sets[split]+"-"+entry+"-"+exit+"-cost"+stress;
   summary.append(String.format(Locale.US,"%s,%s,%s,%d,%d,%.6f,%.8f,%s,%.8f,%.8f,%.8f,%d,%d,%d%n",sets[split],entry,exit,stress,r.trades.size(),r.trades.isEmpty()?0:r.wins/r.trades.size(),r.ending-r.initial,Double.isNaN(r.factor())?"undefined":String.format(Locale.US,"%.6f",r.factor()),r.drawdown,r.fees,r.funding,r.ambiguousBars,r.skippedRisk,r.skippedTiming));
   StringBuilder ledger=new StringBuilder("entry_time,exit_time,side,entry,exit,qty,gross,fees,funding,net,reason\n");
   for(Replay.Trade t:r.trades)ledger.append(String.format(Locale.US,"%d,%d,%s,%.10f,%.10f,%.10f,%.8f,%.8f,%.8f,%.8f,%s%n",t.entryAt,t.exitAt,t.buy?"LONG":"SHORT",t.entry,t.exit,t.qty,t.gross,t.fees,t.funding,t.net,json(t.reason)));
   Files.write(out.resolve(name+"-trades.csv"),ledger.toString().getBytes(StandardCharsets.UTF_8));
   count++;
  }
  Files.write(out.resolve("comparison.csv"),summary.toString().getBytes(StandardCharsets.UTF_8));
  String manifest="{\n  \"engine_version\":"+json(Readiness.MODEL_VERSION)+",\n  \"symbol\":"+json(symbol)+",\n  \"live_approved\":false,\n  \"contract_sha256\":"+json(sha(cp))+",\n  \"mark_sha256\":"+json(sha(mp))+",\n  \"funding_sha256\":"+json(sha(fp))+",\n  \"evaluation_start\":"+start+",\n  \"holdout_start\":"+b2+",\n  \"evaluation_end\":"+end+",\n  \"arguments\":"+json(a.toString())+",\n  \"limitations\":"+json("Single symbol, one position at a time, 15m closed signals; next 1m open fills; mark triggers estimated using contract/mark open basis; stop-first intrabar ambiguity; configurable fees/slippage + actual funding. No order-book queue, liquidation tiers, exchange failures, multi-symbol portfolio ranking, or exact 5s legacy management. Results do not unlock LIVE. Inspect missing funding and survivorship bias. End-of-window exits are artificial.")+"\n}\n";
  Files.write(out.resolve("provenance.json"),manifest.getBytes(StandardCharsets.UTF_8));
  System.out.println("Wrote "+count+" "+stage+" comparisons and per-trade ledgers to "+out+". No LIVE approval.");
 }
}

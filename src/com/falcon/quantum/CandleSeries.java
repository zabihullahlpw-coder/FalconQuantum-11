package com.falcon.quantum;
import java.util.ArrayList;
import java.util.List;

public final class CandleSeries {
    private CandleSeries() {}
    public static Binance.Candles window(Binance.Candles c,int end,int count) {
        int first=Math.max(0,end-count),n=end-first;Binance.Candles w=new Binance.Candles(n);
        System.arraycopy(c.open,first,w.open,0,n);System.arraycopy(c.high,first,w.high,0,n);
        System.arraycopy(c.low,first,w.low,0,n);System.arraycopy(c.close,first,w.close,0,n);
        System.arraycopy(c.volume,first,w.volume,0,n);System.arraycopy(c.openTime,first,w.openTime,0,n);
        System.arraycopy(c.closeTime,first,w.closeTime,0,n);return w;
    }
    public static int closedBy(Binance.Candles c,long at) {
        int lo=0,hi=c.size;
        while(lo<hi){int mid=(lo+hi)/2;if(c.closeTime[mid]<at)lo=mid+1;else hi=mid;}return lo;
    }
    /** Complete contiguous UTC buckets only, with no forward filled/missing bars. */
    public static Binance.Candles aggregate(Binance.Candles c,long interval) {
        List<double[]> values=new ArrayList<double[]>();List<Long> times=new ArrayList<Long>();
        for(int i=0;i<c.size;) {
            long start=(c.openTime[i]/interval)*interval;int end=i;
            while(end<c.size&&c.openTime[end]<start+interval)end++;
            boolean full=c.openTime[i]==start&&c.closeTime[end-1]==start+interval-1;
            double h=-Double.MAX_VALUE,l=Double.MAX_VALUE,v=0;
            for(int k=i;k<end;k++) {
                if(k>i&&c.openTime[k]!=c.closeTime[k-1]+1)full=false;
                h=Math.max(h,c.high[k]);l=Math.min(l,c.low[k]);v+=c.volume[k];
            }
            if(full){times.add(start);values.add(new double[]{c.open[i],h,l,c.close[end-1],v});}
            i=end;
        }
        Binance.Candles out=new Binance.Candles(values.size());
        for(int i=0;i<out.size;i++){double[] r=values.get(i);out.openTime[i]=times.get(i);out.closeTime[i]=times.get(i)+interval-1;
            out.open[i]=r[0];out.high[i]=r[1];out.low[i]=r[2];out.close[i]=r[3];out.volume[i]=r[4];}
        return out;
    }
}

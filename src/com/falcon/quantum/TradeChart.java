package com.falcon.quantum;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.util.Locale;

/** Native price chart: levels are numeric data, never generated imagery. */
public final class TradeChart extends View {
    private final Binance.Candles c;private final double entry,stop,target;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private double low,high;private float top,bottom,left,right,density;
    public TradeChart(Context ctx,Binance.Candles c,double entry,double stop,double target) {
        super(ctx);this.c=c;this.entry=entry;this.stop=stop;this.target=target;
        density=getResources().getDisplayMetrics().density;
        setContentDescription("Candlestick chart. Entry "+entry+" stop "+stop+" target "+target);
    }
    private float y(double v) {return bottom-(float)((v-low)/(high-low))*(bottom-top);}
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);canvas.drawColor(0xFF070C16);
        if(c==null||c.size==0)return;
        low=Double.MAX_VALUE;high=-Double.MAX_VALUE;
        for(int i=0;i<c.size;i++){low=Math.min(low,c.low[i]);high=Math.max(high,c.high[i]);}
        for(double v:new double[]{entry,stop,target})if(v>0){low=Math.min(low,v);high=Math.max(high,v);}
        double pad=Math.max((high-low)*.12,Math.abs(high)*.001);low-=pad;high+=pad;
        top=32*density;bottom=getHeight()-26*density;left=12*density;right=getWidth()-110*density;
        float width=(right-left)/Math.max(1,c.size);
        paint.setTextSize(10*density);paint.setColor(0xFF8EA3C0);
        canvas.drawText("Closed candles • last "+String.format(Locale.US,"%.6g",c.lastClose()),left,17*density,paint);
        for(int i=0;i<c.size;i++) {
            float x=left+width*(i+.5f);paint.setColor(c.close[i]>=c.open[i]?0xFF17B881:0xFFEF4B52);
            paint.setStrokeWidth(density);canvas.drawLine(x,y(c.high[i]),x,y(c.low[i]),paint);
            float a=y(Math.max(c.open[i],c.close[i])),b=y(Math.min(c.open[i],c.close[i]));
            canvas.drawRect(x-width*.32f,a,x+width*.32f,Math.max(b,a+density),paint);
        }
        level(canvas,entry,"ENTRY",0xFF5FC9FF,0);level(canvas,stop,"STOP",0xFFEF4B52,1);level(canvas,target,"TARGET",0xFF17B881,2);
    }
    private void level(Canvas canvas,double value,String name,int color,int index) {
        if(value<=0)return;paint.setColor(color);paint.setStrokeWidth(density);
        canvas.drawLine(left,y(value),right,y(value),paint);
        // Fixed legend rows prevent coincident levels from overwriting labels.
        float label=top+index*42*density;
        canvas.drawText(name,right+5*density,label,paint);
        canvas.drawText(String.format(Locale.US,"%.7g",value),right+5*density,label+14*density,paint);
    }
}

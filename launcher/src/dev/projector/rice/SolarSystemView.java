package dev.projector.rice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/** Static, schematic solar system. All eight orbits fit the measured view. */
final class SolarSystemView extends View {
    // Mercury, Venus, Earth, Mars, Jupiter, Saturn, Uranus, Neptune.
    private static final float[] ORBITS={.16f,.26f,.36f,.46f,.62f,.75f,.87f,1f};
    private static final float[] ANGLES={-62,121,-149,13,154,-38,43,-167};
    private static final float[] SIZES={2.1f,2.8f,3.1f,2.5f,6.3f,5.1f,3.9f,3.8f};
    private static final int[] COLORS={0xffa79d8f,0xffd1bd94,0xffa9c8d1,0xffbc8c77,0xffc5b6a1,0xffcfbf9c,0xffa8c5be,0xff8dabc8};
    private static final float FLAT=.56f, TILT=-18;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private final float unit;

    SolarSystemView(Context context,int accent){
        super(context);this.accent=accent;
        float density=getResources().getDisplayMetrics().density;
        float width=getResources().getDisplayMetrics().widthPixels/density;
        float height=getResources().getDisplayMetrics().heightPixels/density;
        unit=density*Math.min(width/1280f,height/720f);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    private float px(float value){return value*unit;}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float width=getWidth(),height=getHeight(),caption=px(27),margin=px(22);
        float diagramHeight=height-caption;
        if(width<=margin*2||diagramHeight<=margin*2)return;
        double tilt=Math.toRadians(TILT);
        // Exact bounds of a rotated ellipse, including room for planet discs.
        float extentX=(float)Math.sqrt(Math.pow(Math.cos(tilt),2)+Math.pow(FLAT*Math.sin(tilt),2));
        float extentY=(float)Math.sqrt(Math.pow(Math.sin(tilt),2)+Math.pow(FLAT*Math.cos(tilt),2));
        float radius=Math.min((width/2-margin)/extentX,(diagramHeight/2-margin)/extentY);
        float centerX=width/2,centerY=diagramHeight/2;
        canvas.save();canvas.translate(centerX,centerY);canvas.rotate(TILT);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(px(.8f));paint.setColor(accent);paint.setAlpha(48);
        for(float orbit:ORBITS){float r=radius*orbit;canvas.drawOval(-r,-r*FLAT,r,r*FLAT,paint);}

        // A quiet asteroid belt between Mars and Jupiter.
        paint.setStyle(Paint.Style.FILL);paint.setColor(accent);paint.setAlpha(42);
        for(int i=0;i<56;i++){
            double angle=i*Math.PI*2/56;float r=radius*(.535f+(i%3-1)*.012f);
            canvas.drawCircle((float)Math.cos(angle)*r,(float)Math.sin(angle)*r*FLAT,px(.65f),paint);
        }
        paint.setColor(0xffd9b78e);paint.setAlpha(18);canvas.drawCircle(0,0,px(16),paint);
        paint.setAlpha(35);canvas.drawCircle(0,0,px(11),paint);
        paint.setAlpha(255);canvas.drawCircle(0,0,px(6.7f),paint);

        for(int i=0;i<ORBITS.length;i++){
            double angle=Math.toRadians(ANGLES[i]);float r=radius*ORBITS[i];
            float x=(float)Math.cos(angle)*r,y=(float)Math.sin(angle)*r*FLAT,size=px(SIZES[i]);
            if(i==5){
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(px(.9f));paint.setColor(COLORS[i]);paint.setAlpha(190);
                canvas.drawOval(x-size*1.9f,y-size*.63f,x+size*1.9f,y+size*.63f,paint);
            }
            paint.setStyle(Paint.Style.FILL);paint.setColor(COLORS[i]);paint.setAlpha(235);canvas.drawCircle(x,y,size,paint);
        }
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.MONOSPACE);paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(px(10));paint.setColor(accent);paint.setAlpha(105);
        canvas.drawText("СОЛНЦЕ  ·  8 ПЛАНЕТ",centerX,height-px(9),paint);
    }
}

package dev.projector.rice;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;

/** The requested lavender palette, with an optional true-black background. */
final class Palette {
    static final int BACKGROUND=0xff0b0b10,SURFACE=0xff16181d,BORDER=0xff343b49;
    static final int TEXT=0xffc9cad4,MUTED=0xff7b8496,ACCENT=0xffaaa6c4,SELECTION=0xff454157;
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences("rice",Context.MODE_PRIVATE);}
    static void initialize(Context context){
        SharedPreferences p=prefs(context);
        if(p.getInt("palette_version",0)<1)p.edit().putInt("palette_version",1).putInt("accent",ACCENT).apply();
    }
    static boolean oled(Context context){return prefs(context).getBoolean("oled",false);}
    static int background(Context context){return oled(context)?0xff000000:BACKGROUND;}
    static int surface(Context context){return oled(context)?BACKGROUND:SURFACE;}
    static void styleDialog(Context context,AlertDialog dialog){
        GradientDrawable panel=new GradientDrawable();panel.setColor(surface(context));
        float density=context.getResources().getDisplayMetrics().density;panel.setCornerRadius(12*density);panel.setStroke(Math.max(1,Math.round(density)),BORDER);
        if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(panel);
        for(int button:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_NEGATIVE})
            if(dialog.getButton(button)!=null)dialog.getButton(button).setTextColor(ACCENT);
    }
}

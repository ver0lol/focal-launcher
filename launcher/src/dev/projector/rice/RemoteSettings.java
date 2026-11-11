package dev.projector.rice;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.widget.TextView;

final class RemoteSettings {
    static final String ACTION="dev.projector.rice.REMOTE_INFO";
    static void show(Activity activity){
        RemoteAccess access=RemoteAccess.get(activity);
        TextView text=new TextView(activity);text.setTypeface(Typeface.MONOSPACE);text.setTextSize(20);text.setTextColor(Palette.TEXT);text.setPadding(32,24,32,24);
        text.setText(RemoteAccess.url()+"\n\nКод подключения: "+access.pin()+"\n\nОткройте адрес на телефоне в той же Wi-Fi сети.\n"+(access.enabled()?RemoteService.state:"Пульт выключен"));
        AlertDialog themed=new AlertDialog.Builder(activity).setTitle("_VeR0 · веб-пульт").setView(text)
            .setPositiveButton("Готово",null)
            .setNeutralButton("Новый код",(dialog,which)->{access.rotate();show(activity);})
            .setNegativeButton(access.enabled()?"Выключить":"Включить",(dialog,which)->{
                access.enabled(!access.enabled());if(access.enabled())RemoteService.start(activity);else activity.stopService(new Intent(activity,RemoteService.class));show(activity);
            }).create();themed.show();Palette.styleDialog(activity,themed);
    }
}

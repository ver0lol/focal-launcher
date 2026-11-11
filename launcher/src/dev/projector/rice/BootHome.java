package dev.projector.rice;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.FileReader;

/** One Home press after Android boot, not a foreground-app watchdog. */
final class BootHome {
    private static boolean running=false;
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences("boot-home",Context.MODE_PRIVATE);}
    private static String bootToken(Context context){
        try(BufferedReader in=new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))){
            String value=in.readLine();if(value!=null&&value.matches("[a-fA-F0-9-]{36}"))return value;
        }catch(Exception ignored){}
        int count=Settings.Global.getInt(context.getContentResolver(),"boot_count",-1);
        return count>=0?"boot-count-"+count:null;
    }
    static String status(Context context){return prefs(context).getString("status","not_requested");}
    static synchronized void start(Context context,RemoteKeys keys,Runnable finished){
        if(running)return;
        Context app=context.getApplicationContext();SharedPreferences prefs=prefs(app);String token=bootToken(app);
        if(token==null){prefs.edit().putString("status","boot_identity_unavailable").apply();finished.run();return;}
        if(token.equals(prefs.getString("completed_boot",""))){finished.run();return;}
        running=true;prefs.edit().putString("status","waiting_for_input").apply();
        Thread task=new Thread(()->{
            String outcome="input_unavailable";
            try{
                long deadline=SystemClock.elapsedRealtime()+12000;
                while(!keys.ready&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
                if(keys.ready){
                    ResolveInfo home=app.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
                    if(home==null||home.activityInfo==null||!app.getPackageName().equals(home.activityInfo.packageName))outcome="different_default_home";
                    else if(keys.press("home")){
                        outcome="home_opened";
                        prefs.edit().putString("completed_boot",token).commit();
                    }
                }
            }catch(Exception e){outcome="launch_failed";}
            finally{
                prefs.edit().putString("status",outcome).putLong("finished_at",System.currentTimeMillis()).apply();
                synchronized(BootHome.class){running=false;}
                new Handler(Looper.getMainLooper()).post(finished);
            }
        },"ver0-boot-home");task.setDaemon(true);task.start();
    }
}

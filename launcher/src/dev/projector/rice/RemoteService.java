package dev.projector.rice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Unprivileged foreground HTTP service. Root is never a listening process. */
public final class RemoteService extends Service {
    private static final String BOOT_HOME="dev.projector.rice.BOOT_HOME";
    static volatile String state="Запускается…";
    private RemoteServer server;
    private RemoteKeys keys;
    static void start(Context context){if(RemoteAccess.get(context).enabled())context.startForegroundService(new Intent(context,RemoteService.class));}
    static void startOnBoot(Context context){context.startForegroundService(new Intent(context,RemoteService.class).setAction(BOOT_HOME));}
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("web-remote","Веб-пульт",NotificationManager.IMPORTANCE_LOW));
        Intent show=new Intent(this,MainActivity.class).setAction(RemoteSettings.ACTION);
        PendingIntent pending=PendingIntent.getActivity(this,52,show,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startForeground(52,new Notification.Builder(this,"web-remote").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("_VeR0 · веб-пульт").setContentText(RemoteAccess.url()).setContentIntent(pending).setOngoing(true).setShowWhen(false).build());
        keys=new RemoteKeys(this);
        if(RemoteAccess.get(this).enabled()){
            try{server=new RemoteServer(this,keys);server.start();state="Пульт доступен";}
            catch(Exception e){state="Не удалось открыть веб-пульт";stopSelf();}
        }else state="Пульт выключен";
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&BOOT_HOME.equals(intent.getAction())){
            BootHome.start(this,keys,()->{if(!RemoteAccess.get(this).enabled())stopSelf();});
            return START_STICKY;
        }
        if(!RemoteAccess.get(this).enabled()){stopSelf();return START_NOT_STICKY;}return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        out.println("HTTP port="+RemoteAccess.PORT+" ready="+(keys!=null&&keys.ready));
        out.println("accepted_keys="+(server==null?0:server.acceptedKeys)+" last_key="+(server==null?"none":server.lastKey));
        out.println("boot_home="+BootHome.status(this));
        if(keys!=null){
            out.println("input_worker_pid="+keys.relayPid+" uid=2000 startup_ms="+keys.startupMs+" last_dispatch_us="+keys.lastDispatchUs);
            if(args!=null&&java.util.Arrays.asList(args).contains("--benchmark"))try{out.println(keys.benchmark());}catch(Exception e){out.println("benchmark unavailable");}
        }
    }
    @Override public void onDestroy(){if(server!=null)server.close();if(keys!=null)keys.close();state="Пульт выключен";stopForeground(true);super.onDestroy();}
}

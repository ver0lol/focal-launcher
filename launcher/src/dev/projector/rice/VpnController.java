package dev.projector.rice;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Narrow, certificate-bound IPC. Rice never sees proxy credentials or logs. */
final class VpnController {
    static final String SFA="io.nekohasekai.sfa";
    private final Activity activity;
    private final Runnable changed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean polling=false,request=false;
    int state=-1;
    String error="";
    VpnController(Activity a,Runnable listener){activity=a;changed=listener;}
    private final Runnable poll=new Runnable(){public void run(){
        if(!polling||worker.isShutdown())return;
        if(!request){request=true;worker.execute(()->{
            int next=-1;String message="";
            try{
                if(activity.getPackageManager().checkSignatures(activity.getPackageName(),SFA)!=PackageManager.SIGNATURE_MATCH)throw new SecurityException();
                try(Cursor c=activity.getContentResolver().query(Uri.parse("content://io.nekohasekai.sfa.rice/status"),null,null,null,null)){
                    if(c!=null&&c.moveToFirst()){next=c.getInt(c.getColumnIndexOrThrow("state"));message=c.getString(c.getColumnIndexOrThrow("error"));}
                }
            }catch(RuntimeException ignored){message="VPN-приложение недоступно";}
            final int result=next;final String detail=message;
            main.post(()->{request=false;if(activity.isDestroyed())return;state=result;error=detail;changed.run();});
        });}
        main.postDelayed(this,1500);
    }};
    void resume(){polling=true;main.removeCallbacks(poll);poll.run();}
    void pause(){polling=false;main.removeCallbacks(poll);}
    void close(){pause();worker.shutdownNow();}
    String label(){if(state<0)return "VPN · —";if(state==1||state==3)return "VPN · …";
        if(state==2)return "VPN · вкл";return error.isEmpty()?"VPN · выкл":"VPN · ошибка";}
    void toggle(){
        if(state<0){Toast.makeText(activity,"VPN-приложение недоступно",Toast.LENGTH_SHORT).show();return;}
        if(state==1||state==3)return;
        String action=state==2?"io.nekohasekai.sfa.rice.STOP":"io.nekohasekai.sfa.rice.START";
        Intent intent=new Intent(action).setComponent(new ComponentName(SFA,"dev.projector.singbox.RiceControlActivity"));
        try{activity.startActivity(intent);activity.overridePendingTransition(0,0);}
        catch(RuntimeException e){Toast.makeText(activity,"Не удалось открыть управление VPN",Toast.LENGTH_SHORT).show();}
    }
    void openClient(){Intent intent=activity.getPackageManager().getLaunchIntentForPackage(SFA);
        if(intent!=null)activity.startActivity(intent);}
}

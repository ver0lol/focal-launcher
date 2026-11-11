package dev.projector.singbox;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

/** Explicit START/STOP endpoint protected by the shared signing certificate. */
public final class RiceControlActivity extends Activity {
    private boolean starting=false;
    @Override public void onCreate(Bundle b){
        setTheme(android.R.style.Theme_Material_Dialog_NoActionBar);super.onCreate(b);
        TextView text=new TextView(this);text.setText("Подготовка VPN…");text.setTextSize(22);text.setTextColor(Color.WHITE);text.setGravity(Gravity.CENTER);text.setPadding(50,35,50,35);setContentView(text);
        String action=getIntent().getAction();
        if(Bridge.STOP.equals(action)){
            try{Bridge.stop();Bridge.error="";}catch(Exception e){Bridge.error="Не удалось выключить VPN";}
            Bridge.busy=false;finish();return;
        }
        if(!Bridge.START.equals(action)&&!Bridge.REFRESH.equals(action)){finish();return;}
        if(Bridge.busy){finish();return;}starting=Bridge.START.equals(action);Bridge.busy=true;Bridge.error="";
        new Thread(()->{
            try{
                Bridge.configure(getApplicationContext());
                runOnUiThread(()->{if(isFinishing()||isDestroyed()){Bridge.busy=false;return;}
                    if(!starting){Bridge.busy=false;Toast.makeText(this,"Профиль обновлён",Toast.LENGTH_SHORT).show();finish();return;}
                    Intent permission=VpnService.prepare(this);
                    if(permission!=null)startActivityForResult(permission,10);else startNow();});
            }catch(Exception e){Bridge.diagnose(e);runOnUiThread(()->{Bridge.busy=false;Bridge.error="Не удалось подготовить VPN ("+e.getClass().getSimpleName()+")";Toast.makeText(this,Bridge.error,Toast.LENGTH_LONG).show();finish();});}
        },"rice-profile-download").start();
    }
    private void startNow(){
        try{Bridge.start();}catch(Exception e){Bridge.error="Не удалось запустить VPN";Toast.makeText(this,Bridge.error,Toast.LENGTH_LONG).show();}
        Bridge.busy=false;finish();overridePendingTransition(0,0);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==10){if(result==RESULT_OK)startNow();else{Bridge.busy=false;Bridge.error="VPN не разрешён";finish();}}}
    @Override public void onBackPressed(){starting=false;Bridge.busy=false;super.onBackPressed();}
}

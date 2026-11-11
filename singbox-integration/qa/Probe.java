package dev.projector.rice.vpntest;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Temporary ordinary-app network probe. No config, keys or response bodies. */
public final class Probe extends Instrumentation {
    private String mode;
    @Override public void onCreate(Bundle args){super.onCreate(args);mode=args.getString("mode","status");start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Context c=getTargetContext();
            if("start".equals(mode)||"stop".equals(mode)){
                c.startActivity(new android.content.Intent("io.nekohasekai.sfa.rice."+mode.toUpperCase(java.util.Locale.ROOT))
                    .setComponent(new android.content.ComponentName("io.nekohasekai.sfa","dev.projector.singbox.RiceControlActivity"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                result.putString("requested",mode);
            }
            try(Cursor row=c.getContentResolver().query(Uri.parse("content://io.nekohasekai.sfa.rice/status"),null,null,null,null)){
                if(row!=null&&row.moveToFirst()){
                    result.putInt("state",row.getInt(row.getColumnIndexOrThrow("state")));
                    result.putInt("configured",row.getInt(row.getColumnIndexOrThrow("configured")));
                    result.putString("bridge_error",row.getString(row.getColumnIndexOrThrow("error")));
                }
            }
            if("network".equals(mode)){
                ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
                result.putBoolean("vpn_transport",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                HttpsURLConnection conn=(HttpsURLConnection)new URL("https://www.gstatic.com/generate_204").openConnection();
                conn.setConnectTimeout(15000);conn.setReadTimeout(15000);conn.setInstanceFollowRedirects(false);
                long then=System.currentTimeMillis();
                try{result.putInt("https_status",conn.getResponseCode());result.putLong("elapsed_ms",System.currentTimeMillis()-then);}
                finally{conn.disconnect();}
            }
            result.putString("probe","OK");
        }catch(Exception e){result.putString("probe",e.getClass().getSimpleName());}
        finish(0,result);
    }
}

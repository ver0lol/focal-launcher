package dev.projector.singbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.lang.reflect.Proxy;

/** Read-only, signature-protected state: never returns config, endpoints or logs. */
public final class RiceStatusProvider extends ContentProvider {
    private volatile Object connection;
    private boolean connecting=false;
    private volatile int state=0;
    @Override public boolean onCreate(){return true;}
    private synchronized void connect(){
        if(connection!=null||connecting)return;connecting=true;
        Bridge.MAIN.post(()->{try{
            Class<?> callback=Class.forName("io.nekohasekai.sfa.bg.ServiceConnection$Callback");
            Object proxy=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(p,m,args)->{
                if(m.getName().equals("onServiceStatusChanged")){state=((Enum<?>)args[0]).ordinal();if(state==2)Bridge.error="";}
                if(m.getName().equals("onServiceAlert"))Bridge.error="Ошибка подключения VPN";
                return null;
            });
            connection=Class.forName("io.nekohasekai.sfa.bg.ServiceConnection").getConstructor(android.content.Context.class,callback,boolean.class).newInstance(getContext(),proxy,true);
            Bridge.call(connection,"connect");
        }catch(Exception e){Bridge.diagnose(e);Bridge.error="VPN недоступен";}finally{connecting=false;}});
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        if(!"/status".equals(uri.getPath()))throw new IllegalArgumentException("Unknown path");
        boolean configured=getContext().getSharedPreferences("rice-bridge",0).getBoolean("configured",false);
        if(configured)connect();
        int actual=state;
        try{if(connection!=null)actual=((Enum<?>)Bridge.call(connection,"getStatus")).ordinal();}catch(Exception ignored){actual=0;}
        if(Bridge.busy)actual=1;
        MatrixCursor cursor=new MatrixCursor(new String[]{"state","configured","error","updated"});
        cursor.addRow(new Object[]{actual,configured?1:0,Bridge.error,getContext().getSharedPreferences("rice-bridge",0).getLong("updated",0)});return cursor;
    }
    @Override public String getType(Uri uri){return "vnd.android.cursor.item/vnd.rice.vpn-state";}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}

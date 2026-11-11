package dev.projector.singbox;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Small source-available integration layer; sing-box's original core is unchanged. */
final class Bridge {
    static final String SOURCE=VpnBuildConfig.SOURCE;
    static final String START="io.nekohasekai.sfa.rice.START";
    static final String STOP="io.nekohasekai.sfa.rice.STOP";
    static final String REFRESH="io.nekohasekai.sfa.rice.REFRESH";
    static final String NAME=VpnBuildConfig.PROFILE_NAME;
    static final Handler MAIN=new Handler(Looper.getMainLooper());
    static volatile boolean busy=false;
    static volatile String error="";
    static Object singleton(String name)throws Exception{return Class.forName(name).getField("INSTANCE").get(null);}
    static Object call(Object target,String name,Object... args)throws Exception {
        for(Method m:target.getClass().getMethods()){
            if(!m.getName().equals(name)||m.getParameterTypes().length!=args.length)continue;
            boolean fits=true;Class<?>[] types=m.getParameterTypes();
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!types[i].isPrimitive()&&!types[i].isInstance(args[i]))fits=false;
            if(fits)return m.invoke(target,args);
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
    static Object suspend(Object target,String name,Object...args)throws Exception {
        CountDownLatch complete=new CountDownLatch(1);Object[] result={null};
        Method method=null;
        for(Method candidate:target.getClass().getMethods())if(candidate.getName().equals(name)&&candidate.getParameterTypes().length==args.length+1){method=candidate;break;}
        if(method==null)throw new NoSuchMethodException(name);
        Class<?> continuation=method.getParameterTypes()[args.length];
        // R8 renamed Kotlin runtime classes in the SHA-pinned 1.14.4 APK.
        // uu1.X is its EmptyCoroutineContext singleton; SFA API names survive.
        Object context=Class.forName("uu1").getField("X").get(null);
        if(!continuation.getMethod("getContext").getReturnType().isInstance(context))throw new IllegalStateException("Unsupported Kotlin ABI");
        Object callback=Proxy.newProxyInstance(continuation.getClassLoader(),new Class<?>[]{continuation},(p,m,a)->{
            if(m.getName().equals("getContext"))return context;
            if(m.getName().equals("resumeWith")){result[0]=a[0];complete.countDown();}
            return null;
        });
        Object[] all=new Object[args.length+1];System.arraycopy(args,0,all,0,args.length);all[args.length]=callback;
        Object value=method.invoke(target,all);
        if(value instanceof Enum&&"COROUTINE_SUSPENDED".equals(((Enum<?>)value).name())){
            if(!complete.await(45,TimeUnit.SECONDS))throw new IllegalStateException("SFA operation timed out");value=result[0];
        }
        // Kotlin Result.Failure is a wrapper with a Throwable field. Reading
        // the type avoids relying on another R8-renamed implementation name.
        if(value!=null)for(java.lang.reflect.Field field:value.getClass().getDeclaredFields()){
            if(Throwable.class.isAssignableFrom(field.getType())){field.setAccessible(true);Throwable failure=(Throwable)field.get(value);if(failure!=null)throw new java.lang.reflect.InvocationTargetException(failure);}
        }
        return value;
    }
    static void diagnose(Throwable failure){
        StringBuilder trace=new StringBuilder();
        for(int depth=0;failure!=null&&depth<6;depth++,failure=failure.getCause()){
            trace.append(failure.getClass().getName()).append('\n');
            for(StackTraceElement frame:failure.getStackTrace())trace.append(" at ").append(frame).append('\n');
        }
        // Deliberately exclude exception messages, which may contain endpoints.
        android.util.Log.e("RiceVPN",trace.toString());
    }
    static Object settings()throws Exception{return singleton("io.nekohasekai.sfa.database.Settings");}
    static void initialize()throws Exception{suspend(call(settings(),"getDataStore"),"initialize");}
    static void start()throws Exception {
        Object companion=Class.forName("io.nekohasekai.sfa.bg.BoxService").getField("Companion").get(null);
        call(companion,"start");
    }
    static void stop()throws Exception {
        Object companion=Class.forName("io.nekohasekai.sfa.bg.BoxService").getField("Companion").get(null);
        call(companion,"stop");
    }
    static String download()throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL(SOURCE).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent","RiceProjector/0.2");
        try{
            if(c.getResponseCode()!=200)throw new IllegalStateException("Configuration download failed");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;
                while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IllegalStateException("Configuration too large");out.write(b,0,n);}}
            return out.toString("UTF-8");
        }finally{c.disconnect();}
    }
    static String adapt(String original)throws Exception {
        JSONObject config=new JSONObject(original);JSONArray inbounds=config.getJSONArray("inbounds");boolean tun=false;
        // Official Android libbox is built without with_dhcp. Android's local
        // resolver already follows the physical Wi-Fi network's DHCP DNS.
        JSONObject dns=config.optJSONObject("dns");JSONArray servers=dns==null?null:dns.optJSONArray("servers");
        if(servers!=null)for(int i=0;i<servers.length();i++){
            JSONObject server=servers.getJSONObject(i);
            if("dhcp".equals(server.optString("type"))){JSONObject local=new JSONObject();local.put("type","local");if(server.has("tag"))local.put("tag",server.get("tag"));servers.put(i,local);}
        }
        for(int i=0;i<inbounds.length();i++){
            JSONObject inbound=inbounds.getJSONObject(i);if(!"tun".equals(inbound.optString("type")))continue;tun=true;
            JSONArray excluded=inbound.optJSONArray("route_exclude_address");if(excluded==null)excluded=new JSONArray();
            for(String cidr:new String[]{"192.168.0.0/16","10.0.0.0/8","172.16.0.0/12","169.254.0.0/16","127.0.0.0/8","fe80::/10"})add(excluded,cidr);
            inbound.put("route_exclude_address",excluded);
            JSONArray packages=inbound.optJSONArray("exclude_package");if(packages==null)packages=new JSONArray();
            add(packages,"com.android.shell");add(packages,"dev.projector.rice");inbound.put("exclude_package",packages);
        }
        if(!tun)throw new IllegalStateException("TUN configuration required");
        JSONObject experimental=config.optJSONObject("experimental");
        if(experimental!=null){JSONObject clash=experimental.optJSONObject("clash_api");
            if(clash!=null){String address=clash.optString("external_controller");
                if(!address.isEmpty()&&!address.startsWith("127.0.0.1:")&&!address.startsWith("localhost:")&&!address.startsWith("[::1]:"))throw new IllegalStateException("Public controller not allowed");
                if(!address.isEmpty()&&clash.optString("secret").isEmpty())clash.put("secret",java.util.UUID.randomUUID().toString());}}
        return config.toString();
    }
    private static void add(JSONArray array,String value)throws Exception{for(int i=0;i<array.length();i++)if(value.equals(array.optString(i)))return;array.put(value);}
    private static void values(Object object,java.util.List<String> result)throws Exception{
        if(object instanceof JSONObject){java.util.Iterator<String> keys=((JSONObject)object).keys();while(keys.hasNext())values(((JSONObject)object).get(keys.next()),result);}
        else if(object instanceof JSONArray){for(int i=0;i<((JSONArray)object).length();i++)values(((JSONArray)object).get(i),result);}
        else if(object instanceof String&&!((String)object).isEmpty())result.add((String)object);
    }
    private static void parserFailure(Exception exception,String config)throws Exception{
        Throwable cause=exception;while(cause.getCause()!=null)cause=cause.getCause();
        String detail=String.valueOf(cause.getMessage());java.util.List<String> values=new java.util.ArrayList<>();values(new JSONObject(config),values);
        java.util.Collections.sort(values,(a,b)->Integer.compare(b.length(),a.length()));
        for(String value:values)detail=detail.replace(value,"[value]");
        android.util.Log.e("RiceVPN","Parser: "+detail);
    }
    static void configure(Context context)throws Exception {
        initialize();Object manager=singleton("io.nekohasekai.sfa.database.ProfileManager");
        File file=new File(context.getFilesDir(),"rice-vpn.json");String config;
        try{config=adapt(download());}
        catch(Exception e){
            if(!file.isFile())throw e;
            try(InputStream in=new java.io.FileInputStream(file)){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[8192];int n;while((n=in.read(x))!=-1)b.write(x,0,n);config=b.toString("UTF-8");}
        }
        // Native parser only; no VPN or routes are created by checkConfig.
        try{Class.forName("io.nekohasekai.libbox.Libbox").getMethod("checkConfig",String.class).invoke(null,config);}
        catch(Exception e){parserFailure(e,config);throw e;}
        AtomicFile atomic=new AtomicFile(file);FileOutputStream stream=null;
        try{stream=atomic.startWrite();stream.write(config.getBytes("UTF-8"));atomic.finishWrite(stream);}
        catch(Exception e){if(stream!=null)atomic.failWrite(stream);throw e;}
        file.setReadable(false,false);file.setWritable(false,false);file.setReadable(true,true);file.setWritable(true,true);
        @SuppressWarnings("unchecked") List<Object> profiles=(List<Object>)suspend(manager,"list");Object profile=null;
        for(Object item:profiles)if(NAME.equals(call(item,"getName"))){profile=item;break;}
        boolean fresh=profile==null;if(fresh)profile=Class.forName("io.nekohasekai.sfa.database.Profile").getConstructor().newInstance();
        call(profile,"setName",NAME);Object typed=call(profile,"getTyped");call(typed,"setPath",file.getAbsolutePath());
        call(typed,"setRemoteURL",SOURCE);call(typed,"setAutoUpdate",false);call(typed,"setLastUpdated",new java.util.Date());
        if(fresh){call(profile,"setUserOrder",((Number)suspend(manager,"nextOrder")).longValue());suspend(manager,"create",profile,true);}
        else{suspend(manager,"update",profile);call(settings(),"setSelectedProfile",((Number)call(profile,"getId")).longValue());}
        // Keep normal VpnService mode. Never enable SFA's optional root facilities.
        call(settings(),"setAutoRedirect",false);call(settings(),"setAutoUpdateEnabled",false);call(settings(),"setCheckUpdateEnabled",false);
        suspend(call(settings(),"getDataStore"),"flush");
        context.getSharedPreferences("rice-bridge",Context.MODE_PRIVATE).edit().putBoolean("configured",true).putLong("updated",System.currentTimeMillis()).apply();
    }
}

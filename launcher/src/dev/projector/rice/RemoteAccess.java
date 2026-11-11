package dev.projector.rice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONObject;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** Private pairing data; nothing in this class is exposed through Android IPC. */
final class RemoteAccess {
    static final int PORT=8080;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static RemoteAccess instance;
    private final SharedPreferences prefs;
    private RemoteAccess(Context context){prefs=context.getSharedPreferences("web-remote",Context.MODE_PRIVATE);pin();}
    static synchronized RemoteAccess get(Context context){if(instance==null)instance=new RemoteAccess(context.getApplicationContext());return instance;}
    boolean enabled(){return prefs.getBoolean("enabled",true);}
    void enabled(boolean value){prefs.edit().putBoolean("enabled",value).apply();}
    synchronized String pin(){String p=prefs.getString("pin",null);if(p==null){p=String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1000000));prefs.edit().putString("pin",p).commit();}return p;}
    synchronized void rotate(){prefs.edit().remove("pin").remove("sessions").commit();pin();}
    private static String digest(String value){try{
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
        return Base64.encodeToString(bytes,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
    }catch(Exception e){throw new IllegalStateException(e);}}
    private JSONObject sessions(){try{return new JSONObject(prefs.getString("sessions","{}"));}catch(Exception ignored){return new JSONObject();}}
    synchronized String pair(String supplied){
        if(supplied==null||!MessageDigest.isEqual(pin().getBytes(java.nio.charset.StandardCharsets.UTF_8),supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)))return null;
        try{
            JSONObject sessions=sessions();long now=System.currentTimeMillis();List<String> stale=new ArrayList<>();
            java.util.Iterator<String> keys=sessions.keys();while(keys.hasNext()){String key=keys.next();if(sessions.optLong(key)<now)stale.add(key);}
            for(String key:stale)sessions.remove(key);
            while(sessions.length()>=8){String oldest=null;long at=Long.MAX_VALUE;keys=sessions.keys();while(keys.hasNext()){String key=keys.next();if(sessions.optLong(key)<at){oldest=key;at=sessions.optLong(key);}}if(oldest==null)break;sessions.remove(oldest);}
            byte[] bytes=new byte[24];RANDOM.nextBytes(bytes);String token=Base64.encodeToString(bytes,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
            sessions.put(digest(token),now+7L*24*60*60*1000);prefs.edit().putString("sessions",sessions.toString()).commit();return token;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    synchronized boolean valid(String token){return token!=null&&token.length()==32&&sessions().optLong(digest(token),0)>System.currentTimeMillis();}
    synchronized void revoke(String token){if(token==null)return;JSONObject sessions=sessions();sessions.remove(digest(token));prefs.edit().putString("sessions",sessions.toString()).apply();}
    static boolean local(InetAddress address){return address instanceof Inet4Address&&(address.isSiteLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress());}
    static boolean peer(InetAddress address){
        if(!local(address))return false;if(address.isLoopbackAddress())return true;
        try{Enumeration<NetworkInterface> networks=NetworkInterface.getNetworkInterfaces();while(networks.hasMoreElements()){
            NetworkInterface network=networks.nextElement();if(!network.isUp()||!(network.getName().startsWith("wlan")||network.getName().startsWith("eth")))continue;
            for(java.net.InterfaceAddress entry:network.getInterfaceAddresses()){
                InetAddress own=entry.getAddress();int prefix=entry.getNetworkPrefixLength();
                if(!(own instanceof Inet4Address)||prefix<1||prefix>32)continue;
                byte[] left=own.getAddress(),right=address.getAddress();boolean same=true;
                for(int bit=0;bit<prefix;bit++)if(((left[bit/8]^right[bit/8])&(1<<(7-bit%8)))!=0){same=false;break;}
                if(same)return true;
            }
        }}catch(Exception ignored){}return false;
    }
    static List<String> hosts(){
        List<String> result=new ArrayList<>();result.add("127.0.0.1");
        try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){
            NetworkInterface network=interfaces.nextElement();if(!network.isUp())continue;
            Enumeration<InetAddress> addresses=network.getInetAddresses();while(addresses.hasMoreElements()){
                InetAddress address=addresses.nextElement();if(local(address)&&!result.contains(address.getHostAddress()))result.add(address.getHostAddress());
            }
        }}catch(Exception ignored){}return result;
    }
    static String url(){
        try{for(String name:new String[]{"wlan0","eth0"}){
            NetworkInterface network=NetworkInterface.getByName(name);if(network==null)continue;
            Enumeration<InetAddress> addresses=network.getInetAddresses();while(addresses.hasMoreElements()){
                InetAddress address=addresses.nextElement();if(address instanceof Inet4Address&&address.isSiteLocalAddress())return "http://"+address.getHostAddress()+":"+PORT;
            }
        }}catch(Exception ignored){}
        for(String host:hosts())if(!host.equals("127.0.0.1"))return "http://"+host+":"+PORT;
        return "Подключите проектор к Wi-Fi";
    }
}

package dev.projector.rice;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small HTTP/1.1 server: fixed assets and authenticated, bounded input. */
final class RemoteServer implements AutoCloseable {
    private final RemoteAccess access;
    private final RemoteKeys keys;
    private final Map<String,byte[]> assets=new HashMap<>();
    private final ThreadPoolExecutor clients=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12));
    private final Map<String,long[]> attempts=new HashMap<>();
    private ServerSocket listener;
    private volatile boolean open=true;
    volatile long acceptedKeys=0;
    volatile String lastKey="none";
    RemoteServer(Context context,RemoteKeys keys)throws Exception{
        this.access=RemoteAccess.get(context);this.keys=keys;
        for(String name:new String[]{"index.html","remote.css","remote.js"}){
            try(InputStream in=context.getAssets().open("remote/"+name)){
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);assets.put(name,out.toByteArray());
            }
        }
    }
    void start()throws Exception{
        listener=new ServerSocket();listener.setReuseAddress(true);listener.bind(new InetSocketAddress("0.0.0.0",RemoteAccess.PORT),8);
        new Thread(()->{while(open){try{
            Socket socket=listener.accept();
            if(!RemoteAccess.peer(socket.getInetAddress())){socket.close();continue;}
            socket.setSoTimeout(3000);
            socket.setTcpNoDelay(true);
            try{clients.execute(()->handle(socket));}catch(RuntimeException rejected){socket.close();}
        }catch(Exception e){if(!open)break;}}},"ver0-http-listener").start();
    }
    private static String line(InputStream in,int maximum)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1){if(c=='\n'){byte[] b=out.toByteArray();int n=b.length;if(n>0&&b[n-1]=='\r')n--;return new String(b,0,n,StandardCharsets.US_ASCII);}if(c==0||out.size()>=maximum)throw new IllegalArgumentException();out.write(c);}
        throw new IllegalArgumentException();
    }
    private synchronized boolean mayPair(String address){
        long now=android.os.SystemClock.elapsedRealtime();long[] entry=attempts.get(address);
        if(entry==null||now-entry[0]>=60000){if(attempts.size()>64)attempts.clear();attempts.put(address,new long[]{now,1});return true;}
        return ++entry[1]<=6;
    }
    private static String token(Map<String,String> headers){String value=headers.get("authorization");return value!=null&&value.startsWith("Bearer ")?value.substring(7):null;}
    private void handle(Socket socket){
        try(Socket client=socket){
            InputStream in=client.getInputStream();String[] request=line(in,2048).split(" ");
            if(request.length!=3||!"HTTP/1.1".equals(request[2])||!request[1].startsWith("/")||request[1].startsWith("//")){reply(client,400,"application/json",error("Некорректный запрос"));return;}
            String method=request[0],path=request[1].split("\\?",2)[0];Map<String,String> headers=new HashMap<>();int total=0;
            for(int i=0;;i++){
                String header=line(in,4096);if(header.isEmpty())break;total+=header.length();
                int colon=header.indexOf(':');if(i>=32||total>12000||colon<1)throw new IllegalArgumentException();
                String key=header.substring(0,colon).trim().toLowerCase(Locale.ROOT),value=header.substring(colon+1).trim();
                if(headers.put(key,value)!=null)throw new IllegalArgumentException();
            }
            // Consume a bounded request body before rejecting it. Otherwise
            // closing an unread TCP stream can reset the response on clients.
            if(headers.containsKey("transfer-encoding"))throw new IllegalArgumentException();
            int size=Integer.parseInt(headers.getOrDefault("content-length","0"));if(size<0||size>1024)throw new IllegalArgumentException();
            byte[] body=new byte[size];for(int n=0;n<size;){int count=in.read(body,n,size-n);if(count<0)throw new IllegalArgumentException();n+=count;}
            String host=headers.get("host");boolean validHost=false;
            for(String address:RemoteAccess.hosts())if((address+":"+RemoteAccess.PORT).equals(host))validHost=true;
            if(!validHost){reply(client,403,"application/json",error("Недопустимый адрес"));return;}
            if("GET".equals(method)){
                String file=path.equals("/")?"index.html":path.equals("/remote.css")?"remote.css":path.equals("/remote.js")?"remote.js":null;
                if(file!=null){reply(client,200,file.endsWith(".css")?"text/css":file.endsWith(".js")?"text/javascript":"text/html",assets.get(file));return;}
                if(path.equals("/api/status")){
                    if(!access.valid(token(headers))){reply(client,401,"application/json",error("Введите код проектора"));return;}
                    JSONObject value=new JSONObject();value.put("ok",true);value.put("ready",keys.ready);value.put("name","_VeR0");reply(client,200,"application/json",bytes(value.toString()));return;
                }
                reply(client,404,"application/json",error("Страница не найдена"));return;
            }
            if(!"POST".equals(method)){reply(client,405,"application/json",error("Метод не поддерживается"));return;}
            if(!("http://"+host).equals(headers.get("origin"))){reply(client,403,"application/json",error("Откройте пульт по адресу проектора"));return;}
            String type=headers.get("content-type");
            if(type==null||!type.toLowerCase(Locale.ROOT).matches("application/json(?:;\\s*charset=utf-8)?"))throw new IllegalArgumentException();
            JSONObject data=new JSONObject(new String(body,StandardCharsets.UTF_8));
            if(path.equals("/api/pair")){
                if(!mayPair(client.getInetAddress().getHostAddress())){reply(client,429,"application/json",error("Подождите минуту и повторите"));return;}
                String supplied=data.optString("pin","");String session=supplied.matches("[0-9]{6}")?access.pair(supplied):null;
                if(session==null){reply(client,401,"application/json",error("Проверьте код на проекторе"));return;}
                JSONObject result=new JSONObject();result.put("ok",true);result.put("token",session);reply(client,200,"application/json",bytes(result.toString()));return;
            }
            String session=token(headers);
            if(!access.valid(session)){reply(client,401,"application/json",error("Введите код проектора"));return;}
            if(path.equals("/api/logout")){access.revoke(session);reply(client,200,"application/json",bytes("{\"ok\":true}"));return;}
            if(path.equals("/api/text")){
                Object supplied=data.opt("text");
                if(data.length()!=1||!(supplied instanceof String)||!RemoteText.valid((String)supplied)){
                    reply(client,400,"application/json",error("Введите от 1 до 256 символов: латиница, цифры и знаки"));return;
                }
                boolean typed;try{typed=keys.type((String)supplied);}catch(Exception e){typed=false;}
                if(!typed){reply(client,503,"application/json",error("Ввод не подтверждён. Проверьте поле на проекторе"));return;}
                reply(client,200,"application/json",bytes("{\"ok\":true}"));return;
            }
            if(!path.equals("/api/key")){reply(client,404,"application/json",error("Команда не найдена"));return;}
            String key=data.optString("key","");
            if(data.length()!=1||!keys.accepts(key)){reply(client,400,"application/json",error("Кнопка не поддерживается"));return;}
            synchronized(this){acceptedKeys++;lastKey=key;}
            boolean pressed;try{pressed=keys.press(key);}catch(Exception e){pressed=false;}
            if(!pressed){reply(client,503,"application/json",error("Пульт пока не готов"));return;}
            reply(client,200,"application/json",bytes("{\"ok\":true}"));
        }catch(Exception ignored){/* Close malformed or timed-out requests; never log credentials. */}
    }
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static byte[] error(String text){try{return bytes(new JSONObject().put("ok",false).put("error",text).toString());}catch(Exception e){return bytes("{}");}}
    private static void reply(Socket socket,int code,String type,byte[] body)throws Exception{
        String reason=code==200?"OK":code==401?"Unauthorized":code==403?"Forbidden":code==404?"Not Found":code==405?"Method Not Allowed":code==429?"Too Many Requests":code==503?"Service Unavailable":"Bad Request";
        String headers="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nCross-Origin-Resource-Policy: same-origin\r\nContent-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n\r\n";
        byte[] head=bytes(headers),message=new byte[head.length+body.length];System.arraycopy(head,0,message,0,head.length);System.arraycopy(body,0,message,head.length,body.length);
        OutputStream out=socket.getOutputStream();out.write(message);out.flush();
    }
    @Override public void close(){open=false;try{if(listener!=null)listener.close();}catch(Exception ignored){}clients.shutdownNow();}
}

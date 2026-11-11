package dev.projector.rice;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.net.LocalSocket;
import android.net.LocalServerSocket;
import android.net.Credentials;
import android.system.Os;
import android.system.StructStat;
import android.system.StructPollfd;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Reuses a private local channel to a shell-UID input worker. */
final class RemoteKeys implements AutoCloseable {
    private static final String STAGE="/data/local/tmp/ver0-web-remote-20261109";
    private static final String HELPER=STAGE+"/.agingdragonbox";
    private static final String SHELL_HASH="019086536b350b654fc37f85dcdbe4b79a1d30afc8d841b0f91691b3520c6f95";
    private final String apk;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
    private volatile Session session;
    private volatile boolean closed=false;
    private long sequence=0;
    private boolean verified=false;
    volatile boolean ready=false;
    volatile int relayPid=0;
    volatile long startupMs=0,lastDispatchUs=0;
    RemoteKeys(Context context){
        apk=context.getApplicationInfo().sourceDir;
        worker.execute(()->{try{ensureSession();ready=true;}catch(Exception ignored){ready=false;disconnect();}});
    }
    boolean accepts(String name){return RemoteKeyCodes.code(name)>=0;}
    private static String sha(String path)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(path)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
        StringBuilder result=new StringBuilder();for(byte b:hash.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private static void permissions()throws Exception{
        StructStat dir=Os.lstat(STAGE),file=Os.lstat(HELPER);
        if(dir.st_uid!=2000||(dir.st_mode&077777)!=040755||file.st_uid!=2000||(file.st_mode&0177777)!=0100555)throw new SecurityException();
    }
    private void verify()throws Exception{
        if(!"sun50iw12p1".equals(Build.HARDWARE)||!apk.matches("/data/app/[A-Za-z0-9_+=./~-]+/base\\.apk"))throw new SecurityException();
        permissions();
        if(!SHELL_HASH.equals(sha(HELPER))||!SHELL_HASH.equals(sha("/system/bin/sh"))||!"022b638767ddf981ba09b8092460ea4cb837a7cdf713949ecbea7c0a0ced5759".equals(sha("/system/bin/qw")))throw new SecurityException();
        if(!new File("/dev/com.koushikdutta.superuser.daemon/server").exists())throw new SecurityException();
    }
    private Session ensureSession()throws Exception{
        if(closed)throw new IllegalStateException();
        if(!verified){verify();verified=true;}
        Session current=session;if(current!=null&&current.alive)return current;
        disconnect();permissions();
        if(!new File("/dev/com.koushikdutta.superuser.daemon/server").exists())throw new SecurityException();
        String address="ver0_input_"+java.util.UUID.randomUUID().toString();
        LocalServerSocket listener=new LocalServerSocket(address);
        long before=SystemClock.elapsedRealtime();
        try{
            String command="CLASSPATH="+apk+" /system/bin/app_process /system/bin dev.projector.rice.InputRelay "+android.os.Process.myPid()+" "+android.os.Process.myUid()+" "+address+" </dev/null >/dev/null 2>&1 &";
            // The existing daemon only bootstraps UID 2000. Its request exits
            // immediately; no root supervisor is kept for the input worker.
            String wrapper="/system/bin/qw -c '"+command+"' 2000; result=$?; exit \"$result\"";
            Process bootstrap=new ProcessBuilder(HELPER,"-c",wrapper).redirectErrorStream(true).start();
            try{if(!bootstrap.waitFor(3,TimeUnit.SECONDS)||bootstrap.exitValue()!=0)throw new IllegalStateException();}finally{bootstrap.destroy();}
            StructPollfd poll=new StructPollfd();poll.fd=listener.getFileDescriptor();poll.events=(short)OsConstants.POLLIN;
            if(Os.poll(new StructPollfd[]{poll},5000)==0)throw new IllegalStateException();
            LocalSocket channel=listener.accept();Credentials peer=channel.getPeerCredentials();
            if(peer.getUid()!=2000){channel.close();throw new SecurityException();}
            Session created=new Session(channel);session=created;String hello=created.line(3000);
            if(!hello.equals("VER0-READY "+peer.getPid()+" 2000 0")){disconnect();throw new SecurityException();}
            relayPid=peer.getPid();startupMs=SystemClock.elapsedRealtime()-before;return created;
        }finally{listener.close();}
    }
    private boolean exchange(String operation,String name)throws Exception{
        Session current=ensureSession();long id=++sequence;
        current.write(operation+" "+id+(name==null?"":" "+name));
        String reply=current.line(2500);
        return reply.equals((operation.equals("PING")?"PONG ":"DONE ")+id);
    }
    boolean press(String name)throws Exception{
        if(!accepts(name)||!ready||closed)return false;
        return dispatch("KEY",name);
    }
    boolean type(String text)throws Exception{
        if(!RemoteText.valid(text)||!ready||closed)return false;
        RemoteText.events(text);
        return dispatch("TEXT",RemoteText.encode(text));
    }
    private boolean dispatch(String operation,String value)throws Exception{
        Future<Boolean> task=worker.submit(()->{
            long before=SystemClock.elapsedRealtimeNanos();
            try{return exchange(operation,value);}catch(Exception e){disconnect();throw e;}
            finally{lastDispatchUs=(SystemClock.elapsedRealtimeNanos()-before)/1000;}
        });
        try{return task.get(4,TimeUnit.SECONDS);}catch(Exception e){task.cancel(true);throw e;}
    }
    String benchmark()throws Exception{
        Future<String> task=worker.submit(()->{
            long[] values=new long[20];
            for(int i=0;i<values.length;i++){long before=SystemClock.elapsedRealtimeNanos();if(!exchange("PING",null))throw new IllegalStateException();values[i]=(SystemClock.elapsedRealtimeNanos()-before)/1000;}
            Arrays.sort(values);return "pipe_ping_us median="+values[values.length/2]+" max="+values[values.length-1];
        });return task.get(5,TimeUnit.SECONDS);
    }
    private synchronized void disconnect(){Session old=session;session=null;relayPid=0;if(old!=null)old.close();}
    @Override public void close(){closed=true;ready=false;worker.shutdownNow();disconnect();}
    private static final class Session {
        final LocalSocket channel;
        volatile boolean alive=true;
        final BufferedWriter output;
        final BlockingQueue<String> replies=new ArrayBlockingQueue<>(32);
        Session(LocalSocket channel)throws Exception{
            this.channel=channel;output=new BufferedWriter(new OutputStreamWriter(channel.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8));
            Thread reader=new Thread(()->{try(BufferedReader in=new BufferedReader(new InputStreamReader(channel.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){String line;
                while((line=in.readLine())!=null){if(line.startsWith("VER0-")||line.startsWith("DONE ")||line.startsWith("PONG ")||line.startsWith("ERR "))if(!replies.offer(line))break;}
            }catch(Exception ignored){}finally{alive=false;replies.offer("EOF");}},"ver0-input-replies");reader.setDaemon(true);reader.start();
        }
        void write(String command)throws Exception{output.write(command);output.newLine();output.flush();}
        String line(long timeout)throws Exception{String result=replies.poll(timeout,TimeUnit.MILLISECONDS);if(result==null||result.equals("EOF"))throw new IllegalStateException();return result;}
        void close(){alive=false;try{channel.close();}catch(Exception ignored){}}
    }
}

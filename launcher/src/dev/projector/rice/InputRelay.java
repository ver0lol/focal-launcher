package dev.projector.rice;

import android.os.Process;
import android.os.SystemClock;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Credentials;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;

/** app_process entry point, running strictly as shell UID 2000 with no caps.
 * One private local connection. No listener or arbitrary command API. */
public final class InputRelay {
    private static boolean unprivileged()throws Exception{
        if(Process.myUid()!=2000)return false;
        boolean uid=false,gid=false,caps=false;
        try(BufferedReader in=new BufferedReader(new FileReader("/proc/self/status"))){String line;
            while((line=in.readLine())!=null){
                if(line.startsWith("Uid:"))uid=line.matches("Uid:\\s+2000\\s+2000\\s+2000\\s+2000\\s*");
                if(line.startsWith("Gid:"))gid=line.matches("Gid:\\s+2000\\s+2000\\s+2000\\s+2000\\s*");
                if(line.startsWith("CapEff:"))caps=line.matches("CapEff:\\s+0+\\s*");
            }
        }return uid&&gid&&caps;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=3||!args[0].matches("[1-9][0-9]{0,8}")||!args[1].matches("[1-9][0-9]{0,7}")||!args[2].matches("ver0_input_[a-zA-Z0-9_-]{30,50}")||!unprivileged())return;
        int owner=Integer.parseInt(args[0]),ownerUid=Integer.parseInt(args[1]);
        Thread watch=new Thread(()->{for(;;){try{
            Thread.sleep(2000);try{Os.kill(owner,0);}catch(ErrnoException e){if(e.errno==OsConstants.ESRCH)System.exit(0);}
        }catch(InterruptedException e){return;}}},"ver0-input-owner");watch.setDaemon(true);watch.start();
        Class<?> manager=Class.forName("android.hardware.input.InputManager");
        Object input=manager.getMethod("getInstance").invoke(null);
        Method inject=manager.getMethod("injectInputEvent",InputEvent.class,int.class);
        LocalSocket channel=new LocalSocket();channel.connect(new LocalSocketAddress(args[2],LocalSocketAddress.Namespace.ABSTRACT));
        Credentials peer=channel.getPeerCredentials();if(peer.getPid()!=owner||peer.getUid()!=ownerUid){channel.close();return;}
        PrintWriter out=new PrintWriter(channel.getOutputStream(),true);
        out.println("VER0-READY "+Process.myPid()+" 2000 0");
        try(BufferedReader in=new BufferedReader(new InputStreamReader(channel.getInputStream(),"UTF-8"))){String line;
            while((line=in.readLine())!=null){
                if(line.length()>384)break;String[] fields=line.split(" ");
                if(fields.length<2||!fields[1].matches("[1-9][0-9]{0,15}"))break;
                if(fields.length==2&&fields[0].equals("PING")){out.println("PONG "+fields[1]);continue;}
                if(fields.length==3&&fields[0].equals("TEXT")){
                    boolean accepted=true;
                    try{
                        KeyEvent[] events=RemoteText.events(RemoteText.decode(fields[2]));
                        // Validate the complete string before sending any event.
                        // One completion barrier avoids a separate round trip
                        // for every character while retaining event order.
                        for(int i=0;i<events.length;i++){
                            KeyEvent event=events[i];
                            event.setSource(InputDevice.SOURCE_KEYBOARD);
                            long now=SystemClock.uptimeMillis();
                            if(!(Boolean)inject.invoke(input,KeyEvent.changeTimeRepeat(event,now,0),i==events.length-1?2:0)){accepted=false;break;}
                        }
                    }catch(Exception ignored){accepted=false;}
                    finally{
                        // A failed dispatch must never leave a modifier held.
                        for(int modifier:new int[]{KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_SHIFT_RIGHT,KeyEvent.KEYCODE_ALT_LEFT,KeyEvent.KEYCODE_ALT_RIGHT}){
                            long now=SystemClock.uptimeMillis();
                            inject.invoke(input,new KeyEvent(now,now,KeyEvent.ACTION_UP,modifier,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD),0);
                        }
                    }
                    out.println((accepted?"DONE ":"ERR ")+fields[1]);continue;
                }
                if(fields.length!=3||!fields[0].equals("KEY"))break;
                int code=RemoteKeyCodes.code(fields[2]);if(code<0){out.println("ERR "+fields[1]);continue;}
                long now=SystemClock.uptimeMillis();
                KeyEvent down=new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD);
                boolean accepted=false,released=false;
                try{accepted=(Boolean)inject.invoke(input,down,0);}
                finally{released=(Boolean)inject.invoke(input,KeyEvent.changeAction(down,KeyEvent.ACTION_UP),0);}
                out.println((accepted&&released?"DONE ":"ERR ")+fields[1]);
            }
        }finally{channel.close();}
    }
}

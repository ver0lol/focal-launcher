package dev.projector.rice;

import android.util.Base64;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.nio.charset.StandardCharsets;

/** Literal keyboard input only. Text never becomes a shell command. */
final class RemoteText {
    static final int MAX_LENGTH=256;
    static boolean valid(String text){
        if(text==null||text.isEmpty()||text.length()>MAX_LENGTH)return false;
        // Android's virtual hardware key map has no Cyrillic layout. Reject
        // unsupported characters before dispatch, never silently change them.
        for(int i=0;i<text.length();i++)if(text.charAt(i)<32||text.charAt(i)>126)return false;
        return true;
    }
    static String encode(String text){return Base64.encodeToString(text.getBytes(StandardCharsets.US_ASCII),Base64.NO_WRAP);}
    static String decode(String value){
        if(value.length()>344||!value.matches("[A-Za-z0-9+/]+={0,2}"))throw new IllegalArgumentException();
        String text=new String(Base64.decode(value,Base64.NO_WRAP),StandardCharsets.UTF_8);
        if(!valid(text))throw new IllegalArgumentException();return text;
    }
    static KeyEvent[] events(String text){
        if(!valid(text))throw new IllegalArgumentException();
        KeyEvent[] events=KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if(events==null)throw new IllegalArgumentException();return events;
    }
}

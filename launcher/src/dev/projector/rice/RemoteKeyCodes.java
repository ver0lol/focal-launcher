package dev.projector.rice;

/** Shared allowlist for the web service and the unprivileged input worker. */
final class RemoteKeyCodes {
    static int code(String name){
        switch(name){
            case "up":return 19;case "down":return 20;case "left":return 21;case "right":return 22;
            case "ok":return 23;case "back":return 4;case "home":return 3;case "menu":return 82;
            case "play_pause":return 85;case "vol_down":return 25;case "vol_up":return 24;case "mute":return 164;
            case "delete":return 67;case "enter":return 66;
            default:return -1;
        }
    }
}

package dev.projector.rice;
public final class RemoteBootReceiver extends android.content.BroadcastReceiver {
    @Override public void onReceive(android.content.Context context,android.content.Intent intent){
        if(android.content.Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))RemoteService.startOnBoot(context);
        else if(android.content.Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))RemoteService.start(context);
    }
}

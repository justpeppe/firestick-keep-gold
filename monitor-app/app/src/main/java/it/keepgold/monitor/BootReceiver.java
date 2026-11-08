package it.keepgold.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Starts the service at boot and on the keep-alive alarm; opens the show at boot if "Riparti da solo" is on.
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        KioskService.start(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && Store.get(context).autostart) {
            KioskService.bringToFront(context);
        }
    }
}

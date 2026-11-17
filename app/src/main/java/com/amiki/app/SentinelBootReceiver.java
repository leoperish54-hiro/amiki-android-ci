package com.amiki.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the independent Sentinel after device boot when the OS permits it. */
public final class SentinelBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SentinelService.start(context);
        }
    }
}

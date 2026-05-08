package br.com.redesurftank.havalenginereverse.broadcastReceivers;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import br.com.redesurftank.havalenginereverse.services.UniversalMonitorService;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.w(TAG, "Boot completed received, starting UniversalMonitorService...");
        Intent serviceIntent = new Intent(context, UniversalMonitorService.class);
        context.startForegroundService(serviceIntent);
    }
}

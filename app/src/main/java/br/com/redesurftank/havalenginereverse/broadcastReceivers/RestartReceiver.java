package br.com.redesurftank.havalenginereverse.broadcastReceivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import br.com.redesurftank.havalenginereverse.services.UniversalMonitorService;

public class RestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = new Intent(context, UniversalMonitorService.class);
        context.startForegroundService(serviceIntent);
    }
}

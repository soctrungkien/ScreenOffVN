package com.tile.screenoff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        // Khi máy khởi động xong, thử kích hoạt ngầm qua Root/Shizuku.
        final PendingResult pendingResult = goAsync();
        MainActivity.trySilentActivate(context, pendingResult::finish);
    }
}

package com.tile.screenoff;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

@TargetApi(Build.VERSION_CODES.N)
public class tileService extends TileService {

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Override
    public void onClick() {
        if (getQsTile() == null) return;

        if (GlobalService.isScreenOffServiceRunning()) {
            // Không gửi "state" để GlobalService tự động Toggle (đảo trạng thái)
            sendBroadcast(new Intent("action.ScrOff").setPackage(getPackageName()));
        } else {
            Intent intent = new Intent(tileService.this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pendingIntent);
            } else {
                startActivityAndCollapse(intent);
            }
        }
        super.onClick();
    }

}

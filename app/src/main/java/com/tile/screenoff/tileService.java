package com.tile.screenoff;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

@TargetApi(Build.VERSION_CODES.N)
public class tileService extends TileService {

    @Override
    public void onClick() {
        if (getQsTile() == null) return;
        
        if (GlobalService.isScreenOffServiceRunning(this)) {
            // Không gửi "state" để GlobalService tự động Toggle (đảo trạng thái)
            sendBroadcast(new Intent("action.ScrOff"));
        } else {
            startActivityAndCollapse(new Intent(tileService.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        super.onClick();
    }

}

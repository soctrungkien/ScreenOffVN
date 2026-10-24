package com.tile.screenoff;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

public class ScrOff extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_NoDisplay);
        super.onCreate(savedInstanceState);

        final Intent command = new Intent("action.ScrOff")
                .setPackage(getPackageName())
                .putExtra("state", true);

        if (MainActivity.isAccessibilityServiceEnabled(this, GlobalService.class)) {
            sendBroadcast(command);
        } else if (enableAccessibility()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> sendBroadcast(command), 1500);
        } else {
            openAccessibilitySettings();
        }
        finish();
    }

    private boolean enableAccessibility() {
        ComponentName component = new ComponentName(this, GlobalService.class);
        String serviceName = component.flattenToString();
        String shortName = component.flattenToShortString();
        try {
            String oldSetting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String newSetting;
            if (oldSetting == null || oldSetting.isEmpty() || "null".equals(oldSetting)) {
                newSetting = serviceName;
            } else if (oldSetting.contains(serviceName) || oldSetting.contains(shortName)) {
                newSetting = oldSetting;
            } else {
                newSetting = oldSetting + ":" + serviceName;
            }
            boolean servicesUpdated = Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newSetting);
            boolean enabledUpdated = Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return servicesUpdated && enabledUpdated;
        } catch (Exception e) {
            return false;
        }
    }

    private void openAccessibilitySettings() {
        String serviceName = new ComponentName(this, GlobalService.class).flattenToString();
        Bundle bundle = new Bundle();
        bundle.putString(":settings:fragment_args_key", serviceName);
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", serviceName)
                .putExtra(":settings:show_fragment_args", bundle));
    }
}

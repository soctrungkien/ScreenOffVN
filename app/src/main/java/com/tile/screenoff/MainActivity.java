package com.tile.screenoff;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private boolean isExpand = false, isServiceOK = false, isPermissionResultListenerRegistered = false;
    private final Handler checkHandler = new Handler(Looper.getMainLooper());
    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isServiceOK) {
                tryAutoActivate();
            }
            checkHandler.postDelayed(this, 3000);
        }
    };
    private int scrOffKey, scrOnKey;
    public IScreenOff iScreenOff = null;
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            BinderContainer binderContainer = intent.getParcelableExtra("binder");
            if (binderContainer == null) return;
            IBinder binder = binderContainer.getBinder();
            if (binder == null || !binder.pingBinder()) return;
            iScreenOff = IScreenOff.Stub.asInterface(binder);
            enableScreenOffFunctions();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.getAttributes().dimAmount = 0.5f;
        setContentView(R.layout.main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_YES) == Configuration.UI_MODE_NIGHT_YES;
        window.setNavigationBarColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) ? Color.TRANSPARENT : getColor(isNight ? R.color.bgBlack : R.color.bgWhite) : (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) ? Color.TRANSPARENT : (isNight ? 0xff303034 : 0xffe4e2e6));
        window.setStatusBarColor(Color.TRANSPARENT);

        SharedPreferences sp = getSharedPreferences("s", 0);
        if (sp.getBoolean("first", true)) {
            new AlertDialog.Builder(this).setTitle(R.string.privacy).setMessage(R.string.privacypolicy).setNegativeButton(R.string.agree, (d, i) -> {
                help(); sp.edit().putBoolean("first", false).apply(); tryAutoActivate();
            }).setCancelable(false).setPositiveButton(R.string.disagree, (d, i) -> finish()).show();
        } else { tryAutoActivate(); }

        setButtonsOnclick(isNight, sp);
        IntentFilter filter = new IntentFilter("intent.screenoff.sendBinder");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkHandler.post(checkRunnable);
        if (isServiceOK && iScreenOff != null) updateSwitchState();
    }

    private void updateSwitchState() {
        try {
            if (iScreenOff != null) {
                int state = iScreenOff.getNowScreenState();
                Switch aSwitch = findViewById(R.id.screenoff_switch);
                aSwitch.setChecked(state == 1);
            }
        } catch (RemoteException e) {
            isServiceOK = false; iScreenOff = null;
            findViewById(R.id.screenoff_switch).setEnabled(false);
            Button button = findViewById(R.id.activate_button);
            button.setText(R.string.need_active); button.setTextColor(Color.RED);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        checkHandler.removeCallbacks(checkRunnable);
    }

    private void checkPermissionsAuto() {
        if (isServiceOK) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Cấp quyền 'Xuất hiện trên cùng'", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                return;
            }
        }
        if (!isAccessibilityServiceEnabled(this, GlobalService.class)) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Bật dịch vụ Hỗ trợ ScreenOff", Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean isAccessibilityServiceEnabled(Context context, Class<?> service) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo enabledService : enabledServices) {
            ServiceInfo enabledServiceInfo = enabledService.getResolveInfo().serviceInfo;
            if (enabledServiceInfo.packageName.equals(context.getPackageName()) && enabledServiceInfo.name.equals(service.getName())) return true;
        }
        return false;
    }

    private void showNet() {
        String[] i = new String[]{"wlan: ", "eth: ", "usb: ", "p2p: ", "lo: ", "unknown: "};
        int i2; boolean avalible = false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface next = interfaces.nextElement();
                String name = next.getName().toLowerCase(Locale.US);
                if (name.contains("wlan")) i2 = 0; else if (name.contains("eth")) i2 = 1; else if (name.contains("usb")) i2 = 2; else if (name.contains("p2p")) i2 = 3; else if (name.contains("lo")) i2 = 4; else i2 = 5;
                Enumeration<InetAddress> addrs = next.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        i[i2] += addr.getHostAddress() + ":" + GlobalService.port + " "; avalible = true;
                    }
                }
            }
        } catch (Exception ignored) {}
        StringBuilder sb = new StringBuilder();
        for (int j=0; j<5; j++) { if (i[j].contains(".")) sb.append(i[j]); }
        TextView tv = findViewById(R.id.title_text); tv.setOnClickListener(null);
        tv.setText(avalible ? sb.toString() : "no network avalible");
    }

    private void setButtonsOnclick(boolean isNight, SharedPreferences sp) {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            findViewById(R.id.left).setVisibility(View.VISIBLE); findViewById(R.id.right).setVisibility(View.VISIBLE);
        }
        EditText e1 = findViewById(R.id.e1), e2 = findViewById(R.id.e2);
        Switch s1 = findViewById(R.id.s1), s6 = findViewById(R.id.s6), s7 = findViewById(R.id.s7), s8 = findViewById(R.id.s8);
        s1.setChecked(isAccessibilityServiceEnabled(this, GlobalService.class));
        s6.setChecked(sp.getBoolean("shake", false)); s7.setChecked(sp.getBoolean("volume", false)); s8.setChecked(sp.getBoolean("net", false));
        SeekBar sd = findViewById(R.id.sd); sd.setProgress(sp.getInt("sensity", 10));
        EditText ed = findViewById(R.id.ed); ed.setText(String.valueOf(sp.getInt("sensity", 10)));
        
        s1.setOnCheckedChangeListener((cb, isChecked) -> {
            if (!isServiceOK) { cb.setChecked(false); Toast.makeText(this, R.string.active_first, Toast.LENGTH_SHORT).show(); return; }
            if (isChecked) { if (!isAccessibilityServiceEnabled(this, GlobalService.class)) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); if (s8.isChecked()) showNet(); }
            else { ((TextView) findViewById(R.id.title_text)).setText(R.string.shortcutoff); sendBroadcast(new Intent("intent.screenoff.exit")); }
        });
        s6.setOnCheckedChangeListener((cb, b) -> sp.edit().putBoolean("shake", b).apply());
        s7.setOnCheckedChangeListener((cb, b) -> { sp.edit().putBoolean("volume", b).apply(); e1.setEnabled(b); e2.setEnabled(b); });
        s8.setOnCheckedChangeListener((cb, b) -> { if (s1.isChecked()) { if (b) showNet(); else ((TextView) findViewById(R.id.title_text)).setText(R.string.shortcutoff); } sp.edit().putBoolean("net", b).apply(); });
        if (s1.isChecked() && s8.isChecked()) showNet();
        sd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int i, boolean b) { sp.edit().putInt("sensity", i).apply(); ed.setText(String.valueOf(i)); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { if (sb.getProgress() < 1) { sb.setProgress(1); Toast.makeText(MainActivity.this, R.string.toosmall, Toast.LENGTH_SHORT).show(); } }
        });
        ed.setOnKeyListener((v, i, ev) -> {
            if (ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN && ed.getText().length() > 0) {
                int val = Integer.parseInt(ed.getText().toString()); if (val >= 0 && val <= 30) { sp.edit().putInt("sensity", val).apply(); sd.setProgress(val); }
            }
            return false;
        });
        e1.setEnabled(s7.isChecked()); e2.setEnabled(s7.isChecked());
        scrOffKey = sp.getInt("scrOffKey", 25); scrOnKey = sp.getInt("scrOnKey", 24);
        e1.setText(String.valueOf(scrOffKey)); e2.setText(String.valueOf(scrOnKey));
        e1.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int i1, int i2) {}
            @Override public void onTextChanged(CharSequence s, int i, int i1, int i2) { if (s.length() > 0) { scrOffKey = Integer.parseInt(s.toString()); sp.edit().putInt("scrOffKey", scrOffKey).apply(); } }
            @Override public void afterTextChanged(Editable s) {}
        });
        e2.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int i1, int i2) {}
            @Override public void onTextChanged(CharSequence s, int i, int i1, int i2) { if (s.length() > 0) { scrOnKey = Integer.parseInt(s.toString()); sp.edit().putInt("scrOnKey", scrOnKey).apply(); } }
            @Override public void afterTextChanged(Editable s) {}
        });
        findViewById(R.id.title_text).setOnClickListener(v -> help());
        findViewById(R.id.activate_button).setOnClickListener(v -> showActivate());
        float density = getResources().getDisplayMetrics().density;
        ShapeDrawable oval = new ShapeDrawable(new RoundRectShape(new float[]{30*density, 30*density, 30*density, 30*density, 0, 0, 0, 0}, null, null));
        oval.getPaint().setColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? getColor(isNight ? R.color.bgBlack : R.color.bgWhite) : (isNight ? 0xff303034 : 0xffe4e2e6));
        findViewById(R.id.ll).setBackground(oval);
        Switch aSwitch = findViewById(R.id.screenoff_switch);
        aSwitch.setOnCheckedChangeListener((cb, b) -> { if (!isServiceOK || iScreenOff == null) return; try { iScreenOff.setPowerMode(!b); } catch (Exception ignored) {} });
        isExpand = true;
    }

    public static void trySilentActivate(Context context) {
        if (GlobalService.isScreenOffServiceRunning(context)) return;
        unzipFilesStatic(context);
        final String path = context.getExternalFilesDir(null).getPath();
        final String pkg = context.getPackageName();
        final String serviceName = new ComponentName(pkg, GlobalService.class.getName()).flattenToString();
        
        new Thread(() -> {
            String currentServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String newServices = (currentServices == null || currentServices.isEmpty()) ? serviceName : (currentServices.contains(serviceName) ? currentServices : currentServices + ":" + serviceName);
            
            StringBuilder sb = new StringBuilder();
            sb.append("chmod 777 ").append(path).append("/starter.sh && sh ").append(path).append("/starter.sh ").append(path).append("\n");
            sb.append("settings put secure enabled_accessibility_services ").append(newServices).append("\n");
            sb.append("settings put secure accessibility_enabled 1\n");
            sb.append("appops set ").append(pkg).append(" SYSTEM_ALERT_WINDOW allow\n");
            sb.append("dumpsys deviceidle whitelist +").append(pkg).append("\n");
            sb.append("exit\n");
            final String cmd = sb.toString();

            try {
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream o = new DataOutputStream(p.getOutputStream());
                o.writeBytes(cmd); o.flush(); o.close(); p.waitFor();
            } catch (Exception ignored) {}

            if (!GlobalService.isScreenOffServiceRunning(context)) {
                try { if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) runShizukuCommandStatic(cmd); }
                catch (Exception ignored) {}
            }
        }).start();
    }

    private static void runShizukuCommandStatic(String cmd) {
        try {
            Process p = Shizuku.newProcess(new String[]{"sh"}, null, null);
            java.io.OutputStream out = p.getOutputStream();
            out.write(cmd.getBytes()); out.flush(); out.close();
        } catch (Exception ignored) {}
    }

    public static void unzipFilesStatic(Context context) {
        if (context.getExternalFilesDir(null) == null) return;
        String path = context.getExternalFilesDir(null).getPath();
        try (InputStream is = context.getAssets().open("starter.sh"); FileOutputStream fos = new FileOutputStream(path + "/starter.sh")) {
            byte[] buf = new byte[1024]; int len; while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
        } catch (IOException ignored) {}
        try (ZipFile zipFile = new ZipFile(context.getPackageResourcePath())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if ("classes.dex".equals(entry.getName())) {
                    try (InputStream is = zipFile.getInputStream(entry); FileOutputStream fos = new FileOutputStream(path + "/ScreenController.dex")) {
                        byte[] buf = new byte[1024]; int l; while ((l = is.read(buf)) > 0) fos.write(buf, 0, l);
                    }
                    break;
                }
            }
        } catch (IOException ignored) {}
        try {
            FileOutputStream off = new FileOutputStream(path + "/scroff.sh"); off.write("am broadcast -a action.ScrOff --ez state true".getBytes()); off.close();
            FileOutputStream on = new FileOutputStream(path + "/scron.sh"); on.write("am broadcast -a action.ScrOff --ez state false".getBytes()); on.close();
        } catch (IOException ignored) {}
    }

    private void tryAutoActivate() { if (isServiceOK) return; trySilentActivate(this); }

    public void enableScreenOffFunctions() {
        Button btn = findViewById(R.id.activate_button); isServiceOK = true;
        btn.setText(getString(R.string.all_ok)); btn.setTextColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? getColor(R.color.right) : 0xFF00FF00);
        btn.setOnClickListener(null);
        btn.setOnLongClickListener(v -> {
            try { sendBroadcast(new Intent("intent.screenoff.exit")); if (iScreenOff != null) iScreenOff.closeAndExit(); } catch (Exception ignored) {}
            Toast.makeText(this, R.string.service_closed, Toast.LENGTH_SHORT).show(); finish(); return false;
        });
        findViewById(R.id.screenoff_switch).setEnabled(true); updateSwitchState();
    }

    @Override public void onBackPressed() { finish(); }
    @Override public boolean onKeyDown(int k, KeyEvent ev) {
        if (isExpand) { Toast.makeText(this, String.format(Locale.getDefault(), getString(R.string.key_pressed), KeyEvent.keyCodeToString(k).replace("KEYCODE_", ""), k), Toast.LENGTH_SHORT).show(); return true; }
        if (!isServiceOK) return true;
        Switch sw = findViewById(R.id.screenoff_switch);
        if (k == scrOffKey) sw.setChecked(true); if (k == scrOnKey) sw.setChecked(false);
        return true;
    }

    private final Shizuku.OnRequestPermissionResultListener RL = (rc, res) -> check();

    private void check() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (!isPermissionResultListenerRegistered) { Shizuku.addRequestPermissionResultListener(RL); isPermissionResultListenerRegistered = true; }
        boolean hasPerm = false;
        try { if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) hasPerm = true; else Shizuku.requestPermission(0); }
        catch (Exception e) { if (checkSelfPermission("moe.shizuku.manager.permission.API_V23") == PackageManager.PERMISSION_GRANTED) hasPerm = true; }
        if (hasPerm) tryAutoActivate();
    }

    @Override protected void onDestroy() { if (isPermissionResultListenerRegistered) Shizuku.removeRequestPermissionResultListener(RL); try { unregisterReceiver(mBroadcastReceiver); } catch (Exception ignored) {} super.onDestroy(); }
    public void help() { new AlertDialog.Builder(this).setTitle(R.string.help_title).setMessage(R.string.help_conntent).setNegativeButton(R.string.understand, null).show(); }
    public void showActivate() {
        checkPermissionsAuto(); unzipFilesStatic(this);
        String cmd = "sh " + getExternalFilesDir(null).getPath() + "/starter.sh";
        new AlertDialog.Builder(this).setMessage(String.format(getString(R.string.active_steps), cmd)).setTitle(R.string.need_active)
                .setNeutralButton(R.string.copy_cmd, (di, i) -> { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", "adb shell " + cmd)); Toast.makeText(this, String.format(getString(R.string.cmd_copy_finish), cmd), Toast.LENGTH_SHORT).show(); })
                .setNegativeButton(R.string.by_root, (di, i) -> { try { Process p = Runtime.getRuntime().exec("su"); DataOutputStream o = new DataOutputStream(p.getOutputStream()); o.writeBytes(cmd); o.flush(); o.close(); } catch (IOException e) { Toast.makeText(this, R.string.active_failed, Toast.LENGTH_SHORT).show(); } })
                .setPositiveButton(R.string.by_shizuku, (di, i) -> check()).show();
    }
}

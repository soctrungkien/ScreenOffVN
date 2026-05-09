package com.tile.screenoff;

import android.accessibilityservice.AccessibilityServiceInfo;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {
    private boolean isExpand = false, isServiceOK = false, isPermissionResultListenerRegistered = false;
    private final Handler checkHandler = new Handler(Looper.getMainLooper());
    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (isServiceOK && iScreenOff != null) {
                updateSwitchState();
            }
            checkHandler.postDelayed(this, 3000);
        }
    };
    private int scrOffKey, scrOnKey;
    public IScreenOff iScreenOff = null;
    private static final StringBuilder logBuffer = new StringBuilder();
    private static WeakReference<MainActivity> activityRef = new WeakReference<>(null);

    private static void appendLog(Context context, String log) {
        String formattedLog = "[" + new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date()) + "] " + log;
        synchronized (logBuffer) {
            logBuffer.append(formattedLog).append("\n");
            if (logBuffer.length() > 10000) logBuffer.delete(0, 2000);
        }
        Log.d("ScreenOffShell", log);

        if (context != null && context.getExternalFilesDir(null) != null) {
            new Thread(() -> {
                try (java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(context.getExternalFilesDir(null), "shell_logs.txt"), true);
                     java.io.BufferedWriter bw = new java.io.BufferedWriter(fw)) {
                    bw.write(formattedLog);
                    bw.newLine();
                } catch (IOException e) {
                    Log.e("ScreenOffShell", "Failed to write log to file", e);
                }
            }).start();
        }

        if (activityRef != null) {
            MainActivity activity = activityRef.get();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    TextView tv = activity.findViewById(R.id.log_text);
                    if (tv != null) {
                        if (tv.getText().toString().equals(activity.getString(R.string.shell_logs))) tv.setText("");
                        tv.append(formattedLog + "\n");
                    }
                });
            }
        }
    }

    private static void captureOutput(Context context, InputStream is, String prefix) {
        new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(context, prefix + ": " + line);
                }
            } catch (IOException ignored) {}
        }).start();
    }

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
        setTheme(R.style.AppTheme);
        DynamicColors.applyToActivitiesIfAvailable(this.getApplication());

        // Crash handler to log everything
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            Log.e("ScreenOffCrash", "Uncaught exception", e);
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.printStackTrace(pw);
            String trace = sw.toString();
            try {
                java.io.File logFile = new java.io.File(getExternalFilesDir(null), "shell_logs.txt");
                try (java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
                     java.io.BufferedWriter bw = new java.io.BufferedWriter(fw)) {
                    bw.write("\n" + "=".repeat(20) + " CRASH LOG DETECTED " + "=".repeat(20) + "\n");
                    bw.write("Time: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new java.util.Date()) + "\n");
                    bw.write("Device: " + Build.BRAND + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")\n");
                    bw.write("Message: " + e.getMessage() + "\n\n");
                    bw.write("Stack Trace:\n");
                    bw.write(trace);
                    bw.write("\n" + "=".repeat(60) + "\n");
                    bw.flush();
                }
            } catch (Exception ignored) {}
            new Thread(() -> {
                Looper.prepare();
                Toast.makeText(getApplicationContext(), "App Error: Check log file", Toast.LENGTH_LONG).show();
                Looper.loop();
            }).start();
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });

        super.onCreate(savedInstanceState);
        
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.getAttributes().dimAmount = 0.5f;
        setContentView(R.layout.main);

        // Center floating window logic
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = (int) (metrics.widthPixels * 0.85);
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(android.view.Gravity.CENTER);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_YES) == Configuration.UI_MODE_NIGHT_YES;
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setStatusBarColor(Color.TRANSPARENT);

        SharedPreferences sp = getSharedPreferences("s", 0);
        if (sp.getBoolean("first", true)) {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.privacy).setMessage(R.string.privacypolicy).setNegativeButton(R.string.agree, (d, i) -> {
                help(); sp.edit().putBoolean("first", false).apply();
            }).setCancelable(false).setPositiveButton(R.string.disagree, (d, i) -> finish()).show();
        }

        activityRef = new WeakReference<>(this);
        setButtonsOnclick(isNight, sp);
        
        TextView logTv = findViewById(R.id.log_text);
        if (logTv != null) {
            logTv.setMovementMethod(new android.text.method.ScrollingMovementMethod());
            synchronized (logBuffer) {
                if (logBuffer.length() > 0) logTv.setText(logBuffer.toString());
            }
        }
        
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
        if (this.isFinishing()) return;
        try {
            if (iScreenOff != null) {
                int state = iScreenOff.getNowScreenState();
                MaterialSwitch aSwitch = findViewById(R.id.screenoff_switch);
                if (aSwitch != null) aSwitch.setChecked(state == 1);
            }
        } catch (RemoteException e) {
            isServiceOK = false; iScreenOff = null;
            MaterialSwitch sw = findViewById(R.id.screenoff_switch);
            if (sw != null) sw.setEnabled(false);
            Button button = findViewById(R.id.activate_button);
            if (button != null) {
                button.setText(R.string.need_active); 
                button.setTextColor(Color.RED);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        checkHandler.removeCallbacks(checkRunnable);
    }

    private void checkPermissionsAuto() {
        if (isServiceOK) return;
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
        TextView tv = findViewById(R.id.title_text);
        if (tv != null) tv.setText(avalible ? sb.toString() : "no network avalible");
    }

    private void setButtonsOnclick(boolean isNight, SharedPreferences sp) {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            findViewById(R.id.left).setVisibility(View.VISIBLE); findViewById(R.id.right).setVisibility(View.VISIBLE);
        }
        EditText e1 = findViewById(R.id.e1), e2 = findViewById(R.id.e2);
        MaterialSwitch s1 = findViewById(R.id.s1), s6 = findViewById(R.id.s6), s7 = findViewById(R.id.s7), s8 = findViewById(R.id.s8);
        s1.setChecked(isAccessibilityServiceEnabled(this, GlobalService.class));
        s6.setChecked(sp.getBoolean("shake", false)); s7.setChecked(sp.getBoolean("volume", false)); s8.setChecked(sp.getBoolean("net", false));
        SeekBar sd = findViewById(R.id.sd); sd.setProgress(sp.getInt("sensity", 10));
        EditText ed = findViewById(R.id.ed); ed.setText(String.valueOf(sp.getInt("sensity", 10)));
        
        s1.setOnCheckedChangeListener((cb, isChecked) -> {
            if (!isServiceOK) { 
                cb.setChecked(false); 
                Toast.makeText(this, R.string.active_first, Toast.LENGTH_SHORT).show(); 
                return; 
            }
            if (isChecked) { 
                if (s8.isChecked()) showNet(); 
            } else { 
                TextView tv = findViewById(R.id.title_text);
                if (tv != null) tv.setText(R.string.shortcutoff); 
                sendBroadcast(new Intent("intent.screenoff.exit")); 
            }
        });
        s6.setOnCheckedChangeListener((cb, b) -> sp.edit().putBoolean("shake", b).apply());
        s7.setOnCheckedChangeListener((cb, b) -> { sp.edit().putBoolean("volume", b).apply(); e1.setEnabled(b); e2.setEnabled(b); });
        s8.setOnCheckedChangeListener((cb, b) -> { if (s1.isChecked()) { if (b) showNet(); else { TextView tv = findViewById(R.id.title_text); if (tv != null) tv.setText(R.string.shortcutoff); } } sp.edit().putBoolean("net", b).apply(); });
        if (s1.isChecked() && s8.isChecked()) showNet();
        sd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int i, boolean b) { sp.edit().putInt("sensity", i).apply(); ed.setText(String.valueOf(i)); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { if (sb.getProgress() < 1) { sb.setProgress(1); Toast.makeText(MainActivity.this, R.string.toosmall, Toast.LENGTH_SHORT).show(); } }
        });
        ed.setOnKeyListener((v, i, ev) -> {
            if (ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN && !ed.getText().toString().isEmpty()) {
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

        MaterialButton stopBtn = findViewById(R.id.stop_button);
        if (stopBtn != null) {
            stopBtn.setOnClickListener(v -> {
                try {
                    sendBroadcast(new Intent("intent.screenoff.exit"));
                    if (iScreenOff != null) iScreenOff.closeAndExit();
                } catch (Exception ignored) {}
                isServiceOK = false;
                iScreenOff = null;
                updateSwitchState();
                stopBtn.setVisibility(View.GONE);
                MaterialButton activeBtn = findViewById(R.id.activate_button);
                if (activeBtn != null) {
                    activeBtn.setText(R.string.not_ok);
                    activeBtn.setTextColor(ContextCompat.getColor(this, R.color.wrong));
                    activeBtn.setOnClickListener(v1 -> showActivate());
                }
                MaterialSwitch sw = findViewById(R.id.screenoff_switch);
                if (sw != null) sw.setEnabled(false);
            });
        }

        findViewById(R.id.clear_log_btn).setOnClickListener(v -> {
            synchronized (logBuffer) { logBuffer.setLength(0); }
            TextView tv = findViewById(R.id.log_text);
            if (tv != null) tv.setText(R.string.shell_logs);
            if (getExternalFilesDir(null) != null) {
                new java.io.File(getExternalFilesDir(null), "shell_logs.txt").delete();
            }
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.view_log_btn).setOnClickListener(v -> {
            if (getExternalFilesDir(null) == null) return;
            java.io.File file = new java.io.File(getExternalFilesDir(null), "shell_logs.txt");
            if (!file.exists()) {
                Toast.makeText(this, "Log file not found", Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder content = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    content.append(line).append("\n");
                }
            } catch (IOException e) {
                Toast.makeText(this, "Error reading log file", Toast.LENGTH_SHORT).show();
            }
            new MaterialAlertDialogBuilder(this).setTitle("Log File: shell_logs.txt")
                .setMessage(content.toString())
                .setPositiveButton(R.string.understand, null)
                .show();
        });

        findViewById(R.id.title_text).setOnClickListener(v -> help());
        findViewById(R.id.activate_button).setOnClickListener(v -> showActivate());
        
        // M3 Surface Background
        int surfaceAttr = com.google.android.material.R.attr.colorSurfaceContainerHigh;
        int surfaceColor = MaterialColors.getColor(this, surfaceAttr, isNight ? 0xff303034 : 0xffe4e2e6);
        float d = getResources().getDisplayMetrics().density;
        ShapeDrawable backgroundDrawable = new ShapeDrawable(new RoundRectShape(new float[]{28*d, 28*d, 28*d, 28*d, 28*d, 28*d, 28*d, 28*d}, null, null));
        backgroundDrawable.getPaint().setColor(surfaceColor);
        View ll = findViewById(R.id.ll);
        if (ll != null) ll.setBackground(backgroundDrawable);

        MaterialSwitch aSwitch = findViewById(R.id.screenoff_switch);
        if (aSwitch != null) {
            aSwitch.setOnCheckedChangeListener((cb, b) -> { if (!isServiceOK || iScreenOff == null) return; try { iScreenOff.setPowerMode(!b); } catch (Exception ignored) {} });
        }
        isExpand = true;
    }

    public static void trySilentActivate(Context context) {
        if (GlobalService.isScreenOffServiceRunning(context)) return;
        unzipFilesStatic(context);
        final String path = context.getExternalFilesDir(null).getPath();
        final String pkg = context.getPackageName();

        new Thread(() -> {
            appendLog(context, "Starting Activation Sequence...");

            // Step 1: Permissions
            String permCmd = String.format(
                "appops set %s SYSTEM_ALERT_WINDOW allow; " +
                "pm grant %s android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; " +
                "pm grant %s android.permission.BLUETOOTH_CONNECT 2>/dev/null; " +
                "pm grant %s android.permission.BLUETOOTH_SCAN 2>/dev/null; " +
                "dumpsys deviceidle whitelist +%s",
                pkg, pkg, pkg, pkg, pkg
            );
            executeCommand(context, permCmd, "PERMISSIONS");

            // Step 2: Accessibility Service
            String accCmd = String.format(
                "curr=$(settings get secure enabled_accessibility_services); " +
                "service=\"%s/.GlobalService\"; " +
                "if [[ \"$curr\" != *\"$service\"* ]]; then " +
                "if [[ \"$curr\" == \"null\" || \"$curr\" == \"\" ]]; then new=\"$service\"; else new=\"$curr:$service\"; fi; " +
                "settings put secure enabled_accessibility_services \"$new\"; " +
                "fi; " +
                "settings put secure accessibility_enabled 1",
                pkg
            );
            executeCommand(context, accCmd, "ACCESSIBILITY");

            // Step 3: Screen Controller (Shell Service)
            String shellCmd = String.format(
                "chmod 777 %s/starter.sh 2>/dev/null; " +
                "sh %s/starter.sh %s",
                path, path, path
            );
            executeCommand(context, shellCmd, "SHELL_SERVICE");

            appendLog(context, "Activation Sequence Completed.");
        }).start();
    }

    private static void executeCommand(Context context, String cmd, String label) {
        appendLog(context, "Executing " + label + "...");
        
        // Try Root first
        boolean rootSuccess = false;
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream o = new DataOutputStream(p.getOutputStream());
            o.writeBytes(cmd + "\nexit\n"); o.flush(); o.close();
            captureOutput(context, p.getInputStream(), "ROOT_" + label + "_STDOUT");
            captureOutput(context, p.getErrorStream(), "ROOT_" + label + "_STDERR");
            int res = p.waitFor();
            if (res == 0) rootSuccess = true;
            appendLog(context, "Root " + label + " finished with code: " + res);
        } catch (Exception e) {
            appendLog(context, "Root " + label + " failed: " + e.getMessage());
        }

        // If root failed or not available, try Shizuku
        if (!rootSuccess) {
            try {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    appendLog(context, "Trying Shizuku for " + label + "...");
                    Process p = Shizuku.newProcess(new String[]{"sh"}, null, null);
                    java.io.OutputStream out = p.getOutputStream();
                    out.write((cmd + "\nexit\n").getBytes()); out.flush(); out.close();
                    captureOutput(context, p.getInputStream(), "SHIZUKU_" + label + "_STDOUT");
                    captureOutput(context, p.getErrorStream(), "SHIZUKU_" + label + "_STDERR");
                    int res = p.waitFor();
                    appendLog(context, "Shizuku " + label + " finished with code: " + res);
                }
            } catch (Exception e) {
                appendLog(context, "Shizuku " + label + " failed: " + e.getMessage());
            }
        }
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
        MaterialButton btn = findViewById(R.id.activate_button); isServiceOK = true;
        if (btn != null) {
            btn.setText(getString(R.string.all_ok)); 
            btn.setTextColor(ContextCompat.getColor(this, R.color.right));
            btn.setOnClickListener(null);
            btn.setOnLongClickListener(v -> {
                try { sendBroadcast(new Intent("intent.screenoff.exit")); if (iScreenOff != null) iScreenOff.closeAndExit(); } catch (Exception ignored) {}
                Toast.makeText(this, R.string.service_closed, Toast.LENGTH_SHORT).show(); finish(); return false;
            });
        }
        MaterialButton stopBtn = findViewById(R.id.stop_button);
        if (stopBtn != null) stopBtn.setVisibility(View.VISIBLE);
        MaterialSwitch sw = findViewById(R.id.screenoff_switch);
        if (sw != null) {
            sw.setEnabled(true);
            updateSwitchState();
        }
    }

    public void finish(View v) { finish(); }

    @Override public void onBackPressed() { super.onBackPressed(); }
    @Override public boolean onKeyDown(int k, KeyEvent ev) {
        if (isExpand) { Toast.makeText(this, String.format(Locale.getDefault(), getString(R.string.key_pressed), KeyEvent.keyCodeToString(k).replace("KEYCODE_", ""), k), Toast.LENGTH_SHORT).show(); return true; }
        if (!isServiceOK) return true;
        MaterialSwitch sw = findViewById(R.id.screenoff_switch);
        if (sw != null) {
            if (k == scrOffKey) sw.setChecked(true); 
            if (k == scrOnKey) sw.setChecked(false);
        }
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

    @Override protected void onDestroy() { 
        if (isPermissionResultListenerRegistered) Shizuku.removeRequestPermissionResultListener(RL); 
        try { unregisterReceiver(mBroadcastReceiver); } catch (Exception ignored) {} 
        activityRef = null;
        super.onDestroy(); 
    }
    public void help() { new MaterialAlertDialogBuilder(this).setTitle(R.string.help_title).setMessage(R.string.help_conntent).setNegativeButton(R.string.understand, null).show(); }
    public void showActivate() {
        checkPermissionsAuto(); unzipFilesStatic(this);
        String cmd = "sh " + getExternalFilesDir(null).getPath() + "/starter.sh";
        new MaterialAlertDialogBuilder(this).setMessage(String.format(getString(R.string.active_steps), cmd)).setTitle(R.string.need_active)
                .setNeutralButton(R.string.copy_cmd, (di, i) -> { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", "adb shell " + cmd)); Toast.makeText(this, String.format(getString(R.string.cmd_copy_finish), cmd), Toast.LENGTH_SHORT).show(); })
                .setNegativeButton(R.string.by_root, (di, i) -> { try { Process p = Runtime.getRuntime().exec("su"); DataOutputStream o = new DataOutputStream(p.getOutputStream()); o.writeBytes(cmd); o.flush(); o.close(); } catch (IOException e) { Toast.makeText(this, R.string.active_failed, Toast.LENGTH_SHORT).show(); } })
                .setPositiveButton(R.string.by_shizuku, (di, i) -> check()).show();
    }
}

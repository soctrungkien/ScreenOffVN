package com.tile.screenoff;

import android.accessibilityservice.AccessibilityServiceInfo;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {
    private boolean isExpand = false, isServiceOK = false, isPermissionResultListenerRegistered = false;
    private boolean updatingSwitches = false, shizukuPermissionRequested = false, preparingActivation = false;
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
    private volatile IBinder controllerBinder;
    private IBinder.DeathRecipient controllerDeathRecipient;
    private static final StringBuilder logBuffer = new StringBuilder();
    private static WeakReference<MainActivity> activityRef = new WeakReference<>(null);

    private static void appendLog(Context context, String log) {
        String formattedLog = "[" + new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date()) + "] " + log;
        synchronized (logBuffer) {
            logBuffer.append(formattedLog).append("\n");
            if (logBuffer.length() > 10000) logBuffer.delete(0, 2000);
        }
        Log.d("ScreenOffShell", log);

        java.io.File logDir = context != null ? context.getExternalFilesDir(null) : null;
        if (logDir != null) {
            final java.io.File logFile = new java.io.File(logDir, "shell_logs.txt");
            new Thread(() -> {
                try (java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
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
            BinderContainer binderContainer = BinderContainer.fromIntent(intent);
            if (binderContainer == null) return;
            IBinder binder = binderContainer.getBinder();
            if (binder == null || !binder.pingBinder()) return;
            bindController(binder);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        DynamicColors.applyToActivitiesIfAvailable(this.getApplication());

        // Crash handler to log everything. Use the application context so it does not leak this Activity.
        final Context appContext = getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            Log.e("ScreenOffCrash", "Uncaught exception", e);
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.printStackTrace(pw);
            String trace = sw.toString();
            java.io.File logDir = appContext.getExternalFilesDir(null);
            if (logDir != null) {
                try {
                    java.io.File logFile = new java.io.File(logDir, "shell_logs.txt");
                    try (java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
                         java.io.BufferedWriter bw = new java.io.BufferedWriter(fw)) {
                        bw.write("\n==================== CRASH LOG DETECTED ====================\n");
                        bw.write("Time: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new java.util.Date()) + "\n");
                        bw.write("Device: " + Build.BRAND + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")\n");
                        bw.write("Message: " + e.getMessage() + "\n\n");
                        bw.write("Stack Trace:\n");
                        bw.write(trace);
                        bw.write("\n============================================================\n");
                        bw.flush();
                    }
                } catch (Exception ignored) {}
            }
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(appContext, "App Error: Check log file", Toast.LENGTH_LONG).show());
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });

        super.onCreate(savedInstanceState);
        
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams windowParams = window.getAttributes();
        windowParams.dimAmount = 0.5f;
        window.setAttributes(windowParams);
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
        ContextCompat.registerReceiver(this, mBroadcastReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkHandler.post(checkRunnable);
        MaterialSwitch s1 = findViewById(R.id.s1);
        if (s1 != null) {
            updatingSwitches = true;
            s1.setChecked(isAccessibilityServiceEnabled(this, GlobalService.class));
            updatingSwitches = false;
            if (s1.isChecked()) {
                MaterialSwitch s8 = findViewById(R.id.s8);
                if (s8 != null && s8.isChecked()) showNet();
            }
        }
        if (isServiceOK && iScreenOff != null) updateSwitchState();
    }

    private void updateSwitchState() {
        if (this.isFinishing()) return;
        try {
            if (iScreenOff != null) {
                int state = iScreenOff.getNowScreenState();
                MaterialSwitch aSwitch = findViewById(R.id.screenoff_switch);
                if (aSwitch != null) {
                    updatingSwitches = true;
                    try {
                        aSwitch.setChecked(state == 1);
                    } finally {
                        updatingSwitches = false;
                    }
                }
            }
        } catch (RemoteException e) {
            onControllerLost();
        }
    }

    private void bindController(IBinder binder) {
        if (controllerBinder == binder) {
            enableScreenOffFunctions();
            return;
        }

        IBinder oldBinder = controllerBinder;
        IBinder.DeathRecipient oldRecipient = controllerDeathRecipient;
        IBinder.DeathRecipient recipient = () -> runOnUiThread(() -> {
            if (controllerBinder == binder) onControllerLost();
        });
        try {
            binder.linkToDeath(recipient, 0);
        } catch (RemoteException e) {
            return;
        }
        controllerBinder = binder;
        controllerDeathRecipient = recipient;
        iScreenOff = IScreenOff.Stub.asInterface(binder);
        if (oldBinder != null && oldRecipient != null) {
            try { oldBinder.unlinkToDeath(oldRecipient, 0); } catch (Exception ignored) {}
        }
        enableScreenOffFunctions();
    }

    private void unlinkControllerBinder() {
        IBinder binder = controllerBinder;
        IBinder.DeathRecipient recipient = controllerDeathRecipient;
        controllerBinder = null;
        controllerDeathRecipient = null;
        if (binder != null && recipient != null) {
            try { binder.unlinkToDeath(recipient, 0); } catch (Exception ignored) {}
        }
    }

    private void onControllerLost() {
        unlinkControllerBinder();
        isServiceOK = false;
        iScreenOff = null;
        MaterialSwitch sw = findViewById(R.id.screenoff_switch);
        if (sw != null) sw.setEnabled(false);
        MaterialButton button = findViewById(R.id.activate_button);
        if (button != null) {
            button.setText(R.string.need_active);
            button.setTextColor(ContextCompat.getColor(this, R.color.wrong));
            button.setOnClickListener(v -> showActivate());
        }
        MaterialButton stopBtn = findViewById(R.id.stop_button);
        if (stopBtn != null) stopBtn.setVisibility(View.GONE);
        TextView tv = findViewById(R.id.title_text);
        if (tv != null) tv.setText(R.string.shortcutoff);
    }

    private void sendExitBroadcast() {
        sendBroadcast(new Intent("intent.screenoff.exit").setPackage(getPackageName()));
    }

    @Override
    protected void onPause() {
        super.onPause();
        checkHandler.removeCallbacks(checkRunnable);
    }

    public static boolean isAccessibilityServiceEnabled(Context context, Class<?> service) {
        ComponentName component = new ComponentName(context, service);
        String expected = component.flattenToString();
        String expectedShort = component.flattenToShortString();
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled != null) {
            for (String name : enabled.split(":")) {
                if (name.equalsIgnoreCase(expected) || name.equalsIgnoreCase(expectedShort)) return true;
            }
        }

        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabledServices == null) return false;
        for (AccessibilityServiceInfo enabledService : enabledServices) {
            if (enabledService.getResolveInfo() == null || enabledService.getResolveInfo().serviceInfo == null) continue;
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
        if (tv != null) tv.setText(avalible ? sb.toString() : "No network available");
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
            if (updatingSwitches) return;
            if (isChecked && !isServiceOK) {
                updatingSwitches = true;
                cb.setChecked(false);
                updatingSwitches = false;
                Toast.makeText(this, R.string.active_first, Toast.LENGTH_SHORT).show();
                return;
            }
            if (isChecked) {
                if (s8.isChecked()) showNet();
            } else {
                TextView tv = findViewById(R.id.title_text);
                if (tv != null) tv.setText(R.string.shortcutoff);
                sendExitBroadcast();
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
                try {
                    int val = Integer.parseInt(ed.getText().toString().trim());
                    if (val >= 0 && val <= 30) {
                        sp.edit().putInt("sensity", val).apply();
                        sd.setProgress(val);
                    }
                } catch (NumberFormatException ignored) {}
            }
            return false;
        });
        e1.setEnabled(s7.isChecked()); e2.setEnabled(s7.isChecked());
        scrOffKey = sp.getInt("scrOffKey", 25); scrOnKey = sp.getInt("scrOnKey", 24);
        e1.setText(String.valueOf(scrOffKey)); e2.setText(String.valueOf(scrOnKey));
        e1.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int i1, int i2) {}
            @Override public void onTextChanged(CharSequence s, int i, int i1, int i2) {
                if (s.length() == 0) return;
                try {
                    scrOffKey = Integer.parseInt(s.toString());
                    sp.edit().putInt("scrOffKey", scrOffKey).apply();
                } catch (NumberFormatException ignored) {}
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        e2.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int i1, int i2) {}
            @Override public void onTextChanged(CharSequence s, int i, int i1, int i2) {
                if (s.length() == 0) return;
                try {
                    scrOnKey = Integer.parseInt(s.toString());
                    sp.edit().putInt("scrOnKey", scrOnKey).apply();
                } catch (NumberFormatException ignored) {}
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        MaterialButton stopBtn = findViewById(R.id.stop_button);
        if (stopBtn != null) {
            stopBtn.setOnClickListener(v -> {
                try {
                    sendExitBroadcast();
                    if (iScreenOff != null) iScreenOff.closeAndExit();
                } catch (Exception ignored) {}
                onControllerLost();
            });
        }

        findViewById(R.id.clear_log_btn).setOnClickListener(v -> {
            synchronized (logBuffer) { logBuffer.setLength(0); }
            TextView tv = findViewById(R.id.log_text);
            if (tv != null) tv.setText(R.string.shell_logs);
            java.io.File logDir = getExternalFilesDir(null);
            if (logDir != null) {
                new java.io.File(logDir, "shell_logs.txt").delete();
            }
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.view_log_btn).setOnClickListener(v -> {
            java.io.File logDir = getExternalFilesDir(null);
            if (logDir == null) return;
            java.io.File file = new java.io.File(logDir, "shell_logs.txt");
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
            aSwitch.setOnCheckedChangeListener((cb, b) -> {
                if (updatingSwitches || !isServiceOK || iScreenOff == null) return;
                try { iScreenOff.setPowerMode(!b); } catch (Exception ignored) {}
            });
        }
        isExpand = true;
    }

    public static void trySilentActivate(Context context) {
        trySilentActivate(context, null);
    }

    public static void trySilentActivate(Context context, Runnable onComplete) {
        if (GlobalService.isScreenOffServiceRunning()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;

        new Thread(() -> {
            try {
                if (!unzipFilesStatic(appContext)) {
                    appendLog(appContext, "Activation failed: external storage unavailable.");
                    return;
                }
                java.io.File dir = appContext.getExternalFilesDir(null);
                if (dir == null) {
                    appendLog(appContext, "Activation failed: external storage unavailable.");
                    return;
                }
                final String path = dir.getPath();
                final String pkg = appContext.getPackageName();
                appendLog(appContext, "Starting Activation Sequence...");
                String cmd = "chmod 755 \"" + path + "/starter.sh\" 2>/dev/null; "
                        + "sh \"" + path + "/starter.sh\" \"" + path + "\" \"" + pkg + "\"";
                if (executeCommand(appContext, cmd, "ACTIVATION")) {
                    appendLog(appContext, "Activation Sequence Completed.");
                } else {
                    appendLog(appContext, "Activation failed: root/Shizuku is unavailable.");
                }
            } finally {
                if (onComplete != null) onComplete.run();
            }
        }, "ScreenOffActivation").start();
    }

    private static boolean executeCommand(Context context, String cmd, String label) {
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
                    Process p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
                    p.getOutputStream().close();
                    captureOutput(context, p.getInputStream(), "SHIZUKU_" + label + "_STDOUT");
                    captureOutput(context, p.getErrorStream(), "SHIZUKU_" + label + "_STDERR");
                    int res = p.waitFor();
                    appendLog(context, "Shizuku " + label + " finished with code: " + res);
                    return res == 0;
                }
            } catch (Exception e) {
                appendLog(context, "Shizuku " + label + " failed: " + e.getMessage());
            }
        }
        return rootSuccess;
    }

    public static synchronized boolean unzipFilesStatic(Context context) {
        java.io.File dir = context.getExternalFilesDir(null);
        if (dir == null) return false;
        if (!dir.exists() && !dir.mkdirs()) return false;
        String path = dir.getPath();
        boolean starterOk = false;
        boolean dexOk = false;

        try (InputStream is = context.getAssets().open("starter.sh");
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
            String script = new String(bos.toByteArray(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
            try (FileOutputStream fos = new FileOutputStream(path + "/starter.sh")) {
                fos.write(script.getBytes(StandardCharsets.UTF_8));
            }
            starterOk = true;
        } catch (Exception ignored) {}

        try (ZipFile zipFile = new ZipFile(context.getPackageResourcePath())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if ("classes.dex".equals(entry.getName())) {
                    try (InputStream is = zipFile.getInputStream(entry); FileOutputStream fos = new FileOutputStream(path + "/ScreenController.dex")) {
                        byte[] buf = new byte[8192];
                        int l;
                        while ((l = is.read(buf)) > 0) fos.write(buf, 0, l);
                    }
                    dexOk = true;
                    break;
                }
            }
        } catch (Exception ignored) {}

        try {
            String pkg = context.getPackageName();
            try (FileOutputStream off = new FileOutputStream(path + "/scroff.sh");
                 FileOutputStream on = new FileOutputStream(path + "/scron.sh")) {
                off.write(("am broadcast -a action.ScrOff -p " + pkg + " --ez state true\n").getBytes(StandardCharsets.UTF_8));
                on.write(("am broadcast -a action.ScrOff -p " + pkg + " --ez state false\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}

        return starterOk && dexOk;
    }

    private void tryAutoActivate() {
        if (isServiceOK && isAccessibilityServiceEnabled(this, GlobalService.class)) return;
        trySilentActivate(this);
    }

    public void enableScreenOffFunctions() {
        MaterialButton btn = findViewById(R.id.activate_button); isServiceOK = true;
        if (btn != null) {
            btn.setText(getString(R.string.all_ok));
            btn.setTextColor(ContextCompat.getColor(this, R.color.right));
            btn.setOnClickListener(null);
            btn.setOnLongClickListener(v -> {
                try { sendExitBroadcast(); if (iScreenOff != null) iScreenOff.closeAndExit(); } catch (Exception ignored) {}
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
        MaterialSwitch s1 = findViewById(R.id.s1);
        if (s1 != null) {
            updatingSwitches = true;
            s1.setChecked(isAccessibilityServiceEnabled(this, GlobalService.class));
            updatingSwitches = false;
        }
    }

    public void finish(View v) { finish(); }

    @Override public void onBackPressed() { super.onBackPressed(); }
    @Override public boolean onKeyDown(int k, KeyEvent ev) {
        if (k == KeyEvent.KEYCODE_BACK) return super.onKeyDown(k, ev);
        MaterialSwitch sw = findViewById(R.id.screenoff_switch);
        if (isServiceOK && sw != null && (k == scrOffKey || k == scrOnKey)) {
            if (k == scrOffKey) sw.setChecked(false);
            else sw.setChecked(true);
            return true;
        }
        if (isExpand) {
            Toast.makeText(this, String.format(Locale.getDefault(), getString(R.string.key_pressed), KeyEvent.keyCodeToString(k).replace("KEYCODE_", ""), k), Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onKeyDown(k, ev);
    }

    private final Shizuku.OnRequestPermissionResultListener RL = (rc, res) -> {
        shizukuPermissionRequested = false;
        if (res == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(this::tryAutoActivate);
        } else {
            runOnUiThread(() -> Toast.makeText(this, R.string.active_failed, Toast.LENGTH_SHORT).show());
        }
    };

    private void check() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            tryAutoActivate();
            return;
        }

        boolean available;
        try {
            available = Shizuku.pingBinder();
        } catch (Throwable e) {
            available = false;
        }
        if (!available) {
            Toast.makeText(this, R.string.shizuku_notrun, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isPermissionResultListenerRegistered) {
            try {
                Shizuku.addRequestPermissionResultListener(RL);
                isPermissionResultListenerRegistered = true;
            } catch (Exception e) {
                appendLog(this, "Shizuku listener registration failed: " + e.getMessage());
            }
        }

        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                tryAutoActivate();
                return;
            }
        } catch (Exception e) {
            try {
                if (checkSelfPermission("moe.shizuku.manager.permission.API_V23") == PackageManager.PERMISSION_GRANTED) {
                    tryAutoActivate();
                    return;
                }
            } catch (Exception ignored) {}
            Toast.makeText(this, R.string.shizuku_notrun, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!shizukuPermissionRequested) {
            shizukuPermissionRequested = true;
            try {
                Shizuku.requestPermission(0);
            } catch (Exception e) {
                shizukuPermissionRequested = false;
                Toast.makeText(this, R.string.shizuku_notrun, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override protected void onDestroy() {
        checkHandler.removeCallbacks(checkRunnable);
        if (isPermissionResultListenerRegistered) {
            try { Shizuku.removeRequestPermissionResultListener(RL); } catch (Exception ignored) {}
            isPermissionResultListenerRegistered = false;
        }
        try { unregisterReceiver(mBroadcastReceiver); } catch (Exception ignored) {}
        unlinkControllerBinder();
        iScreenOff = null;
        if (activityRef.get() == this) activityRef = new WeakReference<>(null);
        super.onDestroy();
    }
    public void help() { new MaterialAlertDialogBuilder(this).setTitle(R.string.help_title).setMessage(R.string.help_conntent).setNegativeButton(R.string.understand, null).show(); }
    public void showActivate() {
        if (preparingActivation) return;
        preparingActivation = true;
        final Context appContext = getApplicationContext();

        new Thread(() -> {
            final boolean extracted = unzipFilesStatic(appContext);
            runOnUiThread(() -> {
                preparingActivation = false;
                if (isFinishing() || isDestroyed()) return;
                if (!extracted) {
                    Toast.makeText(this, R.string.active_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) return;
                String path = dir.getPath();
                String cmd = "sh \"" + path + "/starter.sh\" \"" + path + "\" \"" + getPackageName() + "\"";
                new MaterialAlertDialogBuilder(this).setMessage(String.format(getString(R.string.active_steps), cmd)).setTitle(R.string.need_active)
                        .setNeutralButton(R.string.copy_cmd, (di, i) -> { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", "adb shell " + cmd)); Toast.makeText(this, String.format(getString(R.string.cmd_copy_finish), cmd), Toast.LENGTH_SHORT).show(); })
                        .setNegativeButton(R.string.by_root, (di, i) -> trySilentActivate(this))
                        .setPositiveButton(R.string.by_shizuku, (di, i) -> check()).show();
            });
        }, "ScreenOffPrepare").start();
    }
}

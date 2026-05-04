package com.tile.screenoff;

import static java.lang.Math.abs;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class GlobalService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private ImageView view;
    SharedPreferences sp;
    private boolean exist = false, canmove, doubleTap, shake, volume, netControl;
    int size, sensity, scrOnKey, scrOffKey;
    private int SCREEN_WIDTH, SCREEN_HEIGHT;
    OrientationEventListener listener;
    IScreenOff iScreenOff = null;

    public static boolean isScreenOffServiceRunning(Context context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return false;
        List<ActivityManager.RunningServiceInfo> runningServices = activityManager.getRunningServices(Integer.MAX_VALUE);
        if (runningServices == null || runningServices.isEmpty()) return false;
        for (ActivityManager.RunningServiceInfo serviceInfo : runningServices) {
            if (GlobalService.class.getName().equals(serviceInfo.service.getClassName())) return true;
        }
        return false;
    }

    final BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case "intent.screenoff.sendBinder":
                    BinderContainer binderContainer = intent.getParcelableExtra("binder");
                    if (binderContainer == null) break;
                    IBinder binder = binderContainer.getBinder();
                    if (binder == null || !binder.pingBinder()) break;
                    iScreenOff = IScreenOff.Stub.asInterface(binder);
                    floatWindow();
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    try {
                        if (iScreenOff != null) iScreenOff.updateNowScreenState(false);
                    } catch (Exception e) { Log.e("GlobalService", "Update state failed", e); }
                    if (view != null) view.setKeepScreenOn(false);
                    if (listener != null) listener.disable();
                    if (exist && windowManager != null && view != null) {
                        try { windowManager.updateViewLayout(view, params); } catch (Exception ignored) {}
                    }
                    break;
                case Intent.ACTION_SCREEN_ON:
                case Intent.ACTION_USER_PRESENT:
                    try {
                        if (iScreenOff != null) iScreenOff.updateNowScreenState(true);
                    } catch (Exception e) { Log.e("GlobalService", "Update state failed", e); }
                    break;
                case "action.ScrOff":
                    if (intent.hasExtra("state")) screenoff(intent.getBooleanExtra("state", true));
                    else {
                        try {
                            if (iScreenOff != null) screenoff(iScreenOff.getNowScreenState() == 1);
                        } catch (Exception ignored) {}
                    }
                    break;
                case "intent.screenoff.exit":
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) disableSelf();
                    else stopSelf();
                    break;
            }
        }
    };

    @Override public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {}
    @Override public void onInterrupt() {}

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        if (s == null || s.startsWith("x") || s.startsWith("y")) return;
        if (view == null || params == null) return;

        view.setVisibility(getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT && sharedPreferences.getBoolean("land", false) ? View.GONE : View.VISIBLE);
        size = (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sharedPreferences.getInt("size", 50), getResources().getDisplayMetrics()));
        params.height = size; params.width = size;
        params.alpha = sharedPreferences.getInt("tran", 90) * 0.01f;
        canmove = sharedPreferences.getBoolean("canmove", true);
        doubleTap = sharedPreferences.getBoolean("doubleTap", false);
        shake = sharedPreferences.getBoolean("shake", false);
        sensity = sharedPreferences.getInt("sensity", 10);
        volume = sharedPreferences.getBoolean("volume", false);
        scrOnKey = sharedPreferences.getInt("scrOnKey", 24);
        scrOffKey = sharedPreferences.getInt("scrOffKey", 25);
        netControl = sharedPreferences.getBoolean("net", false);
        if (netControl) startServer(); else stopServer();
        floatWindow();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sp = getSharedPreferences("s", 0);
        sensity = sp.getInt("sensity", 10);
        listener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;
                boolean wake = ((orientation >= 360 - sensity || orientation <= sensity) || orientation >= 90 - sensity && orientation <= 90 + sensity) || orientation >= 180 - sensity && orientation <= 180 + sensity || orientation >= 270 - sensity && orientation <= 270 + sensity;
                if (wake) { screenoff(false); this.disable(); }
            }
        };
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        GetWidthHeight();
        size = (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sp.getInt("size", 50), getResources().getDisplayMetrics()));

        params = new WindowManager.LayoutParams(size, size, Build.VERSION.SDK_INT >= 22 ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.RGBA_8888);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        params.alpha = sp.getInt("tran", 90) * 0.01f;
        boolean isLand = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        params.x = sp.getInt("x" + (isLand ? "1" : "2"), 0); params.y = sp.getInt("y" + (isLand ? "1" : "2"), 0);
        
        view = new ImageView(this);
        ShapeDrawable oval = new ShapeDrawable(new OvalShape());
        oval.getPaint().setColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? getColor(R.color.bg) : 0xffc1e8ff);
        view.setBackground(oval); view.setImageResource(R.drawable.fw);
        
        canmove = sp.getBoolean("canmove", true); doubleTap = sp.getBoolean("doubleTap", false);
        shake = sp.getBoolean("shake", false); volume = sp.getBoolean("volume", false);
        scrOnKey = sp.getInt("scrOnKey", 24); scrOffKey = sp.getInt("scrOffKey", 25);
        netControl = sp.getBoolean("net", false);
        if (netControl) startServer();
        
        view.setOnTouchListener(new View.OnTouchListener() {
            int lastX = 0, lastY = 0, paramX = 0, paramY = 0;
            long lastDown = 0, lastUp = 0; boolean moved = false;
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (params == null || windowManager == null) return false;
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = (int) motionEvent.getRawX(); lastY = (int) motionEvent.getRawY();
                        paramX = params.x; paramY = params.y; params.alpha = 1;
                        new Handler().postDelayed(() -> { if (System.currentTimeMillis() - lastUp >= 400 && !moved) screenoff(true); }, 400);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) motionEvent.getRawX() - lastX, dy = (int) motionEvent.getRawY() - lastY;
                        if (abs(dx) > 4 || abs(dy) > 4) moved = true;
                        if (!canmove) return true;
                        params.x = paramX + dx; params.y = paramY + dy;
                        try { windowManager.updateViewLayout(view, params); } catch (Exception ignored) {}
                        break;
                    case MotionEvent.ACTION_UP:
                        lastUp = System.currentTimeMillis(); params.alpha = sp.getInt("tran", 90) * 0.01f;
                        params.x = (params.x > (SCREEN_WIDTH - size) * 0.43) ? (SCREEN_WIDTH - size) / 2 : ((params.x < (SCREEN_WIDTH - size) * -0.43) ? -(SCREEN_WIDTH - size) / 2 : params.x);
                        params.y = Math.min(Math.max(params.y, -(SCREEN_HEIGHT - size) / 2), (SCREEN_HEIGHT - size) / 2);
                        try { windowManager.updateViewLayout(view, params); } catch (Exception ignored) {}
                        moved = false;
                        boolean isLand = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                        sp.edit().putInt("x" + (isLand ? "1" : "2"), params.x).putInt("y" + (isLand ? "1" : "2"), params.y).apply();
                        break;
                }
                if (doubleTap && (motionEvent.getAction() == MotionEvent.ACTION_DOWN || motionEvent.getAction() == MotionEvent.ACTION_OUTSIDE)) {
                    if (System.currentTimeMillis() - lastDown <= 400) screenoff(false);
                    lastDown = System.currentTimeMillis();
                }
                return false;
            }
        });
        view.setVisibility(getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT && sp.getBoolean("land", false) ? View.GONE : View.VISIBLE);
        floatWindow();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT); filter.addAction("action.ScrOff");
        filter.addAction("intent.screenoff.sendBinder"); filter.addAction("intent.screenoff.exit");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(myReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(myReceiver, filter);
        sp.registerOnSharedPreferenceChangeListener(this);
    }

    void screenoff(Boolean bb) {
        try {
            if (iScreenOff == null || iScreenOff.getNowScreenState() == 0) return;
            iScreenOff.setPowerMode(bb);
            if (view != null) view.setKeepScreenOn(bb);
            if (shake && bb && listener != null) listener.enable();
        } catch (Exception e) { Log.e("GlobalService", "Screen control failed", e); }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!volume || event.getAction() == KeyEvent.ACTION_UP || iScreenOff == null) return super.onKeyEvent(event);
        try {
            final int keycode = event.getKeyCode();
            final int nowState = iScreenOff.getNowScreenState();
            if (keycode == scrOffKey && nowState == 1) { screenoff(true); return true; }
            if (keycode == scrOnKey && (nowState == 2 || nowState == 0)) { screenoff(false); return true; }
        } catch (Exception e) { Log.e("GlobalService", "KeyEvent processing failed", e); }
        return super.onKeyEvent(event);
    }

    public void floatWindow() {
        if (view == null || windowManager == null) return;
        try {
            if (false) { // Nếu sau này cần hiện phím ảo
                if (!exist) { windowManager.addView(view, params); exist = true; } 
                else windowManager.updateViewLayout(view, params);
            } else {
                windowManager.removeViewImmediate(view);
                exist = false;
            }
        } catch (Exception ignored) {}
    }

    void GetWidthHeight() {
        if (windowManager == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        SCREEN_WIDTH = metrics.widthPixels; SCREEN_HEIGHT = metrics.heightPixels;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (windowManager == null || sp == null || view == null || params == null) return;
        boolean isLand = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT;
        if (sp.getBoolean("land", false)) view.setVisibility(isLand ? View.GONE : View.VISIBLE);
        GetWidthHeight();
        params.x = sp.getInt("x" + (isLand ? "1" : "2"), 0);
        params.y = sp.getInt("y" + (isLand ? "1" : "2"), 0);
        if (exist) try { windowManager.updateViewLayout(view, params); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(myReceiver); } catch (Exception ignored) {}
        try { if (windowManager != null && view != null) windowManager.removeViewImmediate(view); } catch (Exception ignored) {}
        exist = false;
        if (listener != null) listener.disable();
        if (sp != null) sp.unregisterOnSharedPreferenceChangeListener(this);
        stopServer();
        super.onDestroy();
    }

    public static int port = 20000;
    private SimpleTcpServer server;

    private void startServer() {
        if (server != null) return;
        server = new SimpleTcpServer((data, socket) -> {
            HttpRequestParser parser = new HttpRequestParser();
            parser.add(data);
            HttpRequest request = parser.parse();
            if (request != null) handleRequest(request, socket);
        }, port);
        server.start();
    }

    void stopServer() {
        if (server != null) { server.stop(); server = null; }
    }

    private void handleRequest(HttpRequest request, Socket socket) {
        if (server == null || !netControl) return;
        String target = request.getRequestTarget();
        if (target.equals("/") || target.equals("/index.html")) sendHtmlResponse(socket, buildIndexHtml(), "200 OK");
        else if (target.equals("/status")) sendJsonResponse(socket, buildStatusJson());
        else if (target.equals("/favicon.ico") || target.equals("/favicon.png")) {
            byte[] icon = getRoundedAppIcon();
            if (icon != null) sendPngResponse(socket, icon);
            else sendHtmlResponse(socket, "", "404 Not Found");
        } else {
            try {
                if (iScreenOff == null) { sendHtmlResponse(socket, "Service not connected", "503 Service Unavailable"); return; }
                if (target.startsWith("/1?")) { iScreenOff.setPowerMode(false); sendHtmlResponse(socket, "", "200 OK"); }
                else if (target.startsWith("/2?")) { iScreenOff.setPowerMode(true); sendHtmlResponse(socket, "", "200 OK"); }
                else sendHtmlResponse(socket, build404Html(), "404 Not Found");
            } catch (Exception ignored) {}
        }
    }

    private String buildStatusJson() {
        int stateInt = -1;
        try { if (iScreenOff != null) stateInt = iScreenOff.getNowScreenState(); } catch (RemoteException ignored) {}
        String nowState = "Không xác định"; boolean isOffMode = false;
        switch (stateInt) {
            case 1: nowState = "Đang bật"; isOffMode = false; break;
            case 0: nowState = "Đã tắt (Hệ thống)"; isOffMode = true; break;
            case 2: nowState = "Đang tắt (Chạy ngầm)"; isOffMode = true; break;
        }
        return "{\"state\": \"" + nowState + "\", \"isOff\": " + isOffMode + "}";
    }

    private String buildIndexHtml() {
        String nowState = "Không xác định"; boolean isOffMode = false;
        try {
            if (iScreenOff != null) {
                int stateInt = iScreenOff.getNowScreenState();
                switch (stateInt) {
                    case 1: nowState = "Đang bật"; isOffMode = false; break;
                    case 0: nowState = "Đã tắt (Hệ thống)"; isOffMode = true; break;
                    case 2: nowState = "Đang tắt (Chạy ngầm)"; isOffMode = true; break;
                }
            }
        } catch (RemoteException ignored) {}
        String html = loadHtml("index.html");
        if (html == null) return "";

        String ip = "127.0.0.1";
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                java.net.NetworkInterface intf = en.nextElement();
                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        ip = inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        String serverUrl = "http://" + ip + ":" + port + "/";

        String btName = Build.BRAND;
        try {
            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    String name = adapter.getName();
                    if (name != null) btName = name;
                }
            }
        } catch (Exception ignored) {}

        return html.replace("{{brand}}", btName)
                .replace("{{device}}", Build.MODEL + " Android " + Build.VERSION.RELEASE)
                .replace("{{state}}", nowState)
                .replace("{{checked}}", isOffMode ? "selected" : "")
                .replace("{{url}}", serverUrl);
    }

    private String build404Html() { return loadHtml("404.html"); }

    private void sendHtmlResponse(Socket socket, String html, String responseCode) {
        int length = (html != null) ? html.getBytes().length : 0;
        String header = "HTTP/1.1 " + responseCode + "\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: " + length + "\r\n\r\n";
        server.sendResponse(socket, (header + (html != null ? html : "")).getBytes());
    }

    private void sendJsonResponse(Socket socket, String json) {
        int length = json.getBytes().length;
        String header = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: " + length + "\r\n\r\n";
        server.sendResponse(socket, (header + json).getBytes());
    }

    private void sendPngResponse(Socket socket, byte[] png) {
        String header = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " + png.length + "\r\n\r\n";
        byte[] hBytes = header.getBytes(); byte[] output = new byte[hBytes.length + png.length];
        System.arraycopy(hBytes, 0, output, 0, hBytes.length); System.arraycopy(png, 0, output, hBytes.length, png.length);
        server.sendResponse(socket, output);
    }

    private String loadHtml(String fileName) {
        byte[] binary = loadBinary(fileName); return (binary == null) ? null : new String(binary);
    }

    private byte[] getRoundedAppIcon() {
        try {
            Drawable d = getPackageManager().getApplicationIcon(getPackageName());
            Bitmap b;
            if (d instanceof BitmapDrawable) b = ((BitmapDrawable) d).getBitmap();
            else {
                b = Bitmap.createBitmap(d.getIntrinsicWidth(), d.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b); d.setBounds(0, 0, c.getWidth(), c.getHeight()); d.draw(c);
            }
            int s = Math.min(b.getWidth(), b.getHeight());
            Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out); Paint p = new Paint(); p.setAntiAlias(true);
            canvas.drawRoundRect(new RectF(0, 0, s, s), s * 0.2f, s * 0.2f, p);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(b, null, new Rect(0, 0, s, s), p);
            ByteArrayOutputStream os = new ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.PNG, 100, os);
            return os.toByteArray();
        } catch (Exception e) { return null; }
    }

    private byte[] loadBinary(String fileName) {
        try (InputStream is = getAssets().open(fileName); ByteArrayOutputStream bos = new ByteArrayOutputStream(); BufferedInputStream bis = new BufferedInputStream(is, 1024*1024)) {
            byte[] chunk = new byte[1024*1024]; int len;
            while ((len = bis.read(chunk)) > 0) bos.write(chunk, 0, len);
            return bos.toByteArray();
        } catch (IOException e) { return null; }
    }
}

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
import java.util.ArrayList;
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
        List<ActivityManager.RunningServiceInfo> runningServices = activityManager.getRunningServices(Integer.MAX_VALUE);
        if (runningServices == null || runningServices.isEmpty()) {
            return false;
        }
        for (ActivityManager.RunningServiceInfo serviceInfo : runningServices) {
            if (GlobalService.class.getName().equals(serviceInfo.service.getClassName())) {
                return true;
            }
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
                    } catch (RemoteException e) {
                        Log.e("GlobalService", "RemoteException on ACTION_SCREEN_OFF", e);
                    }
                    if (view != null) view.setKeepScreenOn(false);
                    if (listener != null) listener.disable();
                    if (exist && windowManager != null && view != null) windowManager.updateViewLayout(view, params);
                    break;
                case Intent.ACTION_SCREEN_ON:
                case Intent.ACTION_USER_PRESENT:
                    try {
                        if (iScreenOff != null) iScreenOff.updateNowScreenState(true);
                    } catch (RemoteException e) {
                        Log.e("GlobalService", "RemoteException on ACTION_SCREEN_ON", e);
                    }
                    break;
                case "action.ScrOff":
                    if (intent.hasExtra("state")) {
                        screenoff(intent.getBooleanExtra("state", true));
                    } else {
                        try {
                            if (iScreenOff != null) {
                                screenoff(iScreenOff.getNowScreenState() == 1);
                            }
                        } catch (RemoteException e) {
                            Log.e("GlobalService", "RemoteException on action.ScrOff", e);
                        }
                    }
                    break;
                case "intent.screenoff.exit":
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        disableSelf();
                    } else {
                        stopSelf();
                    }
                    break;
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        if (s == null || s.startsWith("x") || s.startsWith("y")) return;
        if (view == null || params == null) return;

        view.setVisibility(getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT && sharedPreferences.getBoolean("land", false) ? View.GONE : View.VISIBLE);
        size = (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sharedPreferences.getInt("size", 50), getResources().getDisplayMetrics()));
        params.height = size;
        params.width = size;
        params.alpha = sharedPreferences.getInt("tran", 90) * 0.01f;
        canmove = sharedPreferences.getBoolean("canmove", true);
        doubleTap = sharedPreferences.getBoolean("doubleTap", false);
        shake = sharedPreferences.getBoolean("shake", false);
        sensity = sharedPreferences.getInt("sensity", 10);
        volume = sharedPreferences.getBoolean("volume", false);
        scrOnKey = sharedPreferences.getInt("scrOnKey", 24);
        scrOffKey = sharedPreferences.getInt("scrOffKey", 25);
        netControl = sharedPreferences.getBoolean("net", false);
        if (netControl) startServer();
        else stopServer();
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
                if (wake) {
                    screenoff(false);
                    this.disable();
                }
            }
        };
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        GetWidthHeight();
        size = (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sp.getInt("size", 50), getResources().getDisplayMetrics()));

        params = new WindowManager.LayoutParams(size, size, Build.VERSION.SDK_INT >= 22 ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.RGBA_8888);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        params.alpha = sp.getInt("tran", 90) * 0.01f;
        boolean isLand = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        params.x = sp.getInt("x" + (isLand ? "1" : "2"), 0);
        params.y = sp.getInt("y" + (isLand ? "1" : "2"), 0);
        view = new ImageView(this);
        ShapeDrawable oval = new ShapeDrawable(new OvalShape());
        oval.getPaint().setColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? getColor(R.color.bg) : 0xffc1e8ff);
        view.setBackground(oval);
        view.setImageResource(R.drawable.fw);
        canmove = sp.getBoolean("canmove", true);
        doubleTap = sp.getBoolean("doubleTap", false);
        shake = sp.getBoolean("shake", false);
        volume = sp.getBoolean("volume", false);
        scrOnKey = sp.getInt("scrOnKey", 24);
        scrOffKey = sp.getInt("scrOffKey", 25);
        netControl = sp.getBoolean("net", false);
        if (netControl) startServer();
        view.setOnTouchListener(new View.OnTouchListener() {
            int lastX = 0, lastY = 0, paramX = 0, paramY = 0;
            long lastDown = 0, lastUp = 0;
            boolean moved = false;

            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = (int) motionEvent.getRawX();
                        lastY = (int) motionEvent.getRawY();
                        paramX = params.x;
                        paramY = params.y;
                        params.alpha = 1;
                        new Handler().postDelayed(() -> {
                            if (System.currentTimeMillis() - lastUp >= 400 && !moved) {
                                screenoff(true);
                            }
                        }, 400);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) motionEvent.getRawX() - lastX;
                        int dy = (int) motionEvent.getRawY() - lastY;
                        if (abs(dx) > 4 || abs(dy) > 4) moved = true;
                        if (!canmove) return true;
                        params.x = paramX + dx;
                        params.y = paramY + dy;
                        if (windowManager != null && view != null) windowManager.updateViewLayout(view, params);
                        break;
                    case MotionEvent.ACTION_UP:
                        lastUp = System.currentTimeMillis();
                        params.alpha = sp.getInt("tran", 90) * 0.01f;
                        params.x = (params.x > (SCREEN_WIDTH - size) * 0.43) ? (SCREEN_WIDTH - size) / 2 : ((params.x < (SCREEN_WIDTH - size) * -0.43) ? -(SCREEN_WIDTH - size) / 2 : params.x);
                        params.y = Math.min(Math.max(params.y, -(SCREEN_HEIGHT - size) / 2), (SCREEN_HEIGHT - size) / 2);
                        if (windowManager != null && view != null) windowManager.updateViewLayout(view, params);
                        moved = false;
                        boolean isLand = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                        sp.edit().putInt("x" + (isLand ? "1" : "2"), params.x).putInt("y" + (isLand ? "1" : "2"), params.y).apply();
                        break;
                }
                if (!doubleTap) return false;
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_OUTSIDE:
                        if (System.currentTimeMillis() - lastDown <= 400) screenoff(false);
                        lastDown = System.currentTimeMillis();
                        break;
                }
                return false;
            }
        });
        view.setVisibility(getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT && sp.getBoolean("land", false) ? View.GONE : View.VISIBLE);
        floatWindow();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction("action.ScrOff");
        filter.addAction("intent.screenoff.sendBinder");
        filter.addAction("intent.screenoff.exit");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(myReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(myReceiver, filter);
        }
        sp.registerOnSharedPreferenceChangeListener(this);
    }

    void screenoff(Boolean bb) {
        try {
            if (iScreenOff == null || iScreenOff.getNowScreenState() == 0) return;
            iScreenOff.setPowerMode(bb);
            if (view != null) view.setKeepScreenOn(bb);
            if (shake && bb && listener != null) listener.enable();
        } catch (RemoteException e) {
            Log.e("GlobalService", "RemoteException on screenoff", e);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!volume || event.getAction() == KeyEvent.ACTION_UP || iScreenOff == null)
            return super.onKeyEvent(event);
        try {
            final int keycode = event.getKeyCode();
            final int nowState = iScreenOff.getNowScreenState();
            if (keycode == scrOffKey && nowState == 1) {
                screenoff(true);
                return true;
            }
            if (keycode == scrOnKey && (nowState == 2 || nowState == 0)) {
                screenoff(false);
                return true;
            }
        } catch (RemoteException e) {
            Log.e("GlobalService", "RemoteException on onKeyEvent", e);
        }
        return super.onKeyEvent(event);
    }

    public void floatWindow() {
        if (false) {
            if (!exist) {
                windowManager.addView(view, params);
                exist = true;
            } else {
                windowManager.updateViewLayout(view, params);
            }
        } else {
            if (view != null) {
                try {
                    windowManager.removeViewImmediate(view);
                } catch (Exception ignored) {}
                exist = false;
            }
        }
    }

    void GetWidthHeight() {
        if (windowManager == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        SCREEN_WIDTH = metrics.widthPixels;
        SCREEN_HEIGHT = metrics.heightPixels;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (windowManager == null || sp == null || view == null) return;
        boolean isLand = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT;
        if (sp.getBoolean("land", false))
            view.setVisibility(isLand ? View.GONE : View.VISIBLE);
        GetWidthHeight();
        params.x = sp.getInt("x" + (isLand ? "1" : "2"), 0);
        params.y = sp.getInt("y" + (isLand ? "1" : "2"), 0);
        ShapeDrawable oval = new ShapeDrawable(new OvalShape());
        oval.getPaint().setColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? getColor(R.color.bg) : 0xffc1e8ff);
        view.setBackground(oval);
        view.setImageResource(R.drawable.fw);
        if (exist) windowManager.updateViewLayout(view, params);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(myReceiver);
        } catch (Exception ignored) {}
        try {
            if (windowManager != null && view != null) windowManager.removeViewImmediate(view);
        } catch (Exception ignored) {}
        exist = false;
        if (listener != null) listener.disable();
        if (sp != null) sp.unregisterOnSharedPreferenceChangeListener(this);
        if (netControl) stopServer();
        super.onDestroy();
    }

    public static int port = 20000;
    private SimpleTcpServer server;

    private void startServer() {
        if (server != null) return;
        server = new SimpleTcpServer(new SimpleTcpServer.TcpConnectionListener() {
            private final HttpRequestParser parser = new HttpRequestParser();
            @Override
            public void onReceive(final byte[] data) {
                parser.add(data);
                final HttpRequest request = parser.parse();
                if (request != null) {
                    output(request);
                    parser.clear();
                }
            }
            @Override
            public void onResponseSent() {
                if (server != null) server.restart();
            }
        }, port);
        server.start();
    }

    void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private void output(HttpRequest request) {
        if (server == null || !netControl) return;
        String target = request.getRequestTarget();
        if (target.equals("/") || target.equals("/index.html")) {
            outputHtml(buildIndexHtml(), "200 OK");
        } else if (target.equals("/status")) {
            outputJson(buildStatusJson());
        } else if (target.equals("/favicon.ico") || target.equals("/favicon.png")) {
            byte[] icon = getRoundedAppIcon();
            if (icon != null) outputPng(icon);
            else outputHtml("", "404 Not Found");
        } else {
            try {
                if (iScreenOff == null) {
                    outputHtml("Service not connected", "503 Service Unavailable");
                    return;
                }
                if (target.startsWith("/1?")) {
                    iScreenOff.setPowerMode(false);
                    outputHtml("", "200 OK");
                } else if (target.startsWith("/2?")) {
                    iScreenOff.setPowerMode(true);
                    outputHtml("", "200 OK");
                } else {
                    outputHtml(build404Html(), "404 Not Found");
                }
            } catch (Exception ignored) {}
        }
    }

    private String buildStatusJson() {
        int stateInt = -1;
        try {
            if (iScreenOff != null) stateInt = iScreenOff.getNowScreenState();
        } catch (RemoteException ignored) {}
        String nowState = "Không xác định";
        boolean isOffMode = false;
        switch (stateInt) {
            case 1: nowState = "Đang bật"; isOffMode = false; break;
            case 0: nowState = "Đã tắt (Hệ thống)"; isOffMode = true; break;
            case 2: nowState = "Đang tắt (Chạy ngầm)"; isOffMode = true; break;
        }
        return "{\"state\": \"" + nowState + "\", \"isOff\": " + isOffMode + "}";
    }

    private String buildIndexHtml() {
        String nowState = "Không xác định";
        boolean isOffMode = false;
        try {
            if (iScreenOff != null) {
                int stateInt = iScreenOff.getNowScreenState();
                switch (stateInt) {
                    case 1: nowState = "Đang bật"; isOffMode = false; break;
                    case 0: nowState = "Đã tắt (Hệ thống)"; isOffMode = true; break;
                    case 2: nowState = "Đang tắt (Chạy ngầm)"; isOffMode = true; break;
                }
            } else {
                nowState = "Mất kết nối với trình điều khiển";
            }
        } catch (RemoteException ignored) {}
        String html = loadHtml("index.html");
        if (html == null) return "";
        return html.replace("{{brand}}", Build.BRAND)
                .replace("{{device}}", Build.MODEL + " Android " + Build.VERSION.RELEASE)
                .replace("{{state}}", nowState)
                .replace("{{checked}}", isOffMode ? "selected" : "");
    }

    private String build404Html() {
        return loadHtml("404.html");
    }

    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final byte LF = 0x0a, CR = 0x0d;

    private void outputHtml(String html, String responseCode) {
        String startLine = "HTTP/1.1 " + responseCode;
        int length = (html != null) ? html.getBytes().length : 0;
        String header = startLine + "\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: " + length + "\r\n\r\n";
        server.output(header + (html != null ? html : ""));
    }

    private void outputJson(String json) {
        String startLine = "HTTP/1.1 200 OK";
        int length = json.getBytes().length;
        String header = startLine + "\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Content-Length: " + length + "\r\n\r\n";
        server.output(header + json);
    }

    private void outputPng(byte[] png) {
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/png\r\n" +
                "Content-Length: " + png.length + "\r\n\r\n";
        byte[] headerBytes = header.getBytes();
        byte[] output = new byte[headerBytes.length + png.length];
        System.arraycopy(headerBytes, 0, output, 0, headerBytes.length);
        System.arraycopy(png, 0, output, headerBytes.length, png.length);
        server.output(output);
    }

    private String loadHtml(String fileName) {
        byte[] binary = loadBinary(fileName);
        return (binary == null) ? null : new String(binary);
    }

    private byte[] getRoundedAppIcon() {
        try {
            Drawable drawable = getPackageManager().getApplicationIcon(getPackageName());
            Bitmap bitmap;
            if (drawable instanceof BitmapDrawable) {
                bitmap = ((BitmapDrawable) drawable).getBitmap();
            } else {
                bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
            }
            int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
            Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            final Paint paint = new Paint();
            final Rect rect = new Rect(0, 0, size, size);
            final float roundPx = size * 0.2f;
            paint.setAntiAlias(true);
            canvas.drawRoundRect(new RectF(rect), roundPx, roundPx, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(bitmap, null, rect, paint);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            output.compress(Bitmap.CompressFormat.PNG, 100, stream);
            return stream.toByteArray();
        } catch (Exception e) { return null; }
    }

    private byte[] loadBinary(String fileName) {
        try (InputStream is = getAssets().open(fileName);
             ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             BufferedInputStream bis = new BufferedInputStream(is, BUFFER_SIZE)) {
            byte[] chunk = new byte[BUFFER_SIZE];
            int len;
            while ((len = bis.read(chunk, 0, BUFFER_SIZE)) > 0) {
                byteStream.write(chunk, 0, len);
            }
            return byteStream.toByteArray();
        } catch (IOException e) { return null; }
    }
}

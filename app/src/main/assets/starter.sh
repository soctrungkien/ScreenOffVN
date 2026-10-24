#!/system/bin/sh
# Activate ScreenOff: grant permissions, enable the accessibility service,
# and start ScreenController through app_process.
#
# Usage: sh starter.sh [base-path] [package-name]

PKG="${2:-com.tile.screenoff}"
BP="$1"
if [ -z "$BP" ]; then
    BP=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
fi
[ -z "$BP" ] && BP="/sdcard/Android/data/$PKG/files"

DEX="$BP/ScreenController.dex"
TMP_DEX="/data/local/tmp/ScreenController.dex"
SERVICE="$PKG/com.tile.screenoff.GlobalService"
SERVICE_SHORT="$PKG/.GlobalService"

echo "[Shell] Base path: $BP"
echo "[Shell] Package: $PKG"

# 1. Permissions that can be granted from adb/root.
pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
pm grant "$PKG" android.permission.BLUETOOTH_CONNECT 2>/dev/null
pm grant "$PKG" android.permission.BLUETOOTH_SCAN 2>/dev/null
appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null
dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1

# 2. Enable the accessibility service without opening Settings.
CURRENT=$(settings get secure enabled_accessibility_services 2>/dev/null)
case ":$CURRENT:" in
    *":$SERVICE:"*|*":$SERVICE_SHORT:"*) ;;
    *)
        if [ -z "$CURRENT" ] || [ "$CURRENT" = "null" ]; then
            NEW="$SERVICE"
        else
            NEW="$CURRENT:$SERVICE"
        fi
        settings put secure enabled_accessibility_services "$NEW"
        ;;
esac
settings put secure accessibility_enabled 1

# 3. Start ScreenController in the background.
if [ ! -f "$DEX" ]; then
    echo "[Shell] Error: dex not found at $DEX"
    exit 1
fi

cp -f "$DEX" "$TMP_DEX" || {
    echo "[Shell] Failed to copy dex to $TMP_DEX"
    exit 1
}
chmod 644 "$TMP_DEX" 2>/dev/null

# Stop an old instance so the sticky binder is refreshed.
OLD_PID=""
if command -v pgrep >/dev/null 2>&1; then
    OLD_PID=$(pgrep -f "com.tile.screenoff.ScreenController" 2>/dev/null)
else
    OLD_PID=$(ps 2>/dev/null | grep "[c]om.tile.screenoff.ScreenController" | awk '{print $2}')
fi
if [ -n "$OLD_PID" ]; then
    echo "[Shell] Stopping old ScreenController: $OLD_PID"
    kill $OLD_PID 2>/dev/null
    sleep 1
fi

export CLASSPATH="$TMP_DEX"
# Detach output so the caller's pipe closes when this script exits.
nohup app_process /system/bin com.tile.screenoff.ScreenController >/dev/null 2>&1 &
PID=$!
sleep 1

if kill -0 "$PID" 2>/dev/null; then
    echo "[Shell] ScreenController started (pid $PID)."
else
    echo "[Shell] Failed to start ScreenController."
    exit 1
fi

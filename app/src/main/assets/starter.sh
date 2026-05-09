#!/system/bin/sh
# Starter script for ScreenController
echo "[Shell] Starting starter.sh..."

BP=$1
[ -z "$BP" ] && BP="/sdcard/Android/data/com.tile.screenoff/files"
ON="$BP/ScreenController.dex"
TG="/data/local/tmp/ScreenController.dex"

echo "[Shell] Base path: $BP"

if [ -f "$ON" ]; then
    echo "[Shell] Found dex at $ON, copying to $TG"
    cp -f "$ON" "$TG"
    chmod 666 "$TG"

    export CLASSPATH="$TG"
    echo "[Shell] Starting app_process..."

    # Check if already running
    if pgrep -f "com.tile.screenoff.ScreenController" > /dev/null; then
        echo "[Shell] ScreenController already running, killing old instance..."
        pkill -f "com.tile.screenoff.ScreenController"
        sleep 1
    fi

    # Run app_process without dev-null to see logs in app console
    nohup app_process /system/bin com.tile.screenoff.ScreenController &

    if [ $? -eq 0 ]; then
        echo "[Shell] ScreenController started successfully in background."
    else
        echo "[Shell] Failed to start app_process."
        exit 1
    fi
else
    echo "[Shell] Error: dex not found at $ON"
    exit 1
fi

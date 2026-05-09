#!/system/bin/sh
pm grant com.tile.screenoff android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
BP=$1
[ -z "$BP" ] && BP="/sdcard/Android/data/com.tile.screenoff/files"
ON="$BP/ScreenController.dex"
TG="/data/local/tmp/ScreenController.dex"
if [ -f "$ON" ]; then
cp -f "$ON" "$TG"
chmod 666 "$TG"
export CLASSPATH="$TG"
# Run app_process without dev-null to see logs in app console
nohup app_process /system/bin com.tile.screenoff.ScreenController &
echo "Script finished, ScreenController should be running."
else
echo "Error: dex not found at $ON"
exit 1
fi

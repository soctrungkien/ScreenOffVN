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
nohup app_process /system/bin com.tile.screenoff.ScreenController > /dev/null 2>&1 &
echo "Started"
else
echo "Error: dex not found"
exit 1
fi

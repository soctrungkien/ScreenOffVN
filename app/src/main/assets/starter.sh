#!/system/bin/sh
# Fix line endings
pm grant com.tile.screenoff android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
base_path=$1
if [ -z "$base_path" ]; then
base_path="/sdcard/Android/data/com.tile.screenoff/files"
fi
file_name="ScreenController.dex"
origin_path="$base_path/$file_name"
target_path="/data/local/tmp/ScreenController.dex"
if [ -f "$origin_path" ]; then
cp -f "$origin_path" "$target_path"
chmod 666 "$target_path"
export CLASSPATH="$target_path"
nohup app_process /system/bin com.tile.screenoff.ScreenController > /dev/null 2>&1 &
echo "Started ScreenController from $origin_path"
else
echo "Error: $origin_path not found"
exit 1
fi

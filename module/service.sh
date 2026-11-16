#!/system/bin/sh
MODDIR=${0%/*}
[ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] && exit 1
[ -f "$MODDIR/repose.apk" ] && [ -f "$MODDIR/lib/arm64-v8a/librepose.so" ] || exit 1
umask 077
mkdir -p /data/adb/repose || exit 1
chmod 700 /data/adb/repose || exit 1
# The Java process owns an advisory file lock; stage replay never unlinks it.
(
    while :; do
        [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] && exit 0
        until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
        CLASSPATH="$MODDIR/repose.apk" /system/bin/app_process /system/bin com.lixingchi.repose.Daemon \
            "$MODDIR/repose.apk" "$MODDIR/lib/arm64-v8a/librepose.so"
        [ "$?" = 75 ] || exit
        sleep 3
    done
) >> /data/adb/repose/startup.log 2>&1 < /dev/null &

#!/system/bin/sh
[ "$API" -ge 33 ] || abort "Repose requires Android 13 or newer."
[ "$ARCH" = arm64 ] || abort "This package requires arm64."
ui_print "Install the matching Repose APK, then configure protection. All triggers start disabled."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755

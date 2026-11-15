#!/system/bin/sh
MODDIR=${0%/*}
[ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
[ -f /data/adb/sayin-helper/enabled ] || exit 0
# Exactly one attempt per boot. Native runtime owns locking and all data writes.
exec "$MODDIR/bin/sayin-helper" run --module-dir "$MODDIR"

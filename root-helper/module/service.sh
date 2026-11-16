#!/system/bin/sh
MODDIR=${0%/*}
[ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
# Magisk late_start can run before Android's framework services are ready.
# Wait only before the first launch; never restart after a user stop or fault.
attempt=0
while [ "$(getprop sys.boot_completed)" != 1 ]; do
    [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
    [ "$attempt" -lt 120 ] || exit 1
    attempt=$((attempt + 1))
    sleep 1
done
[ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
# The idle endpoint never reads the clipboard. Native runtime owns locking/data.
exec "$MODDIR/bin/sayin-helper" run --module-dir "$MODDIR"

#!/system/bin/sh
MODDIR=${0%/*}
[ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
# The idle control endpoint never reads the system clipboard. Exactly one attempt per boot. Native runtime owns locking and all data writes.
exec "$MODDIR/bin/sayin-helper" run --module-dir "$MODDIR"

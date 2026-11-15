#!/system/bin/sh
# Sourced by the manager. No exit, device permission changes or default activation.
SKIPUNZIP=0

[ "$BOOTMODE" = true ] || abort "! Install from the Root manager; recovery is not supported."
case "$API" in ''|*[!0-9]*) abort "! Cannot determine Android API level." ;; esac
[ "$API" -eq 33 ] || abort "! This Binder backend supports Android 13 (API 33) only."
[ "$ARCH" = arm64 ] || abort "! This development package targets arm64 devices only."

for item in bin/sayin-helper app-policy.json config/defaults.json SHA256SUMS; do
    [ -s "$MODPATH/$item" ] || abort "! Incomplete SayIn Helper package."
done
(cd "$MODPATH" && sha256sum -c SHA256SUMS >/dev/null 2>&1) || abort "! Package checksum mismatch."

# Stop the previous native owner before replacing code; preserve its opt-in and history.
OLD=/data/adb/modules/sayin_helper/bin/sayin-helper
if [ -x "$OLD" ] && [ -d /data/adb/sayin-helper ]; then
    "$OLD" stop || abort "! Old collector did not stop. Disable it and retry."
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in service.sh action.sh uninstall.sh; do
    set_perm "$MODPATH/$script" 0 0 0755
done
set_perm "$MODPATH/bin/sayin-helper" 0 0 0755
ui_print "- SayIn Helper native clipboard collector"
ui_print "- First install: collection is OFF. After reboot, Action toggles collection."
ui_print "- Existing history and opt-in are preserved. No App keepalive or permission changes."

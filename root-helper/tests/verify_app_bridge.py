"""Real Root server + debug-signed isolated App client; only generated test data."""
import argparse,hashlib,json,shlex,subprocess,time,os,ctypes
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True)
    p.add_argument('--allow-synthetic-clipboard-writes',action='store_true')
    p.add_argument('--ordinary-regression',action='store_true')
    p.add_argument('--installed',action='store_true',help='Use the installed ELF with isolated test data')
    p.add_argument('--verify-boot-idle',action='store_true',help='Read the real boot endpoint only if no history/key exists and collection is disabled')
    args=p.parse_args()
    if not args.allow_synthetic_clipboard_writes:p.error('Explicit synthetic clipboard opt-in required')
    if args.verify_boot_idle and not args.installed:p.error('--verify-boot-idle requires --installed')
    adb=['C:/Users/seeyuer/AppData/Local/Android/Sdk/platform-tools/adb.exe','-s',args.serial]
    stamp=str(time.time_ns());base='/data/local/tmp/sayin-app-bridge-'+stamp
    binary='/data/adb/modules/sayin_helper/bin/sayin-helper' if args.installed else base+'-bin'
    prefix='SAYIN_TEST_'+stamp+'_';sock='SAYIN_TEST_'+stamp
    evidence={'serial':args.serial,'source':'installed_module' if args.installed else 'temporary_isolated_binary','cases':{},'binary_sha256':hashlib.sha256((ROOT/'root-helper/build/package/sayin-helper').read_bytes()).hexdigest()}
    lease=None
    if os.name=='nt':
        kernel=ctypes.WinDLL('kernel32',use_last_error=True)
        kernel.CreateMutexW.restype=ctypes.c_void_p
        kernel.CreateMutexW.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_wchar_p]
        kernel.WaitForSingleObject.argtypes=[ctypes.c_void_p,ctypes.c_ulong]
        kernel.ReleaseMutex.argtypes=[ctypes.c_void_p]
        kernel.CloseHandle.argtypes=[ctypes.c_void_p]
        lease=kernel.CreateMutexW(None,False,'Local\\SayIn-Android-'+args.serial)
        if not lease:raise OSError('Cannot acquire device verification lease')
        if kernel.WaitForSingleObject(lease,0) not in (0,128):
            kernel.CloseHandle(lease)
            raise RuntimeError('Another SayIn device verification is active')
    proc=None
    replacing_test_app=False
    def call(*a,check=True,timeout=60):
        r=subprocess.run(adb+list(a),capture_output=True,text=True,encoding='utf-8',timeout=timeout)
        if check and r.returncode:raise RuntimeError(r.stdout+r.stderr)
        return r
    def root(s,check=True):return call('shell','su','-c',shlex.quote(s),check=check)
    def helper(s,check=True):return root(f'{binary} {s} --data-dir {base}',check)
    def instrument(*a):
        print("Running " + a[2], flush=True)
        old_pid=call('shell','pidof','seeyuer.sayin.imagetest',check=False).stdout.strip()
        runner=subprocess.Popen(adb+['shell','am','instrument','-w',*a,
            'seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
        try:
            # Instrumentation replaces the old process. Launch only after its new
            # process starts, otherwise MIUI sees the test as a background launch.
            # Never grant AppOps, autostart or background-start permissions.
            deadline=time.monotonic()+10
            while time.monotonic()<deadline:
                pid=call('shell','pidof','seeyuer.sayin.imagetest',check=False).stdout.strip()
                if pid and pid!=old_pid:break
                if runner.poll() is not None:break
                time.sleep(.1)
            root('am start -n seeyuer.sayin.imagetest/androidx.activity.ComponentActivity')
            deadline=time.monotonic()+180
            while True:
                try:
                    out,err=runner.communicate(timeout=1)
                    break
                except subprocess.TimeoutExpired:
                    if time.monotonic()>=deadline:raise
                    windows=call('shell','dumpsys','window').stdout
                    focus=next((line for line in windows.splitlines() if 'mCurrentFocus=' in line),'')
                    if 'com.miui.wakepath.ui.ConfirmStartActivity' in focus:
                        # Dismiss only this launch prompt. Do not approve it or
                        # change any permission; explicitly foreground our test.
                        root('input keyevent 4')
                        root('am start -n seeyuer.sayin.imagetest/androidx.activity.ComponentActivity')
            assert runner.returncode==0 and 'OK (' in out and 'FAILURES' not in out,out+err
            return out
        finally:
            if runner.poll() is None:
                root('am force-stop seeyuer.sayin.imagetest',check=False)
                try:runner.communicate(timeout=5)
                except subprocess.TimeoutExpired:runner.kill();runner.communicate(timeout=5)
    def seed():
        writer=subprocess.Popen(adb+['shell','am','instrument','-w','-e','prefix',prefix,'-e','count','3','-e','delay','300','seeyuer.sayin.helperfixture/.ClipboardFixture'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
        time.sleep(.3);root('am start -W -n seeyuer.sayin.helperfixture/.ClipboardFixtureActivity')
        out,err=writer.communicate(timeout=20)
        assert 'samples_written=3' in out and 'INSTRUMENTATION_CODE: 0' in out,out+err
        end=time.monotonic()+5
        while time.monotonic()<end:
            if json.loads(helper('status').stdout).get('entries')==3:break
            time.sleep(.1)
        else:raise AssertionError('Three generated samples not recorded')

    try:
        evidence['api']=int(root('getprop ro.build.version.sdk').stdout)
        if args.installed:
            assert root(f'sha256sum {binary}').stdout.split()[0]==evidence['binary_sha256']
        else:
            call('push',str(ROOT/'root-helper/build/package/sayin-helper'),binary);root(f'chmod 755 {binary}')
        fixture=ROOT/'root-helper/build/device-fixture/fixture.apk'
        call('push',str(fixture),base+'-fixture.apk');root(f'pm install -r {base}-fixture.apk')
        print('Installing isolated App and test APKs', flush=True)
        for local,remote in [
            ('android/app/build/outputs/apk/imageTest/app-imageTest.apk',base+'-app.apk'),
            ('android/app/build/outputs/apk/androidTest/imageTest/app-imageTest-androidTest.apk',base+'-tests.apk')]:
            call('push',str(ROOT/local),remote);root(f'pm install -r {remote}')
        if args.verify_boot_idle:
            # Never inspect user history through the real endpoint. This check
            # is permitted only for the empty, disabled first-boot owner.
            daily=json.loads(root(f'{binary} status').stdout)
            assert daily.get('running') is True and daily.get('enabled') is False and daily.get('entries')==0
            root('test ! -e /data/adb/sayin-helper/key && test ! -e /data/adb/sayin-helper/history.enc')
            instrument('-e','class','seeyuer.sayin.ui.RootClipboardInstrumentedTest#bootIdleEndpointWithoutHistory','-e','verifyBootIdle','true')
            root('test ! -e /data/adb/sayin-helper/key && test ! -e /data/adb/sayin-helper/history.enc')
            evidence['cases']['real_boot_idle_endpoint_signed_app_empty_without_key']=True
        for method in ('resolvingModuleDoesNotFlashCurrentClipboard', 'absentModuleFallsBackAfterResolution', 'lateResolutionAfterClosingPanelHasNoUiEffect'):
            instrument('-e','class','seeyuer.sayin.ui.RootClipboardUiInstrumentedTest#'+method)
        evidence['cases']['loading_fallback_and_late_resolution_ui']=True
        helper('enable')
        proc=subprocess.Popen(adb+['shell','su','-c',shlex.quote(f'{binary} run --data-dir {base} --test-prefix {prefix} --app-socket {sock} --duration 300')],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        end=time.monotonic()+10
        while time.monotonic()<end:
            if json.loads(helper('status').stdout).get('capture_ready'):break
            time.sleep(.1)
        else:raise AssertionError('Capture not ready')
        # Unknown package/signature cannot read even empty metadata.
        denied=call('shell','am','instrument','-w','-e','socket',sock,'-e','mode','deny','seeyuer.sayin.helperfixture/.SocketProbe')
        assert 'socket_probe=pass' in denied.stdout and 'INSTRUMENTATION_CODE: 0' in denied.stdout,denied.stdout
        evidence['cases']['untrusted_app_denied']=True
        seed()
        for method in ('rootHistoryFullPagePastesWithoutSending','rootHistoryQuickCardPastesWithoutSending'):
            instrument('-e','class','seeyuer.sayin.ui.AssistPanelInstrumentedTest#'+method,'-e','helperSocket',sock,'-e','helperPrefix',prefix)
            evidence['cases'][method]=True
        instrument('-e','class','seeyuer.sayin.ui.AssistPanelInstrumentedTest#rootHistoryClearRequiresConfirmation','-e','helperSocket',sock,'-e','helperPrefix',prefix)
        evidence['cases']['root_history_clear_cancel_and_confirm']=True
        seed()
        helper('disable');proc.communicate(timeout=10);assert proc.returncode==0
        proc=subprocess.Popen(adb+['shell','su','-c',shlex.quote(f'{binary} run --data-dir {base} --test-prefix {prefix} --app-socket {sock} --duration 300')],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        time.sleep(.5)
        instrument('-e','class','seeyuer.sayin.ui.RootClipboardInstrumentedTest#disabledHistoryIsReadable','-e','helperSocket',sock)
        evidence['cases']['disabled_history_remains_readable']=True
        instrument('-e','class','seeyuer.sayin.ui.RootClipboardInstrumentedTest#authenticatedNativeHistory','-e','helperSocket',sock,'-e','helperPrefix',prefix)
        evidence['cases']['signed_app_list_full_read_clear_stale_rejected']=True
        assert json.loads(helper('status').stdout)['entries']==0
        helper('disable');proc.communicate(timeout=10);assert proc.returncode==0
        # Disabled state still serves saved history, without a capture child or new key.
        proc=subprocess.Popen(adb+['shell','su','-c',shlex.quote(f'{binary} run --data-dir {base} --test-prefix {prefix} --app-socket {sock} --duration 300')],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        time.sleep(.5)
        assert json.loads(helper('status').stdout)['enabled'] is False
        owners=root(f'for p in /proc/[0-9]*; do [ "$(readlink $p/exe)" = {binary} ] && echo ${{p##*/}}; done; true').stdout.split()
        # The installed boot owner uses the same executable. Count only this
        # isolated data directory so its presence cannot mask a collector child.
        isolated=[]
        for pid in owners:
            assert pid.isdigit(), 'Invalid process ID'
            command=root(f'cat /proc/{pid}/cmdline',check=False).stdout.split('\0')
            if any(command[i:i+2]==['--data-dir',base] for i in range(len(command)-1)):
                isolated.append(pid)
        assert len(isolated)==1, 'Disabled isolated instance must have no capture child'
        evidence['cases']['idle_control_without_collection']=True
        # Same allowlisted package, different key, new UID: old App authority must not survive.
        replacing_test_app=True
        root('pm uninstall seeyuer.sayin.imagetest')
        call('push',str(ROOT/'root-helper/build/device-fixture/impostor.apk'),base+'-impostor.apk')
        root(f'pm install {base}-impostor.apk')
        denied=call('shell','am','instrument','-w','-e','socket',sock,'-e','mode','deny','seeyuer.sayin.imagetest/seeyuer.sayin.helperfixture.SocketProbe')
        assert 'socket_probe=pass' in denied.stdout and 'INSTRUMENTATION_CODE: 0' in denied.stdout,denied.stdout
        evidence['cases']['allowlisted_package_wrong_signer_after_reinstall_denied']=True
        root('pm uninstall seeyuer.sayin.imagetest')
        root(f'pm install -r {base}-app.apk')
        replacing_test_app=False
        root(f'rm {base}-impostor.apk')
        helper('stop');proc.communicate(timeout=10);assert proc.returncode==0
        helper('purge')
        if args.ordinary_regression:
            methods=(
                'clipboardMenuSavesAsPhraseWithoutInserting','clipboardTextInsertsOnceAndCloses',
                'clipboardTextReplacesSelection','clipboardClearConfirmCancelAndConfirmStaysInPanel',
                'sensitiveClipboardStaysMaskedUntilExplicitlyShown','unavailableClipboardOffersRetryWithoutChangingDraft',
                'pasteKeepsPanelAndEachPasteIsOneUndoStep','copyKeepsPanelAndDoesNotTouchDraft',
                'quickCardUsesTheSamePanel','draftChangedWhilePanelOpenRejectsStaleInsertion')
            insertion=instrument('-e','class','seeyuer.sayin.ui.AssistInsertionInstrumentedTest')
            assert 'OK (11 tests)' in insertion,insertion
            # Each ActivityScenario closes its activity. Launch each UI case
            # explicitly rather than changing MIUI's background-start policy.
            for method in methods:
                ordinary=instrument('-e','class','seeyuer.sayin.ui.AssistPanelInstrumentedTest#'+method)
                assert 'OK (1 test)' in ordinary,ordinary
            evidence['cases']['ordinary_single_clipboard_and_draft_21_regressions']=True
        root(f'rm {base}/instance.lock; rmdir {base}; rm {base}-fixture.apk {base}-app.apk {base}-tests.apk')
        if not args.installed:root(f'rm {binary}')
    finally:
        if replacing_test_app:
            root('pm uninstall seeyuer.sayin.imagetest',check=False)
            root(f'pm install -r {base}-app.apk',check=False)
        if proc and proc.poll() is None:
            helper('stop',check=False)
            try:proc.communicate(timeout=10)
            except subprocess.TimeoutExpired:proc.terminate()
        kind='app-bridge-installed' if args.installed else 'app-bridge'
        target=ROOT/f'docs/research/native-clipboard/evidence/{kind}-api{evidence.get("api","unknown")}-{args.serial}.json'
        target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(evidence,indent=2)+'\n')
        print(json.dumps(evidence,indent=2))
        if lease:
            kernel.ReleaseMutex(lease);kernel.CloseHandle(lease)
if __name__=='__main__':main()

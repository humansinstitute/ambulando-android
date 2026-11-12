#!/usr/bin/env python3
"""Production SDK IPC regression; ONLY a dedicated rooted disposable emulator.

Prepare build33 + a disposable identity using its UI. --seed-disposable-binding
can create a synthetic journal when none exists. Alternatively seed remote.json with a
RemoteBinding(device=71, destination.connectionId='fixture', origin='https://tower.example',
signerNpub='fixture-account', and disposable workspace/channel IDs). The deliberately
unmatched signer prevents microphone capture/sending. Never seed a real binding.
Install :garmin-ipc-fixture:assembleDebug's APK, then run --expect-original-crash
on build33. Run again with --apk <signed minified fixed APK> for upgrade/recovery.
Evidence is operational/private: choose an ignored directory outside public source.
"""
import argparse
import json
import pathlib
import re
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--disposable-emulator', action='store_true', required=True)
parser.add_argument('--evidence', type=pathlib.Path, required=True)
parser.add_argument('--apk', type=pathlib.Path)
parser.add_argument('--seed-disposable-binding', action='store_true', help='Create a synthetic binding only if no journal exists; never replace an existing binding')
parser.add_argument('--expect-original-crash', action='store_true')
parser.add_argument('--check-receipts', action='store_true', help='Verify build38 side-effect-free hello and synthetic wrist receipt validation')
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Physical devices are forbidden'
args.evidence.mkdir(parents=True, exist_ok=True)

def adb(*cmd, check=True):
    return subprocess.run(['adb', '-s', args.serial, *cmd], text=True, capture_output=True, check=check).stdout.strip()

def shell(*cmd, check=True):
    return adb('shell', *cmd, check=check)

assert shell('getprop', 'ro.kernel.qemu') == '1', 'Not an emulator'
assert shell('id', '-u') == '0', 'Start adbd root on this disposable emulator first'
package = 'studio.otherstuff.ambulando.android'
remote = f'/data/user/0/{package}/no_backup/garmin/remote.json'
vault = f'/data/user/0/{package}/no_backup/AmbulandoNative/Identity/vault.json'

def journal():
    return json.loads(shell('cat', remote))

if args.seed_disposable_binding and not shell('cat', remote, check=False):
    destination = dict(connectionId='fixture', origin='https://tower.example', towerNpub='fixture-tower', signerNpub='fixture-account', workspaceId='10000000-0000-4000-8000-000000000001', appNpub='fixture-app', channelId='10000000-0000-4000-8000-000000000002', threadId=None, label='Fixture workspace / Conversation')
    uid = shell('stat', '-c', '%u', f'/data/user/0/{package}')
    assert uid.isdigit()
    shell('am', 'force-stop', package)
    with tempfile.NamedTemporaryFile(mode='w', suffix='.json') as seed:
        json.dump(dict(binding=dict(device=71, destination=destination), sessions=[]), seed)
        seed.flush()
        adb('push', seed.name, '/data/local/tmp/garmin-ipc-seed.json')
    shell('mkdir', '-p', remote.rsplit('/', 1)[0])
    shell('cp', '/data/local/tmp/garmin-ipc-seed.json', remote)
    shell('chown', '-R', uid, remote.rsplit('/', 1)[0])
    shell('rm', '/data/local/tmp/garmin-ipc-seed.json')

before = journal()
assert before['binding']['device'] == 71
assert before['binding']['destination']['connectionId'] == 'fixture'
assert before['binding']['destination']['signerNpub'] == 'fixture-account'
assert before['binding']['destination']['origin'] == 'https://tower.example'
vault_before = shell('sha256sum', vault)
package_before = shell('dumpsys', 'package', package)
(args.evidence / 'package-before.txt').write_text(package_before)
if args.apk:
    shell('am', 'force-stop', package)
    print(adb('install', '-r', str(args.apk)))
    assert journal()['binding'] == before['binding'], 'Upgrade changed binding'
    assert shell('sha256sum', vault) == vault_before, 'Upgrade changed encrypted identity'
adb('logcat', '-c')

def wait_for(predicate, description):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if predicate(): return
        time.sleep(.3)
    raise AssertionError(description)

def launch():
    shell('am', 'start', '-n', package + '/studio.otherstuff.ambulando.app.MainActivity')
    wait_for(lambda: re.search(r'com.garmin.android.connectiq.INCOMING_MESSAGE:\s*\n[^\n]*' + re.escape(package), shell('dumpsys', 'activity', 'broadcasts')), 'SDK receiver not registered at startup')
    time.sleep(1)

def send(session, *extra):
    shell('am', 'start', '-n', 'com.garmin.android.apps.connectmobile/studio.otherstuff.ambulando.garminfixture.SenderActivity', '--es', 'session', session, '--es', 'op', 'start', *extra)
    time.sleep(1)

launch()
if args.check_receipts:
    snapshot = journal()
    component = 'com.garmin.android.apps.connectmobile/studio.otherstuff.ambulando.garminfixture.SenderActivity'
    def probe(*extra):
        shell('am', 'start', '-n', component, '--es', 'session', 'hello', '--es', 'op', 'hello', *extra)
        time.sleep(.5)
    def diagnostic_logs():
        return adb('logcat', '-d', '-s', 'GarminRemote:I', '*:S')
    probe()
    wait_for(lambda: 'Garmin accepted reply; wrist unconfirmed' in diagnostic_logs(), 'SDK status callback missing')
    assert 'Wrist confirmed' not in diagnostic_logs(), 'Transport callback falsely confirmed wrist'
    assert journal() == snapshot, 'Hello mutated replay journal'
    for flag, value in [('--el', '72')]:
        probe('--ez', 'echoReply', 'true', flag, 'device', value)
    probe('--ez', 'echoReply', 'true', '--es', 'app', '00000000000000000000000000000000')
    for reason in ('nonce', 'revision', 'session', 'build'):
        probe('--ez', 'echoReply', 'true', '--es', 'badReceipt', reason)
    assert 'Wrist confirmed' not in diagnostic_logs(), 'Invalid receipt falsely confirmed wrist'
    probe('--ez', 'echoReply', 'true')
    wait_for(lambda: 'Wrist confirmed phone 38 / watch 38' in diagnostic_logs(), 'Valid synthetic receipt not accepted')
    confirmed_count = diagnostic_logs().count('Wrist confirmed phone 38 / watch 38')
    probe('--ez', 'echoReply', 'true')
    assert diagnostic_logs().count('Wrist confirmed phone 38 / watch 38') == confirmed_count, 'Duplicate receipt accepted'
    assert journal() == snapshot, 'Probe/receipt mutated session history'
    (args.evidence / 'synthetic-receipts.txt').write_text(diagnostic_logs())
    print('PASS: synthetic external SDK hello, transport-only unconfirmed, wrong device/app/nonce/session/revision/build rejected, exact receipt accepted once, no journal mutation')
if args.expect_original_crash:
    send('original_crash_probe')
    wait_for(lambda: 'BadParcelableException' in adb('logcat', '-d', '-b', 'crash'), 'Expected original release crash')
    crash = adb('logcat', '-d', '-b', 'crash')
    (args.evidence / 'original-crash.txt').write_text(crash)
    assert 'BadParcelableException' in crash and 'com.garmin.android.connectiq.IQDevice' in crash
    print('PASS: original production release crashes on canonical external IQDevice')
else:
    pid = shell('pidof', package)
    assert pid
    unchanged = journal()
    send('wrong_device_probe', '--el', 'device', '72')
    send('wrong_app_probe', '--es', 'app', '00000000000000000000000000000000')
    send('malformed_probe', '--ez', 'malformed', 'true')
    assert journal() == unchanged, 'Wrong device/app or malformed message changed sessions'
    assert shell('pidof', package) == pid, 'Process crashed on rejected IPC'
    session = 'release_probe_' + str(int(time.time()))
    send(session, '--ez', 'verify', 'true')
    wait_for(lambda: any(s['session'] == session for s in journal()['sessions']), 'Real SDK callback did not accept bound app/device')
    after = journal()
    accepted = [s for s in after['sessions'] if s['session'] == session]
    assert len(accepted) == 1, 'Real SDK callback did not accept bound device/app'
    assert accepted[0]['status'] == 'error', 'Disposable signer must fail authorization before capture'
    send(session)
    assert journal() == after, 'Duplicate Start changed terminal session'
    shell('am', 'force-stop', package)
    launch()
    send(session)
    assert journal() == after, 'Restart changed saved destination/session'
    assert shell('sha256sum', vault) == vault_before
    crash = adb('logcat', '-d', '-b', 'crash')
    assert 'FATAL EXCEPTION' not in crash, crash
    logs = adb('logcat', '-d', '-s', 'GarminIpcFixture:I', '*:S')
    for cls in ('IQDevice', 'IQApp', 'IQMessage'):
        assert 'Canonical Parcelable/CREATOR verified: com.garmin.android.connectiq.' + cls in logs, cls
    assert 'Known device cached status: UNKNOWN' in logs, 'Fixture must expose real default status'
    assert 'Remote registration: 4EDBC3D41F924958B53A29734BA5A821' in logs, 'SDK did not request remote registration'
    assert 'session=' + session + ' state=error' in logs, 'Watch never received the honest authorization error via SDK Binder'
    (args.evidence / 'canonical-creators.txt').write_text(logs)
    (args.evidence / 'journal-after.json').write_text(json.dumps(after, indent=2))
    (args.evidence / 'crash-buffer.txt').write_text(crash)
    (args.evidence / 'package-after.txt').write_text(shell('dumpsys', 'package', package))
    print('PASS: signed upgrade, canonical release DEX/CREATORs, UNKNOWN known-device status + authoritative service connection, SDK inbound/outbound Binder/broadcast, honest error reply, wrong device/app, malformed IPC, duplicate Start and restart; identity/binding retained')

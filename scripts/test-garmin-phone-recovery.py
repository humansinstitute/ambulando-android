#!/usr/bin/env python3
"""Drive debug sample recorder's real Android UI on one explicitly disposable rooted emulator.

Requires installed app debug + androidTest APKs and the external Garmin SDK fixture.
No real account, private key access, real phone, association injection or headset success claim.
"""
import argparse
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--disposable-emulator', action='store_true', required=True)
p.add_argument('--evidence', type=pathlib.Path, required=True)
a = p.parse_args()
assert a.serial.startswith('emulator-')
a.evidence.mkdir(parents=True, exist_ok=True)
package = 'studio.otherstuff.ambulando.android'
cache = f'/data/user/0/{package}/cache'
ready, done = cache + '/garmin-native-ready', cache + '/garmin-native-done'

def adb(*args, check=True):
    return subprocess.run(['adb', '-s', a.serial, *args], capture_output=True, text=True, check=check).stdout.strip()

def shell(*args, check=True):
    return adb('shell', *args, check=check)

assert shell('getprop', 'ro.kernel.qemu') == '1'
assert shell('id', '-u') == '0'
assert 'DEBUGGABLE' in shell('dumpsys', 'package', package), 'Debug sample fixture required'
shell('am', 'force-stop', package)
shell('pm', 'revoke', package, 'android.permission.RECORD_AUDIO', check=False)
shell('pm', 'clear-permission-flags', package, 'android.permission.RECORD_AUDIO', 'user-set', 'user-fixed')
for permission in ('POST_NOTIFICATIONS', 'BLUETOOTH_CONNECT'):
    shell('pm', 'grant', package, 'android.permission.' + permission)
shell('rm', '-f', ready, done)
log = (a.evidence / 'instrumentation.txt').open('w')
process = subprocess.Popen(['adb', '-s', a.serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
    'studio.otherstuff.ambulando.app.integration.GarminPhoneRecoveryTest#phoneConfirmationWithoutAssociationRetainsWaitingUntilVisibleStartAndReportsMissingHeadset',
    package + '.test/androidx.test.runner.AndroidJUnitRunner'], stdout=log, stderr=subprocess.STDOUT)

def wait(condition, label):
    end = time.monotonic() + 28
    while time.monotonic() < end:
        if condition(): return
        if process.poll() is not None: raise AssertionError('Fixture ended before ' + label)
        time.sleep(.15)
    raise AssertionError(label)

def capture(name):
    shell('uiautomator', 'dump', '/data/local/tmp/garmin-native.xml')
    xml = shell('cat', '/data/local/tmp/garmin-native.xml')
    (a.evidence / (name + '.xml')).write_text(xml)
    shell('screencap', '-p', '/data/local/tmp/garmin-native.png')
    adb('pull', '/data/local/tmp/garmin-native.png', str(a.evidence / (name + '.png')))
    return list(ET.fromstring(xml).iter('node'))

def tap(name, label):
    nodes = capture(name)
    normalize = lambda s: s.replace('\u2019', "'")
    node = next(n for n in nodes if normalize(n.get('text', '')) == normalize(label))
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

wait(lambda: shell('cat', ready, check=False).startswith('phone_'), 'Visible fixture ready')
tap('01-waiting', 'Start headset recording')
tap('02-permission', "Don't allow")
nodes = capture('03-denied')
assert any('Recording permission denied' in n.get('text', '') for n in nodes)
assert any(n.get('text') == 'waiting' for n in nodes), 'Denied Start must remain waiting'
tap('04-retry', 'Start headset recording')
tap('05-grant', 'While using the app')
wait(lambda: shell('cat', done, check=False).startswith('phone_'), 'Bluetooth microphone error')
nodes = capture('06-missing-headset')
assert any(n.get('text') == 'error' for n in nodes)
assert any('Bluetooth' in n.get('text', '') for n in nodes)
assert not any('Recording permission denied' in n.get('text', '') for n in nodes), 'Old permission error survived a successful grant'
shell('rm', ready)
process.wait(timeout=30)
log.close()
result = (a.evidence / 'instrumentation.txt').read_text()
assert 'OK (1 test)' in result, result
print('PASS: actual native Start, microphone deny retains waiting, grant callback recovers after focus, no capture while hidden, missing Bluetooth input fails clearly; no association required')

"""Local emulator smoke helper. Credentials stay in memory and are never printed."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = str(Path.home() / 'AppData/Local/Android/Sdk/platform-tools/adb.exe')

def adb(*args):
    return subprocess.check_output([ADB, '-s', 'emulator-5554', *args])

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/nganyaflow-smoke.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/nganyaflow-smoke.xml')).iter('node'))

def show():
    for n in nodes():
        text = n.get('text') or n.get('content-desc')
        if text and not re.search(r'NF[-\s]|Bearer', text):
            print(text, n.get('bounds'))

def tap(label):
    candidates = [n for n in nodes() if label.casefold() in ((n.get('text') or '') + ' ' + (n.get('content-desc') or '')).casefold()]
    if not candidates:
        raise RuntimeError('Control not found: ' + label)
    n = candidates[0]
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.7)

def code(role):
    note = Path(__file__).resolve().parents[2] / 'server/runtime/demo-access-codes.txt'
    line = next(x for x in note.read_text().splitlines() if x.startswith(role + ':'))
    value = line.split(':', 1)[1].split()[0]
    edits = [n for n in nodes() if n.get('class') == 'android.widget.EditText']
    if not edits:
        raise RuntimeError('No code entry field')
    bounds = list(map(int, re.findall(r'\d+', edits[0].get('bounds'))))
    adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
    adb('shell', 'input', 'text', value)
    adb('shell', 'input', 'keyevent', '4')

if __name__ == '__main__':
    action = sys.argv[1]
    if action == 'list': show()
    elif action == 'tap': tap(sys.argv[2]); show()
    elif action == 'code': code(sys.argv[2])
    elif action == 'scroll': adb('shell', 'input', 'swipe', '360', '980', '360', '340', '400'); time.sleep(.5); show()
    elif action == 'shot': Path(sys.argv[2]).write_bytes(adb('exec-out', 'screencap', '-p'))

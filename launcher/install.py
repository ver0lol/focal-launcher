#!/usr/bin/env python3
"""Install the launcher and its verified input helper. No daemon/boot changes."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT.parent/'scripts'))
from project_config import load_device
from toolchain import check_apk, ensure_sdk, java_home
STAGE='/data/local/tmp/ver0-web-remote-20261109'
SHELL_SHA='019086536b350b654fc37f85dcdbe4b79a1d30afc8d841b0f91691b3520c6f95'
QW_SHA='022b638767ddf981ba09b8092460ea4cb837a7cdf713949ecbea7c0a0ced5759'

def main():
    version=ET.parse(ROOT/'AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default=shutil.which('adb') or 'adb')
    p.add_argument('--device-config',type=Path)
    p.add_argument('--serial',help='Override the network address, never the pinned identity')
    p.add_argument('--apk',type=Path,default=ROOT.parent/'dist'/('focal-launcher-'+version+'.apk'))
    p.add_argument('--sdk',type=Path,default=ROOT.parent/'work/android-sdk')
    p.add_argument('--java-home',type=Path)
    p.add_argument('--open',action='store_true',help='Open the pairing information on the projector')
    args=p.parse_args();config=load_device(args.device_config);args.serial=args.serial or config['adb_serial'];adb=[args.adb,'-s',args.serial]
    digest=hashlib.sha256(args.apk.read_bytes()).hexdigest()
    if args.apk.with_suffix('.apk.sha256').read_text().split()[0]!=digest:raise RuntimeError('APK checksum mismatch')
    tools,_=ensure_sdk(args.sdk)
    check_apk(args.apk,tools,java_home(args.java_home))
    # Establish only the explicitly configured connection; never scan/choose a device.
    if ':' in args.serial:
        subprocess.run([args.adb,'connect',args.serial],check=True,timeout=20)
    def read(command):return subprocess.check_output(adb+['shell',command],text=True,timeout=20).strip()
    expected=config['expected']
    for prop,value in expected.items():
        if read('getprop '+prop)!=value:raise RuntimeError('Device mismatch: '+prop)
    for path,digest in [('/system/bin/sh',SHELL_SHA),('/system/bin/qw',QW_SHA)]:
        if read('sha256sum '+path).split()[0]!=digest:raise RuntimeError('Firmware binary mismatch: '+path)
    if not any(re.fullmatch(r'\s*\d+\s+(?:/system/bin/)?qw --daemon\s*',line) for line in read('ps -A -o PID,ARGS').splitlines()):
        raise RuntimeError('Existing factory daemon absent; refusing to start one')
    if read('test -S /dev/com.koushikdutta.superuser.daemon/server && echo present')!='present':raise RuntimeError('Factory socket absent')
    # Ordinary shell-owned files only. Never modify the earlier private root stage.
    setup=('umask 022; d='+STAGE+'; '
           'if [ ! -e "$d" ]; then mkdir "$d" || exit 70; fi; '
           '[ -d "$d" ] && [ ! -L "$d" ] && [ "$(stat -c "%u:%g:%a" "$d")" = 2000:2000:755 ] || exit 71; '
           'if [ ! -e "$d/.agingdragonbox" ]; then cp /system/bin/sh "$d/.agingdragonbox" && chmod 555 "$d/.agingdragonbox" || exit 72; fi; '
           '[ -f "$d/.agingdragonbox" ] && [ ! -L "$d/.agingdragonbox" ] && [ "$(stat -c "%u:%g:%a" "$d/.agingdragonbox")" = 2000:2000:555 ] || exit 73; '
           'sha256sum "$d/.agingdragonbox"')
    if read(setup).split()[0]!=SHELL_SHA:raise RuntimeError('Input helper checksum mismatch')
    subprocess.run(adb+['install','-r',str(args.apk.resolve())],check=True,timeout=60)
    if args.open:subprocess.run(adb+['shell','am','start','-W','-a','dev.projector.rice.REMOTE_INFO','-n','dev.projector.rice/.MainActivity'],check=True,timeout=20)
    print('Installed. The web remote address is shown in focal-launcher settings.')

if __name__=='__main__':main()

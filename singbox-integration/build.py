#!/usr/bin/env python3
"""Reproducible integration overlay on a pinned official SFA APK.

Only AndroidManifest.xml and one added DEX differ. The original sing-box core,
UI, resources, and native libraries are copied unchanged. This is a locally
modified SFA build, signed with the same private development key as focal-launcher.
"""
import argparse
import json
import os
import shutil
from urllib.parse import urlsplit
from pathlib import Path
import hashlib
import subprocess
import zipfile
import sys
from axml_bridge import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from toolchain import check_signing_key, ensure_sdk, java_home, original_key

ROOT=Path(__file__).resolve().parent
REPO=ROOT.parent
PIN='b1ae76164e1f518c0ae343ce30d8ee63fbb46a59f1e12295bf5bd20f1e1a5e1b'

def run(args):subprocess.run([str(v) for v in args],check=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',type=Path,default=Path(os.environ.get('ANDROID_SDK_ROOT',REPO/'work/android-sdk')))
    parser.add_argument('--work',type=Path,default=REPO/'work/singbox/bridge-build')
    parser.add_argument('--base-apk',type=Path,default=REPO/'work/singbox/SFA-1.14.4-armeabi-v7a.apk')
    parser.add_argument('--keystore',type=Path)
    parser.add_argument('--vpn-config',type=Path,default=REPO/'local/vpn.json')
    parser.add_argument('--java-home',type=Path)
    parser.add_argument('--output',type=Path,default=REPO/'dist/focal-vpn-1.14.4.apk')
    args=parser.parse_args()
    SDK=args.sdk.resolve();WORK=args.work.resolve();BASE=args.base_apk.resolve();KEY=original_key(args.keystore);OUT=args.output.resolve()
    args.java_home=java_home(args.java_home)
    check_signing_key(KEY,args.java_home)
    TOOLS,platform=ensure_sdk(SDK);JAVA=args.java_home/'bin'
    config=json.loads(args.vpn_config.read_text());source=config.get('source_url','');name=config.get('profile_name','Focal VPN');url=urlsplit(source)
    if url.scheme!='https' or not url.hostname or url.username or url.password or url.fragment:raise ValueError('Expected an HTTPS source_url without inline credentials')
    if not isinstance(name,str) or not name or len(name)>80 or any(ord(c)<32 for c in name):raise ValueError('Invalid profile_name')
    if hashlib.sha256(BASE.read_bytes()).hexdigest()!=PIN:raise ValueError('Official SFA APK digest mismatch')
    WORK.mkdir(parents=True,exist_ok=True);classes=WORK/'classes';dex=WORK/'dex'

    for directory in (classes,dex):
        if directory.exists():shutil.rmtree(directory)
        directory.mkdir()
    generated=WORK/'VpnBuildConfig.java'
    generated.write_text('package dev.projector.singbox;\nfinal class VpnBuildConfig {\nstatic final String SOURCE='+json.dumps(source)+';\nstatic final String PROFILE_NAME='+json.dumps(name)+';\n}\n')
    generated.chmod(0o600)
    run([JAVA/'javac','--release','8','-encoding','UTF-8','-classpath',platform,'-d',classes,*sorted((ROOT/'src').rglob('*.java')),generated])
    run([JAVA/'java','-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','26','--lib',platform,'--output',dex,*sorted(classes.rglob('*.class'))])
    unsigned=WORK/'unsigned.apk'
    with zipfile.ZipFile(BASE) as source,zipfile.ZipFile(unsigned,'w') as target:
        names=source.namelist();existing=[n for n in names if n.startswith('classes') and n.endswith('.dex')]
        next_dex='classes'+str(len(existing)+1)+'.dex'
        for info in source.infolist():
            if info.filename.upper().startswith('META-INF/') and (info.filename.upper().endswith(('.RSA','.SF','.DSA','.EC')) or info.filename.upper()=='META-INF/MANIFEST.MF'):continue
            data=source.read(info.filename)
            if info.filename=='AndroidManifest.xml':data=patch(data)
            target.writestr(info,data)
        target.write(dex/'classes.dex',next_dex,compress_type=zipfile.ZIP_DEFLATED)
    OUT.parent.mkdir(parents=True,exist_ok=True)
    aligned=WORK/'aligned.apk';run([TOOLS/'zipalign','-f','-P','16','4',unsigned,aligned])
    run([JAVA/'java','-jar',TOOLS/'lib/apksigner.jar','sign','--ks',KEY,'--ks-key-alias','rice','--ks-pass','pass:rice-development','--key-pass','pass:rice-development','--v4-signing-enabled','false','--out',OUT,aligned])
    run([JAVA/'java','-jar',TOOLS/'lib/apksigner.jar','verify','--verbose',OUT])
    with zipfile.ZipFile(BASE) as source,zipfile.ZipFile(OUT) as target:
        for name in source.namelist():
            if name.startswith('lib/'):
                if source.read(name)!=target.read(name):raise ValueError('Native library changed: '+name)
        if target.testzip() is not None:raise ValueError('APK ZIP integrity failure')
    digest=hashlib.sha256(OUT.read_bytes()).hexdigest()
    OUT.with_suffix('.apk.sha256').write_text(digest+'  '+OUT.name+'\n')
    print('Modified SFA APK:',OUT,'SHA256:',digest)

if __name__=='__main__':main()

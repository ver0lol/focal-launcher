"""Build a temporary, non-launchable network test APK; does not install it."""
from pathlib import Path
import argparse
import os
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parent
REPO=ROOT.parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', type=Path, default=REPO/'work/android-sdk')
parser.add_argument('--work', type=Path, default=REPO/'work/singbox/probe')
parser.add_argument('--java-home', type=Path, default=Path(os.environ.get('JAVA_HOME', '/path/to/jdk-21')))
parser.add_argument('--keystore', type=Path, default=REPO/'work/launcher-build/rice-development.jks')
args=parser.parse_args()
WORK=args.work.resolve()
TOOLS=args.sdk/'android-15'
PLATFORM=args.sdk/'android-11/android.jar'
JAVA=args.java_home/'bin'
if not args.keystore.is_file():
    parser.error('Original signing key required for the probe; no key is created or replaced')
def run(args):subprocess.run([str(v) for v in args],check=True)
WORK.mkdir(exist_ok=True,parents=True)
classes=WORK/'classes';dex=WORK/'dex';classes.mkdir(exist_ok=True);dex.mkdir(exist_ok=True)
run([TOOLS/'aapt2','link','-I',PLATFORM,'--manifest',ROOT/'AndroidManifest.xml','-o',WORK/'resources.apk'])
run([JAVA/'javac','--release','8','-classpath',PLATFORM,'-d',classes,ROOT/'Probe.java'])
run([JAVA/'java','-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','26','--lib',PLATFORM,'--output',dex,*classes.rglob('*.class')])
with zipfile.ZipFile(WORK/'resources.apk') as source,zipfile.ZipFile(WORK/'unsigned.apk','w') as target:
    for item in source.infolist():target.writestr(item,source.read(item.filename))
    target.write(dex/'classes.dex','classes.dex',compress_type=zipfile.ZIP_DEFLATED)
run([TOOLS/'zipalign','-f','4',WORK/'unsigned.apk',WORK/'aligned.apk'])
run([JAVA/'java','-jar',TOOLS/'lib/apksigner.jar','sign','--ks',args.keystore,'--ks-pass','pass:rice-development','--key-pass','pass:rice-development','--v4-signing-enabled','false','--out',WORK/'probe.apk',WORK/'aligned.apk'])

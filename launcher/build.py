#!/usr/bin/env python3
"""Build and sign the focal-launcher APK locally; never connects to any Android device."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'scripts'))
from toolchain import check_signing_key, ensure_sdk, java_home, original_key

ROOT=Path(__file__).resolve().parent

def run(command):
    subprocess.run([str(x) for x in command],check=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk",type=Path,default=Path(os.environ.get("ANDROID_SDK_ROOT",ROOT.parent/"work/android-sdk")))
    parser.add_argument("--work",type=Path,default=ROOT.parent/"work/launcher-build")
    version=ET.parse(ROOT/"AndroidManifest.xml").getroot().get('{http://schemas.android.com/apk/res/android}versionName')
    parser.add_argument("--output",type=Path,default=ROOT.parent/"dist"/("focal-launcher-"+version+".apk"))
    parser.add_argument("--bootstrap",action="store_true",help="Download pinned official Google tools if absent")
    parser.add_argument("--java-home",type=Path)
    parser.add_argument("--keystore",type=Path,help="Original private key; default local/signing/ or legacy work/ key")
    parser.add_argument("--test-key",action="store_true",help="Explicit disposable test build, never suitable for updating the owner projector")
    args=parser.parse_args();sdk=args.sdk.resolve();work=args.work.resolve();work.mkdir(parents=True,exist_ok=True)
    args.java_home=java_home(args.java_home)
    if args.test_key and args.keystore:parser.error('--test-key cannot use a private --keystore')
    key=work/'rice-test.jks' if args.test_key else original_key(args.keystore,work)
    if not args.test_key:check_signing_key(key,args.java_home)
    tools,platform=ensure_sdk(sdk,args.bootstrap)
    java=args.java_home/"bin/java";javac=args.java_home/"bin/javac";keytool=args.java_home/"bin/keytool"
    os.environ["JAVA_HOME"]=str(args.java_home)
    compiled=work/"compiled";classes=work/"classes";dex=work/"dex"
    for directory in (compiled,classes,dex):
        if directory.exists():shutil.rmtree(directory)
        directory.mkdir()
    run([tools/"aapt2","compile","--dir",ROOT/"res","-o",compiled])
    resource_apk=work/"resources.apk"
    command=[tools/"aapt2","link","-I",platform,"--manifest",ROOT/"AndroidManifest.xml","--java",work/"generated","-o",resource_apk]
    if (ROOT/"assets").is_dir():command.extend(["-A",ROOT/"assets"])
    command.extend(sorted(compiled.glob("*.flat")));run(command)
    sources=sorted((ROOT/"src").rglob("*.java"))+sorted((work/"generated").rglob("*.java"))
    # javac supplies Java 8's lambda bootstrap signatures; D8 desugars lambdas
    # before Android execution. Android's public stubs omit LambdaMetafactory.
    run([javac,"-encoding","UTF-8","--release","8","-classpath",platform,"-d",classes,*sources])
    class_files=sorted(classes.rglob("*.class"))
    run([java,"-cp",tools/"lib/d8.jar","com.android.tools.r8.D8","--release","--min-api","26","--lib",platform,"--output",dex,*class_files])
    unsigned=work/"unsigned.apk";shutil.copyfile(resource_apk,unsigned)
    with zipfile.ZipFile(unsigned,"a",compression=zipfile.ZIP_DEFLATED) as z:
        for p in sorted(dex.glob("*.dex")):z.write(p,p.name)
    aligned=work/"aligned.apk";run([tools/"zipalign","-f","4",unsigned,aligned])
    if args.test_key and not key.exists():
        run([keytool,"-genkeypair","-keystore",key,"-storepass","rice-development","-keypass","rice-development","-alias","rice","-keyalg","RSA","-keysize","3072","-validity","3650","-dname","CN=Focal disposable test build","-noprompt"])
        key.chmod(0o600)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    run([java,"-jar",tools/"lib/apksigner.jar","sign","--ks",key,"--ks-key-alias","rice","--ks-pass","pass:rice-development","--key-pass","pass:rice-development","--v4-signing-enabled","false","--out",args.output,aligned])
    run([java,"-jar",tools/"lib/apksigner.jar","verify","--verbose","--print-certs",args.output])
    args.output.with_suffix(".apk.sha256").write_text(hashlib.sha256(args.output.read_bytes()).hexdigest()+"  "+args.output.name+"\n")
    print("APK:",args.output.resolve(),"bytes:",args.output.stat().st_size)

if __name__=="__main__":main()

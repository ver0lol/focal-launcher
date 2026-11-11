"""Pinned host SDK, JDK discovery and original-signature checks; no device access."""
import hashlib
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

REPO = Path(__file__).resolve().parents[1]
CERT_SHA256 = '068bcd61ba49ffc0d8c5050fd45cc9aed39c22ca830bedab0f35b473c392a9e5'
PACKAGES = {
    'Linux': ('build-tools_r35_linux.zip', 'bd3a4966912eb8b30ed0d00b0cda6b6543b949d5ffe00bea54c04c81e1561d88', 'android-15'),
    'Darwin': ('build-tools_r35_macosx.zip', '530cdbd1ec315e1477624d7ed2f0f2962108d69f36eddba5894cef9ea2cedb48', 'android-15'),
}
PLATFORM = ('platform-30_r03.zip', 'f3f5b75744dbf6ee6ed3e8174a71e513bfee502d0bc3463ea97e517bff68d84e', 'android-11')


def host():
    system, machine = platform.system(), platform.machine().lower()
    if system not in PACKAGES or (system == 'Linux' and machine not in ('x86_64', 'amd64')):
        raise ValueError('Supported build hosts: Arch/Linux x86_64 and macOS. Linux ARM64 needs a different native SDK.')
    return system


def java_home(explicit=None):
    candidates = [Path(explicit)] if explicit else []
    if not explicit:
        if os.environ.get('JAVA_HOME'): candidates.append(Path(os.environ['JAVA_HOME']))
        candidates += [Path('/usr/lib/jvm/java-21-openjdk'), Path('/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home')]
        if shutil.which('javac'): candidates.append(Path(shutil.which('javac')).resolve().parents[1])
        if platform.system() == 'Darwin':
            result = subprocess.run(['/usr/libexec/java_home', '-v', '21'], capture_output=True, text=True)
            if result.returncode == 0: candidates.append(Path(result.stdout.strip()))
    for candidate in candidates:
        javac = candidate/'bin/javac'
        if not javac.is_file(): continue
        result = subprocess.run([str(javac), '-version'], capture_output=True, text=True, timeout=15)
        if result.returncode == 0 and re.search(r'\bjavac 21(?:\.|\s|$)', result.stdout+result.stderr):
            return candidate.resolve()
    raise ValueError('JDK 21 missing. On Arch install jdk21-openjdk, or set --java-home /path/to/jdk-21.')


def original_key(explicit=None, work=None):
    if explicit: return Path(explicit).resolve()
    private = REPO/'local/signing/rice-development.jks'
    legacy = Path(work or REPO/'work/launcher-build')/'rice-development.jks'
    return private if private.is_file() else legacy


def check_signing_key(key, java):
    if not key.is_file():
        raise ValueError('Original signing key missing. Extract the PRIVATE update kit into this repository first. Do not generate a replacement for an installed app.')
    result = subprocess.run([str(java/'bin/keytool'), '-exportcert', '-keystore', str(key),
                             '-storepass', 'rice-development', '-alias', 'rice'], capture_output=True, timeout=20)
    if result.returncode != 0 or hashlib.sha256(result.stdout).hexdigest() != CERT_SHA256:
        raise ValueError('Signing key does not match the installed launcher/VPN certificate. No update is allowed.')


def check_apk(apk, tools, java):
    result = subprocess.run([str(java/'bin/java'), '-jar', str(tools/'lib/apksigner.jar'),
                             'verify', '--print-certs', str(apk)], text=True, capture_output=True, timeout=30)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', result.stdout)
    if result.returncode or [v.lower() for v in certs] != [CERT_SHA256]:
        raise ValueError('APK signature differs from the installed launcher. Refusing deployment; never uninstall to bypass this.')
    return result.stdout


def download(directory, name, digest):
    directory.mkdir(parents=True, exist_ok=True)
    target = directory/name
    if not target.exists():
        partial = directory/(name+'.part')
        try:
            print('Downloading pinned Google SDK:', name, flush=True)
            with urllib.request.urlopen('https://dl.google.com/android/repository/'+name, timeout=90) as response, partial.open('wb') as output:
                shutil.copyfileobj(response, output)
            if hashlib.sha256(partial.read_bytes()).hexdigest() != digest:
                raise ValueError('Downloaded SDK checksum mismatch: '+name)
            partial.replace(target)
        finally:
            if partial.exists(): partial.unlink()
    if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        raise ValueError('Cached SDK checksum mismatch: '+name+'; remove only that corrupt cached archive and retry.')
    return target


def extract_package(archive, sdk, folder):
    destination = sdk/folder
    if destination.exists():
        raise ValueError('Incomplete SDK directory already exists: '+str(destination)+'; use a fresh --sdk directory.')
    with tempfile.TemporaryDirectory(prefix='.extract-', dir=sdk) as temp:
        with zipfile.ZipFile(archive) as source:
            for info in source.infolist():
                path = PurePosixPath(info.filename)
                mode = info.external_attr >> 16
                if not path.parts or path.is_absolute() or '..' in path.parts or '\\' in info.filename or ':' in path.parts[0] or path.parts[0] != folder or mode & 0o170000 == 0o120000:
                    raise ValueError('Unsafe SDK ZIP entry')
            source.extractall(temp)
            for info in source.infolist():
                path = Path(temp)/info.filename
                if path.is_file(): path.chmod(0o755 if (info.external_attr >> 16) & 0o111 else 0o644)
        (Path(temp)/folder).rename(destination)


def check_host_tools(tools):
    magic = (tools/'aapt2').read_bytes()[:4]
    system = host()
    macho = {b'\xcf\xfa\xed\xfe', b'\xfe\xed\xfa\xcf', b'\xca\xfe\xba\xbe', b'\xbe\xba\xfe\xca', b'\xca\xfe\xba\xbf'}
    if (system == 'Linux' and magic != b'\x7fELF') or (system == 'Darwin' and magic not in macho):
        raise ValueError('SDK binaries belong to another operating system; choose a separate --sdk directory.')


def sdk_paths(sdk):
    sdk = Path(sdk)
    tools = sdk/'build-tools/35.0.0'
    if not (tools/'aapt2').is_file(): tools = sdk/'android-15'
    android = sdk/'platforms/android-30/android.jar'
    if not android.is_file(): android = sdk/'android-11/android.jar'
    return tools, android


def ensure_sdk(sdk, bootstrap=False):
    sdk = Path(sdk).resolve()
    system = host()
    tools, android = sdk_paths(sdk)
    required = ['aapt2', 'zipalign', 'lib/d8.jar', 'lib/apksigner.jar']
    ready = all((tools/name).is_file() for name in required)
    if ready: check_host_tools(tools)
    if ready and android.is_file(): return tools, android
    if not bootstrap: raise ValueError('SDK missing. Use ./focal build or --bootstrap, or specify --sdk.')
    sdk.mkdir(parents=True, exist_ok=True)
    packages = ([] if ready else [PACKAGES[system]]) + ([] if android.is_file() else [PLATFORM])
    for name, digest, folder in packages:
        archive = download(sdk/'.downloads', name, digest)
        extract_package(archive, sdk, folder)
    tools, android = sdk_paths(sdk)
    check_host_tools(tools)
    if not all((tools/name).is_file() for name in required) or not android.is_file():
        raise ValueError('SDK extraction incomplete')
    return tools, android

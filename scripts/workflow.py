#!/usr/bin/env python3
"""One command for checked builds/updates. Only 'update' may contact the projector."""
import argparse
import os
from pathlib import Path
import shutil
import re
import subprocess
import sys

from project_config import load_device
from toolchain import REPO, check_signing_key, host, java_home, original_key


def run(command):
    subprocess.run([str(item) for item in command], cwd=REPO, check=True)


def prepare_dependencies(update=False, allow_install=True, java=None, adb='adb'):
    host()
    missing = []
    for executable, package in [('git', 'git')]:
        if not shutil.which(executable): missing.append(package)
    node = os.environ.get('NODE', 'node')
    if not shutil.which(node): missing.append('nodejs')
    else:
        result = subprocess.run([node, '--version'], capture_output=True, text=True, timeout=10)
        version = re.match(r'v(\d+)\.', result.stdout)
        if result.returncode or not version or int(version.group(1)) < 24: missing.append('nodejs')
    try: java_home(java)
    except ValueError:
        if java: raise
        missing.append('jdk21-openjdk')
    if update and not shutil.which(adb): missing.append('android-tools')
    if not missing: return
    if not allow_install or not Path('/etc/arch-release').is_file():
        raise ValueError('Missing dependencies: '+', '.join(missing)+'. On Arch rerun without --no-system-install; elsewhere install them normally.')
    print('Installing missing official Arch packages:', ', '.join(missing), flush=True)
    prefix = [] if os.geteuid() == 0 else ['sudo']
    run(prefix+['pacman', '-S', '--needed']+missing)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['build', 'update', 'check'])
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_SDK_ROOT', REPO/'work/android-sdk')))
    parser.add_argument('--work', type=Path, default=REPO/'work/launcher-build')
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--device-config', type=Path)
    parser.add_argument('--serial', help='Override address only; pinned physical identity stays unchanged')
    parser.add_argument('--keystore', type=Path)
    parser.add_argument('--test-key', action='store_true', help='Build only: explicit disposable test signature; cannot update the owner projector')
    parser.add_argument('--no-system-install', action='store_true', help='Do not invoke pacman/sudo')
    args = parser.parse_args()
    if args.test_key and args.command != 'build': parser.error('--test-key is supported only by build, never by update')
    if args.command == 'update':
        load_device(args.device_config)  # Reject missing/template identity before network or dependency setup.
    key = original_key(args.keystore, args.work)
    if args.command != 'check' and not args.test_key and not key.is_file():
        raise ValueError('Original signing key missing. Extract the separate PRIVATE update kit into the repository. See README.md.')
    if args.command == 'check':
        # Offline checks never install packages, download SDK, or contact a device.
        run([sys.executable, REPO/'scripts/check_repo.py'])
        run([sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts/qa', '-p', 'test_*.py'])
        run([os.environ.get('NODE', 'node'), REPO/'launcher/qa/check_remote_client.cjs'])
        return
    prepare_dependencies(args.command == 'update', not args.no_system_install, args.java_home, args.adb)
    java = java_home(args.java_home)
    if not args.test_key: check_signing_key(key, java)
    run([sys.executable, REPO/'scripts/workflow.py', 'check'])
    build = [sys.executable, REPO/'launcher/build.py', '--bootstrap', '--sdk', args.sdk, '--work', args.work, '--java-home', java]
    build += ['--test-key'] if args.test_key else ['--keystore', key]
    run(build)
    if args.command == 'update':
        install = [sys.executable, REPO/'launcher/install.py', '--sdk', args.sdk, '--java-home', java, '--adb', args.adb]
        if args.device_config: install += ['--device-config', args.device_config]
        if args.serial: install += ['--serial', args.serial]
        run(install)
        print('Updated without reboot or data reset. Refresh the web remote page on your phone.')


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print('focal:', str(error), file=sys.stderr)
        sys.exit(1)

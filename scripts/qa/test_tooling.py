"""Safety contracts for bootstrap/deployment. No network, SDK or real ADB calls."""
import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import toolchain
import workflow

spec = importlib.util.spec_from_file_location('focal_install_tested', SCRIPTS.parent/'launcher/install.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class BootstrapContracts(unittest.TestCase):
    def test_linux_archive_is_not_the_mac_archive(self):
        with patch.object(toolchain.platform, 'system', return_value='Linux'), patch.object(toolchain.platform, 'machine', return_value='x86_64'):
            self.assertEqual(toolchain.PACKAGES[toolchain.host()][0], 'build-tools_r35_linux.zip')
        with patch.object(toolchain.platform, 'system', return_value='Linux'), patch.object(toolchain.platform, 'machine', return_value='aarch64'):
            with self.assertRaises(ValueError): toolchain.host()

    def test_wrong_host_sdk_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root/'aapt2').write_bytes(b'\xcf\xfa\xed\xfe')
            with patch.object(toolchain, 'host', return_value='Linux'):
                with self.assertRaises(ValueError): toolchain.check_host_tools(root)

    def test_zip_paths_and_symlinks_are_rejected_before_extraction(self):
        for name, mode in [('../outside', 0), ('/outside', 0), ('android-15/../../outside', 0), ('android-15/link', 0o120777), ('C:/outside', 0)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); sdk=root/'sdk'; sdk.mkdir(); archive=root/'sdk.zip'
                with zipfile.ZipFile(archive, 'w') as output:
                    entry=zipfile.ZipInfo(name);entry.external_attr=mode<<16;output.writestr(entry, b'data')
                with self.assertRaises(ValueError): toolchain.extract_package(archive, sdk, 'android-15')
                self.assertFalse((sdk/'android-15').exists())
                self.assertFalse((root/'outside').exists())

    def test_corrupt_cache_does_not_trigger_download_or_extraction(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);(directory/'sdk.zip').write_bytes(b'bad')
            with patch.object(toolchain.urllib.request, 'urlopen') as network:
                with self.assertRaises(ValueError): toolchain.download(directory, 'sdk.zip', '0'*64)
                network.assert_not_called()

    def test_bad_download_is_not_installed_or_retained(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(toolchain.urllib.request, 'urlopen', return_value=io.BytesIO(b'bad')):
                with self.assertRaises(ValueError): toolchain.download(Path(temp), 'sdk.zip', '0'*64)
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_standard_sdk_layout_is_supported_without_download(self):
        with tempfile.TemporaryDirectory() as temp:
            sdk=Path(temp);tools=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-30/android.jar'
            for name in ['aapt2','zipalign','lib/d8.jar','lib/apksigner.jar']:
                p=tools/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'\x7fELF')
            android.parent.mkdir(parents=True);android.write_bytes(b'jar')
            with patch.object(toolchain, 'host', return_value='Linux'), patch.object(toolchain, 'download') as network:
                self.assertEqual(toolchain.ensure_sdk(sdk), (tools.resolve(),android.resolve()));network.assert_not_called()

    def test_original_key_is_checked_by_certificate(self):
        with tempfile.TemporaryDirectory() as temp:
            key=Path(temp)/'key.jks';key.touch()
            response=subprocess.CompletedProcess([], 0, stdout=b'wrong certificate', stderr=b'')
            with patch.object(toolchain.subprocess, 'run', return_value=response):
                with self.assertRaises(ValueError): toolchain.check_signing_key(key, Path(temp))


class UpdateContracts(unittest.TestCase):
    def test_arch_setup_installs_missing_packages_without_system_upgrade(self):
        with patch.object(workflow,'host',return_value='Linux'), patch.object(workflow.shutil,'which',return_value=None), patch.object(workflow,'java_home',side_effect=ValueError('missing')), patch.object(Path,'is_file',return_value=True), patch.object(workflow.os,'geteuid',return_value=1000), patch.object(workflow,'run') as commands:
            workflow.prepare_dependencies(update=True)
            command=commands.call_args.args[0]
            self.assertEqual(command[:4],['sudo','pacman','-S','--needed'])
            self.assertIn('jdk21-openjdk',command);self.assertIn('android-tools',command)
            self.assertNotIn('-Syu',command);self.assertNotIn('--noconfirm',command)

    def test_no_system_install_means_no_pacman(self):
        with patch.object(workflow,'host',return_value='Linux'), patch.object(workflow.shutil,'which',return_value=None), patch.object(workflow,'java_home',side_effect=ValueError('missing')), patch.object(workflow,'run') as commands:
            with self.assertRaises(ValueError): workflow.prepare_dependencies(allow_install=False)
            commands.assert_not_called()

    def test_update_without_private_identity_stops_before_setup(self):
        with patch.object(sys,'argv',['focal','update','--device-config','/nonexistent/focal-device.json']), patch.object(workflow,'prepare_dependencies') as prepare:
            with self.assertRaises(ValueError): workflow.main()
            prepare.assert_not_called()

    def test_update_without_key_stops_before_setup(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(sys,'argv',['focal','update','--keystore',str(Path(temp)/'absent.jks')]), patch.object(workflow,'load_device'), patch.object(workflow,'prepare_dependencies') as prepare:
                with self.assertRaises(ValueError): workflow.main()
                prepare.assert_not_called()

    def test_test_signature_cannot_be_deployed(self):
        with patch.object(sys,'argv',['focal','update','--test-key']), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit): workflow.main()

    def test_failed_build_never_calls_installer(self):
        with tempfile.TemporaryDirectory() as temp:
            key=Path(temp)/'key.jks';key.touch();commands=[]
            def run(command):
                commands.append([str(v) for v in command])
                if any(str(v).endswith('/launcher/build.py') for v in command): raise subprocess.CalledProcessError(1, 'test build')
            with patch.object(sys,'argv',['focal','update','--keystore',str(key)]), patch.object(workflow,'load_device'), patch.object(workflow,'prepare_dependencies'), patch.object(workflow,'java_home',return_value=Path(temp)), patch.object(workflow,'check_signing_key'), patch.object(workflow,'run',side_effect=run):
                with self.assertRaises(subprocess.CalledProcessError): workflow.main()
            self.assertFalse(any(any(v.endswith('/launcher/install.py') for v in c) for c in commands))

    def installation_context(self, temp):
        apk=Path(temp)/'launcher.apk';apk.write_bytes(b'test APK')
        apk.with_suffix('.apk.sha256').write_text(hashlib.sha256(apk.read_bytes()).hexdigest())
        return ['install','--apk',str(apk)]

    def test_wrong_apk_signature_causes_no_adb_calls(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(sys,'argv',self.installation_context(temp)), patch.object(installer,'load_device',return_value={'adb_serial':'test.local:5555','expected':{}}), patch.object(installer,'ensure_sdk',return_value=(Path(temp),None)), patch.object(installer,'java_home',return_value=Path(temp)), patch.object(installer,'check_apk',side_effect=ValueError('signature mismatch')), patch.object(installer.subprocess,'run') as calls:
                with self.assertRaises(ValueError): installer.main()
                calls.assert_not_called()

    def test_wrong_device_identity_causes_no_staging_or_install(self):
        with tempfile.TemporaryDirectory() as temp:
            config={'adb_serial':'test.local:5555','expected':{'ro.serialno':'ExpectedDevice'}}
            with patch.object(sys,'argv',self.installation_context(temp)), patch.object(installer,'load_device',return_value=config), patch.object(installer,'ensure_sdk',return_value=(Path(temp),None)), patch.object(installer,'java_home',return_value=Path(temp)), patch.object(installer,'check_apk'), patch.object(installer.subprocess,'check_output',return_value='AnotherDevice') as reads, patch.object(installer.subprocess,'run') as calls:
                with self.assertRaises(RuntimeError): installer.main()
                self.assertEqual(calls.call_count,1)
                self.assertEqual(calls.call_args.args[0][1:],[ 'connect','test.local:5555'])
                self.assertEqual(reads.call_count,1)


if __name__ == '__main__': unittest.main()

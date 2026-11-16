import contextlib
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('repose_build', ROOT / 'build.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'source'
        self.out = Path(self.temp.name) / 'output'
        (self.root / 'module').mkdir(parents=True)
        (self.root / 'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                                                     'android:versionCode="1" android:versionName="1.0.0"/>')
        (self.root / 'module/module.prop').write_text('id=repose\nversion=1.0.0\nversionCode=1\n')
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(build, 'ROOT', self.root).start()
        self.env = dict(ANDROID_HOME='/sdk', ANDROID_CC='android-cc',
                        SIGNING_KEYSTORE='/permanent/repose.keystore', SIGNING_KEY_ALIAS='repose',
                        SIGNING_STORE_PASSWORD='  store secret  ', SIGNING_KEY_PASSWORD=' key secret ',
                        SIGNING_CERT_SHA256='a' * 64)

    def invoke(self, args, env, run):
        with mock.patch.dict(os.environ, env, clear=True), \
                mock.patch('sys.argv', ['build.py', '--out', str(self.out), *args]), \
                mock.patch.object(build.subprocess, 'run', side_effect=run), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            build.main()

    def fake_run(self, command, **kwargs):
        if 'assembleRelease' in command or 'assembleDebug' in command:
            variant = 'debug' if 'assembleDebug' in command else 'release'
            source = self.out / f'gradle/outputs/apk/{variant}/Repose-{variant}{"" if variant == "debug" else "-unsigned"}.apk'
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(b'fixture apk')
        elif 'sign' in command:
            self.assertNotIn(self.env['SIGNING_STORE_PASSWORD'], command)
            self.assertNotIn(self.env['SIGNING_KEY_PASSWORD'], command)
            self.assertEqual(kwargs['env']['SIGNING_STORE_PASSWORD'], self.env['SIGNING_STORE_PASSWORD'])
            Path(command[-1]).write_bytes(b'fixture signed apk')
        elif 'verify' in command:
            self.assertIn('--print-certs', command)
            return subprocess.CompletedProcess(command, 0, self.signers, '')
        elif command[0] == 'android-cc':
            Path(command[-1]).write_bytes(b'fixture native library')
        else:
            self.fail(command)
        return subprocess.CompletedProcess(command, 0, '', '')

    def test_version_has_no_suffix_and_matches_module(self):
        self.assertEqual(build.release_tag(), '1-1.0.0')
        manifest = self.root / 'AndroidManifest.xml'
        original = manifest.read_text()
        for invalid in ('1.0.0-experimental', '1.0.0-rc1', '1.0.0 preview', ''):
            manifest.write_text(original.replace('versionName="1.0.0"', f'versionName="{invalid}"'))
            with self.assertRaisesRegex(ValueError, 'without suffixes'):
                build.release_tag()
        manifest.write_text(original.replace('versionName="1.0.0"', 'versionName="2.0.0"'))
        with self.assertRaisesRegex(ValueError, 'must match'):
            build.release_tag()

    def test_release_requires_every_signing_input_before_running_commands(self):
        for name in self.env:
            if not name.startswith('SIGNING_'):
                continue
            env = dict(self.env); env.pop(name)
            run = mock.Mock()
            with self.assertRaises(SystemExit):
                self.invoke(['--release'], env, run)
            run.assert_not_called()

    def test_key_and_certificate_guards(self):
        with mock.patch.object(Path, 'is_file', return_value=False):
            with self.assertRaisesRegex(ValueError, 'refusing to generate'):
                build.signing_config(self.env, self.out)
        with mock.patch.object(Path, 'is_file', return_value=True):
            for location in (self.root / 'key.jks', self.out / 'key.jks', Path('/home/test/.cache/key.jks')):
                with self.assertRaisesRegex(ValueError, 'outside source, output and cache'):
                    build.signing_config(dict(self.env, SIGNING_KEYSTORE=str(location)), self.out)
            for digest in ('', 'abc', 'x' * 64, 'a' * 63):
                with self.assertRaises(ValueError):
                    build.signing_config(dict(self.env, SIGNING_CERT_SHA256=digest), self.out)
            _, digest = build.signing_config(dict(self.env, SIGNING_CERT_SHA256='AA:' * 31 + 'AA'), self.out)
            self.assertEqual(digest, 'a' * 64)

    def test_verified_signed_apk_is_exactly_the_embedded_apk(self):
        self.signers = 'Signer #1 certificate SHA-256 digest: ' + 'a' * 64 + '\n'
        with mock.patch.object(Path, 'is_file', return_value=True):
            self.invoke(['--release'], self.env, self.fake_run)
        with zipfile.ZipFile(self.out / 'Repose-module.zip') as archive:
            self.assertEqual(archive.read('repose.apk'), (self.out / 'Repose.apk').read_bytes())
            self.assertEqual(archive.read('lib/arm64-v8a/librepose.so'), (self.out / 'librepose.so').read_bytes())
        for name in ('Repose.apk', 'Repose-module.zip'):
            digest = hashlib.sha256((self.out / name).read_bytes()).hexdigest()
            self.assertEqual((self.out / (name + '.sha256')).read_text(), f'{digest}  {name}\n')

    def test_missing_wrong_or_multiple_signers_never_package_a_release(self):
        for report in ('', 'Signer #1 certificate SHA-256 digest: ' + 'b' * 64,
                       '\n'.join(f'Signer #{i} certificate SHA-256 digest: ' + 'a' * 64 for i in (1, 2))):
            self.signers = report
            with mock.patch.object(Path, 'is_file', return_value=True), self.assertRaises(SystemExit):
                self.invoke(['--release'], self.env, self.fake_run)
            self.assertFalse((self.out / 'Repose-module.zip').exists())
            self.assertFalse((self.out / 'Repose.apk.sha256').exists())

    def test_unsigned_build_needs_no_signing_material(self):
        env = {key: value for key, value in self.env.items() if not key.startswith('SIGNING_')}
        self.invoke([], env, self.fake_run)
        self.assertTrue((self.out / 'Repose-unsigned.apk').is_file())
        with zipfile.ZipFile(self.out / 'Repose-module.zip') as archive:
            self.assertEqual(archive.read('repose.apk'), b'fixture apk')

    def test_modes_are_exclusive_and_version_is_toolchain_free(self):
        run = mock.Mock()
        with self.assertRaises(SystemExit):
            self.invoke(['--release', '--debug'], {}, run)
        self.invoke(['--version'], {}, run)
        run.assert_not_called()
        self.assertFalse(self.out.exists())


if __name__ == '__main__':
    unittest.main()

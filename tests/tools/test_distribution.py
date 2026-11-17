import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('verify_release', ROOT / 'tools/verify_release.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
ASSETS = ['Repose.apk', 'Repose.apk.sha256', 'Repose-module.zip', 'Repose-module.zip.sha256']


class ReleaseFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assets = self.root / 'assets'
        self.assets.mkdir()
        (self.assets / 'Repose.apk').write_bytes(b'signed APK fixture')
        self.make_module()
        self.pin = 'a' * 64
        self.signers = 'Signer #1 certificate SHA-256 digest: ' + self.pin + '\n'
        self.badging = "package: name='com.lixingchi.repose' versionCode='1' versionName='1.0.0'\n"

    def checksums(self):
        for name in ('Repose.apk', 'Repose-module.zip'):
            digest = hashlib.sha256((self.assets / name).read_bytes()).hexdigest()
            (self.assets / (name + '.sha256')).write_text(f'{digest}  {name}\n')

    def make_module(self, apk=b'signed APK fixture', properties='id=repose\nversionCode=1\nversion=1.0.0\n', duplicate=None):
        with zipfile.ZipFile(self.assets / 'Repose-module.zip', 'w') as archive:
            archive.writestr('repose.apk', apk)
            archive.writestr('module.prop', properties)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    archive.writestr(duplicate, b'duplicate')
        self.checksums()


class VerificationTest(ReleaseFixture):
    def verify(self, tag='1-1.0.0', pin=None):
        def run(args, **kwargs):
            self.assertTrue(kwargs['check'])
            return subprocess.CompletedProcess(args, 0, self.signers if 'verify' in args else self.badging, '')
        with mock.patch.object(verifier.subprocess, 'run', side_effect=run):
            verifier.verify(self.assets, tag, self.pin if pin is None else pin, Path('/tools'))

    def test_exact_pair_passes_with_formatted_certificate_pin(self):
        self.verify(pin='AA:' * 31 + 'AA')

    def test_bad_tag_pin_checksum_or_filename_fails(self):
        for tag in ('1-1.0.0-beta', '../1', '0-1.0.0'):
            with self.assertRaises(ValueError):
                self.verify(tag=tag)
        for pin in ('', 'z' * 64, 'a' * 63):
            with self.assertRaises(ValueError):
                self.verify(pin=pin)
        for name in ASSETS:
            path = self.assets / name
            original = path.read_bytes()
            path.write_bytes(original + b'changed')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                self.verify()
            path.write_bytes(original)
        (self.assets / 'Repose.apk.sha256').write_text(hashlib.sha256(b'signed APK fixture').hexdigest() + '  other.apk\n')
        with self.assertRaisesRegex(ValueError, 'filename'):
            self.verify()

    def test_missing_wrong_or_multiple_signers_fail(self):
        good = self.signers
        for report in ('', good.replace('a' * 64, 'b' * 64), good + good.replace('#1', '#2')):
            self.signers = report
            with self.assertRaisesRegex(ValueError, 'certificate'):
                self.verify()

    def test_invalid_signature_propagates_tool_failure(self):
        with mock.patch.object(verifier.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'apksigner')):
            with self.assertRaises(subprocess.CalledProcessError):
                verifier.verify(self.assets, '1-1.0.0', self.pin, Path('/tools'))

    def test_wrong_package_or_version_fails(self):
        good = self.badging
        for text in ('', good.replace('com.lixingchi.repose', 'another.package'),
                     good.replace("versionCode='1'", "versionCode='2'"),
                     good.replace("versionName='1.0.0'", "versionName='2.0.0'")):
            self.badging = text
            with self.assertRaisesRegex(ValueError, 'package/version'):
                self.verify()

    def test_mismatched_apk_module_identity_version_and_duplicate_entries_fail(self):
        cases = [dict(apk=b'other APK'), dict(properties='id=other\nversionCode=1\nversion=1.0.0\n'),
                 dict(properties='id=repose\nversionCode=2\nversion=1.0.0\n'),
                 dict(properties='id=repose\nversionCode=1\nversion=2.0.0\n'),
                 dict(duplicate='repose.apk'), dict(duplicate='module.prop')]
        for case in cases:
            with self.subTest(case=case):
                self.make_module(**case)
                with self.assertRaises(ValueError):
                    self.verify()


class DistributionTest(ReleaseFixture):
    def setUp(self):
        super().setUp()
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        tools = self.root / 'sdk/build-tools/36.0.0'
        tools.mkdir(parents=True)
        for name, output in [('apksigner', self.signers), ('aapt2', self.badging)]:
            path = tools / name
            path.write_text('#!/usr/bin/env python3\nprint(' + repr(output) + ')\n')
            path.chmod(0o700)
        gh = self.bin / 'gh'
        gh.write_text('''#!/usr/bin/env python3
import json, os, pathlib, shutil, sys
args = sys.argv[1:]
role = {'fixture-source': 'source', 'fixture-listing': 'listing'}.get(os.environ.get('GH_TOKEN'))
assert role is not None
with open(os.environ['CALLS'], 'a') as out:
    out.write(json.dumps({'args': args, 'role': role}) + '\\n')
if args[:2] == ['api', 'repos/doccstat/repose/releases/latest']:
    assert role == 'source'
    print(json.dumps({'tag_name': '1-1.0.0', 'name': '1.0.0', 'body': os.environ['SOURCE_BODY'],
                      'draft': os.environ.get('DRAFT') == '1', 'prerelease': os.environ.get('PRERELEASE') == '1'}))
elif args[:2] == ['release', 'download']:
    assert role == 'source' and args[2] == '1-1.0.0'
    directory = pathlib.Path(args[args.index('--dir') + 1])
    for path in pathlib.Path(os.environ['FIXTURES']).iterdir():
        shutil.copyfile(path, directory / path.name)
elif args[:2] == ['api', 'repos/Xposed-Modules-Repo/com.lixingchi.repose/commits/main']:
    assert role == 'listing'
    if os.environ.get('EMPTY_LISTING') == '1': sys.exit(1)
    print('b' * 40)
elif args[:3] == ['api', '--paginate', '--slurp']:
    assert role == 'listing'
    if os.environ.get('FAIL_LIST') == '1': sys.exit(1)
    print(json.dumps([[{'tag_name': '1-1.0.0'}]] if os.environ.get('EXISTS') == '1' else [[]]))
elif args[:2] in (['release', 'create'], ['release', 'edit'], ['release', 'upload']):
    assert role == 'listing'
    assert args[args.index('--repo') + 1] == 'Xposed-Modules-Repo/com.lixingchi.repose'
    if '--notes-file' in args:
        assert pathlib.Path(args[args.index('--notes-file') + 1]).read_text() == os.environ['SOURCE_BODY'] + '\\n'
    for name in args:
        if name.endswith(('.apk', '.zip', '.sha256')):
            assert pathlib.Path(name).read_bytes() == (pathlib.Path(os.environ['FIXTURES']) / name).read_bytes()
else:
    sys.exit(99)
''')
        gh.chmod(0o700)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        GH_TOKEN='fixture-source', LSPOSED_RELEASE_TOKEN='fixture-listing',
                        SIGNING_CERT_SHA256=self.pin, ANDROID_HOME=str(self.root / 'sdk'),
                        RUNNER_TEMP=str(self.root), GITHUB_REPOSITORY='doccstat/repose',
                        FIXTURES=str(self.assets), CALLS=str(self.root / 'calls.jsonl'),
                        SOURCE_BODY='Release notes: café / 工作')

    def run_distribution(self, **env):
        log = self.root / 'calls.jsonl'
        log.unlink(missing_ok=True)
        result = subprocess.run(['bash', str(ROOT / 'tools/distribute.sh')], cwd=ROOT,
                                env=dict(self.env, **env), capture_output=True, text=True)
        calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        self.assertFalse(list(self.root.glob('repose-distribute.*')))
        return result, calls

    def test_create_and_update_keep_exact_assets_version_notes_and_stable_latest(self):
        for exists in ('0', '1'):
            result, calls = self.run_distribution(EXISTS=exists)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(calls[0]['args'], ['api', 'repos/doccstat/repose/releases/latest'])
            publish = [c['args'] for c in calls if c['args'][:2] in
                       (['release', 'create'], ['release', 'edit'], ['release', 'upload'])]
            self.assertEqual(len(publish), 1 if exists == '0' else 2)
            for flag in ('--draft=false', '--prerelease=false', '--latest'):
                self.assertIn(flag, publish[-1])
            self.assertEqual(publish[0][-4:], ASSETS)
            self.assertEqual(publish[-1][2], '1-1.0.0')
            self.assertEqual(publish[-1][publish[-1].index('--title') + 1], '1.0.0')
            self.assertEqual([c['role'] for c in calls[:2]], ['source', 'source'])
            self.assertTrue(all(c['role'] == 'listing' for c in calls[2:]))

    def test_prerelease_draft_missing_token_or_wrong_repository_never_publish(self):
        for env in ({'PRERELEASE': '1'}, {'DRAFT': '1'}, {'LSPOSED_RELEASE_TOKEN': ''},
                    {'GITHUB_REPOSITORY': 'other/repose'}):
            result, calls = self.run_distribution(**env)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(any(c['role'] == 'listing' for c in calls))

    def test_corrupt_pair_or_wrong_certificate_never_contacts_listing(self):
        result, calls = self.run_distribution(SIGNING_CERT_SHA256='b' * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c['role'] == 'listing' for c in calls))
        self.make_module(apk=b'different APK with correct ZIP checksum')
        result, calls = self.run_distribution()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c['role'] == 'listing' for c in calls))

    def test_empty_listing_or_api_failure_never_becomes_release_creation(self):
        for env in ({'EMPTY_LISTING': '1'}, {'FAIL_LIST': '1'}):
            result, calls = self.run_distribution(**env)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(any(c['args'][:2] in (['release', 'create'], ['release', 'edit'], ['release', 'upload'])
                                 for c in calls))


if __name__ == '__main__':
    unittest.main()

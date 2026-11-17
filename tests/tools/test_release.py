import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copyfile(ROOT / 'build.py', self.root / 'build.py')
        (self.root / 'module').mkdir()
        (self.root / 'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                                                     'android:versionCode="1" android:versionName="1.0.0"/>')
        (self.root / 'module/module.prop').write_text('id=repose\nversion=1.0.0\nversionCode=1\n')
        (self.root / 'dist').mkdir()
        for name in ('Repose.apk', 'Repose-module.zip'):
            (self.root / 'dist' / name).write_bytes(b'fixture')
            (self.root / 'dist' / (name + '.sha256')).write_text(hashlib.sha256(b'fixture').hexdigest() + '  ' + name + '\n')
        (self.root / 'bin').mkdir()
        gh = self.root / 'bin/gh'
        gh.write_text('''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as out:
    out.write(json.dumps(args) + '\\n')
if args[:2] == ['api', 'repos/doccstat/repose/commits/main']:
    print(os.environ['HEAD_SHA'])
elif args[:3] == ['api', '--paginate', '--slurp']:
    if os.environ.get('FAIL_LIST') == '1': sys.exit(1)
    print(json.dumps([[{'tag_name': '1-1.0.0'}]] if os.environ.get('EXISTS') == '1' else [[]]))
elif args[:2] == ['workflow', 'run']:
    if os.environ.get('FAIL_DISPATCH') == '1': sys.exit(1)
elif args[0] != 'release' and not (args[0] == 'api' and '--method' in args):
    sys.exit(99)
''')
        gh.chmod(0o700)
        self.env = dict(os.environ, PATH=str(self.root / 'bin') + os.pathsep + os.environ['PATH'],
                        CALLS=str(self.root / 'calls.jsonl'), GITHUB_REPOSITORY='doccstat/repose',
                        GITHUB_REF='refs/heads/main', GITHUB_SHA='a' * 40, HEAD_SHA='a' * 40)

    def run_release(self, **env):
        result = subprocess.run(['bash', str(ROOT / 'tools/release.sh')], cwd=self.root,
                                env=dict(self.env, **env), capture_output=True, text=True)
        log = self.root / 'calls.jsonl'
        return result, [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []

    def test_create_is_stable_latest_and_has_both_artifacts_and_checksums(self):
        result, calls = self.run_release()
        self.assertEqual(result.returncode, 0, result.stderr)
        create = next(call for call in calls if call[:2] == ['release', 'create'])
        self.assertEqual(create[2], '1-1.0.0')
        self.assertEqual(create[create.index('--title') + 1], '1.0.0')
        for flag in ('--draft=false', '--prerelease=false', '--latest'):
            self.assertIn(flag, create)
        self.assertEqual(create[-4:], ['dist/Repose.apk', 'dist/Repose.apk.sha256',
                                      'dist/Repose-module.zip', 'dist/Repose-module.zip.sha256'])

    def test_existing_version_replaces_assets_and_stays_stable(self):
        result, calls = self.run_release(EXISTS='1')
        self.assertEqual(result.returncode, 0, result.stderr)
        patch = next(call for call in calls if '--method' in call)
        self.assertIn('repos/doccstat/repose/git/refs/tags/1-1.0.0', patch)
        self.assertIn('sha=' + 'a' * 40, patch)
        upload = next(call for call in calls if call[:2] == ['release', 'upload'])
        self.assertIn('--clobber', upload)
        self.assertEqual(len([arg for arg in upload if arg.startswith('dist/')]), 4)
        edit = next(call for call in calls if call[:2] == ['release', 'edit'])
        for flag in ('--draft=false', '--prerelease=false', '--latest'):
            self.assertIn(flag, edit)
        self.assertFalse(any(call[:2] == ['release', 'create'] for call in calls))

    def test_create_and_update_dispatch_distribution_after_publishing(self):
        for exists in ('0', '1'):
            result, calls = self.run_release(EXISTS=exists)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(calls[-1], ['workflow', 'run', 'distribute-release.yml',
                                        '--repo', 'doccstat/repose', '--ref', 'main'])
            self.assertIn(calls[-2][:2], (['release', 'create'], ['release', 'edit']))

    def test_dispatch_failure_is_reported(self):
        result, calls = self.run_release(FAIL_DISPATCH='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls[-1][:2], ['workflow', 'run'])

    def test_superseded_commit_never_writes(self):
        result, calls = self.run_release(HEAD_SHA='b' * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 1)
        self.assertIn('superseded', result.stdout)

    def test_other_repository_or_branch_never_contacts_github(self):
        for env in ({'GITHUB_REPOSITORY': 'someone/repose'}, {'GITHUB_REF': 'refs/heads/topic'}):
            result, calls = self.run_release(**env)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(calls, [])

    def test_corrupt_artifact_never_publishes(self):
        (self.root / 'dist/Repose-module.zip').write_bytes(b'changed')
        result, calls = self.run_release()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(calls), 1)

    def test_api_failure_is_not_treated_as_an_absent_release(self):
        result, calls = self.run_release(FAIL_LIST='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(call[0] == 'release' or '--method' in call for call in calls))


if __name__ == '__main__':
    unittest.main()

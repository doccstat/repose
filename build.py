#!/usr/bin/env python3
"""Build and verify a matched APK/module pair; source and credentials stay outside output."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--test', action='store_true', help='run JVM, UI, protocol and packaging tests')
    parser.add_argument('--lint', action='store_true')
    parser.add_argument('--debug', action='store_true', help='build an installable development-signed pair')
    parser.add_argument('--out', type=Path, default=Path.home() / '.cache/repose')
    args = parser.parse_args()
    out = args.out.resolve()
    if ROOT == out or ROOT in out.parents and out != ROOT / 'dist':
        parser.error('output must be outside source or exactly dist/')
    out.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1')
    env.setdefault('GRADLE_USER_HOME', str(out / 'gradle-home'))
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = [str(wrapper), '--no-daemon', '--no-problems-report', '--project-cache-dir', str(out / 'project-cache'), f'-PreposeBuildDir={out / "gradle"}']
    if env.get('ANDROID_AAPT2'):
        command.append('-Pandroid.aapt2FromMavenOverride=' + env['ANDROID_AAPT2'])
    if args.test:
        subprocess.run(command + ['testDebugUnitTest'], cwd=ROOT, env=env, check=True)
        subprocess.run(['python3', '-m', 'unittest', 'discover', '-s', str(ROOT / 'tests/tools'), '-v'], cwd=ROOT, env=env, check=True)
        return
    if args.lint:
        subprocess.run(command + ['lintRelease'], cwd=ROOT, env=env, check=True)
        return
    variant = 'debug' if args.debug else 'release'
    subprocess.run(command + ['assemble' + variant.title()], cwd=ROOT, env=env, check=True)
    source = out / f'gradle/outputs/apk/{variant}/Repose-{variant}{"" if args.debug else "-unsigned"}.apk'
    apk = out / ('Repose.apk' if args.debug or env.get('SIGNING_KEYSTORE') else 'Repose-unsigned.apk')
    shutil.copyfile(source, apk)
    if not args.debug and env.get('SIGNING_KEYSTORE'):
        key = Path(env['SIGNING_KEYSTORE']).resolve()
        if not key.is_file() or ROOT in key.parents or '.cache' in key.parts:
            parser.error('use an existing permanent signing key outside source and cache')
        tools = Path(env['ANDROID_HOME']) / 'build-tools/36.0.0'
        signer = tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
        subprocess.run([str(signer), 'sign', '--ks', str(key), '--ks-key-alias', env['SIGNING_KEY_ALIAS'],
                        '--ks-pass', 'env:SIGNING_STORE_PASSWORD', '--key-pass', 'env:SIGNING_KEY_PASSWORD', str(apk)], check=True, env=env)
        subprocess.run([str(signer), 'verify', '--verbose', str(apk)], check=True, env=env)
    compiler = env.get('ANDROID_CC')
    if not compiler:
        ndk = Path(env['ANDROID_NDK_HOME'])
        host = 'darwin-x86_64' if os.uname().sysname == 'Darwin' else 'linux-x86_64'
        compiler = str(ndk / f'toolchains/llvm/prebuilt/{host}/bin/aarch64-linux-android33-clang')
    library = out / 'librepose.so'
    subprocess.run([compiler, '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror', '-Wl,-z,relro,-z,now,-z,max-page-size=16384',
                    str(ROOT / 'native/alarm.c'), '-o', str(library)], check=True, env=env)
    archive = out / 'Repose-module.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as package:
        for path in sorted((ROOT / 'module').rglob('*')):
            if path.is_file(): package.write(path, str(path.relative_to(ROOT / 'module')))
        package.write(apk, 'repose.apk')
        package.write(library, 'lib/arm64-v8a/librepose.so')
    for path in (apk, archive):
        checksum = hashlib.sha256(path.read_bytes()).hexdigest()
        (out / (path.name + '.sha256')).write_text(f'{checksum}  {path.name}\n')
        print(path, checksum)


if __name__ == '__main__':
    main()

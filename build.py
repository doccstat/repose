#!/usr/bin/env python3
"""Build and verify a matched APK/module pair; source and credentials stay outside output."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def release_tag():
    manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
    android = '{http://schemas.android.com/apk/res/android}'
    code, version = (manifest.attrib[android + key] for key in ('versionCode', 'versionName'))
    if not re.fullmatch(r'[1-9][0-9]*', code) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', version):
        raise ValueError('Use a positive versionCode and a numeric versionName without suffixes')
    properties = dict(line.split('=', 1) for line in (ROOT / 'module/module.prop').read_text().splitlines()
                      if line and not line.startswith('#'))
    if (properties['id'], properties['versionCode'], properties['version']) != ('repose', code, version):
        raise ValueError('APK and module identity/version must match')
    return code + '-' + version


def signing_config(env, out):
    required = ('SIGNING_KEYSTORE', 'SIGNING_STORE_PASSWORD', 'SIGNING_KEY_PASSWORD',
                'SIGNING_KEY_ALIAS', 'SIGNING_CERT_SHA256')
    missing = [name for name in required if not env.get(name)]
    if missing:
        raise ValueError('Release signing requires: ' + ', '.join(missing))
    key = Path(env['SIGNING_KEYSTORE']).resolve()
    if not key.is_file():
        raise ValueError('Release keystore does not exist; refusing to generate a replacement')
    if ROOT in key.parents or out in key.parents or '.cache' in key.parts:
        raise ValueError('Keep the permanent signing key outside source, output and cache directories')
    digest = env['SIGNING_CERT_SHA256'].replace(':', '').strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', digest):
        raise ValueError('SIGNING_CERT_SHA256 must be a certificate SHA-256 fingerprint')
    return key, digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--test', action='store_true', help='run JVM, UI, protocol and packaging tests')
    mode.add_argument('--lint', action='store_true')
    mode.add_argument('--debug', action='store_true', help='build an installable development-signed pair')
    mode.add_argument('--release', action='store_true', help='require permanent signing and verify its certificate pin')
    mode.add_argument('--version', action='store_true', help='validate APK/module versions and print their release tag')
    parser.add_argument('--out', type=Path, default=Path.home() / '.cache/repose')
    args = parser.parse_args()
    try:
        tag = release_tag()
    except (ValueError, KeyError) as error:
        parser.error(str(error))
    if args.version:
        print(tag)
        return
    out = args.out.resolve()
    if ROOT == out or ROOT in out.parents and out != ROOT / 'dist':
        parser.error('output must be outside source or exactly dist/')
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1')
    signing = args.release or bool(env.get('SIGNING_KEYSTORE')) and not (args.debug or args.test or args.lint)
    if signing:
        try:
            key, expected_signer = signing_config(env, out)
        except ValueError as error:
            parser.error(str(error))
    out.mkdir(parents=True, exist_ok=True)
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
    apk = out / ('Repose.apk' if args.debug or signing else 'Repose-unsigned.apk')
    shutil.copyfile(source, apk)
    if signing:
        tools = Path(env['ANDROID_HOME']) / 'build-tools/36.0.0'
        signer = tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
        subprocess.run([str(signer), 'sign', '--ks', str(key), '--ks-key-alias', env['SIGNING_KEY_ALIAS'],
                        '--ks-pass', 'env:SIGNING_STORE_PASSWORD', '--key-pass', 'env:SIGNING_KEY_PASSWORD', str(apk)], check=True, env=env)
        verified = subprocess.run([str(signer), 'verify', '--verbose', '--print-certs', str(apk)],
                                  check=True, env=env, capture_output=True, text=True)
        certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)',
                                  verified.stdout + verified.stderr)
        if [digest.replace(':', '').lower() for digest in certificates] != [expected_signer]:
            parser.error('APK certificate does not match SIGNING_CERT_SHA256')
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

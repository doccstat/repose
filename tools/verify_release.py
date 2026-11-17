#!/usr/bin/env python3
"""Verify a downloaded matched release before distribution; never signs or publishes."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import zipfile


def verify(directory, tag, pin, tools):
    if not re.fullmatch(r'[1-9][0-9]*-[0-9]+(?:\.[0-9]+)*', tag):
        raise ValueError('Expected a numeric versionCode-versionName release tag')
    expected = pin.replace(':', '').strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('Set ANDROID_SIGNING_CERT_SHA256 to the certificate fingerprint')
    for name in ('Repose.apk', 'Repose-module.zip'):
        digest = hashlib.sha256((directory / name).read_bytes()).hexdigest()
        if (directory / (name + '.sha256')).read_text(encoding='ascii') != f'{digest}  {name}\n':
            raise ValueError('Release checksum or filename does not match: ' + name)
    apk = directory / 'Repose.apk'
    result = subprocess.run([str(tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')),
                             'verify', '--verbose', '--print-certs', str(apk)],
                            check=True, capture_output=True, text=True)
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)',
                              result.stdout + result.stderr)
    if [digest.replace(':', '').lower() for digest in certificates] != [expected]:
        raise ValueError('APK certificate does not match ANDROID_SIGNING_CERT_SHA256')
    result = subprocess.run([str(tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2')),
                             'dump', 'badging', str(apk)], check=True, capture_output=True, text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
                        result.stdout, re.MULTILINE)
    code, version = tag.split('-', 1)
    if not package or package.groups() != ('com.lixingchi.repose', code, version):
        raise ValueError('APK package/version does not match the release')
    with zipfile.ZipFile(directory / 'Repose-module.zip') as archive:
        if any(archive.namelist().count(name) != 1 for name in ('repose.apk', 'module.prop')):
            raise ValueError('Module must contain exactly one APK and module.prop')
        if archive.read('repose.apk') != apk.read_bytes():
            raise ValueError('Module does not contain the exact release APK')
        properties = dict(line.split('=', 1) for line in archive.read('module.prop').decode().splitlines()
                          if line and not line.startswith('#'))
        if tuple(properties.get(key) for key in ('id', 'versionCode', 'version')) != ('repose', code, version):
            raise ValueError('Module identity/version does not match the release')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--build-tools', required=True, type=Path)
    args = parser.parse_args()
    verify(args.directory, args.tag, os.environ.get('SIGNING_CERT_SHA256', ''), args.build_tools)
    print('Release checksums, signer, package, versions and embedded APK verified.')

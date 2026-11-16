#!/usr/bin/env python3
"""Create an exact Repose R1 command with an existing OpenSSL P-256 private key."""
import argparse
import base64
import re
import secrets
import subprocess
import sys
import time


def sign(key, device, issued, ttl):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', device) or not 1 <= ttl <= 86400 or issued < 0:
        raise ValueError('invalid device ID, time or lifetime')
    public = subprocess.run(['openssl', 'pkey', '-in', str(key), '-pubout', '-text_pub', '-noout'], capture_output=True, check=True).stdout
    if b'ASN1 OID: prime256v1' not in public.splitlines():
        raise ValueError('signing key must use P-256')
    body = f'R1|{device}|{issued}|{issued + ttl}|{secrets.token_hex(16)}'.encode('ascii')
    signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(key)], input=body, capture_output=True, check=True).stdout
    return body.decode() + '|' + base64.urlsafe_b64encode(signature).decode().rstrip('=')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--key', required=True, help='existing P-256 private key; never send it to the phone')
    parser.add_argument('--device', required=True, help='exact device ID configured in Repose')
    parser.add_argument('--ttl', type=int, default=300, help='validity in seconds (default: 300)')
    args = parser.parse_args()
    try:
        sys.stdout.write(sign(args.key, args.device, int(time.time()), args.ttl))
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Could not sign command: {type(error).__name__}\n')


if __name__ == '__main__':
    main()

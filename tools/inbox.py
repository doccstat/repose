#!/usr/bin/env python3
"""HTTPS command inbox for LastSignal or another explicitly authorized sender."""
import argparse
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import sqlite3
import ssl
import sys
import threading
import time


class Queue:
    def __init__(self, path):
        self.path = str(path)
        with self.connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS commands (id INTEGER PRIMARY KEY, expires INTEGER NOT NULL, body BLOB NOT NULL)')
        os.chmod(path, 0o600)

    def connect(self):
        return sqlite3.connect(self.path, timeout=5)

    def put(self, body, expires, now):
        if not body or len(body) > 4096 or not now < expires <= now + 86400:
            raise ValueError('invalid_command')
        body.decode('utf-8', errors='strict')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('DELETE FROM commands WHERE expires <= ?', (now,))
            if db.execute('SELECT COUNT(*) FROM commands').fetchone()[0] >= 64:
                raise ValueError('queue_full')
            db.execute('INSERT INTO commands (expires, body) VALUES (?, ?)', (expires, body))

    def take(self, now):
        # At-most-once transport is intentional for replayable passphrases: consume before sending.
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('DELETE FROM commands WHERE expires <= ?', (now,))
            row = db.execute('SELECT id, body FROM commands ORDER BY id LIMIT 1').fetchone()
            if row is None:
                return None
            db.execute('DELETE FROM commands WHERE id = ?', (row[0],))
            return row[1]

    def diagnostics(self):
        with self.connect() as db:
            return {'schema': 1, 'version': '1.0.0', 'queued': db.execute('SELECT COUNT(*) FROM commands').fetchone()[0],
                    'delivery': 'at-most-once', 'sqlite': sqlite3.sqlite_version}


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, *args, **kwargs):
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(*args, **kwargs)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        print('inbox_request_failed', file=sys.stderr)


def handler(queue, device, phone_token, publish_token):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, format, *args):
            # Do not put endpoint identifiers, client addresses, commands or credentials in logs.
            pass

        def answer(self, status, body=b''):
            self.send_response(status)
            self.send_header('Content-Type', 'text/plain; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True

        def authorized(self, token):
            expected = 'Bearer ' + token
            supplied = self.headers.get('Authorization', '')
            return self.path == '/commands/' + device and hmac.compare_digest(supplied.encode(), expected.encode())

        def do_GET(self):
            if not self.authorized(phone_token):
                self.answer(401)
                return
            body = queue.take(int(time.time()))
            self.answer(204 if body is None else 200, body or b'')

        def do_POST(self):
            if not self.authorized(publish_token):
                self.answer(401)
                return
            try:
                if self.headers.get('Transfer-Encoding') is not None:
                    raise ValueError('chunked_not_supported')
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= 4096:
                    raise ValueError('invalid_size')
                body = self.rfile.read(size)
                if len(body) != size:
                    raise ValueError('truncated_body')
                queue.put(body, int(self.headers['X-Repose-Expires']), int(time.time()))
                self.answer(202)
            except (ValueError, TypeError, UnicodeError):
                self.answer(400)
    return Handler


def token(path):
    value = path.read_text()
    if not re.fullmatch(r'[A-Za-z0-9_-]{32,256}', value):
        raise ValueError('token file must contain 32–256 URL-safe characters with no newline')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, required=True, help='private persistent SQLite database outside the source tree')
    parser.add_argument('--diagnostics', action='store_true', help='print credential-redacted local diagnostics and exit')
    parser.add_argument('--device')
    parser.add_argument('--phone-token-file', type=Path)
    parser.add_argument('--publish-token-file', type=Path)
    parser.add_argument('--cert', type=Path)
    parser.add_argument('--key', type=Path)
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8443)
    args = parser.parse_args()
    os.umask(0o077)
    if args.diagnostics and not args.state.is_file():
        parser.error('state database does not exist')
    queue = Queue(args.state)
    if args.diagnostics:
        print(json.dumps(queue.diagnostics(), indent=2))
        return
    if not args.device or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', args.device) or not all((args.phone_token_file, args.publish_token_file, args.cert, args.key)):
        parser.error('serve requires device, both token files, TLS certificate and TLS key')
    phone, publish = token(args.phone_token_file), token(args.publish_token_file)
    if hmac.compare_digest(phone, publish):
        parser.error('phone and publisher tokens must differ')
    server = Server((args.bind, args.port), handler(queue, args.device, phone, publish))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    print('Repose inbox listening', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()

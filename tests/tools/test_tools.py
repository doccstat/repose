import base64
import http.client
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from unittest import mock
from http.server import ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


inbox = load('inbox')
command = load('command')


class ToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_queue_exact_once_expiry_and_private_permissions(self):
        q = inbox.Queue(self.path / 'queue.sqlite')
        message = '  密码 🌙  '.encode()
        q.put(message, 200, 100)
        self.assertEqual(q.take(110), message)
        self.assertIsNone(q.take(110))
        q.put(b'expired', 150, 100)
        self.assertIsNone(q.take(150))
        self.assertEqual((self.path / 'queue.sqlite').stat().st_mode & 0o777, 0o600)
        self.assertNotIn('密码', json.dumps(q.diagnostics()))
        for body, expiry in [(b'', 101), (b'x' * 4097, 101), (b'ok', 100), (b'ok', 86501), (b'\xff', 101)]:
            with self.assertRaises((ValueError, UnicodeError)):
                q.put(body, expiry, 100)

    def test_http_roles_expiry_and_exact_body(self):
        q = inbox.Queue(self.path / 'queue.sqlite')
        server = inbox.Server(('127.0.0.1', 0), inbox.handler(q, 'phone', 'read-token', 'write-token'))
        worker = threading.Thread(target=server.serve_forever)
        worker.start()
        def request(method, token, body=None, path='/commands/phone', expires=None):
            connection = http.client.HTTPConnection(*server.server_address, timeout=3)
            headers = {'Authorization': 'Bearer ' + token}
            if expires is not None: headers['X-Repose-Expires'] = str(expires)
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            result = response.status, response.read()
            connection.close()
            return result
        try:
            self.assertEqual(request('POST', 'read-token', b'panic')[0], 401)
            self.assertEqual(request('GET', 'write-token')[0], 401)
            self.assertEqual(request('GET', 'read-token', path='/commands/wrong')[0], 401)
            self.assertEqual(request('POST', 'write-token', b'panic')[0], 400)
            message = b' exact passphrase '
            self.assertEqual(request('POST', 'write-token', message, expires=int(time.time()) + 60)[0], 202)
            self.assertEqual(request('GET', 'read-token'), (200, message))
            self.assertEqual(request('GET', 'read-token'), (204, b''))
        finally:
            server.shutdown(); server.server_close(); worker.join()

    def test_generator_has_valid_openssl_signature_and_no_newline(self):
        key = self.path / 'key.pem'
        public = self.path / 'public.pem'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256', '-out', str(key)], check=True, capture_output=True)
        subprocess.run(['openssl', 'pkey', '-in', str(key), '-pubout', '-out', str(public)], check=True, capture_output=True)
        result = command.sign(key, 'phone', 1000, 300)
        body, encoded = result.rsplit('|', 1)
        self.assertTrue(body.startswith('R1|phone|1000|1300|'))
        self.assertNotIn('\n', result)
        sig = self.path / 'signature'
        sig.write_bytes(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        checked = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(public), '-signature', str(sig)], input=body.encode(), capture_output=True)
        self.assertEqual(checked.returncode, 0)
        for device, ttl in [('bad id', 60), ('phone', 0), ('phone', 86401)]:
            with self.assertRaises(ValueError): command.sign(key, device, 1000, ttl)

    def test_ndk_emulator_plan_stays_inside_toolchain(self):
        module = load('ndk_qemu')
        ndk = self.path / 'ndk'
        toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
        (toolchain / 'lib/clang/18').mkdir(parents=True)
        env = {'ANDROID_NDK_HOME': str(ndk), 'QEMU_X86_64': 'qemu', 'QEMU_LD_PREFIX': '/loader'}
        plan = subprocess.CompletedProcess([], 0, '', f'"{toolchain}/bin/clang" "-cc1" "input name.c"\n')
        done = subprocess.CompletedProcess([], 0)
        with mock.patch.dict(os.environ, env), mock.patch.object(module.subprocess, 'run', side_effect=[plan, done]) as run:
            self.assertEqual(module.main(), 0)
            self.assertEqual(run.call_args_list[1].args[0][-1], 'input name.c')
        plan.stderr = '"/not-the-ndk/tool" "input.c"\n'
        with mock.patch.dict(os.environ, env), mock.patch.object(module.subprocess, 'run', return_value=plan):
            with self.assertRaises(ValueError): module.main()

    def test_alarm_syscall_contract(self):
        jdk = Path(os.environ['JAVA_HOME'])
        binary = self.path / 'alarm-test'
        subprocess.run([os.environ.get('CC', 'cc'), '-Wall', '-Wextra', '-Werror', '-I' + str(jdk / 'include'),
                        '-I' + str(jdk / 'include/linux'), str(ROOT / 'tests/native/alarm_test.c'), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)

    def test_module_source_and_catalog_contract(self):
        import xml.etree.ElementTree as ET
        strings = {e.attrib['name'] for e in ET.parse(ROOT / 'res/values/strings.xml').getroot()}
        source = (ROOT / 'src/com/lixingchi/repose/Settings.java').read_text()
        import re
        for key in re.findall(r'(?:flag|number|text|choice)\("[^"\n]+", "([^"\n]+)"', source):
            self.assertIn('f_' + key, strings)
        self.assertNotIn('permissive', (ROOT / 'module/customize.sh').read_text())
        self.assertNotIn('soft-reboot', (ROOT / 'src/com/lixingchi/repose/AndroidDevice.java').read_text())
        self.assertEqual(json.loads((ROOT / 'i18n.json').read_text())['targetLocales'], [])
        for name in ['service.sh', 'customize.sh']:
            subprocess.run(['sh', '-n', str(ROOT / 'module' / name)], check=True)


if __name__ == '__main__':
    unittest.main()

# Remote commands

Remote triggers run immediately on authenticated receipt, independently of the
unlock timer, its window and skip/snooze. They use the same selected-profile/full-
reboot executor. An already-BFU device does not repeatedly reboot.

## Plain passphrase

Select **Plain passphrase** or **Both**, set a dedicated remote passphrase, then
send that exact SMS body. Do not reuse the local panic PIN/password. Whitespace
and Unicode are significant; multipart SMS segments are joined without alteration.
Sender numbers are not used as authentication. Anyone who sees the passphrase can
reuse it. SMS remains in the normal messaging app.

## Signed one-use command

Generate and retain a P-256 key outside source/cache. For example, in a private
persistent directory:

```sh
umask 077
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out remote-key.pem
openssl pkey -in remote-key.pem -pubout -out remote-public.pem
```

Configure the public PEM, an exact device ID and the maximum allowed lifetime in
Repose. The private key stays with the sender. Generate a message with:

```sh
python3 tools/command.py --key /private/remote-key.pem --device my-phone --ttl 300
```

Send the exact output as SMS or an inbox body; no trailing newline. The ASCII
format is `R1|device|issued|expires|nonce|signature`. Times are UTC Unix seconds,
nonce is 16 random bytes encoded as 32 lowercase hex characters, and signature is
unpadded URL-safe Base64 of the DER ECDSA/SHA-256 signature over everything before
the last `|`. `R1` authorizes exactly the Repose pause-and-full-reboot action, not
arbitrary shell execution. Keys are X.509 SubjectPublicKeyInfo, curve P-256 only.

The engine validates the device, signature, issuance, expiry and lifetime. It
persists the nonce **before** invoking the executor, including when pausing fails.
Replays stay rejected across full boots and framework/module restarts. A backwards
wall-clock jump relative to the last accepted command fails closed until clock
recovery. Generate a fresh command after an acknowledged failed action.

## HTTPS inbox / LastSignal adapter

The bundled `tools/inbox.py` is a small standard-library HTTPS service. It accepts
commands from a separately authorized sender and exposes one device inbox to the
phone. It does not change LastSignal's `/webhooks/keepalive` check-in semantics.
A LastSignal-side sender or other automation publishes the desired command by
POST; merely receiving a keepalive never causes a reboot.

Create two distinct random URL-safe token files, at least 32 characters, with no
newline, outside source/cache. Use a TLS certificate trusted by the phone (for
example an appropriate private-network certificate) and a private persistent
SQLite database:

```sh
python3 tools/inbox.py --state /private/repose.sqlite --device my-phone \
  --phone-token-file /private/phone.token --publish-token-file /private/publish.token \
  --cert /private/cert.pem --key /private/tls-key.pem --bind 127.0.0.1 --port 8443
```

Bind address is explicit; `127.0.0.1` requires a trusted HTTPS proxy/forwarder.
Configure the externally reachable `https://host:port/commands/my-phone` URL,
phone token, polling interval and desired mode in Repose. The root engine uses a
wake-capable poll deadline, validates TLS normally and refuses redirects. Delivery
latency includes the configured polling interval and network availability.

Publisher request:

```http
POST /commands/my-phone
Authorization: Bearer <publisher-token>
Content-Type: text/plain; charset=utf-8
X-Repose-Expires: <UTC Unix seconds, within the next 24 hours>
Content-Length: <exact byte count>

<exact passphrase or signed R1 command>
```

`202` queues the command; `400` rejects input and `401` rejects authorization.
The phone performs authenticated GET: `200` returns one exact body, `204` means
empty. The bounded persistent queue expires entries and consumes them before
responding. This is **at-most-once delivery**: a lost response can lose a command,
but a reusable passphrase is not automatically redelivered after reboot. Signed
commands additionally have the phone's persistent replay protection.

Local redacted inbox diagnostics:

```sh
python3 tools/inbox.py --state /private/repose.sqlite --diagnostics
```

No command bodies, tokens, client addresses or access URLs are logged. Queue
storage can contain pending literal passphrases and must remain private.

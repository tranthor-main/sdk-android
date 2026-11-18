// node --test scripts/send-fcm-push.test.mjs  (no network: a local stub plays OAuth and FCM)
import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { generateKeyPairSync, createVerify } from 'node:crypto';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { buildJwt, buildMessage, parseArgs, SCOPE } from './send-fcm-push.mjs';

const script = join(dirname(fileURLToPath(import.meta.url)), 'send-fcm-push.mjs');
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });

function decode(part) { return JSON.parse(Buffer.from(part, 'base64url').toString('utf8')); }

test('JWT is RS256-signed with the FCM scope and audience', () => {
  const jwt = buildJwt({ client_email: 'svc@p.iam.gserviceaccount.com', private_key: pem, token_uri: 'https://oauth.example/token' }, 1000);
  const [header, claims, signature] = jwt.split('.');
  assert.deepEqual(decode(header), { alg: 'RS256', typ: 'JWT' });
  assert.deepEqual(decode(claims), { iss: 'svc@p.iam.gserviceaccount.com', scope: SCOPE, aud: 'https://oauth.example/token', iat: 1000, exp: 4600 });
  assert.ok(createVerify('RSA-SHA256').update(`${header}.${claims}`).verify(publicKey, Buffer.from(signature, 'base64url')));
});

test('data message carries the tranthor marker as a JSON string plus high priority', () => {
  const body = buildMessage({ token: 'device', title: 'T', body: 'B', imageUrl: 'https://img.example/a.png', deliveryId: 'd-1', notification: false });
  assert.deepEqual(body, { message: { token: 'device', android: { priority: 'high' },
    data: { tranthor: '{"version":1,"deliveryId":"d-1","imageUrl":"https://img.example/a.png"}', title: 'T', body: 'B' } } });
  const notification = buildMessage({ token: 'device', title: 'T', body: 'B', deliveryId: 'd-2', notification: true });
  assert.deepEqual(notification.message.notification, { title: 'T', body: 'B' });
  assert.equal(notification.message.data.tranthor, '{"version":1,"deliveryId":"d-2"}');
});

test('argument validation', () => {
  assert.throws(() => parseArgs(['--token', 't'], {}), /--key/);
  assert.throws(() => parseArgs(['--key', 'k'], {}), /--token/);
  assert.throws(() => parseArgs(['--key', 'k', '--token', 't', '--delivery-id', 'bad id'], {}), /delivery-id/);
  assert.throws(() => parseArgs(['--key', 'k', '--token', 't', '--image-url', 'http://x/a.png'], {}), /https/);
  assert.match(parseArgs(['--token', 't'], { FCM_SERVICE_ACCOUNT: 'k' }).deliveryId, /^fcm-/);
});

test('end to end against a local stub: no secrets printed', async () => {
  const seen = [];
  const server = createServer((req, res) => {
    let raw = ''; req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      seen.push({ url: req.url, headers: req.headers, raw });
      if (req.url === '/token') {
        const assertion = new URLSearchParams(raw).get('assertion');
        const [h, c, s] = assertion.split('.');
        const ok = createVerify('RSA-SHA256').update(`${h}.${c}`).verify(publicKey, Buffer.from(s, 'base64url'));
        res.writeHead(ok ? 200 : 401, { 'content-type': 'application/json' });
        res.end(JSON.stringify(ok ? { access_token: 'stub-access-token-secret', expires_in: 3600 } : { error: 'bad jwt' }));
      } else {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ name: 'projects/demo-project/messages/0:123' }));
      }
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const dir = mkdtempSync(join(tmpdir(), 'fcm-test-'));
  const key = join(dir, 'sa.json');
  writeFileSync(key, JSON.stringify({ project_id: 'demo-project', client_email: 'svc@demo.iam.gserviceaccount.com', private_key: pem, token_uri: `${base}/token` }));
  try {
    const child = spawn(process.execPath, [script, '--key', key, '--token', 'device-token-secret', '--image-url', 'https://img.example/a.png', '--delivery-id', 'stub-1'],
      { env: { ...process.env, FCM_API_BASE: base } });
    let out = ''; child.stdout.on('data', (d) => { out += d; }); child.stderr.on('data', (d) => { out += d; });
    const code = await new Promise((r) => child.on('close', r));
    assert.equal(code, 0, out);
    assert.match(out, /projects\/demo-project\/messages\/0:123/);
    for (const secret of ['stub-access-token-secret', 'device-token-secret', 'PRIVATE KEY', 'svc@demo']) assert.ok(!out.includes(secret), `printed ${secret}`);
    const send = seen.find((r) => r.url === '/v1/projects/demo-project/messages:send');
    assert.equal(send.headers.authorization, 'Bearer stub-access-token-secret');
    const body = JSON.parse(send.raw);
    assert.equal(body.message.token, 'device-token-secret');
    assert.equal(body.message.android.priority, 'high');
    assert.deepEqual(JSON.parse(body.message.data.tranthor), { version: 1, deliveryId: 'stub-1', imageUrl: 'https://img.example/a.png' });
  } finally {
    server.close(); rmSync(dir, { recursive: true, force: true });
  }
});

test('FCM error prints only status and message', async () => {
  const server = createServer((req, res) => {
    req.resume(); req.on('end', () => {
      if (req.url === '/token') { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"access_token":"x-secret"}'); return; }
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { code: 404, message: 'Requested entity was not found.', status: 'NOT_FOUND' } }));
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const dir = mkdtempSync(join(tmpdir(), 'fcm-test-'));
  const key = join(dir, 'sa.json');
  writeFileSync(key, JSON.stringify({ project_id: 'p', client_email: 'e@p', private_key: pem, token_uri: `${base}/token` }), { mode: 0o600 });
  try {
    const child = spawn(process.execPath, [script, '--key', key, '--notification'], { env: { ...process.env, FCM_API_BASE: base, FCM_TOKEN: 'gone' } });
    let out = ''; child.stdout.on('data', (d) => { out += d; }); child.stderr.on('data', (d) => { out += d; });
    const code = await new Promise((r) => child.on('close', r));
    assert.equal(code, 1);
    assert.equal(out.trim(), 'FCM send failed: 404 Requested entity was not found.');
  } finally { server.close(); rmSync(dir, { recursive: true, force: true }); }
});

// Review r2 N6 regressions.
import { chmodSync } from 'node:fs';
function run(args, env = {}) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [script, ...args], { env: { ...process.env, ...env } });
    let out = ''; let err = '';
    child.stdout.on('data', (d) => { out += d; }); child.stderr.on('data', (d) => { err += d; });
    child.on('close', (code) => resolve({ code, out, err }));
  });
}

test('token from --token-file or FCM_TOKEN; --token warns it is visible', () => {
  const dir = mkdtempSync(join(tmpdir(), 'fcm-n6-'));
  try {
    const file = join(dir, 'token.txt'); writeFileSync(file, 'file-token\n');
    assert.equal(parseArgs(['--key', 'k', '--token-file', file], {}).token, 'file-token');
    assert.equal(parseArgs(['--key', 'k'], { FCM_TOKEN: 'env-token' }).token, 'env-token');
    const warnings = [];
    parseArgs(['--key', 'k', '--token', 't'], {}, (w) => warnings.push(w));
    assert.ok(warnings.some((w) => /visible|ps|history/i.test(w)), JSON.stringify(warnings));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('FCM_API_BASE is restricted to loopback http or the real FCM origin', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'fcm-n6-'));
  const key = join(dir, 'sa.json');
  writeFileSync(key, JSON.stringify({ project_id: 'p', client_email: 'e@p', private_key: pem, token_uri: 'http://127.0.0.1:9/token' }));
  chmodSync(key, 0o600);
  try {
    const result = await run(['--key', key, '--token', 't'], { FCM_API_BASE: 'https://evil.example' });
    assert.equal(result.code, 1);
    assert.match(result.out + result.err, /FCM_API_BASE/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('warns when the key file is group/world-readable', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'fcm-n6-'));
  const key = join(dir, 'sa.json');
  writeFileSync(key, JSON.stringify({ project_id: 'p', client_email: 'e@p', private_key: pem, token_uri: 'http://127.0.0.1:9/token' }));
  chmodSync(key, 0o644);
  try {
    const result = await run(['--key', key, '--token-file', key]);
    assert.match(result.err, /readable by other users|chmod 600/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

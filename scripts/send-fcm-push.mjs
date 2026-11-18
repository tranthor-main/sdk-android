#!/usr/bin/env node
// Sends a real FCM HTTP v1 message to the example app (Node >= 22, no npm dependencies).
//
//   FCM_TOKEN=$(pbpaste) node scripts/send-fcm-push.mjs --key /path/outside/repo/service-account.json \
//     [--token-file FILE] [--title T] [--body B] [--image-url https://cdn.example/x.png] [--delivery-id ID] [--notification]
//
// The service account comes from --key or FCM_SERVICE_ACCOUNT (keep it outside the repository,
// chmod 600). The device token comes from FCM_TOKEN, --token-file, or --token (discouraged: visible
// in `ps` and shell history). The key contents, private key, OAuth access token and device token are
// never printed. android.priority is "high": AndroidConfig.priority accepts "normal" or "high" in
// FCM v1 JSON (the enum names are NORMAL/HIGH). Output: the FCM message name, or the
// error status and message. Default is a DATA message carrying the `tranthor` marker exactly as
// tranthor-push expects (the app builds the notification, including rich images, in every app
// state). --notification sends a NOTIFICATION message instead: the system tray renders it while the
// app is in the background without running app code (no allowlisted image download, and taps arrive
// as unverified launcher extras, see docs/push.md).
import { createSign, randomUUID } from 'node:crypto';
import { readFileSync, statSync, realpathSync } from 'node:fs';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const DELIVERY_ID = /^[A-Za-z0-9._:-]{1,128}$/;

const base64url = (value) => Buffer.from(value).toString('base64url');

export function buildJwt(serviceAccount, nowSeconds = Math.floor(Date.now() / 1000)) {
  const header = { alg: 'RS256', typ: 'JWT' };
  const claims = {
    iss: serviceAccount.client_email,
    scope: SCOPE,
    aud: serviceAccount.token_uri,
    iat: nowSeconds,
    exp: nowSeconds + 3600,
  };
  const unsigned = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claims))}`;
  const signature = createSign('RSA-SHA256').update(unsigned).end().sign(serviceAccount.private_key);
  return `${unsigned}.${signature.toString('base64url')}`;
}

export function buildMessage({ token, title, body, imageUrl, deliveryId, notification }) {
  const marker = { version: 1, deliveryId };
  if (imageUrl) marker.imageUrl = imageUrl;
  const message = { token, data: { tranthor: JSON.stringify(marker) }, android: { priority: 'high' } };
  if (notification) {
    message.notification = { title, body };
    if (imageUrl) message.notification.image = imageUrl;
  } else {
    message.data.title = title;
    message.data.body = body;
  }
  return { message };
}

const warn = (message) => console.error(`Warning: ${message}`);
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** Loopback http (local stubs/tests) or the real FCM origin only. */
export function checkedApiBase(value) {
  const base = value ?? 'https://fcm.googleapis.com';
  let url;
  try { url = new URL(base); } catch { throw new Error('FCM_API_BASE is not a URL.'); }
  const loopback = url.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  const real = url.protocol === 'https:' && url.hostname === 'fcm.googleapis.com' && (url.port === '' || url.port === '443');
  if (!loopback && !real) throw new Error('FCM_API_BASE must be https://fcm.googleapis.com or a loopback http:// stub.');
  return base.replace(/\/+$/, '');
}

/** Warns (never prints contents) when the key file is readable by others or lives inside the repository. */
export function checkKeyFile(path, onWarning = warn) {
  try {
    const mode = statSync(path).mode;
    if (mode & 0o077) onWarning(`the service-account file is readable by other users; run chmod 600 on it.`);
    const real = realpathSync(path);
    if (real === REPO_ROOT || real.startsWith(REPO_ROOT + sep)) onWarning('the service-account file is inside the repository; move it outside (it must never be committed).');
  } catch { /* reported when reading */ }
}

export function parseArgs(argv, env = process.env, onWarning = warn) {
  const options = { key: env.FCM_SERVICE_ACCOUNT, title: 'Tranthor FCM test', body: 'Sent with scripts/send-fcm-push.mjs',
    deliveryId: `fcm-${randomUUID()}`, notification: false };
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    const value = () => { if (i + 1 >= argv.length) throw new Error(`${flag} needs a value`); return argv[++i]; };
    switch (flag) {
      case '--key': options.key = value(); break;
      case '--token': options.token = value(); onWarning('--token is visible in `ps` output and shell history; prefer FCM_TOKEN or --token-file.'); break;
      case '--token-file': {
        const file = value();
        try { options.token = readFileSync(file, 'utf8').trim(); } catch { throw new Error('Could not read --token-file.'); }
        break;
      }
      case '--title': options.title = value(); break;
      case '--body': options.body = value(); break;
      case '--image-url': options.imageUrl = value(); break;
      case '--delivery-id': options.deliveryId = value(); break;
      case '--notification': options.notification = true; break;
      default: throw new Error(`Unknown argument ${flag}`);
    }
  }
  if (!options.key) throw new Error('Pass --key or set FCM_SERVICE_ACCOUNT (a service-account JSON kept outside the repository).');
  options.token ||= env.FCM_TOKEN?.trim();
  if (!options.token) throw new Error('Set FCM_TOKEN or pass --token-file (copy the token with the example app\'s debug "Copy FCM token" button).');
  if (!DELIVERY_ID.test(options.deliveryId)) throw new Error('Invalid --delivery-id (1-128 of [A-Za-z0-9._:-]).');
  if (options.imageUrl && !/^https:\/\/[^\s#]+$/.test(options.imageUrl)) throw new Error('--image-url must be an https:// URL.');
  return options;
}

async function errorText(response) {
  try { const json = await response.json(); return json?.error?.message ?? json?.error_description ?? json?.error ?? ''; }
  catch { return ''; }
}

export async function send(options, { fetchImpl = fetch, apiBase = checkedApiBase(process.env.FCM_API_BASE) } = {}) {
  checkKeyFile(options.key);
  let serviceAccount;
  try { serviceAccount = JSON.parse(readFileSync(options.key, 'utf8')); }
  catch { throw new Error('Could not read the service-account JSON (contents not shown).'); }
  for (const field of ['client_email', 'private_key', 'project_id']) {
    if (typeof serviceAccount[field] !== 'string' || !serviceAccount[field]) throw new Error(`Service account is missing ${field}.`);
  }
  serviceAccount.token_uri ||= 'https://oauth2.googleapis.com/token';
  const tokenUri = new URL(serviceAccount.token_uri);
  if (!(tokenUri.protocol === 'https:' || (tokenUri.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(tokenUri.hostname)))) {
    throw new Error('Service account token_uri must be https (or a loopback http stub).');
  }
  const tokenResponse = await fetchImpl(serviceAccount.token_uri, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: buildJwt(serviceAccount) }),
  });
  if (!tokenResponse.ok) throw new Error(`OAuth token request failed: ${tokenResponse.status} ${await errorText(tokenResponse)}`.trim());
  const accessToken = (await tokenResponse.json()).access_token;
  if (!accessToken) throw new Error('OAuth response had no access token.');
  const url = `${apiBase}/v1/projects/${encodeURIComponent(serviceAccount.project_id)}/messages:send`;
  const response = await fetchImpl(url, {
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(buildMessage(options)),
  });
  if (!response.ok) throw new Error(`FCM send failed: ${response.status} ${await errorText(response)}`.trim());
  return (await response.json()).name;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const options = parseArgs(process.argv.slice(2));
    const name = await send(options);
    console.log(`Sent ${options.notification ? 'notification' : 'data'} message ${name} (deliveryId ${options.deliveryId}).`);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

export const DAY = 86_400_000, RETENTION = 7 * DAY, MAX_BODY = 512 * 1024;
const enc = new TextEncoder(), dec = new TextDecoder('utf-8', { fatal: true, ignoreBOM: false });
export type Signed = { payload: string; signature: string };
export type Envelope = { header: string; enc: string; ciphertext: string; signature: string };
export type Obj = Record<string, any>;
export class Failure extends Error { constructor(public code: string, public status = 400) { super(code); } }
export function need(ok: unknown, code = 'INVALID', status = 400): asserts ok { if (!ok) throw new Failure(code, status); }
export const bytes = (s: string) => enc.encode(s);
export const hex = (b: Uint8Array) => Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
export function unhex(s: string) { need(typeof s === 'string' && /^(?:[0-9a-f]{2})+$/.test(s)); return Uint8Array.from(s.match(/../g)!, x => parseInt(x, 16)); }
export const id = (s: unknown): s is string => typeof s === 'string' && /^[0-9a-f]{64}$/.test(s);
export const integer = (n: unknown): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n >= 0;
export const b64 = (b: Uint8Array) => btoa(Array.from(b, x => String.fromCharCode(x)).join(''));
export function unb64(s: string) { need(typeof s === 'string' && s.length <= MAX_BODY * 2); let b: Uint8Array; try { b = Uint8Array.from(atob(s), x => x.charCodeAt(0)); } catch { throw new Failure('INVALID'); } need(b64(b) === s); return b; }
export function join(...parts: Uint8Array[]) { const result = new Uint8Array(parts.reduce((s, p) => s + p.length, 0)); let i = 0; for (const p of parts) { result.set(p, i); i += p.length; } return result; }
export const domain = (kind: string) => bytes(`SeniorLink mailbox ${kind} v1\n`);
export async function hash(b: Uint8Array) { return hex(new Uint8Array(await crypto.subtle.digest('SHA-256', b))); }
export const jsonBytes = (o: unknown) => bytes(JSON.stringify(o));
export function fields(o: any, required: string[], optional: string[] = []): asserts o is Obj {
  need(o && typeof o === 'object' && !Array.isArray(o));
  need(required.every(k => Object.hasOwn(o, k)) && Object.keys(o).every(k => [...required, ...optional].includes(k)));
}
export function parse(raw: Uint8Array): Obj {
  need(raw.length <= MAX_BODY, 'TOO_LARGE', 413); const s = dec.decode(raw);
  const stack: { object: boolean; key: boolean; keys: Set<string> }[] = [];
  for (let i = 0; i < s.length; i++) {
    const f = stack.at(-1);
    if (s[i] === '{' || s[i] === '[') { need(stack.length < 32); stack.push({ object: s[i] === '{', key: true, keys: new Set() }); }
    else if (s[i] === '}' || s[i] === ']') { need(stack.length); stack.pop(); }
    else if (s[i] === ':' && f?.object) f.key = false;
    else if (s[i] === ',' && f?.object) f.key = true;
    else if (s[i] === '"') {
      const start = i++; let escaped = false;
      for (; i < s.length; i++) { const c = s[i]; if (c === '"' && !escaped) break; escaped = c === '\\' && !escaped; }
      need(i < s.length);
      if (f?.object && f.key) { const k = JSON.parse(s.slice(start, i + 1)); need(!f.keys.has(k), 'DUPLICATE_KEY'); f.keys.add(k); }
    }
  }
  need(!stack.length); const o = JSON.parse(s); need(o && typeof o === 'object' && !Array.isArray(o)); return o;
}
export async function verify(key: string, data: Uint8Array, signature: string) {
  need(id(key) && typeof signature === 'string' && /^[0-9a-f]{128}$/.test(signature), 'UNAUTHORIZED', 401);
  const pk = await crypto.subtle.importKey('raw', unhex(key), { name: 'Ed25519' }, false, ['verify']);
  need(await crypto.subtle.verify('Ed25519', pk, unhex(signature), data), 'UNAUTHORIZED', 401);
}
export async function signed(value: Signed, key: string, kind: string): Promise<Obj> {
  fields(value, ['payload', 'signature']); const raw = unb64(value.payload);
  await verify(key, join(domain(kind), raw), value.signature); return parse(raw);
}
export async function sign(value: unknown, privateKey: string, kind: string): Promise<Signed> {
  const raw = jsonBytes(value); const key = await crypto.subtle.importKey('pkcs8', unb64(privateKey), { name: 'Ed25519' }, false, ['sign']);
  const signature = new Uint8Array(await crypto.subtle.sign('Ed25519', key, join(domain(kind), raw)));
  return { payload: b64(raw), signature: hex(signature) };
}
export function envelopeBytes(e: Envelope) { return join(...[e.header, e.enc, e.ciphertext].map(v => { const b = unb64(v); const n = new Uint8Array(4); new DataView(n.buffer).setUint32(0, b.length); return join(n, b); })); }
export const envelopeHash = (e: Envelope) => hash(join(envelopeBytes(e), unhex(e.signature)));
export async function readBody(request: Request) {
  need(request.body, 'INVALID'); const reader = request.body.getReader(); const parts: Uint8Array[] = []; let size = 0;
  try { while (true) { const { value, done } = await reader.read(); if (done) break; size += value.length; need(size <= MAX_BODY, 'TOO_LARGE', 413); parts.push(value); } }
  finally { await reader.cancel(); }
  return join(...parts);
}
export async function authenticate(request: Request, raw: Uint8Array, origin: string, allowed: Set<string>, now: number) {
  need(request.method === 'POST' && request.headers.get('content-type') === 'application/json' && !request.headers.has('content-encoding'));
  const url = new URL(request.url); need(url.origin === origin && !url.search && !url.hash, 'WRONG_ORIGIN');
  const input = request.headers.get('signature-input') ?? '';
  const m = /^sl=(\("@method" "@target-uri" "content-type" "content-digest"\);created=(\d+);expires=(\d+);nonce="([0-9a-f]{64})";keyid="([0-9a-f]{64})";alg="ed25519";tag="seniorlink-mailbox-v1")$/.exec(input);
  need(m, 'UNAUTHORIZED', 401); const created = Number(m[2]), expires = Number(m[3]), nonce = m[4], caller = m[5];
  need(allowed.has(caller), 'NOT_ADMITTED', 403);
  need(integer(created) && integer(expires) && expires === created + 300 && created <= now / 1000 + 120 && expires >= now / 1000 - 120, 'SIGNATURE_EXPIRED', 401);
  const digest = `sha-256=:${b64(new Uint8Array(await crypto.subtle.digest('SHA-256', raw)))}:`;
  need(request.headers.get('content-digest') === digest, 'BAD_DIGEST', 401);
  const signature = /^sl=:([A-Za-z0-9+/=]+):$/.exec(request.headers.get('signature') ?? ''); need(signature, 'UNAUTHORIZED', 401);
  const base = `"@method": POST\n"@target-uri": ${request.url}\n"content-type": application/json\n"content-digest": ${digest}\n"@signature-params": ${m[1]}`;
  await verify(caller, bytes(base), hex(unb64(signature[1])));
  return { caller, nonce, nonceUntil: (expires + 120) * 1000 };
}

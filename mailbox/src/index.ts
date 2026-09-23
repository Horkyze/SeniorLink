import { DurableObject } from 'cloudflare:workers';
import { authenticate, b64, bytes, DAY, domain, envelopeBytes, envelopeHash, Failure, fields, Envelope, hash, hex, id, integer, join, jsonBytes, MAX_BODY, need, Obj, parse, readBody, RETENTION, sign, signed, Signed, unb64, verify } from './protocol';

export interface Env { MAILBOX: DurableObjectNamespace; ORIGIN: string; SERVICE_PUBLIC_KEY: string; SERVICE_PRIVATE_KEY: string; ALLOWED_IDENTITIES: string }
const response = (o: unknown, status = 200) => new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
function admitted(env: Env) { return new Set(env.ALLOWED_IDENTITIES.split(/[\s,]+/).filter(id)); }
async function service(env: Env) { need(/^https:\/\/[^/?#@:]+$/.test(env.ORIGIN) && id(env.SERVICE_PUBLIC_KEY) && env.SERVICE_PRIVATE_KEY, 'NOT_CONFIGURED', 503); return hash(bytes(`${env.ORIGIN}\n${env.SERVICE_PUBLIC_KEY}`)); }
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      await service(env);
      const route = /^\/v1\/sources\/([0-9a-f]{64})\/(info|register|policy|put|fetch|ack|receipts|close|confirm|reject)$/.exec(new URL(request.url).pathname);
      need(route, 'NOT_FOUND', 404); const source = route[1]; const raw = await readBody(request);
      const auth = await authenticate(request, raw, env.ORIGIN, admitted(env), Date.now());
      need(admitted(env).has(source), 'NOT_ADMITTED', 403);
      // No client-supplied identity header is forwarded. RPC is only available via this binding.
      return env.MAILBOX.getByName(source).fetch(new Request("https://mailbox/operation", { method: "POST", body: JSON.stringify({ source, op: route[2], auth, raw: b64(raw) }) }));
    } catch (error) { return response({ error: error instanceof Failure ? error.code : 'INVALID' }, error instanceof Failure ? error.status : 400); }
  }
};
type Auth = { caller: string; nonce: string; nonceUntil: number };
export class SourceMailbox extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS grants (id TEXT PRIMARY KEY, recipient TEXT NOT NULL, json TEXT NOT NULL, keyid TEXT NOT NULL, keyexpires INTEGER NOT NULL, closed INTEGER NOT NULL DEFAULT 0)`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS mail (id TEXT PRIMARY KEY, grantid TEXT NOT NULL, hash TEXT NOT NULL, json TEXT NOT NULL, expires INTEGER NOT NULL, size INTEGER NOT NULL, invalid INTEGER NOT NULL DEFAULT 0)`);
    ctx.storage.sql.exec(`CREATE INDEX IF NOT EXISTS mail_queue ON mail(grantid,expires,id)`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, grantid TEXT NOT NULL, hash TEXT NOT NULL, json TEXT NOT NULL, expires INTEGER NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0)`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS nonces (caller TEXT NOT NULL, nonce TEXT NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY(caller,nonce))`);
  }
  async fetch(request: Request): Promise<Response> {
    try { const r = await request.json() as { source: string; op: string; auth: Auth; raw: string };
      return response(await this.operation(r.source, r.op, r.auth, unb64(r.raw)));
    } catch (e) { return response({ error: e instanceof Failure ? e.code : 'INVALID' }, e instanceof Failure ? e.status : 400); }
  }
  private sql(query: string, ...args: any[]): Obj[] { return this.ctx.storage.sql.exec(query, ...args).toArray(); }
  private get(key: string): any { const r = this.sql('SELECT value FROM state WHERE key=?', key)[0]; return r ? JSON.parse(r.value) : null; }
  private set(key: string, value: any) { this.sql('INSERT OR REPLACE INTO state(key,value) VALUES(?,?)', key, JSON.stringify(value)); }
  private grant(grantid: string, caller: string, source: string) {
    need(id(grantid)); const g = this.sql('SELECT * FROM grants WHERE id=?', grantid)[0];
    need(g && !g.closed && (caller === source || caller === g.recipient), 'REVOKED', 403); return g;
  }
  private cleanup(now: number) {
    const expired = this.sql('SELECT COUNT(*) AS n FROM mail WHERE expires<=?', now)[0].n;
    if (expired) this.set('expired', (this.get('expired') ?? 0) + expired);
    this.sql('DELETE FROM mail WHERE expires<=?', now); this.sql('DELETE FROM receipts WHERE expires<=?', now);
    this.sql('DELETE FROM nonces WHERE expires<?', now);
  }
  async alarm() { this.ctx.storage.transactionSync(() => this.cleanup(Date.now())); await this.schedule(); }
  private async schedule() {
    const r = this.sql('SELECT MIN(expires) AS next FROM (SELECT expires FROM mail UNION ALL SELECT expires FROM receipts UNION ALL SELECT expires FROM nonces)')[0];
    if (r.next != null) await this.ctx.storage.setAlarm(Math.max(Date.now() + 1000, r.next)); else await this.ctx.storage.deleteAlarm();
  }
  async operation(source: string, op: string, auth: Auth, raw: Uint8Array): Promise<Obj> {
    need(id(source) && admitted(this.env).has(auth.caller) && admitted(this.env).has(source), 'NOT_ADMITTED', 403);
    const now = Date.now(), sid = await service(this.env), body = parse(raw), caller = auth.caller;
    fields(body, ['version'], ['registration', 'policy', 'envelope', 'grantId', 'receipts', 'ids', 'close']); need(body.version === 1, 'UNSUPPORTED_VERSION');
    const allowedFields: Record<string, string[]> = { info: [], register: ['registration'], policy: ['policy'], put: ['envelope'], fetch: ['grantId'], ack: ['grantId','receipts'], receipts: ['grantId'], close: ['close'], confirm: ['grantId','ids'], reject: ['grantId','ids'] };
    need(Object.hasOwn(allowedFields, op)); fields(body, ['version', ...allowedFields[op]]);
    need(raw.length <= (op === 'put' || op === 'ack' ? MAX_BODY : 16 * 1024), 'TOO_LARGE', 413);
    let doc: Obj = {}, key: Obj = {}, signedPolicy: Signed | undefined, header: Obj = {}, envelopeDigest = '', prepared: Obj[] = [];
    if (op === 'register') {
      fields(body.registration, ['grant', 'acceptance']);
      doc = await signed(body.registration.grant, source, 'grant');
      fields(doc, ['version','service','incarnation','source','recipient','grantId','key','created']);
      need(doc.version === 1 && doc.source === source && doc.service === sid && id(doc.incarnation) && id(doc.recipient) && doc.recipient !== source && id(doc.grantId) && integer(doc.created) && doc.created <= now + 120000);
      need(caller === source || caller === doc.recipient, 'FORBIDDEN', 403); need(admitted(this.env).has(doc.recipient), 'NOT_ADMITTED', 403);
      key = await signed(doc.key, doc.recipient, 'key'); fields(key, ['version','owner','keyId','publicKey','revision','created','expires']);
      need(key.version === 1 && key.owner === doc.recipient && id(key.keyId) && integer(key.revision) && key.revision > 0 && integer(key.created) && integer(key.expires) && key.expires > now && key.created <= now + 120000 && unb64(key.publicKey).length === 32);
      const acceptance = await signed(body.registration.acceptance, doc.recipient, 'acceptance');
      fields(acceptance, ['version','grantHash','source','recipient']);
      need(acceptance.version === 1 && acceptance.grantHash === await hash(unb64(body.registration.grant.payload)) && acceptance.source === source && acceptance.recipient === doc.recipient);
    } else if (op === 'policy') {
      need(caller === source, 'FORBIDDEN', 403); signedPolicy = body.policy; doc = await signed(body.policy, source, 'policy');
      fields(doc, ['version','service','incarnation','source','revision','generation','enabled','until']);
      need(doc.version === 1 && doc.service === sid && doc.source === source && integer(doc.revision) && doc.revision > 0 && integer(doc.generation) && doc.generation > 0 && typeof doc.enabled === 'boolean' && integer(doc.until));
      need(doc.enabled ? doc.until > now && doc.until <= now + DAY + 120000 : doc.until === 0, 'EXPIRED');
    } else if (op === 'put') {
      need(caller === source, 'FORBIDDEN', 403); fields(body.envelope, ['header','enc','ciphertext','signature']);
      need(jsonBytes(body.envelope).length <= 64 * 1024, 'TOO_LARGE', 413);
      await verify(source, join(domain('envelope'), envelopeBytes(body.envelope as Envelope)), body.envelope.signature);
      header = parse(unb64(body.envelope.header));
      fields(header, ['version','suite','service','source','recipient','grantId','generation','keyId','messageId','expires']);
      need(header.version === 1 && header.suite === 'HPKE-X25519-SHA256-CHACHA20POLY1305' && header.service === sid && header.source === source && id(header.recipient) && id(header.grantId) && id(header.keyId) && id(header.messageId) && integer(header.generation) && integer(header.expires));
      need(header.expires > now && header.expires <= now + RETENTION + 120000, 'EXPIRED');
      need(unb64(body.envelope.enc).length === 32 && unb64(body.envelope.ciphertext).length >= 16);
      envelopeDigest = await envelopeHash(body.envelope as Envelope);
    } else if (op === 'ack') {
      need(Array.isArray(body.receipts) && body.receipts.length > 0 && body.receipts.length <= 20);
      for (const receipt of body.receipts) {
        const r = await signed(receipt, caller, 'receipt'); fields(r, ['version','service','source','recipient','grantId','messageId','envelopeHash','expires']);
        need(r.version === 1 && r.service === sid && r.source === source && r.recipient === caller && r.grantId === body.grantId && id(r.messageId) && id(r.envelopeHash) && integer(r.expires));
        prepared.push({ ...r, signed: receipt });
      }
    } else if (op === 'close') {
      doc = await signed(body.close, caller, 'close'); fields(doc, ['version','service','source','grantId','grant']);
      need(doc.version === 1 && doc.source === source && doc.service === sid && id(doc.grantId));
      key = await signed(doc.grant, source, 'grant');
      need(key.source === source && key.service === sid && key.grantId === doc.grantId && id(key.recipient) && (caller === source || caller === key.recipient), 'FORBIDDEN', 403);
    }
    const result = this.ctx.storage.transactionSync(() => {
      this.cleanup(now);
      need(!this.sql('SELECT 1 FROM nonces WHERE caller=? AND nonce=?', caller, auth.nonce).length, 'REPLAY', 409);
      need(this.sql('SELECT COUNT(*) AS n FROM nonces')[0].n < 10000, 'FULL', 429);
      this.sql('INSERT INTO nonces VALUES(?,?,?)', caller, auth.nonce, auth.nonceUntil);
      let info = this.get('info');
      if (!info) {
        need(op === 'info' && caller === source, 'NOT_INITIALIZED', 409);
        info = { version: 1, service: sid, source, incarnation: hex(crypto.getRandomValues(new Uint8Array(32))) }; this.set('info', info);
      }
      need(info.source === source && info.service === sid, 'SERVICE_CHANGED', 409);
      const policy = this.get('policy');
      if (op === 'info') { need(caller === source, 'FORBIDDEN', 403); return { info }; }
      if (op === 'register') {
        need(doc.incarnation === info.incarnation, 'STALE_INCARNATION', 409);
        const existing = this.sql('SELECT * FROM grants WHERE id=?', doc.grantId)[0];
        if (existing) { need(!existing.closed && existing.json === JSON.stringify(body.registration), 'REVOKED', 403); }
        else {
          need(this.sql('SELECT COUNT(*) AS n FROM grants').at(0)!.n < 10000, 'FULL', 429);
          need(this.sql('SELECT COUNT(*) AS n FROM grants WHERE closed=0')[0].n < 8 && !this.sql('SELECT 1 FROM grants WHERE recipient=? AND closed=0', doc.recipient).length, 'FULL', 409);
          this.sql('INSERT INTO grants(id,recipient,json,keyid,keyexpires) VALUES(?,?,?,?,?)', doc.grantId, doc.recipient, JSON.stringify(body.registration), key.keyId, key.expires);
        } return {};
      }
      if (op === 'policy') {
        need(doc.incarnation === info.incarnation, 'STALE_INCARNATION', 409);
        if (policy && doc.revision === policy.revision) { need(this.get('signedPolicy').payload === signedPolicy!.payload, 'POLICY_STALE', 409); return {}; }
        need(!policy || doc.revision > policy.revision, 'POLICY_STALE', 409);
        need(!policy || doc.generation >= policy.generation, 'POLICY_STALE', 409);
        if (policy && doc.enabled !== policy.enabled) need(doc.generation > policy.generation, 'POLICY_STALE', 409);
        if (!doc.enabled || (policy && doc.generation !== policy.generation)) this.sql('DELETE FROM mail');
        this.set('policy', doc); this.set('signedPolicy', signedPolicy); return {};
      }
      if (op === 'close') {
        const g = this.sql('SELECT * FROM grants WHERE id=?', doc.grantId)[0];
        need(key.incarnation === info.incarnation, 'STALE_INCARNATION', 409);
        if (g) need(caller === source || caller === g.recipient, 'FORBIDDEN', 403);
        else { need(this.sql('SELECT COUNT(*) AS n FROM grants')[0].n < 10000, 'FULL', 429);
          this.sql('INSERT INTO grants VALUES(?,?,?,?,?,1)', doc.grantId, key.recipient, JSON.stringify(doc.grant), '', 0); }
        this.sql('UPDATE grants SET closed=1 WHERE id=?', doc.grantId); this.sql('DELETE FROM mail WHERE grantid=?', doc.grantId); return {};
      }
      const grantid = op === 'put' ? header.grantId : body.grantId;
      const g = this.grant(grantid, caller, source);
      if (op === 'put' || op === 'fetch') need(policy?.enabled && policy.until > now && g.keyexpires > now, 'PAUSED', 409);
      if (op === 'put') {
        need(header.recipient === g.recipient && header.keyId === g.keyid && header.generation === policy.generation, 'POLICY_STALE', 409);
        const existing = this.sql('SELECT hash FROM mail WHERE id=? UNION ALL SELECT hash FROM receipts WHERE id=?', header.messageId, header.messageId)[0];
        if (existing) need(existing.hash === envelopeDigest, 'CONFLICT', 409);
        else {
          const counts = this.sql('SELECT COUNT(*) AS n, COALESCE(SUM(size),0) AS size FROM mail WHERE grantid=?', grantid)[0];
          const length = jsonBytes(body.envelope).length;
          need(counts.n < 10000 && counts.size + length <= 32 * 1024 * 1024, 'FULL', 429);
          this.sql('INSERT INTO mail(id,grantid,hash,json,expires,size) VALUES(?,?,?,?,?,?)', header.messageId, grantid, envelopeDigest, JSON.stringify(body.envelope), header.expires, length);
        }
        return { stored: { version: 1, service: sid, source, recipient: g.recipient, grantId: grantid, messageId: header.messageId, envelopeHash: envelopeDigest, expires: header.expires } };
      }
      if (op === 'fetch') {
        need(caller === g.recipient, 'FORBIDDEN', 403);
        const envelopes: Obj[] = []; let size = 1024;
        for (const r of this.sql('SELECT json FROM mail WHERE grantid=? AND invalid=0 ORDER BY expires,id LIMIT 20', grantid)) { if (size + r.json.length > MAX_BODY - 2048) break; envelopes.push(JSON.parse(r.json)); size += r.json.length; }
        return { envelopes, generation: policy.generation, expired: this.get('expired') ?? 0 };
      }
      if (op === 'ack') {
        need(caller === g.recipient, 'FORBIDDEN', 403);
        for (const r of prepared) {
          const existing = this.sql('SELECT * FROM receipts WHERE id=?', r.messageId)[0];
          if (existing) { need(existing.hash === r.envelopeHash && existing.grantid === grantid, 'CONFLICT', 409); continue; }
          const mail = this.sql('SELECT * FROM mail WHERE id=? AND grantid=?', r.messageId, grantid)[0];
          if (!mail) continue; // Already expired or purged: retire the ACK, never manufacture a delivery receipt.
          need(mail.hash === r.envelopeHash && mail.expires === r.expires, 'CONFLICT', 409);
          need(this.sql('SELECT COUNT(*) AS n FROM receipts WHERE grantid=?', grantid)[0].n < 20000, 'FULL', 429);
          this.sql('INSERT INTO receipts(id,grantid,hash,json,expires) VALUES(?,?,?,?,?)', r.messageId, grantid, r.envelopeHash, JSON.stringify(r.signed), r.expires + DAY);
          this.sql('DELETE FROM mail WHERE id=?', r.messageId);
        } return {};
      }
      if (op === 'confirm' || op === 'reject') {
        need(caller === (op === 'confirm' ? source : g.recipient), 'FORBIDDEN', 403);
        need(Array.isArray(body.ids) && body.ids.length > 0 && body.ids.length <= 20 && body.ids.every(id));
        for (const message of body.ids) {
          if (op === 'confirm') this.sql('UPDATE receipts SET confirmed=1 WHERE id=? AND grantid=?', message, grantid);
          else this.sql('UPDATE mail SET invalid=1 WHERE id=? AND grantid=?', message, grantid);
        } return {};
      }
      if (op === 'receipts') { need(caller === source, 'FORBIDDEN', 403); return { receipts: this.sql('SELECT json FROM receipts WHERE grantid=? AND confirmed=0 ORDER BY expires,id LIMIT 20', grantid).map(r => JSON.parse(r.json)) }; }
      throw new Failure('NOT_FOUND', 404);
    });
    await this.schedule();
    // DO output gates and the explicit committed transaction precede successful responses.
    if ('info' in result) return { version: 1, info: await sign(result.info, this.env.SERVICE_PRIVATE_KEY, 'info') };
    if ('stored' in result) return { version: 1, stored: await sign(result.stored, this.env.SERVICE_PRIVATE_KEY, 'stored') };
    return { version: 1, ...result };
  }
}

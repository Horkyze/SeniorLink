import { test } from 'node:test';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join as pathJoin } from 'node:path';
import assert from 'node:assert/strict';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { generateKeyPairSync, sign as nodeSign, createHash } from 'node:crypto';
import { authenticate, bytes, b64, hex, hash, jsonBytes, domain, join, sign, envelopeBytes, envelopeHash, Signed } from '../src/protocol.ts';
const origin='https://mailbox.example';
function identity() { const {publicKey,privateKey}=generateKeyPairSync('ed25519'); return { id:publicKey.export({type:'spki',format:'der'}).subarray(-32).toString('hex'), secret:privateKey.export({type:'pkcs8',format:'der'}).toString('base64'), privateKey }; }
const source=identity(), recipient=identity(), other=identity(), serviceKey=identity();
const sid=await hash(bytes(`${origin}\n${serviceKey.id}`));
function headers(caller: ReturnType<typeof identity>, url: string, raw: Uint8Array, nonce=hex(crypto.getRandomValues(new Uint8Array(32)))) {
  const created=Math.floor(Date.now()/1000);
  const input=`("@method" "@target-uri" "content-type" "content-digest");created=${created};expires=${created+300};nonce="${nonce}";keyid="${caller.id}";alg="ed25519";tag="seniorlink-mailbox-v1"`;
  const digest=`sha-256=:${createHash('sha256').update(raw).digest('base64')}:`;
  const base=`"@method": POST\n"@target-uri": ${url}\n"content-type": application/json\n"content-digest": ${digest}\n"@signature-params": ${input}`;
  return {'content-type':'application/json','content-digest':digest,'signature-input':`sl=${input}`,'signature':`sl=:${nodeSign(null,Buffer.from(base),caller.privateKey).toString('base64')}:`};
}
async function fixture(persist?: string) {
  const options=convertV4MiniflareOptions({resourcePersistencePath:persist,workers:[{name:"mailbox",modules:true,scriptPath:'build/index.js',compatibilityDate:'2026-09-22',durableObjects:{MAILBOX:{className:'SourceMailbox',useSQLite:true}},bindings:{ORIGIN:origin,SERVICE_PUBLIC_KEY:serviceKey.id,SERVICE_PRIVATE_KEY:serviceKey.secret,ALLOWED_IDENTITIES:[source.id,recipient.id,other.id].join(',')}}]});
  let mf=new Miniflare(options);
  async function call(op: string, body: any={}, who=source, nonce?: string) {
    const url=`${origin}/v1/sources/${source.id}/${op}`, raw=jsonBytes({version:1,...body});
    const response=await mf.dispatchFetch(url,{method:'POST',headers:headers(who,url,raw,nonce),body:raw});
    return {status:response.status,body:await response.json() as any};
  }
  const info=await call('info'); assert.equal(info.status,200,JSON.stringify(info.body));
  const incarnation=JSON.parse(Buffer.from(info.body.info.payload,'base64').toString()).incarnation;
  const now=Date.now();
  const key=await sign({version:1,owner:recipient.id,keyId:'a'.repeat(64),publicKey:b64(crypto.getRandomValues(new Uint8Array(32))),revision:1,created:now,expires:now+86400000},recipient.secret,'key');
  const grant=await sign({version:1,service:sid,incarnation,source:source.id,recipient:recipient.id,grantId:'b'.repeat(64),key,created:now},source.secret,'grant');
  const acceptance=await sign({version:1,grantHash:await hash(Buffer.from(grant.payload,'base64')),source:source.id,recipient:recipient.id},recipient.secret,'acceptance');
  const registration={grant,acceptance}; assert.equal((await call('register',{registration})).status,200);
  async function policy(revision: number,generation: number,enabled=true) { return sign({version:1,service:sid,incarnation,source:source.id,revision,generation,enabled,until:enabled?Date.now()+86400000:0},source.secret,'policy'); }
  assert.equal((await call('policy',{policy:await policy(1,1)})).status,200);
  async function envelope(messageId='c'.repeat(64),generation=1) {
    const header=b64(jsonBytes({version:1,suite:'HPKE-X25519-SHA256-CHACHA20POLY1305',service:sid,source:source.id,recipient:recipient.id,grantId:'b'.repeat(64),generation,keyId:'a'.repeat(64),messageId,expires:Date.now()+100000}));
    const e={header,enc:b64(crypto.getRandomValues(new Uint8Array(32))),ciphertext:b64(crypto.getRandomValues(new Uint8Array(40))),signature:''};
    e.signature=nodeSign(null,join(domain('envelope'),envelopeBytes(e)),source.privateKey).toString('hex');return e;
  }
  return {get mf(){return mf;},call,policy,envelope,registration,restart:async()=>{await mf.dispose();mf=new Miniflare(options);}};
}
test('durable queue, independent recipient authorization, ACK and replay protection',async()=>{
  const f=await fixture();try {
    const e=await f.envelope(); assert.equal((await f.call('put',{envelope:e})).status,200);
    assert.equal((await f.call('put',{envelope:e})).status,200);
    assert.equal((await f.call('fetch',{grantId:'b'.repeat(64)},other)).status,403);
    const fetched=await f.call('fetch',{grantId:'b'.repeat(64)},recipient);assert.equal(fetched.body.envelopes.length,1);
    const h=JSON.parse(Buffer.from(e.header,'base64').toString());
    const receipt=await sign({version:1,service:sid,source:source.id,recipient:recipient.id,grantId:h.grantId,messageId:h.messageId,envelopeHash:await envelopeHash(e),expires:h.expires},recipient.secret,'receipt');
    assert.equal((await f.call('ack',{grantId:h.grantId,receipts:[receipt]},recipient)).status,200);
    assert.equal((await f.call('ack',{grantId:h.grantId,receipts:[receipt]},recipient)).status,200);
    assert.equal((await f.call('fetch',{grantId:h.grantId},recipient)).body.envelopes.length,0);
    assert.equal((await f.call('receipts',{grantId:h.grantId})).body.receipts.length,1);
    // Retried PUT after end-to-end receipt does not resurrect a consumed message.
    await f.call('put',{envelope:e}); assert.equal((await f.call('fetch',{grantId:h.grantId},recipient)).body.envelopes.length,0);
    const nonce='d'.repeat(64);assert.equal((await f.call('info',{},source,nonce)).status,200);assert.equal((await f.call('info',{},source,nonce)).status,409);
  } finally {await f.mf.dispose();}
});
test('policy generations purge pending data and old renewals cannot resurrect access',async()=>{
  const f=await fixture();try {
    const e=await f.envelope();await f.call('put',{envelope:e});
    const paused=await f.policy(2,2,false);assert.equal((await f.call('policy',{policy:paused})).status,200);
    // A lost response retries with a new revision but the same disabled generation.
    assert.equal((await f.call('policy',{policy:await f.policy(3,2,false)})).status,200);
    assert.equal((await f.call('fetch',{grantId:'b'.repeat(64)},recipient)).status,409);
    assert.equal((await f.call('policy',{policy:await f.policy(1,1)})).status,409);
    assert.equal((await f.call('policy',{policy:await f.policy(4,3)})).status,200);
    assert.equal((await f.call('put',{envelope:e})).status,409);
    assert.equal((await f.call('fetch',{grantId:'b'.repeat(64)},recipient)).body.envelopes.length,0);
    const close=await sign({version:1,service:sid,source:source.id,grantId:'b'.repeat(64),grant:f.registration.grant},source.secret,'close');
    assert.equal((await f.call('close',{close})).status,200);
    assert.equal((await f.call('register',{registration:f.registration})).status,403);
  } finally {await f.mf.dispose();}
});
test('changed signed body and duplicate JSON fields are rejected',async()=>{
  const f=await fixture();try {
    const url=`${origin}/v1/sources/${source.id}/info`,raw=jsonBytes({version:1});
    const tampered=await f.mf.dispatchFetch(url,{method:'POST',headers:headers(source,url,raw),body:'{"version":2}'});assert.equal(tampered.status,401);
    const duplicate=bytes('{"version":1,"version":1}');
    const response=await f.mf.dispatchFetch(url,{method:'POST',headers:headers(source,url,duplicate),body:duplicate});assert.equal(response.status,400);
    const e=await f.envelope();e.signature='0'.repeat(128);assert.equal((await f.call('put',{envelope:e})).status,401);
  } finally {await f.mf.dispose();}
});

async function receiptFor(e: Awaited<ReturnType<Awaited<ReturnType<typeof fixture>>['envelope']>>) {
  const h=JSON.parse(Buffer.from(e.header,'base64').toString());
  return sign({version:1,service:sid,source:source.id,recipient:recipient.id,grantId:h.grantId,messageId:h.messageId,envelopeHash:await envelopeHash(e),expires:h.expires},recipient.secret,'receipt');
}
test('receipt confirmation advances the feed without resurrecting retried PUTs',async()=>{
  const f=await fixture();try {
    const envelopes=[];
    for(let i=0;i<21;i++) { const e=await f.envelope(i.toString(16).padStart(64,'0'));envelopes.push(e);assert.equal((await f.call('put',{envelope:e})).status,200); }
    for(const e of envelopes) assert.equal((await f.call('ack',{grantId:'b'.repeat(64),receipts:[await receiptFor(e)]},recipient)).status,200);
    const page=await f.call('receipts',{grantId:'b'.repeat(64)});assert.equal(page.body.receipts.length,20);
    const ids=page.body.receipts.map((r:Signed)=>JSON.parse(Buffer.from(r.payload,'base64').toString()).messageId);
    assert.equal((await f.call('confirm',{grantId:'b'.repeat(64),ids},recipient)).status,403);
    assert.equal((await f.call('confirm',{grantId:'b'.repeat(64),ids})).status,200);
    assert.equal((await f.call('receipts',{grantId:'b'.repeat(64)})).body.receipts.length,1);
    await f.call('put',{envelope:envelopes[0]});assert.equal((await f.call('fetch',{grantId:'b'.repeat(64)},recipient)).body.envelopes.length,0);
  } finally {await f.mf.dispose();}
});
test('quarantined ciphertext does not block valid messages or become a receipt',async()=>{
  const f=await fixture();try {
    const bad=await f.envelope('1'.repeat(64)),good=await f.envelope('2'.repeat(64));
    await f.call('put',{envelope:bad});await f.call('put',{envelope:good});
    assert.equal((await f.call('reject',{grantId:'b'.repeat(64),ids:['1'.repeat(64)]},other)).status,403);
    assert.equal((await f.call('reject',{grantId:'b'.repeat(64),ids:['1'.repeat(64)]},recipient)).status,200);
    assert.deepEqual((await f.call('fetch',{grantId:'b'.repeat(64)},recipient)).body.envelopes,[good]);
    assert.equal((await f.call('receipts',{grantId:'b'.repeat(64)})).body.receipts.length,0);
    await f.call('put',{envelope:bad});assert.equal((await f.call('fetch',{grantId:'b'.repeat(64)},recipient)).body.envelopes.length,1);
  } finally {await f.mf.dispose();}
});
test('ACK after a concurrent purge retires successfully without manufacturing delivery',async()=>{
  const f=await fixture();try {
    const e=await f.envelope();await f.call('put',{envelope:e});
    await f.call('policy',{policy:await f.policy(2,2,false)});
    assert.equal((await f.call('ack',{grantId:'b'.repeat(64),receipts:[await receiptFor(e)]},recipient)).status,200);
    assert.equal((await f.call('receipts',{grantId:'b'.repeat(64)})).body.receipts.length,0);
  } finally {await f.mf.dispose();}
});
test('revocation of an offered but unregistered grant blocks delayed registration',async()=>{
  const f=await fixture();try {
    const original=JSON.parse(Buffer.from(f.registration.grant.payload,'base64').toString());
    const grant=await sign({...original,grantId:'9'.repeat(64)},source.secret,'grant');
    const acceptance=await sign({version:1,grantHash:await hash(Buffer.from(grant.payload,'base64')),source:source.id,recipient:recipient.id},recipient.secret,'acceptance');
    const close=await sign({version:1,service:sid,source:source.id,grantId:'9'.repeat(64),grant},recipient.secret,'close');
    assert.equal((await f.call('close',{close},recipient)).status,200);
    assert.equal((await f.call('close',{close},recipient)).status,200);
    // The closed tombstone exists even though this route was never registered.
    assert.equal((await f.call('register',{registration:{grant,acceptance}},recipient)).status,403);
  } finally {await f.mf.dispose();}
});

test('shared Kotlin HTTP signature vector is accepted with exact body and target binding',async()=>{
  const v=JSON.parse(readFileSync('../core/src/test/resources/mailbox-http-vector.json','utf8'));
  const request=new Request(v.url,{method:'POST',headers:{'content-type':'application/json','content-digest':v.digest,'signature-input':'sl='+v.input,'signature':'sl=:'+v.signature+':'},body:v.raw});
  assert.equal((await authenticate(request,bytes(v.raw),v.origin,new Set([v.id]),v.created*1000)).caller,v.id);
  await assert.rejects(()=>authenticate(request,bytes(v.raw+' '),v.origin,new Set([v.id]),v.created*1000));
  await assert.rejects(()=>authenticate(new Request(v.url.replace('/info','/fetch'),request),bytes(v.raw),v.origin,new Set([v.id]),v.created*1000));
});

test('ciphertext and replay-protection tombstones survive runtime restart',async()=>{
  const directory=mkdtempSync(pathJoin(tmpdir(),'seniorlink-mailbox-'));
  const f=await fixture(directory);try {
    const e=await f.envelope();assert.equal((await f.call('put',{envelope:e})).status,200);
    const nonce='7'.repeat(64);await f.call('info',{},source,nonce);
    await f.restart();
    const restored=await f.call('fetch',{grantId:'b'.repeat(64)},recipient);assert.equal(restored.status,200,JSON.stringify(restored.body));
    assert.deepEqual(restored.body.envelopes,[e]);
    assert.equal((await f.call('info',{},source,nonce)).status,409);
    await f.call('ack',{grantId:'b'.repeat(64),receipts:[await receiptFor(e)]},recipient);
    await f.restart();
    assert.equal((await f.call('receipts',{grantId:'b'.repeat(64)})).body.receipts.length,1);
    await f.call('put',{envelope:e});
    assert.equal((await f.call('fetch',{grantId:'b'.repeat(64)},recipient)).body.envelopes.length,0);
  } finally {await f.mf.dispose();rmSync(directory,{recursive:true,force:true});}
});

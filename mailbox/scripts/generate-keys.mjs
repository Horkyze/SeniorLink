import { generateKeyPairSync } from 'node:crypto';
import { writeFileSync } from 'node:fs';
const [origin, ...identities] = process.argv.slice(2);
if (!origin || !/^https:\/\/[a-z0-9.-]+$/.test(origin) || identities.some(x=>!/^[0-9a-f]{64}$/.test(x))) {
  console.error('Usage: npm run keys -- https://your-worker.your-subdomain.workers.dev [PHONE_ID ...]');
  process.exit(1);
}
const {publicKey,privateKey}=generateKeyPairSync('ed25519');
const publicHex=publicKey.export({type:'spki',format:'der'}).subarray(-32).toString('hex');
const secret=privateKey.export({type:'pkcs8',format:'der'}).toString('base64');
const env={ORIGIN:origin,SERVICE_PUBLIC_KEY:publicHex,SERVICE_PRIVATE_KEY:secret,ALLOWED_IDENTITIES:identities.join(',')};
writeFileSync('.dev.vars',Object.entries(env).map(([key,value])=>`${key}=${JSON.stringify(value)}`).join('\n')+'\n',{mode:0o600,flag:'wx'});
console.log('Created .dev.vars with permissions 0600. Keep it private; do not commit it.');
if (!identities.length) console.log('Phone admission is disabled until public phone IDs are added to ALLOWED_IDENTITIES.');
console.log('Public setup code (paste on both phones):');
console.log('seniorlink-mailbox:'+Buffer.from(JSON.stringify({origin,publicKey:publicHex})).toString('base64'));

# SeniorLink encrypted mailbox

A Cloudflare Worker and one SQLite Durable Object per sharing phone hold encrypted
updates for approved caregivers. Phones use their existing iroh Ed25519 identity
to authenticate HTTPS requests. Only the caregiver holds the X25519 private key
needed to decrypt its copies. The Worker never receives event plaintext.

This is an optional delivery path alongside `family.seniorlink/sync/3`. It uses
HTTPS, not an iroh endpoint running inside Workers. Both apps must support mailbox
control version 1 and be online together once to authorize a route. Afterwards,
the sharing phone uploads while sharing is enabled; the caregiver can retrieve
while the sharing phone is offline. No Cloudflare Queues broker, VM or push service
is required.

## Current private deployment

Deployed on 23 September 2026:
[seniorlink-mailbox.liquid-rock.workers.dev](https://seniorlink-mailbox.liquid-rock.workers.dev).
The API is online; opening its root in a browser returns a JSON `NOT_FOUND`
response because it has no website. The initial admission list is empty.

The [public setup code](setup-code.txt) identifies this service and pins its public
signing key. It grants no access by itself. Add the real phones' public mailbox IDs
to `ALLOWED_IDENTITIES` in the private `.dev.vars`, then run
`npx wrangler secret bulk .dev.vars` from this directory. Preserve the existing
service key and origin. No phone's private key is needed.

Live synthetic queue, receipt and revocation checks passed; physical-phone setup
and offline delivery still need verification. See the
[verification record](../docs/verification.md).

## Local checks

Use Node 22 or later (verified with Node 26). From this directory:

```sh
npm ci
npm run check
npm test
```

Tests use local workerd/Miniflare with SQLite Durable Objects and synthetic keys.
`npm test` includes a Wrangler dry-run build; it does not deploy or use a Cloudflare
account. Dependencies and lockfile are pinned. The bundled Wrangler currently uses
an alpha-versioned Miniflare package; it is a local development/test dependency.

## Provision a private family mailbox

The app does not ship a public mailbox address. Deployment is an operator action,
separate from building or installing the APK.

1. Choose a Cloudflare account with Workers and SQLite Durable Objects available.
   Authenticate Wrangler with `npx wrangler login`. Review the Worker name in
   `wrangler.jsonc`. Its migration creates `SourceMailbox` as a SQLite class.
2. Obtain each participating phone's **Mailbox phone ID** in **Settings →
   Encrypted queued delivery**. These public IDs form an admission allowlist;
   listing a phone does not grant access to another phone's data.
3. Generate the service signing key, private configuration and public setup code:

   ```sh
   npm run keys -- https://seniorlink-mailbox.YOUR-SUBDOMAIN.workers.dev SOURCE_PHONE_ID CAREGIVER_PHONE_ID
   ```

   Replace the URL with the exact lowercase HTTPS origin you will use. The script
   writes `.dev.vars` with mode 0600 and refuses to overwrite it. Phone IDs may
   be omitted for initial deployment with all phone access disabled. It prints only
   the public setup code. Keep `.dev.vars` private and backed up securely; do not
   paste it into issues or commit it. Phone private keys remain on their phones.
4. Deploy and install the four configuration values as Worker secrets:

   ```sh
   npm run deploy
   npx wrangler secret bulk .dev.vars
   ```

   Until configured, the Worker fails closed. `ORIGIN` must match its public URL
   exactly. `SERVICE_PUBLIC_KEY` is raw Ed25519 hex; `SERVICE_PRIVATE_KEY` is PKCS8
   base64; `ALLOWED_IDENTITIES` contains comma-separated public phone IDs. To add
   phones, update that allowlist and upload the secrets again, retaining the same
   service key. Do not rerun key generation for routine updates.
5. Install the mailbox-capable Android build on both phones. Preserve app data
   when upgrading. Paste the same public setup code into both phones' settings.
   Turn on queued delivery for each other, keep both apps open, and enable sharing
   on the sharing phone until setup completes. Existing caregiver approval is
   still required. If setup stalls, verify the origin, allowlist, phone clocks and
   both opt-ins.
6. Perform the staging checks below before relying on unattended delivery.

Keep the origin, service key, Durable Object binding/class and durable storage
stable across deployments. Worker observability is off in this configuration.
Do not log bodies, signatures, raw phone IDs or encrypted envelopes when adding
operational diagnostics. Cloudflare can still observe request metadata.

## Delivery and privacy semantics

- Local event creation and its per-caregiver outbox rows commit together. A signed
  server receipt means **stored in mailbox**. Only a caregiver-signed receipt after
  a local SQLite commit means **received**. Direct-sync cursors remain independent.
- HTTP requests bind method, exact URL, content type and raw-body digest, with
  Ed25519 signatures, short validity windows and replay nonces. Grants require
  both phones' signatures. Caregiver key descriptors and source envelopes are
  signed separately. HPKE uses X25519/HKDF-SHA256/ChaCha20-Poly1305 with fresh
  encapsulation per envelope. The suite has a public RFC 9180 test vector.
- At-least-once delivery uses stable message IDs, durable receipt intents and
  deduplication. Retrying a successful upload or ACK does not create another event.
  The server retains receipt tombstones for one day beyond envelope expiry.
  Invalid ciphertext is quarantined until expiry and never counted as received.
- A source authorizes retrieval for **24 hours**, renewing approximately hourly
  while its sharing session can run. After that lease expires, retrieval stops
  until the source renews it. Messages expire seven days after their original local
  storage time; retrying never extends that deadline. Phone history is also capped
  at 10,000 events. A mailbox upload is currently one encrypted event per request.
- Pause, feature withdrawal and revocation invalidate queued copies. Offline
  changes persist as pending cleanup and retry when the app can connect. A server
  cannot learn an offline decision immediately: delivery may continue until the
  existing lease expires. Already downloaded copies cannot be recalled. Expired
  ciphertext is removed by alarms or the next request; a paused lease alone does
  not immediately erase ciphertext, though it blocks retrieval.
- Each source supports up to eight active caregiver routes. Each route holds at
  most 10,000 envelopes / 32 MiB; each envelope is at most 64 KiB. Requests and
  responses are at most 512 KiB. Fetch/receipt batches contain at most 20 entries.
  Admission, nonce, receipt and grant limits bound storage, but this is a private
  pilot service, not a public anonymous relay. Configure account spending alerts
  and operator-side rate controls for a wider deployment.
- A route has a one-year signed key descriptor. This version requires disabling
  queued delivery on both phones, letting deletion finish, then enabling both to
  renew an expired descriptor. It retains the device's encryption key; automatic
  key rotation and forward secrecy for stored ciphertext are not implemented.
- Android Doze, Force stop, offline periods and vendor battery restrictions still
  delay work. There is no notification push or remote activation. Mailbox receipt
  does not imply a live measurement, a live source phone, or a read notification.

To replace a mailbox, first disable all queued routes on both phones and let
pending deletion finish; then configure the new setup code and enable the routes.
Recreating Durable Object storage changes its incarnation. After such a reset,
disable routes, allow cleanup to retire the old grants, and paste the setup code
again to explicitly provision the new mailbox. Keep phone app data. Lost server
storage cannot recover updates already expired or removed from the source phone.

The service sees identities, routes, sizes, times and traffic patterns. It can
withhold or delete ciphertext. Compromise of a recipient's static encryption key
can expose previously captured ciphertext; transport encryption and a mailbox do
not provide protection against a compromised phone.

## API and implementation

All operations are signed `POST /v1/sources/{sourceId}/{operation}` requests with
`version: 1`. Operations are `info`, `register`, `policy`, `put`, `fetch`, `ack`,
`receipts`, `confirm`, `reject` and `close`. `confirm` advances the source's receipt
feed while retaining duplicate-prevention tombstones. `close` includes the
source-signed grant so an interrupted, not-yet-registered setup can be revoked.
Mutable authorization and writes are checked in the same SQLite transaction.

See [the protocol design](../docs/design/iroh-mailbox.md),
[core wire/crypto](../core/src/main/kotlin/family/seniorlink/core/mailbox/), and
[Android integration](../app/src/main/java/family/seniorlink/mailbox/).
The direct protocol remains version 3; the additional setup ALPN is
`family.seniorlink/mailbox-control/1`. No native-library version change is required.

## Staging acceptance

Use synthetic events and a dedicated mailbox. These are still required on a
real deployment and two physical phones; local tests alone do not establish them:

- Complete both opt-ins; leave the caregiver offline, upload a synthetic check-in,
  take the sharing phone offline, then receive and persist it on the caregiver.
- Kill/reopen each app around upload and receipt acknowledgment. Confirm one event,
  retained direct cursor, and transition from stored to received on the source.
- Pause or revoke while online, then offline. Confirm queued deletion, pending
  deletion wording, blocked retrieval after lease expiry and no remote restart.
- Exercise Doze, Force stop, network changes, a full queue and a second caregiver.
  Confirm existing direct-only phones still sync and caregiver mode collects no data.
- Keep the Worker service key and Durable Object data across an ordinary deployment.
  Confirm stored ciphertext and grant tombstones survive the deployment.

See [verification records](../docs/verification.md) for checks actually performed.

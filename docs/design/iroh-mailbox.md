# Serverless mailbox using SeniorLink's iroh identities

Status: base implementation added to the source tree, 2026-09-22; private Worker
deployed 2026-09-23. The Android feature is included in the 0.1.13 family pilot release. This document records the architecture and longer-term requirements.
The implemented scope, exact API, setup steps and remaining staging checks are in
[mailbox/README.md](../../mailbox/README.md). Implemented: signed HTTPS requests,
HPKE envelopes, two-party grants, SQLite Durable Object queues, durable Android
outbox/receipts, opt-in settings, Pause/revocation cleanup and separate iroh setup
ALPN. Delivery polls while Android permits execution. Automatic key rotation,
WebSocket wakeups and the full deployment/failure matrix below remain future work.
No additional collection is enabled by this feature.

## Intended behavior

Use Cloudflare Workers for the HTTPS mailbox API and SQLite-backed Durable Objects
for persistent encrypted envelopes, authorization and receipts. Phones authenticate
HTTP requests using signatures made by their existing permanent iroh identities.
They can upload and retrieve at different times. Cloudflare manages execution and
storage; no always-running VM, container, Kafka broker or Firebase is required.

This revises the earlier native-iroh server proposal. Direct phone-to-phone sync
still uses iroh. Mailbox access uses HTTPS with the same identity trust and encrypted
message design, not iroh QUIC transport. Workers exposes HTTP(S)/WebSocket handlers,
not the native UDP/custom-QUIC listener used by the current Android transport.
Cloudflare's HTTP/3 support does not expose arbitrary iroh ALPNs to a Worker.

Iroh has browser/Wasm support and relay connections over WebSockets. That does not
establish a supported, hibernation-compatible iroh endpoint inside Workers: such a
port would need separate runtime, lifecycle, relay and interoperability research.
It is outside this proposal, rather than claimed impossible in principle. There
is no iroh-to-HTTP bridge requiring a hidden always-running server in this design.

Public iroh discovery/relays remain relevant to direct sync. Cloudflare is the
operator of the mailbox infrastructure but receives only encrypted event content.
Direct synchronization continues independently if the mailbox fails.

```mermaid
sequenceDiagram
    participant S as Sharing phone
    participant M as Worker + Durable Object
    participant C as Caregiver phone
    S->>S: Save event and pending deliveries
    S->>M: HTTPS PUT envelope encrypted for C; signature by S
    M->>M: Commit envelope to durable storage
    M-->>S: Stored receipt
    Note over S: Can now go offline
    C->>M: HTTPS FETCH pending envelopes; signature by C
    M-->>C: Encrypted envelopes
    C->>C: Verify, decrypt, validate, commit event and receipt intent
    C->>M: ACK exact envelope IDs with signed receipts
    M->>M: Commit receipts and remove acknowledged ciphertext
    M-->>C: ACK committed
    Note over S,C: Existing direct sync remains available when both are reachable
```

Upload promptly for each enabled mailbox recipient, rather than waiting through a
long direct-delivery failure. A direct protocol-3 acknowledgment is insufficient
to prune this independent mailbox queue in v1; a later mailbox fetch can cheaply
deduplicate and acknowledge that same event.

## Reuse identities; separate encryption keys

The current permanent identity is an Ed25519 key and already signs pairing
exchanges. Reuse it for direct iroh authentication, signed mailbox HTTP requests,
authorization documents, message signatures and recipient-signed delivery receipts.
Private identity keys stay on their phones. A public key is an identifier, not a
password or a secret queue address. The Worker verifies request signatures; HTTPS
alone does not establish the caller's iroh identity. Cloudflare Web Crypto supports
Ed25519 verification. Use standard Ed25519, not its legacy NODE-ED25519 variant.

Give each caregiver an additional randomly generated X25519 mailbox encryption key.
Bind it to the existing identity with an Ed25519-signed key descriptor containing
the protocol version, owner identity, key ID, public encryption key, monotonic key
revision, and validity interval. Exchange and pin this descriptor directly over an
authenticated connection between the already paired phones. Persist the highest
accepted revision. The server must not choose replacement keys for either phone.
Rotation uses a newer identity-signed descriptor and retains old private keys only
while their outstanding envelopes may still legitimately arrive.

This is reuse of the existing identity and trust relationship, not reuse of the
same raw key bytes for two cryptographic algorithms. Do not reinterpret the iroh
Ed25519 public key as an X25519 encryption key or build a custom encryption scheme.
Store new secret material through the existing Android Keystore-backed Secrets
mechanism, with backups disabled as today.

Proposed envelope encryption is RFC 9180 HPKE base mode with DHKEM(X25519,
HKDF-SHA256), HKDF-SHA256, and ChaCha20-Poly1305. Use a maintained implementation
and its test vectors; library selection and Android/native interoperability are
implementation prerequisites. Base mode does not authenticate the sender, so
the source's separate Ed25519 signature is mandatory. Each envelope uses a fresh
HPKE context; retry the identical persisted envelope bytes after an uncertain PUT.

Static recipient-key HPKE does not provide forward secrecy against later compromise
of that recipient key. Rotation limits exposure but is not a ratchet. A ratcheted
protocol is a separate future design, not a property claimed for this v1.

## Protocol boundaries and pairing

| Interface | Purpose | Compatibility |
| --- | --- | --- |
| `family.seniorlink/sync/3` | Existing direct Pull / Batch / Ack / Receipt | Keep byte format and behavior unchanged |
| `family.seniorlink/mailbox-control/1` | Approved phones exchange mailbox capabilities, key descriptors and setup consent | New phones only; unsupported means direct-only |
| HTTPS `/v1/...`, mailbox schema version 1 | Phone-to-Worker enrollment, policy, upload, fetch and receipts | New service and phones only; no custom mailbox ALPN on Workers |

Do not add fields to old protocol-3 messages and assume old decoders accept them.
Keep existing pairing and history. Already paired phones need one overlapping
online setup session to exchange capabilities and agree to mailbox delivery; they
do not need a new identity or a repeated QR pairing. Both must be upgraded to use
the mailbox. Mixed-version pairs continue direct synchronization.

Explicit setup pins an HTTPS origin and an application service-signing public key.
Their combination defines the service ID used in grants and envelopes. This is
not an iroh EndpointId for a reachable mailbox listener. Verify normal HTTPS
certificates and reject redirects to another origin. Store the service signing key
as a Worker secret and plan authenticated rotation before deployment. Bind all
setup documents to the service ID so grants cannot be replayed against another
service. Replacing it requires an authenticated migration or explicit setup.

Use one owner of the permanent iroh endpoint per phone process for direct sync
and mailbox-control setup; do not bind competing endpoints with the same key.
HTTPS delivery itself does not need an additional iroh endpoint. Keep the temporary
pairing endpoint separate as today. The existing Kotlin/native API must be checked
for setup-protocol ALPN dispatch support during implementation; a Rust Router API
is not automatically an exposed Kotlin API. Any required FFI work follows
[native build compatibility requirements](../../native/README.md).

## Queue authorization

One logical queue belongs to `(source identity, caregiver identity, grant ID)`.
Each caregiver has independent access and acknowledgment state. All queues from
one sharing phone live in one source-specific Durable Object, allowing a source
Pause or policy change to update them atomically. The grant ID is fresh for each
authorization after revocation; a display name never grants access.

The source signs a grant identifying the service and the source object's storage
incarnation, source, recipient, accepted recipient-key descriptor hash, policy
generation, allowed operations, and bounded delivery authorization. The caregiver
signs acceptance of that exact grant.
Both participants register it with signed HTTPS requests. Knowing a recipient
public key alone cannot create a queue or upload to it.

For a private pilot, the operator provisions an allowlist of permitted phone
identities; both participants must be admitted. Authentication does not confer
unlimited storage. Quotas apply per queue and across all queues for a source.
There are no reusable secrets embedded in the APK and no open anonymous enrollment.

The source alone uploads source envelopes and updates source policy. The recipient
alone fetches and acknowledges its envelopes; it can also close its queue. Either
participant can close the grant, but neither can reopen it unilaterally. Removal
requires a new mutually accepted grant after reapproval. The server retains closed
grant tombstones and highest policy revisions to reject replay, including across
restart. Its backup/restore procedure must preserve these records. If restoring
an older snapshot or losing policy state, fail closed: create a fresh random
storage incarnation, invalidate all restored grants and require newly signed
mutual registration. Old signed grants bind the old incarnation and cannot reopen
queues. Normal durable restarts retain the incarnation. Do not claim automatic
detection of an operator or malicious server rolling back its entire state.

## HTTPS request authentication

Profile RFC 9421 HTTP Message Signatures with Ed25519 and RFC 9530 Content-Digest
(SHA-256). Require signatures over method, complete target URI, content type,
content digest and signature parameters. Require a fixed SeniorLink mailbox tag,
the existing phone public identity as key ID, created/expires times, and a random
nonce. For v1 use POST operation endpoints, no query parameters, JSON bodies, and
reject content encodings; specify exact URL and header handling in interoperability
fixtures. Verify the digest of the raw request body, not reserialized JSON.

Proposed request validity is at most five minutes, with two minutes of allowed
clock skew. Reject missing covered fields, expired signatures, an unrecognized
tag/key, and an incorrect signature. No bearer tokens or Cloudflare account API
credentials are distributed to phones. Caregiver FETCH and ACK need authentication
just as much as source PUT. These HTTP signatures are separate from the durable
source signatures on envelopes and caregiver signatures on delivery receipts.

The Worker applies body/rate/admission limits and verifies authentication before
routing via an internal binding to the source's Durable Object. The object checks
current grant/policy and consumes `(caller, nonce)` durably with the operation's
transaction. A repeated nonce is rejected. Retain it through the signature's full
acceptance window including clock skew. Retry using a fresh request signature and
nonce, but the same immutable message ID, policy revision or operation ID: retries
remain idempotent if a response was lost. No authorization decision lives only in
Worker memory or a stale cache. Never serve cached FETCH data after revocation.

Bind the signed target path and operation body to the same source ID before routing.
Use parameterized SQL and strict identifier parsing. Internal object RPC endpoints
are not exposed as a second unauthenticated public API. Mutable authorization and
replay decisions are made inside the object; an earlier valid signature is not
sufficient to bypass a policy change while requests interleave.

## Envelope and operations

Use bounded UTF-8 JSON request/response bodies over HTTPS. Binary fields use a
single specified base64 encoding. Content-Length alone is not a size safeguard:
bound streamed input too. Responses use `Cache-Control: no-store`; no cache layer
may retain recipient ciphertext responses or override policy checks.
V1 rejects unknown versions, duplicate JSON keys, unknown fields, invalid ranges,
oversized bodies, and invalid enums. JSON is a transport wrapper, not an implicitly
canonical signature format.

Durable signed objects carry the exact payload bytes plus their signature. Sign a
distinct ASCII domain prefix followed by those bytes; verify the same bytes before parsing.
Use separate prefixes for key descriptors, grants, policy updates, envelopes, and
delivery receipts. Freeze encodings and cross-language fixtures before coding.

An envelope has an authenticated header with version, cryptographic suite, service
ID, source ID, recipient ID, grant ID, policy generation, recipient key ID, random
256-bit message ID, and absolute expiry. HPKE uses the exact header bytes as AAD
and a mailbox-specific context string. Ciphertext contains one existing Event and
its original source storage time. The source signs a length-delimited encoding of
the header, HPKE encapsulation output, and ciphertext. Sequence number, kind,
measurement time and readings stay inside the ciphertext. Logical event identity
remains `(source ID, Event.sequence)`.

The recipient verifies the signature against its already approved source identity,
checks the intended recipient/service/grant/key/policy, decrypts, and runs existing
Event validation. The server verifies the outer signature and routing metadata,
but cannot validate event contents. Every check also respects local peer removal.

| Operation | Required behavior |
| --- | --- |
| `REGISTER_GRANT` | Validate both grant signatures, HTTP caller identity, admission, key binding and bounded policy; idempotent for identical bytes |
| `SET_POLICY` | Source-signed increasing revision; atomically renew authorization or invalidate a generation and purge/close it |
| `SET_SOURCE_POLICY` | Source-signed increasing revision that pauses/purges all its queues atomically in the source object; per-grant policy cannot override it |
| `PUT` | Source-authenticated; persist exact envelope before returning a server-signed stored receipt bound to message ID, envelope hash and expiry; repeated message ID with different bytes is an error |
| `FETCH` | Recipient-authenticated; return at most 20 unacknowledged envelopes within byte limits; recheck policy before each response |
| `ACK` | Recipient-signed receipts for exact message IDs and envelope hashes; persist receipts before deleting ciphertext and replying |
| `FETCH_RECEIPTS` | Source fetches/verifies caregiver receipts and persists delivery state; server receipt-retention limits must be explicit |
| `CLOSE_GRANT` | Either authorized participant can permanently close that grant; purge ciphertext and preserve replay protection |

No global destructive queue pop: FETCH without ACK leaves an item available for
retry. A queue receipt means stored, not delivered. A caregiver receipt means
durably received, not viewed by a person. A malicious server can withhold or delete
messages, but cannot forge a caregiver signature proving delivery.

Proposed pilot limits: 64 KiB per encrypted envelope, 512 KiB per data body,
16 KiB per control body, and 20 envelopes/receipts per request, including encoding
overhead. Cap each queue at 10,000 envelopes and 32 MiB; at most eight active
caregiver queues and 256 MiB of envelope data per source. Separately bound control,
nonce, receipt and tombstone state; reserve room for purge and ACK operations.
Reject new PUTs explicitly when full; do not silently discard acknowledged uploads.
Validate limits against synthetic maximum-size existing Event fixtures before
freezing them. Bound concurrent requests, timeouts, retries and signature
work; cap input and perform cheap admission checks before expensive parsing. Return bounded errors
such as `FULL`, `EXPIRED`, `POLICY_STALE`, `REVOKED`, and `UNSUPPORTED_VERSION`.

## Durable delivery and coexistence with direct sync

On the sharing phone, persist each event and one pending-delivery row per opted-in
caregiver in the same database transaction. A background worker seals and stores
an immutable envelope, uploads it, and records server receipt. Every stage checks
the current sharing session and policy generation. Retention, withdrawal, and
revocation must cancel pending rows too. A crash must neither lose the enqueue
intent nor resurrect a withdrawn delivery. Retained-history enrollment uses the
same eligibility checks and original retention deadline.

On the caregiver, serialize direct and mailbox ingestion per source. In one
transaction, store the event if absent and a durable mailbox receipt intent. Only
then sign/send ACK. On a duplicate event, compare validated content rather than
silently accepting a conflicting `(source, sequence)` payload. An ACK lost in
transit is retried from that persisted intent. Keep replay/deduplication records
through the maximum envelope validity even if event data is pruned; old mailbox
replays must not refresh local retention indefinitely.

**Mailbox arrivals must never advance the existing direct-sync cursor.** Suppose
the direct cursor is 100 and event 102 arrives through the mailbox before 101.
Advancing the cursor to 102 would make the next direct Pull skip 101 permanently.
Protocol-3 Batch.through also represents intentional filtering/retention gaps;
maximum observed sequence is not a substitute for that protocol's progress.

Keep three separate concepts:

- Existing direct-sync cursor, advanced only by validated protocol-3 batches.
- Exact mailbox message receipt ledger, independent of event ordering.
- Displayed event history, deduplicated by source and sequence across both paths.

Mailbox v1 fetches outstanding IDs; any later pagination token is only a server
queue position, never a source-history cursor. Source-side mailbox receipts do not
call the existing `Store.acknowledge()` with a maximum sequence. They update the
separate per-recipient delivery ledger. Mailbox ingestion must not imply a recent
live connection with the source or mark the entire source history "Up to date".

Track gaps by delivery path. The current sticky direct-history gap flag may remain
set even when a mailbox event fills that gap. Reconcile known missing ranges before
presenting a combined history warning; do not clear an unrelated gap merely because
one mailbox message arrived.

Expiry leaves bounded status/tombstone information for the sender and recipient
so the UI can report expired deliveries. Server omission cannot be ruled out by
cryptography; do not claim that an empty mailbox proves complete history. Direct
catch-up remains an independent reconciliation path. Invalid envelopes are
quarantined/reported with bounded retries rather than blocking every later item;
they never receive a successful delivery ACK.

## Consent, Pause, withdrawal and expiration

This is the unavoidable semantic change: the current source checks Sharing before
every direct batch. An offline source cannot tell a server immediately that the
user just paused, withdrew a feature, or removed a caregiver. Already uploaded
ciphertext may still be delivered. No key-reuse technique removes that fact.

Keep mailbox delivery off for existing installations until the sharing user
explicitly enables "Allow encrypted queued delivery while this phone is offline"
for an approved caregiver and the caregiver accepts. Explain the pending-deletion
limit at setup. This proposal does not authorize silently weakening the current
Pause contract. If immediate offline Pause must prohibit all future receipt of
previously uploaded data, this asynchronous mode cannot meet that requirement;
retain direct-only delivery for that choice.

Proposed pilot policy: envelopes expire no later than seven days after original
source storage, matching existing retention rather than restarting it on upload.
Separately, server delivery authorization expires within 24 hours of the source's
last signed renewal. Renew only while Sharing and mailbox consent remain enabled.
Expiry blocks FETCH even if ciphertext has not yet expired; a later authorized
renewal can expose still-valid envelopes again. This supports up to 24 hours of
source-offline delivery per renewal, not seven days of guaranteed availability.
Longer leases increase availability and the maximum uncommunicated withdrawal
window together. This is an explicit proposed product parameter, not measured need.

Require sound server time and reject excessive clock skew at renewal. Envelopes
bind absolute expiry; retries never extend it. Clients enforce their own known
revocations and expiry but cannot infer an offline source's unseen policy change.

When the source pauses, cancels mailbox delivery, loses required permission, or
revokes a peer: stop collection/delivery as appropriate locally immediately,
cancel unsent work, and persist a higher signed policy revision that purges pending
server data. A control-only cleanup attempt may run while sharing is paused: it
must not collect data, upload events, renew delivery authorization or start Sharing.
On reconnect, process pending purge/close operations before any new PUT or renewal.
Report "Mailbox deletion pending" until server acknowledgment.

For a feature/SMS-policy/wearable-selection change, invalidate and purge the whole
affected policy generation, then rebuild only still-authorized retained events
under a new generation. This avoids exposing feature labels in the server index.
Do not start the replacement generation until the purge revision is committed.
Reapproval after peer removal requires a new grant; a normal resume uses a newer
policy generation within a still-open grant. Delayed workers cannot upload or
acknowledge policy changes as if their obsolete generation were current.

The server atomically serializes purge/close with FETCH and PUT policy checks and
cancels affected active transfers on revocation. Bytes already transmitted cannot
be recalled. A malicious server can retain ciphertext, and an approved recipient
with its decryption key can read ciphertext it obtains despite a later deletion
request. Lease limits constrain a compliant server, not cryptographic recall.
Previously received caregiver copies remain outside source-side deletion control,
as with direct sharing today.

## Serverless service and mobile integration

Implement the API and Durable Object in TypeScript with a pinned Workers
compatibility date and Wrangler configuration. Route by source identity to one
`SourceMailbox` object with SQLite-backed storage. It contains the source policy,
recipient grants, ciphertext envelopes, HTTP replay nonces, delivery receipts,
idempotency records and revocation/expiry tombstones. Multiple recipients remain
separate logical queues with separate ACLs. The source object is a small unit of
consistent storage, not a dedicated always-running VM.

SQLite-backed Durable Objects provide transactional, strongly consistent storage.
Keep each eligibility check and corresponding mutation inside the appropriate
storage transaction. Do not assume that asynchronous handlers cannot interleave.
PUT responses wait for durable commit; ACK records receipt and removes ciphertext
atomically. Source-wide pause and purge update all recipient queues in the same
object. No external network call occurs inside these transactions. Receipt signing
after commit must remain recoverable on request retry.

Use an object alarm for the next expiry requiring cleanup. The alarm processes a
bounded batch, is idempotent under at-least-once execution, and schedules the next
deadline. Do not wake every object every second. FETCH independently enforces
expiry, leases and revocation even if cleanup is delayed. Inactivity/eviction or a
deployment must lose no state: reconstruct from storage, not in-memory counters.

Cloudflare Queues is not needed for the authoritative mailbox. This application
needs recipient-specific queries, per-recipient ACKs, policy generation purges and
transactional authorization alongside data. Durable Object tables implement those
semantics directly. Queues may later dispatch optional background jobs; it must
not become a second source of truth. Never put Cloudflare queue/account API tokens
on phones. D1 and R2 are also unnecessary for v1: the bounded event envelopes fit
in object storage, and external blob storage would complicate atomic deletion.

Initially use short signed HTTP requests. A later optional WebSocket can signal
"mail available" while the caregiver is connected, using the hibernation API and
reauthorization after reconnect. It is a hint, not a delivery receipt or an Android
wake mechanism. Do not require a permanent socket or relay connection to keep the
mailbox addressable; requests activate the Worker/object when needed.

The service knows identities, IP addresses, who exchanges with whom, sizes and
timing. Cloudflare terminates HTTPS but sees event ciphertext because phones apply
envelope encryption first. The service remains trusted for availability, durability
and enforcement of remote deletion. Avoid request-body capture, signature/key dumps
and personally identifying logs. Worker secrets hold only service-side signing
material, never phone private identity or decryption keys.

Deletion removes ciphertext from the live mailbox API; do not promise immediate
physical removal from provider backups/PITR. Recovery must preserve newer policy
state or follow the fail-closed storage-incarnation procedure above. Retention and
backup behavior need to be described to users before deployment.

Cloudflare outages, quota exhaustion or account/configuration failures can interrupt
the mailbox. Phones keep retained local history and retry; direct iroh sync remains
independent. Signed server storage receipts attest to the service's promise, not
infallible storage. Only caregiver signatures establish caregiver receipt.

Serverless does not mean free. Budget Worker requests, object requests/CPU, storage
and SQL writes including indexes and nonce/receipt records. Batch small operations
within existing limits and back off empty polls and failures. Set operational
alerts and bounded admission. Choose a plan from measured pilot traffic; do not
promise the free tier will remain sufficient or reliable after quotas are reached.

Android continues using the existing network windows, foreground session and
recovery mechanisms. The mailbox worker shares those lifecycle constraints. Fetch
immediately when the caregiver becomes active and during permitted background
windows. An optional live WebSocket can notify a currently running receiver of new
mail; this cannot wake a suspended Android app. No Firebase dependency is required
for v1. The improvement is independent connectivity windows, not guaranteed instant
delivery or continuous collection.

| Existing component | Proposed change |
| --- | --- |
| [Protocol.kt](../../core/src/main/kotlin/family/seniorlink/core/Protocol.kt) | Reuse Event validation; add separate mailbox models/codecs without altering Wire.VERSION 3 |
| [IrohPairing.kt](../../app/src/main/java/family/seniorlink/pairing/IrohPairing.kt) | Reuse permanent-identity signing approach with new domain prefixes |
| [IrohSync.kt](../../app/src/main/java/family/seniorlink/net/IrohSync.kt) | Keep direct wire behavior; share endpoint ownership for setup ALPN dispatch only; HTTPS mailbox is a separate transport adapter |
| [Store.kt](../../app/src/main/java/family/seniorlink/data/Store.kt) | Explicit migration for outbox, grants, key descriptors, mailbox receipt intents and tombstones; preserve identity/history/direct cursors |
| [SeniorApp.kt](../../app/src/main/java/family/seniorlink/SeniorApp.kt) | Coordinate endpoint demand and per-source ingestion |
| [MonitorService.kt](../../app/src/main/java/family/seniorlink/monitor/MonitorService.kt) | Tie upload/renewal to current sharing consent; cleanup is distinct from active sharing |
| New Android mailbox client | Signed HTTPS requests, origin validation, retries and receipt verification using existing identities |
| New Worker and SourceMailbox Durable Object | HTTP signature validation, durable queues, admission, quotas, expiry and transactional policy enforcement |

Display separate statuses: saved locally, stored in mailbox, received by caregiver,
expired before receipt, and remote deletion pending. Keep measurement time, local
receipt time, mailbox contact time and direct source contact time distinct.

## Implementation and verification sequence

1. Freeze consent text, lease policy, wire encodings, cryptographic suite and
   cross-language fixtures, including HTTP signing with Kotlin and verification
   in Workers Web Crypto. Verify the chosen HPKE implementation and setup-protocol
   iroh APIs on Android 8+ and all supported ABIs. No transport dependency upgrade
   is assumed by this document.
2. Implement/test the protocol, Worker and SQLite Durable Object with synthetic
   identities/events. Verify migrations, alarm retries, hibernation/eviction,
   HTTP request replays, rate limits, quota failures and measured resource usage.
3. Add explicit database migrations and opt-in setup; test old installations and
   direct-only peers before enabling uploads.
4. Add outbox, receiver, deletion control and honest delivery UI; run the repository
   checks and native/APK validation appropriate to changed code.
5. Test real phones overnight and through offline/reboot/network changes, measuring
   receipt delay, expiration and battery impact. Record only completed checks in
   [verification.md](../verification.md).

Required tests include: authenticated-ID spoofing; missing or replayed grants;
service/key substitution; malformed request bodies and forged ciphertext/signatures;
out-of-order mailbox 102 before direct 101; simultaneous duplicate delivery and
conflicting event IDs; sender crash around outbox/sealing/PUT receipt; caregiver
crash around database commit/ACK; server crash around storage/delete/receipt commit;
quota rejection; retention and lease expiry; clock skew; key rotation with pending
mail; Pause/revoke while server is unreachable; stale PUT after generation purge;
feature/SMS-policy withdrawal; peer remove/reapprove; old/new app interoperability;
server restore retaining revocations; unavailable server with successful direct
sync; and delivery after source goes offline within its authorization lease.
For HTTP, also test altered method/path/body/digest, wrong origin, duplicate JSON
keys, request nonce replay, timestamp skew, a lost response retried with a fresh
nonce, and authenticated requests arriving after source-wide revocation. Deploying
to Cloudflare and connecting actual phones are later implementation tasks.

## Sources and current-code evidence

- [Current direct protocol](../../core/src/main/kotlin/family/seniorlink/core/Protocol.kt),
  [storage](../../app/src/main/java/family/seniorlink/data/Store.kt),
  [pairing identity model](../pairing.md), and
  [pinned native transport](../../native/README.md), inspected for this design.
- [iroh protocol streams](https://docs.iroh.computer/protocols/using-quic) and
  [protocol routing](https://docs.rs/iroh/latest/iroh/protocol/struct.Router.html):
  application protocols run over iroh; online API documentation may describe newer
  versions than the repository's pinned 1.1.0.
- [iroh identity signing API](https://docs.rs/iroh/latest/iroh/struct.SecretKey.html):
  signing and identity functions, also exercised by current local pairing code.
- [RFC 9180](https://www.rfc-editor.org/rfc/rfc9180.html): HPKE construction, algorithm
  suites, test vectors and limits. Message signatures, authorization, durable queues
  and replay/delivery tracking above are application design, not guarantees supplied
  by HPKE itself.
- [Workers protocols](https://developers.cloudflare.com/workers/reference/protocols/):
  supported public interfaces; HTTP/3 is not a native custom-QUIC listener.
- [Workers Web Crypto](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/):
  Ed25519 verification support.
- [SQLite Durable Object storage](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/),
  [alarms](https://developers.cloudflare.com/durable-objects/api/alarms/), and
  [WebSocket hibernation](https://developers.cloudflare.com/durable-objects/best-practices/websockets/):
  platform primitives used by this proposal.
- [Cloudflare Queues pull consumers](https://developers.cloudflare.com/queues/configuration/pull-consumers/):
  a separate queue API, not the chosen mailbox authorization/storage model.
- [Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/):
  usage-based billing and free-plan quota behavior, checked for this revision.
- [RFC 9421](https://www.rfc-editor.org/rfc/rfc9421.html) and
  [RFC 9530](https://www.rfc-editor.org/rfc/rfc9530.html): HTTP request signatures
  and content digests; the required profile and replay policy are application rules.
- [Iroh browser/Wasm support](https://www.iroh.computer/blog/iroh-0-33-0-browsers-and-discovery-and-0-RTT-oh-my)
  and [relay WebSocket transport](https://www.iroh.computer/blog/iroh-0-91-0-the-last-relay-break):
  these establish browser/relay capabilities, not verified Workers endpoint support.

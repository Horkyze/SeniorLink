# Single-scan pairing

The sharing phone displays an invitation, the caregiver scans it, and both show the
same four-character code. The sharing phone's **Codes match — connect** button is
the explicit consent action. A name is optional and is never an authentication factor.
Both apps must be reachable. This protocol does not wake a closed app.

## Identities and invitation

Each installation already has a permanent iroh/Ed25519 identity, encrypted locally
with an Android Keystore key. Pairing uses separate, temporary iroh endpoints so it
does not replace the identity/address used by ongoing monitoring and catch-up.

The versioned QR contains the sharing phone's permanent public key, name, temporary
endpoint ID and address ticket, a random 128-bit invitation token, and a commitment
to a random 256-bit host nonce. The nonce itself is not in the QR. The invitation
is a short-lived capability to request pairing, not permission to read history.
No permanent private keys are transmitted. The temporary listener accepts only the
pairing ALPN and never handles history requests.

## Signed exchange

1. The caregiver signs a request with its permanent identity. The request includes
   the hash of the entire invitation, its permanent and temporary public keys,
   role, name, and a fresh 256-bit nonce. The sharing phone verifies the signature,
   checks the temporary key against the authenticated QUIC remote ID, and requires
   the caregiver role. It pins one immutable request for this invitation.
2. The sharing phone reveals its committed nonce in a signed WAITING response
   bound to the exact request hash. The caregiver verifies the permanent signature,
   request hash and nonce commitment. Each phone derives the code independently
   from the same request and host nonce. Replies and requests have distinct signing
   domain prefixes; the visual-code hash has another distinct prefix.
3. The user compares the codes and confirms on the sharing phone. APPROVED is an
   in-memory decision for that exact request; it does not start monitoring or yet
   create new sharing access. Polls/reconnections retain this decision and nonce.
4. After verifying APPROVED, the caregiver durably saves the sharing identity and
   sends a signed acknowledgement. Only then does the sharing phone save the
   caregiver and return COMPLETE. Retries are idempotent. No acknowledgement is
   sent after a failed local write. Removing a saved peer blocks old-request reuse.

The short code is 20 bits mapped to four symbols from a 32-character alphabet that
omits I, O, 0 and 1. It supplements the QR's identity pinning and signature checks;
it is not a password or proof of a person's real-world identity. Committing the
host nonce before receiving the caregiver contribution, and freezing the caregiver
request before revealing that nonce, prevents choosing a contribution after seeing
the final code. The short-authentication-string commitment principle is described
in [RFC 6189 section 7](https://www.rfc-editor.org/rfc/rfc6189.html#section-7).

## Cancellation, limits and recovery

The host uses a monotonic five-minute deadline, bounded 8 KiB messages, four concurrent
connection slots and ten-second exchanges. Rejection consumes the invitation; a
different request cannot replace its pinned identity or obtain a fresh challenge.
Caregiver cancellation is sticky and synchronized with approval persistence.

The ViewModel owns pairing so rotation preserves it. The invitation can remain alive
briefly when using Android's share sheet; Android may still suspend the process.
Closing a completed/rejected dialog hides it while keeping the final response
available until expiry. The final-save screen stays open until completion or error.

The caregiver retries temporary transport failures with the same endpoint, request
and code. An expired invitation or process death requires a new QR. Two independent
phone databases cannot commit atomically: a crash between their writes can leave
one phone saved. Error messages do not claim that nothing was saved. Re-pairing is
safe and preserves existing history/cursors; users can inspect the Phones list.

Existing approved phones and permanent identities are unchanged. Legacy public-key
QRs are explicitly rejected with an update instruction; they cannot silently fall
back to the former two-scan/manual-approval flow.

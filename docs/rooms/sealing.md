# Sealing, the JoinService grant, & the rendezvous inbox

Because the server does not gate reads, any key material handed to a recipient must be encrypted so
that **only the intended recipient profile can read it**. This ECIES-style sealed envelope is
implemented in `Sealing` (`social-common-domain`, using ktcrypto's ECDH and AES-GCM) and is shared by
two paths: the group `JoinService` seals each per-joiner grant with it (see [groups.md](groups.md)),
and the rendezvous bootstrap seals its invite into the peer's inbox.

## The inbox (rendezvous bootstrap)

Each profile's own room carries an unlocked **inbox** keyspace (`4`). Because profile rooms are
locked only at the profile-content keyspace (`3`), the inbox keyspace is open: anyone who knows a
profile id can compute `RoomKeying.publicKeyed(profileId)` and write a locker there. That write
stays open (no signing key is routed for another profile's room), so no authorization is needed to
deliver a rendezvous invite — but the payload is sealed, so delivery ≠ disclosure. (Groups no longer
use the inbox; their key travels through the server-mediated `JoinService`.)

The invite locker id is `sha256(envelope.ephemeral_public_key)`: unique per invite (the ephemeral
key is random) and **unlinkable** — an observer of the inbox learns nothing about which room or
inviter it concerns.

## Envelope format (`SealedEnvelope`)

Hybrid encryption — a random content key encrypts the payload; the content key is wrapped to the
recipient via ephemeral ECDH:

```
seal(recipientPub, payload):
  ephemeral         = Secp256r1KeyPair.generate()
  kek               = AESSymmetricKey( sha256( ECDH(ephemeral.private, recipientPub) ) )
  contentKey        = AES-256 (random)
  ephemeral_public_key = ephemeral.public                       # 33-byte compressed
  wrapped_key          = AES-GCM( kek,        contentKey.bytes )
  ciphertext           = AES-GCM( contentKey, payload )
```

```
unseal(recipientPriv, envelope):
  kek        = AESSymmetricKey( sha256( ECDH(recipientPriv, envelope.ephemeral_public_key) ) )
  contentKey = AES-GCM.decrypt( kek, envelope.wrapped_key )
  payload    = AES-GCM.decrypt( contentKey, envelope.ciphertext )
```

ECDH is commutative, so `ECDH(ephemeral.private, recipientPub) == ECDH(recipientPriv,
ephemeral.public)` — both sides derive the same KEK. The recipient's private key never leaves
`profiles-domain`: the rooms manager calls
`MyProfilesManager.deriveSharedSecret(ownedProfileId, envelope.ephemeral_public_key)` to obtain the
ECDH secret, then `Sealing.unsealWith(secret, envelope)`.

> Implementation note: ktcrypto's `AESSymmetricKey` bytes-constructor is JVM-only; in common code
> build a key with `AESSymmetricKey.decodeKey(bytes)` and read its bytes with `encodePublic()`.

## Delivery & discovery

`RoomsManagerImpl` watches keyspace `4` on **each** of the user's profile rooms. New envelopes are
unsealed with that profile's key; only rendezvous bootstrap invites arrive here now (a group grant is
returned directly by the `JoinService.Join` RPC, not dropped in an inbox). Already-known rooms are
skipped (idempotent). Discovery of *who* to invite (learning a peer's `profileId`) is out of band.

## What is and isn't confidential

Sealed: the rendezvous bootstrap payload, and a group's shared private key inside a `JoinService`
grant. **Not** sealed: room contents (info, membership, member-profiles) are cleartext and readable
by anyone who learns the room id — so the server, which stores them, can already read group content.
Rendezvous rooms rely on room-id secrecy instead (see [rendezvous.md](rendezvous.md)). Encrypting
room contents is deferred.

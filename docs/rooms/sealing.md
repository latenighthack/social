# Sealing & the invite inbox

Because the server does not gate reads, any key material carried in an invite must be encrypted by
the client so that **only the intended recipient profile can read it**. Rooms use an ECIES-style
sealed envelope, implemented in `RoomSealing` (`rooms-domain`, internal), using ktcrypto's ECDH and
AES-GCM.

## The inbox

Each profile's own room carries an unlocked **inbox** keyspace (`4`). Because profile rooms are now
locked only at the profile-content keyspace (`3`), the inbox keyspace is open: anyone who knows a
profile id can compute `RoomKeying.publicKeyed(profileId)` and write a locker there. That write
stays open (no signing key is routed for another profile's room), so no authorization is needed to
deliver an invite — but the payload is sealed, so delivery ≠ disclosure.

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
ECDH secret, then `RoomSealing.unsealWith(secret, envelope)`.

> Implementation note: ktcrypto's `AESSymmetricKey` bytes-constructor is JVM-only; in common code
> build a key with `AESSymmetricKey.decodeKey(bytes)` and read its bytes with `encodePublic()`.

## Delivery & discovery

`RoomsManagerImpl` watches keyspace `4` on **each** of the user's profile rooms. New envelopes are
unsealed with that profile's key and dispatched by `Invite.kind`
([rendezvous](rendezvous.md) / [groups](groups.md)); already-known rooms are skipped (idempotent).
Discovery of *who* to invite (learning a peer's `profileId`) is out of band and out of scope.

## What is and isn't confidential

Sealed: the invite payload — including a group's shared private key. **Not** sealed: room contents
(info, membership, member-profiles) are cleartext and readable by anyone who learns the room id.
Rendezvous rooms rely on room-id secrecy instead (see [rendezvous.md](rendezvous.md)). Encrypting
room contents is deferred.

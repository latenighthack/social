# Rendezvous rooms

A rendezvous room is the 1:1 space shared by two profiles. Both parties derive the same room id and
lock key purely from the ECDH of their profile keys — nothing secret is transmitted, and the server
never learns the relationship (the room id is an opaque 32-byte value).

## Derivation

Let `A` be my profile key pair and `B` the peer's profile (I know `B.public` = `peerProfileId`).

```
S        = ECDH(A.private, B.public)            # == ECDH(B.private, A.public), commutative
roomId   = sha256( "social.rooms.rendezvous.room.v1" ‖ S )    # opaque 32-byte RoomId
lockKey  = Secp256r1KeyPair.fromPrivateKey(
             sha256( "social.rooms.rendezvous.lock.v1" ‖ S ) )
```

Domain-separated KDF prefixes keep the room id and the lock key independent — knowing one reveals
nothing about the other. Both constants live in `RoomsManagerImpl`. `S` is produced inside
`profiles-domain` via `MyProfilesManager.deriveSharedSecret(myProfileId, peerProfileId)`, so the
profile private key never leaves that module.

## Why it is safe

- **Confidentiality of existence & contents:** the room id is `sha256(S)`; without `S` (which needs
  one of the two private keys) the id is unguessable, so no one else can find or read the room.
  Reads being ungated does not matter — you must know the id to read.
- **Write authority:** the room is locked at room scope to `lockKey`, which both parties derive and
  no one else can. Even someone who somehow learned the room id could not write to it. It is an
  opaque (non-public-keyed) room, so the first lock is a TOFU root (`parentKeyPair = null`); since
  both sides derive the same `lockKey`, whoever locks first wins and the other's attempt is a no-op.

## Flow

```
Alice.openRendezvous(bobProfileId):
  S       = deriveSharedSecret(aliceProfile, bobProfileId)
  roomId  = sha256(ROOM_DOMAIN ‖ S);  lockKey = fromPrivateKey(sha256(LOCK_DOMAIN ‖ S))
  record RoomRecord(RENDEZVOUS, lockKey, aliceProfile) in the account room (keyspace 8)
  lockLocker(roomId, ROOM scope, lockKey, parent = null)  # TOFU root
  write Member + MemberProfile for aliceProfile
  seal Invite{kind: RENDEZVOUS, inviter_profile_id: aliceProfile} into Bob's inbox

Bob (inbox watcher unseals the invite):
  S       = deriveSharedSecret(bobProfile, invite.inviter_profile_id)
  roomId  = sha256(ROOM_DOMAIN ‖ S);  lockKey = fromPrivateKey(sha256(LOCK_DOMAIN ‖ S))
  record RoomRecord(RENDEZVOUS, lockKey, bobProfile) in the account room (keyspace 8)
  lockLocker(roomId, ROOM scope, lockKey, parent = null)  # no-op, already locked
  write Member + MemberProfile for bobProfile
```

The invite only conveys Alice's `profileId` so Bob can compute `S` — it carries **no** key material
(unlike a group invite). Both then hold `lockKey` and can mutate the shared room info; both appear
in the membership roster.

## Test

`RoomsManagerIntegrationTest."rendezvous rooms converge on the same id and both profiles can write"`
asserts Alice's returned room id equals the id Bob independently derives from the bootstrap invite,
that both are members, and that info written by either side is seen by the other.

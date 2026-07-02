# Rooms

Rooms are shared, mutable, multi-participant spaces built on the lockers lock system. They are
**not** chat — messaging is a separate, independent concern. A room has shared **info** (like a
profile, but for a room), a **membership** roster, and per-member **profile associations**.

There are two kinds:

- **Rendezvous rooms** — 1:1. Two profiles meet at `sha256(ECDH(their keys))`. No key is
  transmitted; both sides derive the room id and its lock key from the exchange. See
  [rendezvous.md](rendezvous.md).
- **Groups** — many-member. A group key pair is generated at creation and handed to invitees
  through a sealed invite; every member holds it and may write the membership list to invite
  others. See [groups.md](groups.md).

## The two facts that shape the whole design

1. **The lockers server does not gate reads.** `RoomServiceImpl.getLocker` / `getAllLockers`
   return a locker's contents to anyone who knows the `roomId` + `lockerId`. The lock system only
   verifies **write** signatures. Encrypted lockers are still deferred in the connector. So
   confidentiality never comes from the server — it comes from room-id secrecy (rendezvous) or
   from client-side sealing (invite key material).
2. **A public-keyed room's writes must be signed by the embedded authority key.** Account and
   profile rooms are `RoomKeying.publicKeyed(...)`; the lock verifier requires every locked write
   to carry a signature from that key. This is why an outsider cannot write into another profile's
   room — and why invites are **sealed** and dropped into an intentionally **unlocked** inbox
   keyspace rather than into a locked one. See [sealing.md](sealing.md).

## Membership model (v1)

Flat: every member holds the one shared room key and is equal — any member may invite others, edit
the room info, and write the membership list. Leaving deletes only your own entries. **Deferred:**
member removal / kick, key rotation & revocation, roles/permissions, and encrypting room *contents*
(only invite key material is sealed today).

## Modules

Mirrors the `account` / `profiles` slices:

- `rooms-api` — protos (`SealedEnvelope`, `Invite`, `RoomInfo`, `Member`, `MemberProfile`,
  `RoomRecord`, `RoomKind`).
- `rooms-domain` — `RoomsManager` + `RoomsManagerImpl` (owns shared key material, persists it
  device-locally in `RoomStore`, routes it via `RoomsKeySource`, watches profile inboxes),
  `RoomSealing`, `RoomsKeyspaces`.
- `rooms-usecase` — thin use cases (`CreateGroup`, `OpenRendezvous`, `InviteToGroup`, `LeaveRoom`,
  `UpdateRoomInfo`, `WatchRoomInfo`, `WatchMembers`, `WatchRooms`).

`rooms-domain` depends on `profiles-domain`: it enumerates the user's profiles and asks
`MyProfilesManager.deriveSharedSecret(...)` to do ECDH (rendezvous derivation and inbox unsealing)
without ever holding a profile's private key.

## Keyspaces (global allocation)

Numbering is a cross-feature concern: account = 1, profile-source = 2, profile = 3.

| # | keyspace | lives in | locked to |
|---|----------|----------|-----------|
| 4 | profile **inbox** (sealed invites) | each profile room | **unlocked** — anyone may write |
| 5 | room **info** | a room | shared room key |
| 6 | **membership** (roster) | a room | shared room key |
| 7 | **member-profiles** | a room | shared room key |

To make keyspace 4 writable while keeping profile content private, `profiles-domain` now locks the
profile room at **keyspace scope** (keyspace 3 only) instead of room scope, leaving the inbox
keyspace open. The lock verifier resolves the most-specific lock (locker → keyspace → room), so an
unlocked keyspace stays open even though a sibling keyspace is locked.

## Key-source chain

The lock key routed to the client per write is resolved by a linear chain, most-specific first:

```
RoomsKeySource(rooms, fallback =
  ProfileKeySource(myProfiles, fallback =
    AccountKeySource(account)))
```

`RoomsKeySource` returns a room's shared key for rooms the user is in; otherwise it falls back to
the profile key (profile-room writes) and then the account key. Wiring is linear and cycle-free —
each manager receives the `LockersClient` at `start(lockers)`, not in its constructor.

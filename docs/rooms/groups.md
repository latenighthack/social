# Group rooms

A group is a many-member room keyed by a single shared **group key pair** `G`. The room id is
`RoomKeying.publicKeyed(G.public)`, so the room authority is `G` and every write must be signed by
it. Membership is flat: every member holds `G.private` and may mint invite codes, write the
membership roster, and edit the room info. **Sharing key material means distributing `G.private`** —
done through a **server-mediated, revocable invite code** (the `JoinService` in `rooms-service`): a
member hands the server `G.private`, and the server seals a per-joiner grant (see
[sealing.md](sealing.md)) to each redeemer.

## Room layout

- `roomId = RoomKeying.publicKeyed(G.public)` — the authority is the group key.
- Locked at **room scope** to `G` (root lock signed by `G`, as the room authority). All keyspaces
  in the room therefore require a `G` signature to write.
- Keyspaces (all signed by `G`): room **info** (5, a single locker of signed disclosures — like a
  profile, but for a room), **membership** (6, one `Member` per member keyed by profile id),
  **member-profiles** (7, one `MemberProfile` per member keyed by profile id). Contents are
  cleartext — anyone who learns the room id can read them; only writes are gated. Encrypting
  contents is deferred.

Room info mirrors `Profile`: `RoomInfo` is a list of disclosures (currently just a `name`), each a
standard `SignedContent` (see `social-common-api`) whose `content` is an encoded `DisclosurePayload`
signed by the **shared room key**. Each payload also carries the **room id** (`room_id`), stamped in
at signing time, so the signature binds the disclosure to this room — a signed disclosure cannot be
replayed under a different room. (Profiles do the same with `profile_id`.) Any member holds the key,
so any member can (re)write and sign the info. `RoomsManager.updateInfo(roomId) { replaceDisclosure
{ name { value = "…" } } }` applies the builder to the current info, re-signs every disclosure
(re-stamping the room id), and writes it back; read it with the `RoomInfo.name()` helper.
`RoomsManager.watchInfo` drops any disclosure whose signature doesn't verify against the room key.
Signing is redundant with the room lock (both require `G`) but is kept as the on-wire format,
exactly as profiles do.

Membership vs member-profiles: the **membership** roster records who is in the room (presence there
is what makes a profile a member); **member-profiles** is each member's self-declared profile
association for display, resolved to profile info via the existing `ProfilesManager`. In flat v1 a
member writes both for itself on join. (This split is a deliberate modeling choice, kept minimal.)

## Create

```
RoomsManager.createGroup(name):
  G       = Secp256r1KeyPair.generate()
  roomId  = RoomKeying.publicKeyed(G.public)
  record RoomRecord(GROUP, G.private, myProfile) in the account room (keyspace 8, signed by account key)
  lockLocker(roomId, ROOM scope, G, parent = G)            # public-keyed root lock
  write RoomInfo with a signed 'name' disclosure           # signed by G
  write Member + MemberProfile for myProfile               # signed by G
```

## Invite codes & join

Group access goes through the server-side `JoinService` (`rooms-service`), a lockers `ServerExtension`
that holds room keys for code-enabled rooms and mints per-joiner grants. Membership on create/revoke
is proven by presenting `G.private` (whoever holds it is a member; the server checks it matches the
room); the grant a joiner receives is sealed to their own profile, so an intercepted code is useless
to anyone else.

```
member.createInviteCode(roomId):                          # must be a member (holds G.private)
  → JoinService.CreateInviteCode(roomId, G.private, policy?)
    server verifies publicKeyed(fromPrivateKey(G.private)) == roomId,
    stores { code → roomId, G.private, policy }, returns a 32-byte code
  → the code is the shareable blob (e.g. put on a website / QR / DM)

joiner.joinByCode(code):                                  # joins as the primary profile
  → JoinService.Join(code, myProfileId)
    server validates code + policy, seals Invite{GROUP, roomId, G.private} to myProfileId (ECIES),
    returns the SealedEnvelope
  unseal with my profile key (deriveSharedSecret + Sealing.unsealWith)
  verify roomId == publicKeyed(fromPrivateKey(group_private_key).public)   # bind key ↔ room
  persist RoomRecord(GROUP, G', myProfile)
  write Member + MemberProfile for myProfile               # signed by G' — accepted, room is G-locked

member.revokeInviteCode(roomId, code):
  → JoinService.RevokeInviteCode(code, G.private)
    server verifies membership and deletes the code → future joins fail
```

**Policy** (`CodePolicy`) lets the server narrow admission: an expiry, a max-uses count, and an
optional `allowed_profile_id`. A single-use, profile-restricted code is a direct 1:1 invite; a
multi-use code is a public join link.

**Trust & revocation.** The server holds `G.private` only for rooms with an active code. Because group
content is already cleartext to the server (only writes are gated), this is **not** a read regression
— but it does let the server (or a breach of it) write as a member and admit anyone, so it is
opt-in per room. Revoking a code stops **future** joins; already-joined members keep access, since
true eviction needs group-key rotation (deferred). The joiner does not lock the room (the creator
already did); holding `G` lets its own membership write verify against the existing room-scope lock.

## Leave

```
RoomsManager.leave(roomId):
  delete this member's Member (keyspace 6) and MemberProfile (keyspace 7)   # signed by G, still held
  drop the in-memory RoomRecord and shared key
  delete the RoomRecord from the account room (keyspace 8)                  # signed by account key
```

The order matters: the entries are deleted while `G` is still routed for the room, then the local
key is dropped. The member still *knows* `G` afterward — true revocation would require rotating the
group key and re-distributing it to the remaining members, which is deferred.

## Access rights (v1)

All members are equal. There is no admin role, no forced removal/kick, and no key rotation. Because
authority is a single shared key, any member can also edit or delete another member's roster entry —
acceptable under the flat model; finer-grained control would need per-member delegated keys via the
lock grant chain (deferred).

## Test

`JoinServiceIntegrationTest` (`rooms-service`) drives the server directly over gRPC: create → join
returns a grant only the joiner can unseal; wrong key → unauthorized; revoked / expired / exhausted /
profile-restricted codes are all rejected.

`RoomsManagerIntegrationTest."an invite code grants group access and a revoked code cannot"`
(`rooms-domain`) covers the client path against a real lockers server with an in-process Join stand-in:
create group → `createInviteCode` → the joiner `joinByCode` unseals `G` and joins → both appear in a
two-member roster and share the info → a non-member can read but not write → `revokeInviteCode` then a
later `joinByCode` fails → leaving removes the member's roster entry.

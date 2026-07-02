# Group rooms

A group is a many-member room keyed by a single shared **group key pair** `G`. The room id is
`RoomKeying.publicKeyed(G.public)`, so the room authority is `G` and every write must be signed by
it. Membership is flat: every member holds `G.private` and may invite others, write the membership
roster, and edit the room info. **Sharing key material means distributing `G.private`** — done
confidentially through a sealed invite (see [sealing.md](sealing.md)).

## Room layout

- `roomId = RoomKeying.publicKeyed(G.public)` — the authority is the group key.
- Locked at **room scope** to `G` (root lock signed by `G`, as the room authority). All keyspaces
  in the room therefore require a `G` signature to write.
- Keyspaces (all signed by `G`): room **info** (5, single locker), **membership** (6, one `Member`
  per member keyed by profile id), **member-profiles** (7, one `MemberProfile` per member keyed by
  profile id). Contents are cleartext — anyone who learns the room id can read them; only writes are
  gated. Encrypting contents is deferred.

Membership vs member-profiles: the **membership** roster records who is in the room (presence there
is what makes a profile a member); **member-profiles** is each member's self-declared profile
association for display, resolved to profile info via the existing `ProfilesManager`. In flat v1 a
member writes both for itself on join. (This split is a deliberate modeling choice, kept minimal.)

## Create

```
RoomsManager.createGroup(name):
  G       = Secp256r1KeyPair.generate()
  roomId  = RoomKeying.publicKeyed(G.public)
  persist RoomRecord(GROUP, G.private, myProfile)          # device-local, never synced
  lockLocker(roomId, ROOM scope, G, parent = G)            # public-keyed root lock
  write RoomInfo{name}                                     # signed by G
  write Member + MemberProfile for myProfile               # signed by G
```

## Invite & join

```
member.invite(roomId, [inviteeProfileId, ...]):           # one or more invitees at once
  for each invitee:
    seal Invite{kind: GROUP, room_id: roomId, group_private_key: G.private}
         into invitee's inbox                              # confidential: only they unwrap G;
                                                           # a fresh envelope is sealed per recipient

invitee (inbox watcher unseals the invite):
  if already a member of room_id: skip
  G' = fromPrivateKey(invite.group_private_key)
  persist RoomRecord(GROUP, G', invitee profile)
  write Member + MemberProfile for invitee                 # signed by G' — accepted, room is G-locked
```

The invitee does not lock the room (the creator already did); holding `G` lets its membership write
verify against the existing room-scope lock. Any member can invite further members by forwarding the
same sealed `G`.

## Leave

```
RoomsManager.leave(roomId):
  delete this member's Member (keyspace 6) and MemberProfile (keyspace 7)   # signed by G, still held
  drop the local RoomRecord and shared key
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

`RoomsManagerIntegrationTest."group invite shares the key, both become members, non-member can read
but not write"` covers: create → invite → the invitee unseals `G` and joins → both appear in a
two-member roster and share the info → a non-member can read the room but a write is rejected by the
server lock verifier → leaving removes the member's roster entry.

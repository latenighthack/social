# Locker keyspace allocation

Keyspace numbers partition lockers within a room and are a **cross-repo allocation concern**:
two features sharing a number would collide in every room. Each feature declares its numbers in
its own `*Keyspaces.kt`; this file is the registry of who owns what. Claim a contiguous block
here before shipping a new feature.

| # | Owner (repo/module) | Constant | Room | Contents |
|---|---------------------|----------|------|----------|
| 1 | social/account-domain | `AccountKeyspaces` | account | account state |
| 2–3 | social/profiles-domain | `ProfilesKeyspaces` | account + profile | profile secrets, disclosures |
| 4 | social/rooms-domain | `RoomsKeyspaces.INBOX` | profile | sealed invites (unlocked, open writes) |
| 5 | social/rooms-domain | `RoomsKeyspaces.ROOM_INFO` | any | room info disclosures |
| 6 | social/rooms-domain | `RoomsKeyspaces.MEMBERSHIP` | any | member roster entries |
| 7 | social/rooms-domain | `RoomsKeyspaces.MEMBER_PROFILES` | any | member profile associations |
| 8 | social/rooms-domain | `RoomsKeyspaces.ACCOUNT_ROOMS` | account | synced room records |
| 9 | social/messages-domain | `MessagesKeyspaces.MESSAGING` | any | single locker; messages ride as notifications |
| 10 | social/contacts-domain | `ContactsKeyspaces` | account | contact records |
| 11 | social/typing-domain | `TypingKeyspaces` | any | typing signals |
| 12 | social/read-receipts-domain | `ReadReceiptsKeyspaces` | any | read pointers |
| 13 | gwb/widgets-domain | `WidgetsKeyspaces.WIDGET_REGISTRY` | parent chat room | one `WidgetRef` per widget (locker id = widget id) |
| 14 | gwb/widgets-domain | `WidgetsKeyspaces.WIDGET_INFO` | widget child room | `WidgetInfo` (child→parent link; marks widget rooms) |
| 15 | gwb/widgets-domain | `WidgetsKeyspaces.WIDGET_BUNDLE` | widget child room | rendered `WidgetBundleState` |
| 16 | gwb/widgets-domain | `WidgetsKeyspaces.WIDGET_DATA` | widget child room | `WidgetData` bundle |
| 17 | gwb/widgets-domain | `WidgetsKeyspaces.WIDGET_STATE` | widget child room | generator `GenState` JSON |
| 18 | gwb/widgets-domain | `WidgetsKeyspaces.WIDGET_KV` | widget child room | agent notes/counters (locker id = sha256(key)) |
| 19 | gwb (reserved) | — | — | reserved for widgets growth |
| 20+ | unallocated | | | |

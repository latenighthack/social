# Configured storage

Social managers accept a shared ktstore `Database`. Compose `MessagesStorage.definitions`,
`ProfilesStorage.definitions` and `RemoteContentStorage.definitions` with
`ConnectorStorage.definitions` and application definitions before `createDatabase`.
Open the database before preparing/starting managers. Each group also has a
configured in-memory test factory. Key/value stores keep their independent API.

Server hosts compose `LoginStorage.definitions` with lockers/application definitions.
`LoginServerExtensionFactory` declares these through the extension seam and uses
the shared database; standalone extension construction uses a configured login handle.

V1 definitions preserve physical names, payload codecs and index encodings. Version 3
adoption preserves the original protobuf bytes, reconstructs indexes and converts
legacy Android text blob keys to actual BLOB bindings. Optional legacy stores absent
from an installation are created. Frozen SQLite fixtures verify every owned store,
unknown fields, key lookups and reopen. Keep V1 contracts unchanged for future
migrations; introduce new definitions and consecutive version transitions instead.

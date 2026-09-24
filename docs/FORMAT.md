# Keyrook file format: envelope version 1, document schema 2

The `core` module reads and writes `.keyrook` files. A file consists of a 76-byte binary header followed by AES-256-GCM ciphertext and its 16-byte authentication tag. There is no compression or plaintext JSON on disk. Backup copies use the same bytes and can use the extension `.keyrook.bak`.

## Header

Integers are unsigned, big endian; accepted numeric ranges are narrower than their storage types. The exact header bytes are GCM additional authenticated data (AAD).

| Offset | Size | Value |
| --- | --- | --- |
| 0 | 8 | `4b4559524f4f4b00` (`KEYROOK` and NUL) |
| 8 | 2 | Envelope version: `1` |
| 10 | 2 | Header length: `76` |
| 12 | 2 | Flags: `0` without key file, `1` with key file |
| 14 | 1 | KDF identifier: `1` = Argon2id |
| 15 | 1 | Cipher identifier: `1` = AES-256-GCM |
| 16 | 4 | Argon2 version: `0x13` |
| 20 | 4 | Argon2 memory in KiB |
| 24 | 4 | Argon2 iterations |
| 28 | 4 | Argon2 parallelism |
| 32 | 32 | Random salt |
| 64 | 12 | Random GCM nonce |
| 76 | remaining | Ciphertext followed by 16-byte tag |

Unknown versions, flags, algorithms and header lengths are rejected. Minimum envelope length is 92 bytes; a valid document requires additional ciphertext. Maximum total file size is 67,108,864 bytes (64 MiB). Appending bytes changes the tag boundary and fails authentication; truncation also fails.

## Key derivation

The master password contains 1–1024 UTF-16 code units in the current API. It is strictly encoded as UTF-8, without Unicode normalization. Invalid surrogate sequences fail. The password bytes are the Argon2 password input. When flag 1 is set, the separate key file's exact 32 bytes are the Argon2 optional secret input. There is no additional Argon2 associated-data input. Output length is 32 bytes.

Defaults: 65,536 KiB, 3 iterations, parallelism 4. Accepted bounds: 65,536–1,048,576 KiB, 1–10 iterations and parallelism 1–16. Memory must be divisible by four times parallelism. Values above 262,144 KiB or 5 iterations additionally require `allowExpensive=true`; approval does not override hard limits. Bounds are checked before running Argon2, even though the header cannot yet be authenticated. The header must not be displayed as trusted information before successful decryption.

Each encryption draws a new salt and nonce from `SecureRandom`, derives a new key, then authenticates the header and payload. Session saves preserve the current KDF parameters unless the caller explicitly supplies replacements. Changing credentials creates a new encrypted file; it does not change older copies.

## Encrypted payload

The payload is a single strict UTF-8 JSON document serialized using kotlinx.serialization. All seven root fields are required, even for an empty vault:

```json
{
  "schemaVersion": 2,
  "id": "11111111-2222-4333-8444-555555555555",
  "revision": 0,
  "customers": [],
  "projects": [],
  "entries": [],
  "templates": []
}
```

IDs use canonical lowercase UUID strings. Revision is a nonnegative signed 64-bit number. Creation through `VaultStore` requires revision zero; updates must retain the vault ID and increment revision exactly once. File concurrency tokens are SHA-256 digests of the complete encrypted file, held in memory.

Customers contain `id`, `name`, optional `contactName`, `contactEmail`, `phone` and `website`, and `notes`. Projects contain `id`, `name`, optional `customerId` and `description`, and `notes`. Entries contain `id`, `title`, `data`, `createdAt`, `modifiedAt`, optional `customerId` and `projectId`, `tags`, `notes`, optional `expiresOn` and `deletedAt`, `history` and `pinned`. Timestamps parse as `java.time.Instant`; expiry dates parse as `java.time.LocalDate`. Deletion is represented by `deletedAt`; deleted entries remain encrypted in the payload. History items contain `changedAt` and a prior `data` value. `pinned` (boolean, default `false`) marks a favorite; it replaces the schema 1 tag `keyrook:favorite`, which schema 2 reserves: a stored tag of exactly that name is rejected.

Customer and project `notes` are strings like entry notes and belong to the secret values: the model holds them as `Secret`, erases them when a vault is closed and never includes them in reports. The other customer and project fields are plain metadata; unset values are omitted or `null`. A set value is non-blank, has no leading or trailing whitespace and no control characters (a description may contain line breaks and tabs). Formats are checked leniently: `contactEmail` has exactly one `@`, a non-empty local part and a domain with an inner dot, and no whitespace; `phone` consists of digits, spaces and `+ ( ) . / -` with at least one digit; `website` has no whitespace and, if it names a scheme, uses `http://` or `https://` followed by more text.

Templates contain `id`, `name`, `type`, `fields`, `tags` and optional `customerId` and `projectId`. `type` uses the entry type names below. `fields` lists the layout as `{ "name": "…", "hidden": true, "kind": "TEXT" }` without any value: for fixed types the names are the type's field names (`web`: url, username, password, optional totp; `transfer`: host, username, password, directory; `email`: address, username, password, optional imap, pop3, smtp; `panel`: url, username, password, role; `server`: host, username, password, operatingSystem, role; `ssh`: privateKey, publicKey, passphrase, fingerprint; `domain`: name, registrar, dnsNotes), each at most once and all non-optional ones present; for `custom` they are the custom field labels. A template holds no field values, notes, ports, protocols, key types or entry references. A new entry from a template gets empty values, port 22, SFTP, Ed25519 and TLS mail endpoints on ports 993, 995 and 465.

`data.type` selects the variant:

| Type | Specific fields |
| --- | --- |
| `web` | url, username, password, optional totp |
| `transfer` | host, port, protocol, username, password, directory |
| `email` | address, username, password, optional imap/pop3/smtp endpoints |
| `panel` | url, username, password, role |
| `server` | host, port, username, password, operatingSystem, role |
| `ssh` | keyType, privateKey, publicKey, passphrase, fingerprint, serverIds |
| `domain` | name, registrar, dnsNotes, optional registrarLoginId |
| `custom` | values: map of labels to fields |

A field is `{ "value": "…", "hidden": true, "kind": "TEXT" }`. `kind` is `TEXT` or `URL`; display visibility is independent. All field values, visible or hidden, are encrypted on disk. Ports are integers 1–65535. Transfer protocols are `FTP`, `SFTP`, `FTPS`; mail encryption values are `NONE`, `STARTTLS`, `TLS`; key types are `ED25519`, `RSA4096`. A mail endpoint contains `host` (a field), `port`, and `encryption`.

Limits: 10,000 customers, projects and entries each; 1,000 templates; 100 tags, history items and custom fields per entry and 100 tags and fields per template; 262,144 UTF-16 code units per secret field or notes (entry, customer and project notes); 4,096 per title/name/template name/project description; 256 per tag/custom label/template field name and contact name; 254 per e-mail address; 64 per phone number; 2,048 per website. Template names must not be blank. Current references must resolve, SSH references must target server entries, and customer/project assignments of entries and templates must agree. Historical references need only be valid UUIDs because their old targets may have been removed.

Before deserialization a guard rejects nesting beyond 32 levels, individual JSON strings above 1,572,864 encoded bytes, containers above 10,000 members, more than 500,000 structural commas/colons, malformed UTF-8, and duplicate object keys (including equivalent escaped names). The JSON decoder rejects unknown fields, types, malformed syntax and trailing documents. Schema validation follows authentication and parsing. Errors never include parser excerpts of decrypted data.

## Compatibility and migrations

Envelope version 1 is the only envelope. Schema 1 was the first document schema, written by Keyrook up to 0.7.x; schema 2 is current since 0.8.0 and the only schema written. Unsupported versions fail with the generic invalid-vault error without modifying the file.

Two frozen test vectors in `core/src/test/kotlin/app/keyrook/core/Fixtures.kt` protect compatibility alongside RFC and NIST primitive vectors. Both are checked in as fixed bytes with sequential salt and nonce and one Argon2 iteration, and were encrypted independently of Keyrook's cipher code with the JDK AES-GCM provider:

- `frozenV1Fixture`: an empty schema 1 vault without key file, read through the registered migration (`CodecTest`, `FormatMigrationTest`).
- `frozenV2Fixture`: a schema 2 vault protected by password and key file, with customers with and without contact details and notes, a project with description and notes, pinned and unpinned entries (one of them in the trash), history, an expiry date, a TOTP secret and two templates. `CodecTest` checks that it decodes without migration to exactly this content.

These bytes are never regenerated. A later schema adds its own frozen vector and keeps the earlier ones readable.

**Envelope.** The header parser reads the magic and then dispatches on the envelope version; only version 1 has a branch. A future envelope would add a separate branch that parses its own layout (including its own header length, which is the AAD) into the same in-memory header, while encryption keeps writing only the newest envelope. Unknown versions are rejected before key derivation.

**Schema.** After authentication and the JSON guard, the codec reads only `schemaVersion`:

- equal to the current schema (2): decoded and validated exactly as before;
- lower, with a contiguous chain of registered `SchemaMigration(from, to)` steps: the decrypted JSON tree is transformed step by step in ascending order, then re-encoded and passed through the same guard, decoder and schema validation as a stored current document;
- higher, negative, non-numeric or without a complete chain: rejected.

Each step must set `schemaVersion` to its target and keep `id` and `revision` unchanged, otherwise the document is rejected. The production registry contains one step, from schema 1 to 2.

**Schema 1 to 2.** The step changes the decrypted tree only:

- `schemaVersion` becomes 2 and an empty `templates` list is added;
- every entry whose `tags` contain `keyrook:favorite` loses every occurrence of that tag and gets `"pinned": true`; other tags keep their order; entries without it keep no `pinned` field and decode as `false`;
- customers and projects are unchanged, so their new metadata is unset and their notes are empty;
- history, trash, secrets and every other field are copied as they are.

A schema 1 document that already contains a schema 2 field (root `templates`, entry `pinned`, customer fields other than `id` and `name`, project fields other than `id`, `name` and `customerId`) is not a valid schema 1 document and is rejected. The step is deterministic; its output then passes the same guard, decoder and validation as a stored schema 2 document, and a migrated document needs no further step.

A migrated document is held only in memory; opening never writes the file. The next ordinary save writes the current format through the unchanged atomic storage path with the next revision. Before that first replacement the session always copies the unchanged older ciphertext, after checking that it still matches the authenticated file, to `<vault file>.schema-v<old version>-r<revision>.keyrook.bak` in the vault's folder (owner-only permissions, verified by reading back; a random suffix is added if a different file already has that name, and an identical copy is reused). This copy does not match the managed backup naming pattern, so rotation and the integrity check never touch it; it can be opened by the previous release or restored like any backup. If the copy cannot be written, the save fails and the older file stays unchanged. If a session backup folder is configured, the existing pre-commit backup additionally copies the same older ciphertext into that folder, exactly as before every other save; that copy is subject to rotation.

Releases that know only schema 1 read `schemaVersion` 2 as a newer schema without a registered chain and reject the file with the generic invalid-vault error, before building any document tree and without modifying the file. JSON exports carry the same schema version: Keyrook JSON and Keyrook CSV imports migrate an older export with the same registered steps, so an export from a schema 1 release still imports, and its `keyrook:favorite` tags become pins. Entries of a current export or of any other import format (KeePass XML tags, the tag column of a mapped CSV) that carry the tag `keyrook:favorite` are pinned instead, and templates drop it.

A future incompatible change must never be silent. It requires, together: a version bump (schema or envelope), a registered migration step (or header branch) from the previous version, a frozen fixture of the previous version, and tests proving the step applies, that output validates like a fresh document, that values and secrets survive, and that newer or unbridged versions are still rejected. `SchemaV2MigrationTest` covers the production step from schema 1 with a realistic document, and `FormatMigrationTest` demonstrates the mechanism with a synthetic test-only schema 0 chained to it.

Planned extensions that would require such a change are listed in [ARCHITECTURE.md](ARCHITECTURE.md).

## Compatibility promise from 1.0.0

From release 1.0.0 on, these rules apply to every 1.x release:

- Every 1.x release reads every vault file, backup and Keyrook JSON/CSV or encrypted export written by any earlier Keyrook release, including the 0.x releases.
- The schema or envelope changes only together with a registered migration from the previous version, as described above. A migrated vault is written in the new format on its next save, and the unchanged older file is kept as `<vault file>.schema-v<old version>-r<revision>.keyrook.bak`.
- There is no forward compatibility: an older release refuses a vault or export written in a newer schema or envelope without modifying it. Downgrading after a conversion means opening the kept copy with the older release.
- `settings.json` is kept compatible on a best-effort basis only: later releases read the preferences of earlier ones, while a settings file a release cannot read (for example one written by a newer release) is ignored and default preferences apply. Preferences never affect the vault contents.
- Semantic Versioning covers the vault format and the application's behaviour. The Kotlin API of the `core` module is an internal library of the application, not a public API, and may change in any release.

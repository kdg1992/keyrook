# Keyrook file format, version 1

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

The payload is a single strict UTF-8 JSON document serialized using kotlinx.serialization. All six root fields are required, even for an empty vault:

```json
{
  "schemaVersion": 1,
  "id": "11111111-2222-4333-8444-555555555555",
  "revision": 0,
  "customers": [],
  "projects": [],
  "entries": []
}
```

IDs use canonical lowercase UUID strings. Revision is a nonnegative signed 64-bit number. Creation through `VaultStore` requires revision zero; updates must retain the vault ID and increment revision exactly once. File concurrency tokens are SHA-256 digests of the complete encrypted file, held in memory.

Customers contain `id` and `name`. Projects additionally have optional `customerId`. Entries contain `id`, `title`, `data`, `createdAt`, `modifiedAt`, optional `customerId` and `projectId`, `tags`, `notes`, optional `expiresOn` and `deletedAt`, and `history`. Timestamps parse as `java.time.Instant`; expiry dates parse as `java.time.LocalDate`. Deletion is represented by `deletedAt`; deleted entries remain encrypted in the payload. History items contain `changedAt` and a prior `data` value.

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

Limits: 10,000 customers, projects and entries each; 100 tags, history items and custom fields per entry; 262,144 UTF-16 code units per secret field or notes; 4,096 per title/name; 256 per tag/custom label. Current references must resolve, SSH references must target server entries, and customer/project assignments must agree. Historical references need only be valid UUIDs because their old targets may have been removed.

Before deserialization a guard rejects nesting beyond 32 levels, individual JSON strings above 1,572,864 encoded bytes, containers above 10,000 members, more than 500,000 structural commas/colons, malformed UTF-8, and duplicate object keys (including equivalent escaped names). The JSON decoder rejects unknown fields, types, malformed syntax and trailing documents. Schema validation follows authentication and parsing. Errors never include parser excerpts of decrypted data.

## Compatibility and migrations

Version 1 is the first supported envelope and schema, and both remain the only versions written. A frozen v1 test vector, independently encrypted using the JDK AES-GCM provider, protects compatibility alongside RFC and NIST primitive vectors. Unsupported versions fail with the generic invalid-vault error without modifying the file.

**Envelope.** The header parser reads the magic and then dispatches on the envelope version; only version 1 has a branch. A future envelope would add a separate branch that parses its own layout (including its own header length, which is the AAD) into the same in-memory header, while encryption keeps writing only the newest envelope. Unknown versions are rejected before key derivation.

**Schema.** After authentication and the JSON guard, the codec reads only `schemaVersion`:

- equal to the current schema (1): decoded and validated exactly as before;
- lower, with a contiguous chain of registered `SchemaMigration(from, to)` steps: the decrypted JSON tree is transformed step by step in ascending order, then re-encoded and passed through the same guard, decoder and schema validation as a stored current document;
- higher, negative, non-numeric or without a complete chain: rejected.

Each step must set `schemaVersion` to its target and keep `id` and `revision` unchanged, otherwise the document is rejected. The production registry contains no steps because no predecessor schema exists.

A migrated document is held only in memory; opening never writes the file. The next ordinary save writes the current format through the unchanged atomic storage path with the next revision. If a session backup folder is configured, the existing pre-commit backup copies the original, still-authenticated older ciphertext before that first replacement, exactly as before every other save. Without a configured backup the older file is replaced like any other revision.

A future incompatible change must never be silent. It requires, together: a version bump (schema or envelope), a registered migration step (or header branch) from the previous version, a frozen fixture of the previous version, and tests proving the step applies, that output validates like a fresh document, that values and secrets survive, and that newer or unbridged versions are still rejected. `FormatMigrationTest` demonstrates this with a synthetic test-only schema 0.

Planned extensions that would require such a change are listed in [ARCHITECTURE.md](ARCHITECTURE.md).

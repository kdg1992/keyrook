// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.transfer

import app.keyrook.core.crypto.Secret
import app.keyrook.core.crypto.SecretSerializer
import app.keyrook.core.format.SchemaMigrations
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.format.VaultTooLargeException
import app.keyrook.core.model.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

class InvalidImportException : IllegalArgumentException("Import is invalid or uses unsupported fields")

/** The UI must obtain these two decisions in separate confirmation steps. */
class PlaintextConsent(acknowledgedExposure: Boolean, confirmedExport: Boolean) {
    init { require(acknowledgedExposure && confirmedExport) { "Plaintext export needs two confirmations" } }
}

/**
 * Columns of a mapped CSV import. [tags] holds tags separated by commas or semicolons; the schema 1 favorite tag
 * `keyrook:favorite` in it pins the entry instead. [pinned] holds `true`/`false`, `1`/`0`, `yes`/`no`, `ja`/`nein`
 * or `x`/empty; any other value rejects the import.
 */
data class CsvMapping(val title: String, val url: String? = null, val username: String? = null,
                      val password: String? = null, val notes: String? = null, val tags: String? = null,
                      val pinned: String? = null)

/** Raised before any row is parsed when a file selected as KeePass CSV has other columns. */
class KeePassCsvHeaderException : IllegalArgumentException("CSV header does not match a KeePass CSV export")

/**
 * Fixed KeePass CSV presets. The header must contain exactly these five columns (any order), so no
 * additional exported column is silently dropped. Username and password are imported as hidden fields.
 */
enum class KeePassCsvLayout(val mapping: CsvMapping, internal val backslashEscapes: Boolean) {
    /** "KeePass CSV (1.x)" export of KeePass 2.x and 1.x: all fields quoted, quotes as `\"`, backslashes as `\\`. */
    KEEPASS_1X(CsvMapping("Account", "Web Site", "Login Name", "Password", "Comments"), true),
    /** KeePass 2.x standard field names with ordinary CSV quoting (doubled quotes). */
    KEEPASS_2X_FIELDS(CsvMapping("Title", "URL", "UserName", "Password", "Notes"), false);

    val columns: Set<String> get() = setOf(mapping.title, mapping.url!!, mapping.username!!, mapping.password!!, mapping.notes!!)
}

/** All returned models/buffers are caller-owned. Input bytes are never retained or logged. */
class VaultTransfer {
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }
    private val codec = VaultCodec()

    @Suppress("UNUSED_PARAMETER")
    fun exportJson(vault: Vault, consent: PlaintextConsent): ByteArray {
        vault.validate()
        return json.encodeToString(Vault.serializer(), vault).toByteArray(Charsets.UTF_8).also {
            if (it.size > VaultCodec.MAX_FILE_BYTES) { it.fill(0); throw InvalidImportException() }
        }
    }

    /**
     * Reads a Keyrook JSON export of the current or an older schema; older ones pass the same migration steps as an
     * opened vault file. Entries that still carry the schema 1 favorite tag are pinned instead, and templates drop it.
     */
    fun importJson(bytes: ByteArray): Vault = guarded {
        VaultCodec.checkJsonLimits(bounded(bytes))
        val tree = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val version = SchemaMigrations.schemaVersion(tree)
        val document = if (version != null && version < Vault.SCHEMA_VERSION) SchemaMigrations.PRODUCTION.migrate(tree) else tree
        SecretSerializer.trackDecoding { json.decodeFromJsonElement(Vault.serializer(), document) }.let { decoded ->
            val vault = decoded.copy(entries = decoded.entries.map(Entry::withLegacyFavorite),
                templates = decoded.templates.map { it.copy(tags = ReservedTags.visible(it.tags)) })
            try { vault.also { it.validate(); codec.requireStorable(it) } } catch (e: Exception) { vault.close(); throw e }
        }
    }

    /** Lossless Keyrook CSV: one JSON record per row, including encrypted-model metadata/history. */
    fun exportCsv(vault: Vault, consent: PlaintextConsent): ByteArray {
        val bytes = exportJson(vault, consent)
        return try {
            ("keyrook-json\r\n\"" + bytes.toString(Charsets.UTF_8).replace("\"", "\"\"") + "\"\r\n")
                .toByteArray(Charsets.UTF_8).also {
                    if (it.size > VaultCodec.MAX_FILE_BYTES) { it.fill(0); throw InvalidImportException() }
                }
        } finally { bytes.fill(0) }
    }

    fun importCsv(bytes: ByteArray, mapping: CsvMapping? = null): Vault = guarded {
        val rows = csv(bounded(bytes).toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        require(rows.isNotEmpty())
        if (rows[0] == listOf("keyrook-json")) {
            require(rows.size == 2 && rows[1].size == 1)
            val content = rows[1][0].toByteArray(Charsets.UTF_8)
            try { importJson(content) } finally { content.fill(0) }
        } else mappedCsv(rows, mapping ?: CsvMapping("Title", "URL", "UserName", "Password", "Notes"))
    }

    /** Uses the generic CSV parser with a fixed [KeePassCsvLayout]; the header is validated before any row. */
    fun importKeePassCsv(bytes: ByteArray): Vault {
        val header = csvColumns(bytes).toSet()
        val layout = KeePassCsvLayout.entries.firstOrNull { it.columns == header } ?: throw KeePassCsvHeaderException()
        return guarded {
            val text = bounded(bytes).toString(Charsets.UTF_8).removePrefix("\uFEFF")
            val rows = csv(if (layout.backslashEscapes) keePass1xEscapes(text) else text)
            require(rows.isNotEmpty() && rows[0].toSet() == layout.columns)
            mappedCsv(rows, layout.mapping)
        }
    }

    private fun mappedCsv(rows: List<List<String>>, selected: CsvMapping): Vault {
        val header = rows[0]
        validateCsvHeader(header)
        listOfNotNull(selected.title, selected.url, selected.username, selected.password, selected.notes,
            selected.tags, selected.pinned).forEach { require(it in header) }
        return buildVault { entries ->
            for (row in rows.drop(1)) {
                require(row.size == header.size)
                fun value(column: String?) = if (column == null) "" else row[header.indexOf(column)]
                val tags = value(selected.tags).split(',', ';').map(String::trim).filter(String::isNotEmpty).distinct()
                val pinned = when (value(selected.pinned).trim().lowercase(java.util.Locale.ROOT)) {
                    "true", "1", "yes", "ja", "x" -> true
                    "false", "0", "no", "nein", "" -> false
                    else -> throw InvalidImportException()
                }
                entries += web(value(selected.title), value(selected.url), value(selected.username),
                    value(selected.password), value(selected.notes)).copy(tags = tags, pinned = pinned)
            }
        }
    }

    /**
     * KeePass 1.x CSV encodes `"` as `\"` and `\` as `\\` inside quoted fields. Rewriting these escapes to
     * RFC 4180 lets the generic parser read the file; any other backslash sequence is refused.
     */
    private fun keePass1xEscapes(text: String): String {
        val output = StringBuilder(text.length)
        try {
            var index = 0
            while (index < text.length) {
                val c = text[index++]
                if (c != '\\') { output.append(c); continue }
                require(index < text.length)
                when (text[index++]) {
                    '\\' -> output.append('\\')
                    '"' -> output.append("\"\"")
                    else -> throw InvalidImportException()
                }
            }
            return output.toString()
        } finally { for (i in output.indices) output.setCharAt(i, '\u0000') }
    }

    /** Only column names are returned; data rows are not retained for the mapping dialog. */
    fun csvColumns(bytes: ByteArray): List<String> = guarded {
        val header = csv(bounded(bytes).toString(Charsets.UTF_8).removePrefix("\uFEFF"), headerOnly = true).first()
        validateCsvHeader(header)
        header.toList()
    }

    private fun validateCsvHeader(header: List<String>) {
        require(header.isNotEmpty() && header.all { it.isNotBlank() && it.length <= 512 })
        require(header.toSet().size == header.size)
    }

    /**
     * Imports unencrypted Bitwarden login/secure-note exports; rejects other types rather than dropping data. The
     * user name and addresses are visible fields, the password, TOTP secret and hidden custom fields are hidden.
     */
    fun importBitwarden(bytes: ByteArray): Vault = guarded {
        VaultCodec.checkJsonLimits(bounded(bytes))
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(root["encrypted"]?.jsonPrimitive?.booleanOrNull != true)
        val items = root.getValue("items").jsonArray
        require(items.size <= 10_000)
        val projects = linkedMapOf<String, Project>()
        root["folders"]?.takeUnless { it == JsonNull }?.jsonArray?.let { folders ->
            require(folders.size <= 10_000)
            for (folder in folders) {
                val value = folder.jsonObject
                val id = value["id"].text()
                require(id.isNotEmpty() && id !in projects)
                projects[id] = Project(UUID.randomUUID().toString(), value["name"].text())
            }
        }
        buildVault(projects.values.toList()) { entries ->
            items.forEach { element ->
                val item = element.jsonObject
                require(item["attachments"]?.let { it == JsonNull || it.jsonArray.isEmpty() } != false)
                require(item["organizationId"].text().isEmpty())
                require(item["collectionIds"]?.let { it == JsonNull || it.jsonArray.isEmpty() } != false)
                require(item["type"]?.jsonPrimitive?.int in listOf(1, 2))
                require(item["reprompt"]?.let { it == JsonNull || it.jsonPrimitive.int == 0 } != false)
                val folderId = item["folderId"].text()
                require(folderId.isEmpty() || folderId in projects)
                val login = item["login"]?.takeUnless { it == JsonNull }?.jsonObject
                val fields = linkedMapOf<String, Field>()
                val history = mutableListOf<HistoryItem>()
                fun add(name: String, value: String, hidden: Boolean = true) {
                    require(name !in fields && fields.size < 100)
                    fields[name] = field(value, hidden)
                }
                try {
                    if (login != null) {
                        require(login["fido2Credentials"]?.let { it == JsonNull || it.jsonArray.isEmpty() } != false)
                        add("username", login["username"].text(), false); add("password", login["password"].text())
                        if (login["totp"].text().isNotEmpty()) add("totp", login["totp"].text())
                        login["uris"]?.takeUnless { it == JsonNull }?.jsonArray?.forEachIndexed { index, uri ->
                            add("url${index + 1}", uri.jsonObject["uri"].text(), false)
                            uri.jsonObject["match"]?.takeUnless { it == JsonNull }?.let {
                                add("url${index + 1}-match", it.jsonPrimitive.content, false)
                            }
                        }
                    }
                    item["fields"]?.takeUnless { it == JsonNull }?.jsonArray?.forEach {
                        val custom = it.jsonObject
                        require(custom["type"]?.jsonPrimitive?.int in listOf(0, 1, 2))
                        add(custom["name"].text(), custom["value"].text(), custom["type"]?.jsonPrimitive?.int == 1)
                    }
                    val prior = item["passwordHistory"]?.takeUnless { it == JsonNull }?.jsonArray
                    require(prior == null || prior.size <= 100)
                    prior?.forEach {
                        val old = it.jsonObject
                        val changed = Instant.parse(old["lastUsedDate"].text()).toString()
                        history += HistoryItem(changed, EntryData.Custom(mapOf("password" to field(old["password"].text()))))
                    }
                    val now = Instant.now()
                    val modified = item["revisionDate"].text().takeIf { it.isNotEmpty() }?.let(Instant::parse) ?: now
                    val created = item["creationDate"].text().takeIf { it.isNotEmpty() }?.let(Instant::parse)
                        ?: (history.map { Instant.parse(it.changedAt) } + modified).min()
                    val deleted = item["deletedDate"].text().takeIf { it.isNotEmpty() }?.let { Instant.parse(it).toString() }
                    entries += Entry(UUID.randomUUID().toString(), item["name"].text(), EntryData.Custom(fields),
                        created.toString(), modified.toString(), projectId = projects[folderId]?.id,
                        notes = secret(item["notes"].text()), history = history,
                        pinned = item["favorite"]?.jsonPrimitive?.booleanOrNull == true,
                        deletedAt = deleted)
                } catch (e: Exception) {
                    fields.values.forEach { it.value.close() }
                    history.forEach { it.data.fields().forEach { field -> field.value.close() } }
                    throw e
                }
            }
        }
    }

    /**
     * KeePass 2 XML, with external entities and DTDs disabled before parsing. The standard `UserName` and `URL` strings
     * become visible fields, all other strings hidden ones.
     */
    fun importKeePassXml(bytes: ByteArray): Vault = guarded {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "32")
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(e: SAXParseException) { throw InvalidImportException() }
            override fun error(e: SAXParseException) { throw InvalidImportException() }
            override fun fatalError(e: SAXParseException) { throw InvalidImportException() }
        })
        val document = builder.parse(ByteArrayInputStream(bounded(bytes)))
        require(document.documentElement.tagName == "KeePassFile")
        require(document.getElementsByTagName("Binary").length == 0)
        val metadata = document.documentElement.children("Meta")
        require(metadata.size <= 1)
        val recycleIds = metadata.singleOrNull()?.children("RecycleBinUUID").orEmpty()
        require(recycleIds.size <= 1)
        fun uuid(value: String): String? {
            if (value.isBlank()) return null
            val decoded = Base64.getDecoder().decode(value.trim())
            require(decoded.size == 16)
            return if (decoded.all { it == 0.toByte() }) null else Base64.getEncoder().encodeToString(decoded)
        }
        val recycleId = recycleIds.singleOrNull()?.textContent?.let(::uuid)
        val groupNodes = document.getElementsByTagName("Group")
        val recycleGroups = if (recycleId == null) emptyList() else (0 until groupNodes.length)
            .map { groupNodes.item(it) as Element }.filter { group ->
                val ids = group.children("UUID")
                require(ids.size <= 1)
                ids.singleOrNull()?.textContent?.let(::uuid) == recycleId
            }
        require(recycleGroups.size <= 1)
        fun isRecycled(entry: Element): Boolean {
            val recycleGroup = recycleGroups.singleOrNull() ?: return false
            var ancestor = entry.parentNode
            while (ancestor != null) {
                if (ancestor === recycleGroup) return true
                ancestor = ancestor.parentNode
            }
            return false
        }
        val nodes = document.getElementsByTagName("Entry")
        val current = (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.parentNode.nodeName != "History" }
        require(current.size <= 10_000)
        val projects = linkedMapOf<Element, Project>()
        current.forEach { entry ->
            val group = entry.parentNode as? Element ?: throw InvalidImportException()
            require(group.tagName == "Group")
            if (group !in projects) {
                val names = mutableListOf<String>()
                var ancestor: Element? = group
                while (ancestor?.tagName == "Group") {
                    names += ancestor.children("Name").singleOrNull()?.textContent.orEmpty()
                    ancestor = ancestor.parentNode as? Element
                }
                val name = names.asReversed().filter { it.isNotEmpty() }.joinToString(" / ")
                if (name.isNotEmpty()) projects[group] = Project(UUID.randomUUID().toString(), name)
            }
        }
        buildVault(projects.values.toList()) { entries ->
            for (node in current) {
                val values = keepassStrings(node)
                val fields = linkedMapOf<String, Field>()
                val history = mutableListOf<HistoryItem>()
                try {
                    values.filterKeys { it !in listOf("Title", "Notes") }.forEach { (key, value) ->
                        fields[key] = field(value, key !in KEEPASS_VISIBLE)
                    }
                    val histories = node.children("History")
                    require(histories.size <= 1)
                    val previous = histories.singleOrNull()?.children("Entry").orEmpty()
                    require(previous.size <= 100)
                    previous.forEach { old ->
                        require(old.children("History").isEmpty())
                        val oldValues = keepassStrings(old)
                        val changed = keepassTime(old, "LastModificationTime") ?: throw InvalidImportException()
                        val oldFields = linkedMapOf<String, Field>()
                        try {
                            oldValues.forEach { (key, value) -> oldFields[key] = field(value, key !in KEEPASS_VISIBLE) }
                            history += HistoryItem(changed.toString(), EntryData.Custom(oldFields))
                        } catch (e: Exception) { oldFields.values.forEach { it.value.close() }; throw e }
                    }
                    val modified = keepassTime(node, "LastModificationTime") ?: Instant.now()
                    val created = keepassTime(node, "CreationTime")
                        ?: (history.map { Instant.parse(it.changedAt) } + modified).min()
                    val times = node.children("Times").singleOrNull()
                    val expiryFlags = times?.children("Expires").orEmpty()
                    require(expiryFlags.size <= 1)
                    val expiryFlag = expiryFlags.singleOrNull()?.textContent
                    require(expiryFlag == null || expiryFlag.equals("True", true) || expiryFlag.equals("False", true))
                    val expiry = if (expiryFlag.equals("True", true))
                        (keepassTime(node, "ExpiryTime") ?: throw InvalidImportException()).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                    else null
                    val tags = node.children("Tags").singleOrNull()?.textContent.orEmpty().split(';').filter { it.isNotEmpty() }
                    entries += Entry(UUID.randomUUID().toString(), values["Title"].orEmpty(),
                        EntryData.Custom(fields), created.toString(), modified.toString(),
                        projectId = projects[node.parentNode]?.id, tags = tags, expiresOn = expiry, history = history,
                        // KeePass identifies trash by group; it does not provide a separate deletion date.
                        deletedAt = if (isRecycled(node)) modified.toString() else null,
                        notes = secret(values["Notes"].orEmpty()))
                } catch (e: Exception) {
                    fields.values.forEach { it.value.close() }
                    history.forEach { it.data.fields().forEach { field -> field.value.close() } }
                    throw e
                }
            }
        }
    }

    private companion object {
        /** KeePass standard strings that are not secret. */
        val KEEPASS_VISIBLE = setOf("UserName", "URL")
    }

    private fun Element.children(name: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == name }

    private fun keepassTime(entry: Element, name: String): Instant? {
        val times = entry.children("Times")
        require(times.size <= 1)
        val values = times.singleOrNull()?.children(name).orEmpty()
        require(values.size <= 1)
        return values.singleOrNull()?.textContent?.let(Instant::parse)
    }

    private fun keepassStrings(entry: Element): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val strings = entry.children("String")
        require(strings.size <= 100)
        for (string in strings) {
            val key = string.children("Key").single().textContent
            val value = string.children("Value").single()
            require(!value.getAttribute("Protected").equals("True", true))
            require(values.put(key, value.textContent) == null)
        }
        require(entry.children("CustomData").all { it.children("Item").isEmpty() })
        return values
    }

    private fun buildVault(projects: List<Project> = emptyList(), populate: (MutableList<Entry>) -> Unit): Vault {
        val entries = mutableListOf<Entry>()
        try {
            populate(entries)
            return Vault(projects = projects, entries = entries.map(Entry::withLegacyFavorite))
                .also { it.validate(); codec.requireStorable(it) }
        }
        catch (e: Exception) { Vault(entries = entries).close(); throw e }
    }

    private fun web(title: String, url: String, username: String, password: String, notes: String): Entry {
        val now = Instant.now().toString()
        return Entry(UUID.randomUUID().toString(), title,
            EntryData.Web(field(url, false), field(username), field(password)), now, now, notes = secret(notes))
    }
    private fun secret(value: String): Secret {
        val chars = value.toCharArray()
        return try { Secret(chars) } finally { chars.fill('\u0000') }
    }
    private fun field(value: String, hidden: Boolean = true) = Field(secret(value), hidden)
    private fun JsonElement?.text(): String = if (this == null || this == JsonNull) "" else jsonPrimitive.content
    private fun bounded(bytes: ByteArray): ByteArray {
        require(bytes.size <= VaultCodec.MAX_FILE_BYTES)
        // Strict UTF-8; parser exceptions are deliberately replaced with a content-free error.
        val characters = CharArray(bytes.size)
        try {
            val decoder = Charsets.UTF_8.newDecoder()
            val output = java.nio.CharBuffer.wrap(characters)
            val result = decoder.decode(java.nio.ByteBuffer.wrap(bytes), output, true)
            if (result.isError) result.throwException()
            require(!result.isOverflow)
            val flushed = decoder.flush(output)
            if (flushed.isError) flushed.throwException()
            require(!flushed.isOverflow)
        } finally { characters.fill('\u0000') }
        return bytes
    }
    /** Content-free errors; an import that would parse but could never be saved reports [VaultTooLargeException]. */
    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (e: VaultTooLargeException) { throw e }
        catch (_: Exception) { throw InvalidImportException() }

    private fun csv(text: String, headerOnly: Boolean = false): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var index = 0
        fun cell() { row.add(field.toString()); field.setLength(0); closed = false; require(row.size <= 100) }
        fun line() { cell(); rows.add(row); row = mutableListOf(); require(rows.size <= 10_001) }
        while (index < text.length) {
            val c = text[index++]
            if (quoted) {
                if (c == '"') {
                    if (index < text.length && text[index] == '"') { field.append('"'); index++ }
                    else { quoted = false; closed = true }
                } else field.append(c)
            } else when (c) {
                '"' -> { require(field.isEmpty() && !closed); quoted = true }
                ',' -> cell()
                '\r', '\n' -> { if (c == '\r' && index < text.length && text[index] == '\n') index++; line() }
                else -> { require(!closed); field.append(c) }
            }
            require(field.length <= VaultCodec.MAX_FILE_BYTES)
            if (headerOnly && rows.isNotEmpty()) return rows
        }
        require(!quoted)
        if (field.isNotEmpty() || row.isNotEmpty() || closed) line()
        return rows
    }
}

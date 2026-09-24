// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.format

import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.model.ReservedTags
import app.keyrook.core.model.Vault
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One explicit step of the decrypted document schema, applied to the authenticated JSON tree before
 * it is decoded into the model. A step must set `schemaVersion` to [to] and keep `id` and `revision`.
 */
internal class SchemaMigration(val from: Int, val to: Int, private val transform: (JsonObject) -> JsonObject) {
    init { require(from >= 0 && to > from) { "Migration must advance the schema version" } }
    internal fun apply(document: JsonObject): JsonObject = transform(document)
}

/**
 * Ordered registry of schema steps ending at [current]. Older documents are migrated only in memory;
 * the file is rewritten in the current format by the next ordinary atomic save.
 */
internal class SchemaMigrations(val current: Int, steps: List<SchemaMigration>) {
    private val byFrom = steps.associateBy { it.from }

    init {
        require(current >= 0 && byFrom.size == steps.size) { "Duplicate migration source version" }
        require(steps.all { it.to <= current }) { "Migration target exceeds the current schema" }
    }

    /** Steps from [version] to [current] in order, or null for newer, negative or unbridged versions. */
    fun chain(version: Int): List<SchemaMigration>? {
        if (version < 0 || version > current) return null
        val chain = mutableListOf<SchemaMigration>()
        var next = version
        while (next < current) {
            val step = byFrom[next] ?: return null
            chain += step
            next = step.to
        }
        return chain
    }

    /** Applies the complete chain for the document's version; any gap or inconsistent step is rejected. */
    fun migrate(document: JsonObject): JsonObject {
        val steps = chain(schemaVersion(document) ?: throw InvalidVaultException()) ?: throw InvalidVaultException()
        val id = document["id"]
        val revision = document["revision"]
        return steps.fold(document) { source, step ->
            val result = step.apply(source)
            if (schemaVersion(result) != step.to || result["id"] != id || result["revision"] != revision)
                throw InvalidVaultException()
            result
        }
    }

    companion object {
        /**
         * Schema 1 to 2: entries tagged [ReservedTags.LEGACY_FAVORITE] lose the tag and become `pinned`, and an empty
         * `templates` list is added. Customer and project metadata start unset. A schema 1 document that already
         * uses a field only schema 2 defines is not a valid schema 1 document and is rejected.
         */
        internal val V1_TO_V2 = SchemaMigration(1, 2) { source ->
            fun objects(key: String): List<JsonObject> = when (val value = source[key]) {
                null -> emptyList()
                is JsonArray -> value.map { it as? JsonObject ?: throw InvalidVaultException() }
                else -> throw InvalidVaultException()
            }
            fun onlyKeys(value: JsonObject, allowed: Set<String>) {
                if (!allowed.containsAll(value.keys)) throw InvalidVaultException()
            }
            if ("templates" in source) throw InvalidVaultException()
            objects("customers").forEach { onlyKeys(it, setOf("id", "name")) }
            objects("projects").forEach { onlyKeys(it, setOf("id", "name", "customerId")) }
            val entries = objects("entries").map { entry ->
                if ("pinned" in entry) throw InvalidVaultException()
                val tags = when (val value = entry["tags"]) {
                    null -> null
                    is JsonArray -> value
                    else -> throw InvalidVaultException()
                }
                val favorite = JsonPrimitive(ReservedTags.LEGACY_FAVORITE)
                if (tags == null || favorite !in tags) entry
                else JsonObject(entry + mapOf<String, JsonElement>("tags" to JsonArray(tags.filterNot { it == favorite }),
                    "pinned" to JsonPrimitive(true)))
            }
            JsonObject(source.mapValues { (key, value) ->
                when (key) {
                    "schemaVersion" -> JsonPrimitive(2)
                    "entries" -> JsonArray(entries)
                    else -> value
                }
            } + ("templates" to JsonArray(emptyList())))
        }

        /** Every registered step up to [Vault.SCHEMA_VERSION]; schema 1 was the first format. */
        val PRODUCTION = SchemaMigrations(Vault.SCHEMA_VERSION, listOf(V1_TO_V2))

        internal fun schemaVersion(document: JsonObject): Int? {
            val value = document["schemaVersion"] as? JsonPrimitive ?: return null
            if (value.isString) return null
            return value.longOrNull?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        }
    }
}

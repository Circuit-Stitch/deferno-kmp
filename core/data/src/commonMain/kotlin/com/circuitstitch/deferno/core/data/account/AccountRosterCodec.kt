package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pure (de)serialization of an [AccountRegistry] roster to/from a single string, for persistent
 * registries (e.g. [SharedPreferencesAccountRegistry]). A JSON array of `{id,label}` that preserves
 * insertion order, built with the kotlinx.serialization runtime only — no `@Serializable` / compiler
 * plugin (the same approach the outbox uses to render bodies). Holds only non-secret metadata
 * (ADR-0002): never the token itself — `token_id` is the token's safe-to-return server id (ADR-0026),
 * present for browser-minted accounts and omitted for pasted dev ones.
 *
 * Tolerant on read (ADR-0009 cache posture): malformed input, a non-array, or an entry with a blank
 * id decodes to a clean empty / skip rather than throwing — a corrupt roster degrades to "no
 * accounts" and the caller re-authenticates rather than crashing.
 */
object AccountRosterCodec {

    /**
     * The roster plus the active selection as one document, for single-file registries (e.g. the
     * iOS `FileAccountRegistry`) whose store has no second slot for the active id the way
     * SharedPreferences does. The codec stays dumb storage: [activeId] is not validated against
     * [accounts] — the [AccountManager] coerces a dangling active id to "none" on load.
     */
    data class Document(val accounts: List<Account>, val activeId: AccountId?)

    fun encode(accounts: List<Account>): String = rosterArray(accounts).toString()

    fun decode(serialized: String?): List<Account> {
        if (serialized.isNullOrBlank()) return emptyList()
        val array: JsonArray = try {
            Json.parseToJsonElement(serialized).jsonArray
        } catch (e: SerializationException) {
            return emptyList() // not valid JSON
        } catch (e: IllegalArgumentException) {
            return emptyList() // valid JSON but not an array
        }
        return accountsOf(array)
    }

    /** Encode the roster + active selection as a `{"active": …, "roster": […]}` object ([Document]). */
    fun encodeDocument(accounts: List<Account>, activeId: AccountId?): String =
        buildJsonObject {
            activeId?.let { put("active", it.value) }
            put("roster", rosterArray(accounts))
        }.toString()

    /** Decode a [Document]; anything malformed degrades to an empty document (ADR-0009 posture). */
    fun decodeDocument(serialized: String?): Document {
        if (serialized.isNullOrBlank()) return Document(emptyList(), null)
        val obj: JsonObject = try {
            Json.parseToJsonElement(serialized) as? JsonObject ?: return Document(emptyList(), null)
        } catch (e: SerializationException) {
            return Document(emptyList(), null)
        }
        val accounts = (obj["roster"] as? JsonArray)?.let(::accountsOf).orEmpty()
        val activeId = (obj["active"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }?.let(::AccountId)
        return Document(accounts, activeId)
    }

    private fun rosterArray(accounts: List<Account>): JsonArray =
        buildJsonArray {
            accounts.forEach { account ->
                addJsonObject {
                    put("id", account.id.value)
                    put("label", account.label)
                    account.tokenId?.let { put("token_id", it) }
                }
            }
        }

    private fun accountsOf(array: JsonArray): List<Account> =
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
            val label = obj["label"]?.jsonPrimitive?.content.orEmpty()
            val tokenId = obj["token_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            if (id.isBlank()) null else Account(AccountId(id), label, tokenId)
        }
}

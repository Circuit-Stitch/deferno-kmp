package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pure (de)serialization of an [AccountRegistry] roster to/from a single string, for persistent
 * registries (e.g. [SharedPreferencesAccountRegistry]). A JSON array of `{id,label}` that preserves
 * insertion order, built with the kotlinx.serialization runtime only — no `@Serializable` / compiler
 * plugin (the same approach the outbox uses to render bodies). Holds only non-secret metadata
 * (ADR-0002): never tokens.
 *
 * Tolerant on read (ADR-0009 cache posture): malformed input, a non-array, or an entry with a blank
 * id decodes to a clean empty / skip rather than throwing — a corrupt roster degrades to "no
 * accounts" and the caller re-authenticates rather than crashing.
 */
object AccountRosterCodec {

    fun encode(accounts: List<Account>): String =
        buildJsonArray {
            accounts.forEach { account ->
                addJsonObject {
                    put("id", account.id.value)
                    put("label", account.label)
                }
            }
        }.toString()

    fun decode(serialized: String?): List<Account> {
        if (serialized.isNullOrBlank()) return emptyList()
        val array: JsonArray = try {
            Json.parseToJsonElement(serialized).jsonArray
        } catch (e: SerializationException) {
            return emptyList() // not valid JSON
        } catch (e: IllegalArgumentException) {
            return emptyList() // valid JSON but not an array
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
            val label = obj["label"]?.jsonPrimitive?.content.orEmpty()
            if (id.isBlank()) null else Account(AccountId(id), label)
        }
    }
}

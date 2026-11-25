package com.example.enginehost.demo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Loads the tenant schema from `demo/workspace/collections` JSON files.
 *
 * Collection definitions are data, not Kotlin constants: adding or changing a collection does
 * not require a source change. `index.json` is the only manifest the loader needs because Java
 * resources do not expose directory listings in every runtime (notably inside an APK).
 */
object DemoCollectionRepository {

    /** Workspace whose packaged collections are read when no id is supplied. */
    const val DEFAULT_WORKSPACE = "acme_pos"

    private fun root(workspaceId: String) = "demo/$workspaceId/collections"

    data class Loaded(
        val version: Int,
        val names: List<String>,
        val schemaPayload: JsonObject
    )

    fun load(workspaceId: String = DEFAULT_WORKSPACE): Loaded? {
        val root = root(workspaceId)
        val index = readObject("$root/index.json") ?: return null
        val files = index["collections"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        if (files.isEmpty()) return null

        val collections = files.mapNotNull { file ->
            readObject("$root/$file")
        }
        if (collections.isEmpty()) return null

        val names = collections.mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }
        val payload = buildJsonObject {
            putJsonObject("schema") {
                putJsonArray("collections") {
                    collections.forEach { add(it) }
                }
            }
        }
        return Loaded(
            version = index["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1,
            names = names,
            schemaPayload = payload
        )
    }

    private fun readObject(path: String): JsonObject? {
        val text = DemoCardRepository.rawAsset(path) ?: return null
        return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    }
}

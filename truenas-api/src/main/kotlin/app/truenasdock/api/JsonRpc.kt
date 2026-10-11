package app.truenasdock.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Shared JSON configuration. TrueNAS adds fields between releases, so unknown keys are ignored. */
val TrueNasJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

internal fun buildRequest(id: String, method: String, params: JsonArray): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }.toString()

/** A message received from the middleware, classified by JSON-RPC shape. */
internal sealed interface Incoming {
    data class Result(val id: String, val result: JsonElement) : Incoming
    data class Error(val id: String?, val error: TrueNasRpcException) : Incoming
    data class Notification(val method: String, val params: JsonElement?) : Incoming
}

internal fun parseIncoming(text: String): Incoming? {
    val obj = runCatching { TrueNasJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
    val error = obj["error"] as? JsonObject
    return when {
        error != null -> Incoming.Error(id, parseError(error))
        id != null && "result" in obj -> Incoming.Result(id, obj.getValue("result"))
        obj["method"] is JsonPrimitive -> Incoming.Notification(obj.getValue("method").jsonPrimitive.content, obj["params"])
        else -> null
    }
}

private fun parseError(error: JsonObject): TrueNasRpcException {
    val data = error["data"] as? JsonObject
    return TrueNasRpcException(
        code = error["code"]?.jsonPrimitive?.intOrNull ?: 0,
        rpcMessage = (error["message"] as? JsonPrimitive)?.contentOrNull,
        errno = (data?.get("error") as? JsonPrimitive)?.intOrNull,
        errname = (data?.get("errname") as? JsonPrimitive)?.contentOrNull,
        reason = (data?.get("reason") as? JsonPrimitive)?.contentOrNull,
        extra = (data?.get("extra") as? JsonArray) ?: JsonArray(emptyList()),
    )
}

/** `collection_update` notification payload, used for both event subscriptions and job progress. */
data class CollectionUpdate(
    val collection: String,
    /** "added", "changed", or "removed". */
    val msg: String,
    val id: JsonElement?,
    val fields: JsonObject?,
)

internal fun parseCollectionUpdate(params: JsonElement?): CollectionUpdate? {
    val obj = params as? JsonObject ?: return null
    val collection = (obj["collection"] as? JsonPrimitive)?.contentOrNull ?: return null
    val msg = (obj["msg"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["event"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        ?: "changed"
    val fields = obj["fields"] as? JsonObject
    return CollectionUpdate(collection, msg, obj["id"] ?: fields?.get("id"), fields)
}

/** Builds a JSON-RPC params array from Kotlin values already converted to [JsonElement]. */
fun params(vararg values: JsonElement): JsonArray = JsonArray(values.toList())

internal fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

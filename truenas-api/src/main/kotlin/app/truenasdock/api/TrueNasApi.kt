package app.truenasdock.api

import app.truenasdock.api.model.Alert
import app.truenasdock.api.model.Pool
import app.truenasdock.api.model.RealtimeStats
import app.truenasdock.api.model.Service
import app.truenasdock.api.model.ServiceVerb
import app.truenasdock.api.model.SystemInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * Typed wrappers over [TrueNasClient], grouped like the web UI. Anything not wrapped yet can be
 * reached with [TrueNasClient.call] / [TrueNasClient.callJob] directly.
 */
class TrueNasApi(val client: TrueNasClient) {

    // Dashboard / System

    suspend fun systemInfo(): SystemInfo = decode(client.call("system.info"))

    fun realtimeStats(intervalSeconds: Int = 2): Flow<RealtimeStats> =
        client.subscribe("reporting.realtime:{\"interval\":${intervalSeconds.coerceAtLeast(2)}}")
            .mapNotNull { update -> update.fields?.let { decode<RealtimeStats>(it) } }

    suspend fun reboot(reason: String, delaySeconds: Int? = null) {
        client.callJob("system.reboot", powerParams(reason, delaySeconds))
    }

    suspend fun shutdown(reason: String, delaySeconds: Int? = null) {
        client.callJob("system.shutdown", powerParams(reason, delaySeconds))
    }

    // Storage

    suspend fun pools(): List<Pool> = decode(client.call("pool.query"))

    // Alerts

    suspend fun alerts(): List<Alert> = decode(client.call("alert.list"))

    suspend fun dismissAlert(uuid: String) {
        client.call("alert.dismiss", params(JsonPrimitive(uuid)))
    }

    suspend fun restoreAlert(uuid: String) {
        client.call("alert.restore", params(JsonPrimitive(uuid)))
    }

    // Services

    suspend fun services(): List<Service> = decode(client.call("service.query"))

    /** Returns whether the service is running (START/RESTART/RELOAD) or stopped (STOP) afterwards. */
    suspend fun controlService(verb: ServiceVerb, service: String): Boolean {
        val options = buildJsonObject { put("silent", false) }
        val result = client.callJob("service.control", params(JsonPrimitive(verb.name), JsonPrimitive(service), options))
        return (result as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
    }

    suspend fun setServiceStartOnBoot(id: Int, enable: Boolean) {
        client.call("service.update", params(JsonPrimitive(id), buildJsonObject { put("enable", enable) }))
    }

    private fun powerParams(reason: String, delaySeconds: Int?) = params(
        JsonPrimitive(reason),
        buildJsonObject { if (delaySeconds != null) put("delay", delaySeconds) },
    )

    private inline fun <reified T> decode(element: JsonElement): T = TrueNasJson.decodeFromJsonElement(element)
}

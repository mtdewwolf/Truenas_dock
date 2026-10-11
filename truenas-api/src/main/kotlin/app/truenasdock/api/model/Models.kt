package app.truenasdock.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models keep only the fields the app uses; TrueNasJson ignores the rest.

/** `system.info` */
@Serializable
data class SystemInfo(
    val version: String = "",
    val hostname: String = "",
    val physmem: Long = 0,
    val model: String = "",
    val cores: Int = 0,
    @SerialName("physical_cores") val physicalCores: Int = 0,
    val loadavg: List<Double> = emptyList(),
    val uptime: String = "",
    @SerialName("uptime_seconds") val uptimeSeconds: Double = 0.0,
    @SerialName("system_product") val systemProduct: String? = null,
    @SerialName("system_manufacturer") val systemManufacturer: String? = null,
    @SerialName("ecc_memory") val eccMemory: Boolean = false,
    val timezone: String = "",
)

/** `pool.query` */
@Serializable
data class Pool(
    val id: Int,
    val name: String,
    val guid: String = "",
    val status: String = "",
    val path: String = "",
    val healthy: Boolean = false,
    val warning: Boolean = false,
    @SerialName("status_detail") val statusDetail: String? = null,
    val size: Long? = null,
    val allocated: Long? = null,
    val free: Long? = null,
    val fragmentation: String? = null,
    val autotrim: PoolProperty? = null,
)

@Serializable
data class PoolProperty(val value: String? = null, val parsed: String? = null)

/** `alert.list` */
@Serializable
data class Alert(
    val uuid: String,
    val klass: String = "",
    val level: String = "INFO",
    val formatted: String? = null,
    val text: String? = null,
    val dismissed: Boolean = false,
    @SerialName("one_shot") val oneShot: Boolean = false,
)

/** `service.query` */
@Serializable
data class Service(
    val id: Int,
    val service: String,
    val enable: Boolean = false,
    /** "RUNNING", "STOPPED", ... */
    val state: String = "",
    val pids: List<Int> = emptyList(),
) {
    val isRunning: Boolean get() = state == "RUNNING"
}

enum class ServiceVerb { START, STOP, RESTART, RELOAD }

/** `reporting.realtime` event fields. */
@Serializable
data class RealtimeStats(
    val cpu: Map<String, CpuStat> = emptyMap(),
    val memory: MemoryStat? = null,
    val interfaces: Map<String, InterfaceStat> = emptyMap(),
) {
    /** Aggregate CPU usage across all cores (the `cpu` key). */
    val cpuUsage: Double? get() = cpu["cpu"]?.usage
    val cpuTempMax: Double? get() = cpu.values.mapNotNull { it.temp }.maxOrNull()
}

@Serializable
data class CpuStat(val usage: Double = 0.0, val temp: Double? = null)

@Serializable
data class MemoryStat(
    @SerialName("physical_memory_total") val total: Long = 0,
    @SerialName("physical_memory_available") val available: Long = 0,
    @SerialName("arc_size") val arcSize: Long = 0,
)

@Serializable
data class InterfaceStat(
    @SerialName("link_state") val linkState: String = "",
    val speed: Double? = null,
    @SerialName("received_bytes_rate") val receivedBytesRate: Double = 0.0,
    @SerialName("sent_bytes_rate") val sentBytesRate: Double = 0.0,
)

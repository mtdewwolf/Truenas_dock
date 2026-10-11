package app.truenasdock.api

import kotlinx.serialization.json.JsonArray

/** A JSON-RPC error returned by the middleware (see "Error Response" in the API docs). */
class TrueNasRpcException(
    val code: Int,
    val rpcMessage: String?,
    val errno: Int?,
    val errname: String?,
    val reason: String?,
    val extra: JsonArray,
) : Exception(reason ?: rpcMessage ?: "JSON-RPC error $code") {
    val isTooManyConcurrentCalls: Boolean get() = code == -32000
}

/** The socket closed or failed before a pending call completed. */
class TrueNasConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server certificate is not trusted by the system and does not match the pinned fingerprint. */
class UntrustedCertificateException(
    val sha256Fingerprint: String,
    val subject: String,
    val issuer: String,
) : Exception("Untrusted certificate $subject (SHA-256 $sha256Fingerprint)")

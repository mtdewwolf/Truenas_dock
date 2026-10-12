package app.truenasdock.api

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

data class RpcRequest(val id: String, val method: String, val params: JsonArray)

/** Minimal TrueNAS middleware stand-in: TLS with a self-signed cert, scripted JSON-RPC replies. */
class FakeTrueNas(private val handler: FakeTrueNas.(RpcRequest) -> Unit) : AutoCloseable {
    val certificate: HeldCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val fingerprint: String = CertificateTrust.fingerprint(certificate.certificate)
    val requests = LinkedBlockingQueue<RpcRequest>()

    private val server = MockWebServer()
    private val closed = CountDownLatch(1)
    @Volatile private var socket: WebSocket? = null

    init {
        val handshake = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(handshake.sslSocketFactory())
        repeat(4) { server.enqueue(MockResponse.Builder().webSocketUpgrade(listener()).build()) }
        server.start()
    }

    val url: String get() = "https://localhost:${server.port}"

    fun nextUpgradePath(): String =
        server.takeRequest(5, TimeUnit.SECONDS)?.url?.encodedPath
            ?: error("Timed out waiting for WebSocket upgrade")

    fun respond(id: String, result: JsonElement) = send(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", result)
    })

    fun respondError(id: String, code: Int, reason: String, errname: String) = send(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject {
            put("code", code); put("message", "method call error")
            put("data", buildJsonObject { put("error", 22); put("errname", errname); put("reason", reason) })
        })
    })

    fun collectionUpdate(collection: String, msg: String, fields: JsonObject, id: JsonElement = fields["id"] ?: JsonNull) =
        send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "collection_update")
            put("params", buildJsonObject {
                put("collection", collection); put("msg", msg); put("id", id); put("fields", fields)
            })
        })

    fun nextRequest(method: String): RpcRequest {
        while (true) {
            val r = requests.poll(5, TimeUnit.SECONDS) ?: error("Timed out waiting for $method")
            if (r.method == method) return r
        }
    }

    private fun send(obj: JsonObject) {
        checkNotNull(socket) { "No client connected" }.send(obj.toString())
    }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = TrueNasJson.parseToJsonElement(text).jsonObject
            val request = RpcRequest(
                id = obj.getValue("id").jsonPrimitive.content,
                method = obj.getValue("method").jsonPrimitive.content,
                params = obj["params"] as? JsonArray ?: JsonArray(emptyList()),
            )
            requests += request
            handler(request)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.countDown()
        }
    }

    override fun close() {
        socket?.let {
            it.close(1000, null)
            check(closed.await(5, TimeUnit.SECONDS)) { "Timed out closing test WebSocket" }
        }
        server.close()
    }

    companion object {
        val TRUE = JsonPrimitive(true)
        fun loginSuccess() = buildJsonObject { put("response_type", "SUCCESS"); put("user_info", JsonNull) }
    }
}

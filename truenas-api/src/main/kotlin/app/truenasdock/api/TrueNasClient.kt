package app.truenasdock.api

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val cause: Throwable) : ConnectionState
}

sealed interface Credentials {
    val username: String

    data class ApiKey(override val username: String, val apiKey: String) : Credentials
    data class Password(override val username: String, val password: String) : Credentials
}

sealed interface LoginResult {
    data class Success(val userInfo: JsonObject?) : LoginResult
    data class OtpRequired(val username: String) : LoginResult
    data object AuthError : LoginResult
    data object Expired : LoginResult
    data class Redirect(val urls: List<String>) : LoginResult
}

data class JobProgress(val jobId: Long, val percent: Double?, val description: String?)

/**
 * Client for the TrueNAS 25.04+ JSON-RPC 2.0 WebSocket API (`/api/current`).
 *
 * The web UI is itself a client of this API, so every dashboard action is a [call] (or a job,
 * via [callJob]) plus [subscribe] for live data.
 */
class TrueNasClient(
    private val scope: CoroutineScope,
    private val baseHttpClient: OkHttpClient = OkHttpClient(),
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val updates = MutableSharedFlow<CollectionUpdate>(extraBufferCapacity = 512)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _jobs = MutableStateFlow<Map<Long, JsonObject>>(emptyMap())

    @Volatile private var socket: WebSocket? = null
    @Volatile private var httpClient: OkHttpClient = baseHttpClient
    @Volatile private var jobTracking: Job? = null

    /** Base `https://host[:port]/` URL of the connected server, for `/_upload` and `/_download`. */
    @Volatile var serverUrl: HttpUrl? = null
        private set

    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Live view of `core.get_jobs`, keyed by job id. Populated after a successful login. */
    val jobs: StateFlow<Map<Long, JsonObject>> = _jobs.asStateFlow()

    /** Opens the WebSocket. Throws [UntrustedCertificateException] if the server cert needs approval. */
    suspend fun connect(serverUrl: String, trust: CertificateTrust) {
        close()
        val base = normalizeServerUrl(serverUrl)
        val client = trust.applyTo(baseHttpClient.newBuilder()).build()
        val opened = CompletableDeferred<Unit>()
        httpClient = client
        this.serverUrl = base
        _state.value = ConnectionState.Connecting

        val request = Request.Builder().url(base.newBuilder().addPathSegments("api/current").build()).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = ConnectionState.Connected
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onDisconnected(webSocket, TrueNasConnectionException("Connection closed ($code) $reason"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val cause = trust.lastRejected ?: t
                opened.completeExceptionally(cause)
                onDisconnected(webSocket, cause)
            }
        })
        opened.await()
    }

    suspend fun login(credentials: Credentials): LoginResult {
        val loginData = buildJsonObject {
            when (credentials) {
                is Credentials.ApiKey -> {
                    put("mechanism", "API_KEY_PLAIN")
                    put("username", credentials.username)
                    put("api_key", credentials.apiKey)
                }
                is Credentials.Password -> {
                    put("mechanism", "PASSWORD_PLAIN")
                    put("username", credentials.username)
                    put("password", credentials.password)
                }
            }
        }
        return handleLoginResponse(call("auth.login_ex", params(loginData)))
    }

    /** Second step after [LoginResult.OtpRequired]. */
    suspend fun submitOtp(code: String): LoginResult {
        val loginData = buildJsonObject {
            put("mechanism", "OTP_TOKEN")
            put("otp_token", code)
        }
        return handleLoginResponse(call("auth.login_ex", params(loginData)))
    }

    suspend fun call(method: String, params: JsonArray = JsonArray(emptyList())): JsonElement =
        callWithId(nextId.getAndIncrement().toString(), method, params)

    /**
     * Calls a job method. The result arrives when the job finishes; meanwhile progress from the
     * `core.get_jobs` collection is reported for the job whose `message_ids` contains this call's id.
     */
    suspend fun callJob(
        method: String,
        params: JsonArray = JsonArray(emptyList()),
        onProgress: (JobProgress) -> Unit = {},
    ): JsonElement {
        val id = nextId.getAndIncrement().toString()
        val watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            jobs.collect { all ->
                val job = all.values.firstOrNull { id in it.stringList("message_ids") } ?: return@collect
                onProgress(job.toProgress())
            }
        }
        try {
            return callWithId(id, method, params)
        } finally {
            watcher.cancel()
        }
    }

    /**
     * Subscribes to a middleware event (e.g. `reporting.realtime`, `alert.list`, `pool.query`).
     * Arguments use the `name:{json}` form, e.g. `reporting.realtime:{"interval":2}`.
     */
    fun subscribe(event: String): Flow<CollectionUpdate> = channelFlow {
        // Collect before subscribing so no update sent right after the ack is missed.
        launch(start = CoroutineStart.UNDISPATCHED) {
            updates.filter { matchesEvent(event, it.collection) }.collect { send(it) }
        }
        val subscriptionId = call("core.subscribe", params(JsonPrimitive(event)))
        awaitClose {
            scope.launch { runCatching { call("core.unsubscribe", params(subscriptionId)) } }
        }
    }

    fun close() {
        jobTracking?.cancel()
        jobTracking = null
        socket?.close(1000, null)
        socket?.let { onDisconnected(it, TrueNasConnectionException("Connection closed by client")) }
        _state.value = ConnectionState.Disconnected
    }

    /** OkHttp client configured with the same certificate trust, for `/_upload` and `/_download`. */
    fun httpClient(): OkHttpClient = httpClient

    private suspend fun callWithId(id: String, method: String, params: JsonArray): JsonElement {
        val ws = socket ?: throw TrueNasConnectionException("Not connected")
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            if (!ws.send(buildRequest(id, method, params))) {
                throw TrueNasConnectionException("Failed to send $method; socket is closing")
            }
            return deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    private fun handleMessage(text: String) {
        when (val msg = parseIncoming(text)) {
            is Incoming.Result -> pending[msg.id]?.complete(msg.result)
            is Incoming.Error -> msg.id?.let { pending[it] }?.completeExceptionally(msg.error)
            is Incoming.Notification -> if (msg.method == "collection_update") {
                parseCollectionUpdate(msg.params)?.let { updates.tryEmit(it) }
            }
            null -> Unit
        }
    }

    private fun onDisconnected(webSocket: WebSocket, cause: Throwable) {
        if (socket !== webSocket) return
        socket = null
        val error = cause as? TrueNasConnectionException
            ?: cause as? UntrustedCertificateException
            ?: TrueNasConnectionException(cause.message ?: "Connection failed", cause)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        _jobs.value = emptyMap()
        _state.value = ConnectionState.Failed(error)
    }

    private fun handleLoginResponse(response: JsonElement): LoginResult {
        val obj = response as? JsonObject ?: return LoginResult.AuthError
        val result = when (obj["response_type"]?.jsonPrimitive?.contentOrNull) {
            "SUCCESS" -> LoginResult.Success(obj["user_info"] as? JsonObject)
            "OTP_REQUIRED" -> LoginResult.OtpRequired(obj["username"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "EXPIRED" -> LoginResult.Expired
            "REDIRECT" -> LoginResult.Redirect(obj.stringList("urls"))
            else -> LoginResult.AuthError
        }
        if (result is LoginResult.Success) startJobTracking()
        return result
    }

    private fun startJobTracking() {
        jobTracking?.cancel()
        jobTracking = scope.launch {
            subscribe("core.get_jobs").collect { update ->
                val id = (update.id as? JsonPrimitive)?.longOrNull ?: return@collect
                _jobs.update { current ->
                    when (update.msg) {
                        "removed" -> current - id
                        else -> current + (id to merge(current[id], update.fields))
                    }
                }
            }
        }
    }

    companion object {
        /** Accepts `nas.local`, `https://nas.local`, or `https://10.0.0.5:8443`. Plain HTTP is refused. */
        fun normalizeServerUrl(input: String): HttpUrl {
            val trimmed = input.trim().trimEnd('/')
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrl()
            // TrueNAS revokes API keys that are sent over an insecure transport.
            require(url.isHttps) { "Use https:// — TrueNAS revokes API keys sent over plain HTTP." }
            return url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        }

        internal fun matchesEvent(event: String, collection: String): Boolean =
            collection == event || collection == event.substringBefore(':')

        private fun merge(existing: JsonObject?, fields: JsonObject?): JsonObject =
            JsonObject((existing ?: JsonObject(emptyMap())) + (fields ?: JsonObject(emptyMap())))

        private fun JsonObject.toProgress(): JobProgress {
            val progress = this["progress"] as? JsonObject
            return JobProgress(
                jobId = (this["id"] as? JsonPrimitive)?.longOrNull ?: -1,
                percent = (progress?.get("percent") as? JsonPrimitive)?.doubleOrNull,
                description = (progress?.get("description") as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}

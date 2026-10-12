package app.truenasdock.api

import app.truenasdock.api.FakeTrueNas.Companion.TRUE
import app.truenasdock.api.FakeTrueNas.Companion.loginSuccess
import app.truenasdock.api.model.ServiceVerb
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TrueNasClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<FakeTrueNas>()

    @After fun tearDown() {
        scope.cancel()
        servers.forEach { it.close() }
    }

    private fun fake(handler: FakeTrueNas.(RpcRequest) -> Unit) = FakeTrueNas(handler).also { servers += it }

    /** Default handler: auth succeeds, subscriptions are acked, everything else is scripted per test. */
    private fun FakeTrueNas.defaults(r: RpcRequest): Boolean = when (r.method) {
        "auth.login_ex" -> { respond(r.id, loginSuccess()); true }
        "core.subscribe" -> { respond(r.id, JsonPrimitive("sub-${r.id}")); true }
        "core.unsubscribe" -> { respond(r.id, TRUE); true }
        else -> false
    }

    private suspend fun connected(server: FakeTrueNas): TrueNasClient =
        TrueNasClient(scope).apply { connect(server.url, CertificateTrust(server.fingerprint)) }

    @Test fun `untrusted self-signed cert surfaces its fingerprint`() = runBlocking {
        val server = fake { }
        try {
            TrueNasClient(scope).connect(server.url, CertificateTrust(pinnedSha256 = null))
            fail("expected UntrustedCertificateException")
        } catch (e: UntrustedCertificateException) {
            assertEquals(server.fingerprint, e.sha256Fingerprint)
        }
    }

    @Test fun `connects to api current and logs in with API key`() = runBlocking {
        val server = fake { defaults(it) }
        val client = connected(server)
        assertEquals(ConnectionState.Connected, client.state.value)
        assertEquals("/api/current", server.nextUpgradePath())

        val result = client.login(Credentials.ApiKey("admin", "1-secret"))
        assertTrue(result is LoginResult.Success)
        val sent = server.nextRequest("auth.login_ex").params[0].jsonObject
        assertEquals("API_KEY_PLAIN", sent["mechanism"]!!.jsonPrimitive.content)
        assertEquals("admin", sent["username"]!!.jsonPrimitive.content)
        assertEquals("1-secret", sent["api_key"]!!.jsonPrimitive.content)
        // Job tracking subscribes to core.get_jobs after login.
        assertEquals("core.get_jobs", server.nextRequest("core.subscribe").params[0].jsonPrimitive.content)
    }

    @Test fun `password login with OTP second step`() = runBlocking {
        val server = fake { r ->
            if (r.method != "auth.login_ex") { defaults(r); return@fake }
            val mechanism = r.params[0].jsonObject["mechanism"]!!.jsonPrimitive.content
            respond(r.id, if (mechanism == "PASSWORD_PLAIN") {
                buildJsonObject { put("response_type", "OTP_REQUIRED"); put("username", "admin") }
            } else loginSuccess())
        }
        val client = connected(server)
        assertEquals(LoginResult.OtpRequired("admin"), client.login(Credentials.Password("admin", "pw")))
        assertTrue(client.submitOtp("123456") is LoginResult.Success)
        server.nextRequest("auth.login_ex")
        val otp = server.nextRequest("auth.login_ex").params[0].jsonObject
        assertEquals("OTP_TOKEN", otp["mechanism"]!!.jsonPrimitive.content)
        assertEquals("123456", otp["otp_token"]!!.jsonPrimitive.content)
    }

    @Test fun `rpc errors map to TrueNasRpcException`() = runBlocking {
        val server = fake { r -> if (!defaults(r)) respondError(r.id, -32001, "[EINVAL] bad pool", "EINVAL") }
        val client = connected(server)
        try {
            client.call("pool.query")
            fail("expected error")
        } catch (e: TrueNasRpcException) {
            assertEquals(-32001, e.code)
            assertEquals("EINVAL", e.errname)
            assertEquals("[EINVAL] bad pool", e.message)
        }
    }

    @Test fun `subscribe streams updates and unsubscribes on cancel`() = runBlocking {
        val server = fake { r ->
            defaults(r)
            if (r.method == "core.subscribe") {
                collectionUpdate("reporting.realtime", "added", buildJsonObject {
                    put("cpu", buildJsonObject { put("cpu", buildJsonObject { put("usage", 12.5) }) })
                    put("memory", buildJsonObject {
                        put("physical_memory_total", 100); put("physical_memory_available", 40); put("arc_size", 10)
                    })
                }, id = JsonPrimitive(0))
            }
        }
        val api = TrueNasApi(connected(server))
        val stats = withTimeout(5_000) { api.realtimeStats(intervalSeconds = 2).first() }
        assertEquals(12.5, stats.cpuUsage!!, 0.0)
        assertEquals(40L, stats.memory!!.available)

        val sub = server.nextRequest("core.subscribe")
        assertEquals("reporting.realtime:{\"interval\":2}", sub.params[0].jsonPrimitive.content)
        val unsub = server.nextRequest("core.unsubscribe")
        assertEquals("sub-${sub.id}", unsub.params[0].jsonPrimitive.content)
    }

    @Test fun `job calls report progress from core get_jobs`() = runBlocking {
        val server = fake { r ->
            if (defaults(r)) return@fake
            if (r.method == "service.control") {
                collectionUpdate("core.get_jobs", "added", buildJsonObject {
                    put("id", 42); put("method", "service.control")
                    put("message_ids", buildJsonArray { add(JsonPrimitive(r.id)) })
                })
                collectionUpdate("core.get_jobs", "changed", buildJsonObject {
                    put("id", 42)
                    put("progress", buildJsonObject { put("percent", 50); put("description", "Starting smb") })
                })
                Thread.sleep(200)
                respond(r.id, TRUE)
            }
        }
        val client = connected(server)
        client.login(Credentials.ApiKey("admin", "k"))
        server.nextRequest("core.subscribe") // wait for job tracking to be live

        val progress = CopyOnWriteArrayList<JobProgress>()
        val running = client.callJob("service.control", params(JsonPrimitive(ServiceVerb.START.name), JsonPrimitive("cifs"))) {
            progress += it
        }
        assertEquals(TRUE, running)
        assertTrue("progress: $progress", progress.any { it.jobId == 42L && it.percent == 50.0 && it.description == "Starting smb" })
    }

    @Test fun `pending calls fail when socket closes`() = runBlocking {
        val server = fake { defaults(it) } // never answers pool.query
        val client = connected(server)
        val call = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.call("pool.query") } }
        server.nextRequest("pool.query")
        client.close()
        assertTrue(call.await().exceptionOrNull() is TrueNasConnectionException)
        assertFalse(client.state.value is ConnectionState.Connected)
    }

    @Test fun `server url normalization`() {
        assertEquals("https://nas.local/", TrueNasClient.normalizeServerUrl("nas.local").toString())
        assertEquals("https://10.0.0.5:8443/", TrueNasClient.normalizeServerUrl(" https://10.0.0.5:8443/ui/ ").toString())
        try {
            TrueNasClient.normalizeServerUrl("http://nas.local")
            fail("plain http must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `event names with arguments match their base collection`() {
        assertTrue(TrueNasClient.matchesEvent("reporting.realtime:{\"interval\":2}", "reporting.realtime"))
        assertTrue(TrueNasClient.matchesEvent("alert.list", "alert.list"))
        assertFalse(TrueNasClient.matchesEvent("alert.list", "pool.query"))
    }
}

package app.truenasdock.session

import android.content.Context
import app.truenasdock.api.CertificateTrust
import app.truenasdock.api.Credentials
import app.truenasdock.api.LoginResult
import app.truenasdock.api.TrueNasApi
import app.truenasdock.api.TrueNasClient
import app.truenasdock.data.AuthMethod
import app.truenasdock.data.ServerProfile
import app.truenasdock.data.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the [TrueNasClient] lifetime for the process: connect, login, cached API, logout.
 *
 * Must be used off the main thread for [ServerStore] access; all suspend functions
 * are safe to call from ViewModels (they switch internally where needed).
 */
class SessionManager(appContext: Context) {
    private val store = ServerStore(appContext.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var client: TrueNasClient? = null
        private set

    @Volatile var api: TrueNasApi? = null
        private set

    @Volatile var profile: ServerProfile? = null
        private set

    fun savedCredentials(): ServerStore.LoadResult = store.loadResult()

    /**
     * Connects the WebSocket. Throws [app.truenasdock.api.UntrustedCertificateException]
     * when the server certificate needs explicit user approval.
     */
    suspend fun connect(url: String, pinnedSha256: String?) {
        val trust = CertificateTrust(pinnedSha256)
        val next = TrueNasClient(scope)
        next.connect(url, trust)
        client?.close()
        client = next
        api = TrueNasApi(next)
    }

    suspend fun login(username: String, secret: String, authMethod: AuthMethod): LoginResult {
        val node = client ?: error("Not connected")
        val credentials = when (authMethod) {
            AuthMethod.API_KEY -> Credentials.ApiKey(username, secret)
            AuthMethod.PASSWORD -> Credentials.Password(username, secret)
        }
        return node.login(credentials)
    }

    suspend fun submitOtp(code: String): LoginResult {
        val node = client ?: error("Not connected")
        return node.submitOtp(code)
    }

    /** Persists the profile + secret after a successful login. */
    fun save(url: String, username: String, authMethod: AuthMethod, pinnedSha256: String?, secret: String) {
        val next = ServerProfile(url = url, username = username, authMethod = authMethod, pinnedSha256 = pinnedSha256)
        store.save(next, secret)
        profile = next
    }

    fun forget() {
        store.clear()
        profile = null
    }

    fun logout() {
        client?.close()
        client = null
        api = null
        profile = null
    }

    fun requireApi(): TrueNasApi = api ?: error("Not logged in")

    fun close() {
        logout()
        scope.cancel()
    }
}

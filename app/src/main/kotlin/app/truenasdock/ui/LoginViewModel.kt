package app.truenasdock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.truenasdock.api.LoginResult
import app.truenasdock.api.UntrustedCertificateException
import app.truenasdock.data.AuthMethod
import app.truenasdock.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoginUiState(
    val url: String = "",
    val username: String = "",
    val secret: String = "",
    val authMethod: AuthMethod = AuthMethod.API_KEY,
    val pinnedSha256: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val untrusted: UntrustedCertificateException? = null,
    val otpUsername: String? = null,
    val otpCode: String = "",
    val redirectUrls: List<String> = emptyList(),
    val loggedIn: Boolean = false,
)

class LoginViewModel(private val session: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { session.savedCredentials() }
            when (saved) {
                is app.truenasdock.data.ServerStore.LoadResult.Success -> {
                    _state.value = _state.value.copy(
                        url = saved.profile.url,
                        username = saved.profile.username,
                        authMethod = saved.profile.authMethod,
                        pinnedSha256 = saved.profile.pinnedSha256,
                        secret = saved.secret,
                        notice = "Restored saved server — tap Connect to sign in.",
                    )
                }
                is app.truenasdock.data.ServerStore.LoadResult.Corrupt ->
                    _state.value = _state.value.copy(
                        notice = "Saved credentials could not be decrypted (device key changed?). Re-enter them.",
                    )
                is app.truenasdock.data.ServerStore.LoadResult.Missing -> Unit
            }
        }
    }

    fun onUrlChange(value: String) {
        _state.value = _state.value.copy(url = value, error = null)
    }

    fun onUsernameChange(value: String) {
        _state.value = _state.value.copy(username = value, error = null)
    }

    fun onSecretChange(value: String) {
        _state.value = _state.value.copy(secret = value, error = null)
    }

    fun onAuthMethodChange(value: AuthMethod) {
        _state.value = _state.value.copy(authMethod = value, error = null)
    }

    fun onOtpCodeChange(value: String) {
        _state.value = _state.value.copy(otpCode = value, error = null)
    }

    fun dismissUntrusted() {
        _state.value = _state.value.copy(untrusted = null)
    }

    fun trustAndRetry() {
        val cert = _state.value.untrusted ?: return
        _state.value = _state.value.copy(pinnedSha256 = cert.sha256Fingerprint, untrusted = null)
        connectAndLogin()
    }

    fun connectAndLogin() {
        val snapshot = _state.value
        if (snapshot.url.isBlank() || snapshot.username.isBlank() || snapshot.secret.isBlank()) {
            _state.value = snapshot.copy(error = "Server URL, username, and API key/password are required.")
            return
        }
        _state.value = snapshot.copy(isLoading = true, error = null, redirectUrls = emptyList())
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { session.connect(snapshot.url.trim(), snapshot.pinnedSha256) }
                val result = withContext(Dispatchers.IO) {
                    session.login(snapshot.username.trim(), snapshot.secret, snapshot.authMethod)
                }
                handleLoginResult(result, snapshot)
            } catch (e: UntrustedCertificateException) {
                _state.value = _state.value.copy(isLoading = false, untrusted = e)
            } catch (e: IllegalArgumentException) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
                session.logout()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Connection failed",
                )
                session.logout()
            }
        }
    }

    fun submitOtp() {
        val code = _state.value.otpCode
        if (code.isBlank()) {
            _state.value = _state.value.copy(error = "Enter the one-time code.")
            return
        }
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val snapshot = _state.value
                val result = withContext(Dispatchers.IO) { session.submitOtp(code.trim()) }
                handleLoginResult(result, snapshot)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "OTP failed")
            }
        }
    }

    private suspend fun handleLoginResult(result: LoginResult, snapshot: LoginUiState) {
        when (result) {
            is LoginResult.Success -> {
                withContext(Dispatchers.IO) {
                    session.save(
                        snapshot.url.trim(),
                        snapshot.username.trim(),
                        snapshot.authMethod,
                        snapshot.pinnedSha256,
                        snapshot.secret,
                    )
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    otpUsername = null,
                    otpCode = "",
                    error = null,
                    loggedIn = true,
                )
            }
            is LoginResult.OtpRequired ->
                _state.value = _state.value.copy(isLoading = false, otpUsername = result.username)
            LoginResult.AuthError ->
                _state.value = _state.value.copy(isLoading = false, error = "Authentication failed — check credentials.")
            LoginResult.Expired ->
                _state.value = _state.value.copy(isLoading = false, error = "Credentials expired — create a new API key.")
            is LoginResult.Redirect ->
                _state.value = _state.value.copy(isLoading = false, redirectUrls = result.urls)
        }
    }

    companion object {
        fun factory(session: SessionManager): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LoginViewModel(session) as T
                }
            }
    }
}

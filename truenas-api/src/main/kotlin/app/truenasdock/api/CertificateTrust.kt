package app.truenasdock.api

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * TrueNAS ships with a self-signed certificate, so the system trust store alone would reject most
 * home servers. This trusts a certificate if the platform trusts it, or if its SHA-256 fingerprint
 * matches the one the user explicitly accepted (trust on first use).
 *
 * When neither applies, the rejected leaf certificate is recorded in [lastRejected] so the UI can
 * show its fingerprint and ask the user whether to trust it.
 */
class CertificateTrust(private val pinnedSha256: String?) {
    @Volatile
    var lastRejected: UntrustedCertificateException? = null
        private set

    /** Clears any previously rejected certificate. Called at the start of every `connect()`. */
    fun clearRejected() {
        lastRejected = null
    }

    private val systemTrustManager: X509TrustManager = run {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            systemTrustManager.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                systemTrustManager.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val leaf = chain.firstOrNull() ?: throw e
                if (isPinned(leaf)) return
                val rejected = UntrustedCertificateException(
                    sha256Fingerprint = fingerprint(leaf),
                    subject = leaf.subjectX500Principal.name,
                    issuer = leaf.issuerX500Principal.name,
                )
                lastRejected = rejected
                throw rejected
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
    }

    /** Self-signed certs rarely list the IP/VPN name used to reach the box; a pin match is sufficient. */
    val hostnameVerifier = HostnameVerifier { hostname, session ->
        if (defaultHostnameVerifier.verify(hostname, session)) return@HostnameVerifier true
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        leaf != null && isPinned(leaf)
    }

    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), null)
        return builder
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
    }

    private fun isPinned(cert: X509Certificate): Boolean =
        pinnedSha256 != null && normalize(pinnedSha256) == normalize(fingerprint(cert))

    companion object {
        private val defaultHostnameVerifier: HostnameVerifier =
            javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()

        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }

        private fun normalize(fp: String) = fp.replace(":", "").uppercase()
    }
}

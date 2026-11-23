package work.localize.sdk

import android.content.pm.PackageManager
import android.net.SSLCertificateSocketFactory
import android.net.http.X509TrustManagerExtensions
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HTTPS to the real CDN with the library's own transport: a handful of plain
 * `GET`s for public files of the project `example-project`.
 *
 * The README says which versions can download from it: Android 7.1.1 and
 * later. Older ones do not know the authority that signs the CDN's
 * certificate, and there the documented failure is asserted. Where a
 * download fails, the log says why, with the device's clock and the
 * certificates the server sent, so that a wrong date is not taken for a
 * missing root certificate.
 */
@RunWith(AndroidJUnit4::class)
class RealCdnDeviceTest {
    private val host = "cdn.localize.work"
    private val project = "example-project"

    /** Android 7.1.1 is the first version whose own certificates vouch for the CDN's. */
    private val trustsTheCdn = Device.sdk >= Build.VERSION_CODES.N_MR1

    /** What the README quotes of the failure on older versions. */
    private val untrusted = "Trust anchor for certification path not found"

    @Test
    fun theBuiltInTransportReadsThePointerAndAFile() {
        val transport = HttpUrlConnectionTransport(connectTimeoutMillis = 10_000, readTimeoutMillis = 15_000)
        if (!trustsTheCdn) {
            val failure = try {
                transport.get("https://$host/$project/latest.json")
                null
            } catch (refused: Exception) {
                refused
            }
            Report.line("https", "RESULT transport: ${failure?.let { "FAILED ${causes(it)}" } ?: "success"}")
            whyItFailed()
            assertTrue(
                "HTTPS to $host on API ${Device.sdk}, where the README says it fails: ${failure?.let(::causes)}",
                failure != null && causes(failure).contains("SSLHandshakeException") && causes(failure).contains(untrusted),
            )
            return
        }
        try {
            val pointer = transport.get("https://$host/$project/latest.json")
            Report.line("https", "latest.json: status ${pointer.status}, body ${pointer.body.trim().take(80)}")
            assertEquals(200, pointer.status)
            val version = (Json.parse(pointer.body) as Map<*, *>)["version"] as String
            assertTrue(version, CdnUrls.isVersion(version))

            val file = transport.get("https://$host/$project/$version/et/translation.json")
            Report.line("https", "$version/et/translation.json: status ${file.status}, ${file.body.length} characters")
            assertEquals(200, file.status)
            val tree = Json.parse(file.body) as Map<*, *>
            assertTrue("the file has no keys", tree.isNotEmpty())
            Report.line("https", "RESULT transport: success")
        } catch (failure: Exception) {
            Report.line("https", "RESULT transport: FAILED ${causes(failure)}")
            whyItFailed()
            throw AssertionError("HTTPS to $host failed on API ${Device.sdk}: ${causes(failure)}", failure)
        }
    }

    @Test
    fun aRealProjectLoadsThroughThePublicApi() {
        val cache = File(Device.context.cacheDir, "lw-real-${System.nanoTime()}")
        try {
            Device.onMainThread {
                Localize.init(
                    Device.context,
                    LocalizeConfig(project) {
                        locale("et")
                        cacheDirectory(cache)
                        refreshOnInit(false)
                    },
                )
            }
            val result = AtomicReference<RefreshResult?>()
            Localize.refresh { result.set(it) }
            assertTrue("no result within 90 s", Device.waitFor(90_000) { result.get() != null })
            val outcome = result.get()!!
            Report.line(
                "https",
                "RESULT Localize.refresh: ${outcome.status} ${outcome.version}" +
                    (outcome.error?.let { " reason=${it.reason} retryable=${it.isRetryable} ${causes(it)}" } ?: ""),
            )
            if (!trustsTheCdn) {
                // As the README has it: a network error, marked retryable, and no release.
                assertEquals(RefreshResult.Status.FAILED, outcome.status)
                assertNull(outcome.version)
                assertEquals(LocalizeException.Reason.NETWORK, outcome.error!!.reason)
                assertTrue(outcome.error!!.isRetryable)
                assertTrue(causes(outcome.error!!), causes(outcome.error!!).contains(untrusted))
                assertNull(Localize.version)
                return
            }
            if (!outcome.isSuccess) {
                throw AssertionError("Localize.refresh failed on API ${Device.sdk}: ${causes(outcome.error!!)}", outcome.error)
            }
            assertEquals(RefreshResult.Status.APPLIED, outcome.status)
            assertEquals(outcome.version, Localize.version)
            val stored = File(cache, "$project/${outcome.version}.json")
            assertTrue(stored.isFile)

            // What `init` costs the thread it is called on, with this project stored: a new launch reads the file.
            val started = SystemClock.elapsedRealtimeNanos()
            val launch = LocalizeClient(
                config = LocalizeConfig(project) {
                    locale("et")
                    cacheDirectory(cache)
                },
                cacheRoot = cache,
                deviceLocale = "et",
                transport = { throw java.io.IOException("not asked for") },
                bundled = AndroidBundledStrings(Device.context),
                worker = { task -> task.run() },
                callbacks = { task -> task.run() },
            )
            val micros = (SystemClock.elapsedRealtimeNanos() - started) / 1_000
            assertEquals(outcome.version, launch.version)
            Report.line("https", "a new launch read the stored release (${stored.length()} bytes) in $micros microseconds")
        } finally {
            cache.deleteRecursively()
        }
    }

    /** What the library needs from the app's manifest, and that it got there by itself. */
    @Test
    fun theLibrarysManifestBringsTheInternetPermission() {
        val context = Device.context
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList().orEmpty()
        Report.line("packaging", "permissions the test app asks for: $requested; targetSdk ${context.applicationInfo.targetSdkVersion}")
        assertTrue("INTERNET is not requested: $requested", "android.permission.INTERNET" in requested)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission("android.permission.INTERNET", context.packageName),
        )
    }

    private fun causes(failure: Throwable): String {
        val out = ArrayList<String>()
        var current: Throwable? = failure
        while (current != null && out.size < 6) {
            out.add("${current.javaClass.name}: ${current.message}")
            current = current.cause
        }
        return out.joinToString(" <- caused by ")
    }

    /** One TLS handshake that accepts any certificate, to see what the server sends, then the device's own verdict on it. */
    private fun whyItFailed() {
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        Report.line("https", "device clock: ${utc.format(Date())}")
        try {
            // This factory checks nothing, and can be told the host name to
            // send: a plain socket of Android 5 and 6 sends none, and the
            // server then answers with another site's certificate.
            @Suppress("DEPRECATION")
            val factory = SSLCertificateSocketFactory.getInsecure(15_000, null) as SSLCertificateSocketFactory
            val chain: Array<X509Certificate> = (factory.createSocket(host, 443) as SSLSocket).use { socket ->
                socket.soTimeout = 15_000
                @Suppress("DEPRECATION")
                factory.setHostname(socket, host)
                socket.startHandshake()
                Report.line("https", "handshake without verification: ${socket.session.protocol} ${socket.session.cipherSuite}")
                socket.session.peerCertificates.filterIsInstance<X509Certificate>().toTypedArray()
            }
            for ((index, certificate) in chain.withIndex()) {
                Report.line(
                    "https",
                    "certificate $index: subject <${certificate.subjectDN.name}> issuer <${certificate.issuerDN.name}> " +
                        "valid ${utc.format(certificate.notBefore)} to ${utc.format(certificate.notAfter)}",
                )
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(null as KeyStore?)
            val system = trust.trustManagers.filterIsInstance<X509TrustManager>().first()
            val roots = system.acceptedIssuers
            val isrg = roots.filter { it.subjectDN.name.contains("ISRG") || it.subjectDN.name.contains("DST Root") }
            Report.line("https", "system trust store: ${roots.size} roots; Let's Encrypt's: ${isrg.map { it.subjectDN.name }}")
            try {
                // The check a connection makes: for this chain and this host.
                val keyExchange = if (chain.firstOrNull()?.publicKey?.algorithm == "EC") "ECDHE_ECDSA" else "ECDHE_RSA"
                X509TrustManagerExtensions(system).checkServerTrusted(chain, keyExchange, host)
                Report.line("https", "the system trust manager accepts this chain")
            } catch (refused: Exception) {
                Report.line("https", "the system trust manager refuses this chain: ${causes(refused)}")
            }
        } catch (failure: Exception) {
            Report.line("https", "handshake without verification failed too: ${causes(failure)}")
        }
    }
}

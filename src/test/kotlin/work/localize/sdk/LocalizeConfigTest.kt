package work.localize.sdk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalizeConfigTest {
    private fun assertRejected(configure: LocalizeConfig.Builder.() -> Unit, slug: String = "demo") {
        try {
            LocalizeConfig(slug, configure)
            fail("the configuration was accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun onlyTheSlugIsRequired() {
        val config = LocalizeConfig.Builder("my-app").build()

        assertEquals("my-app", config.projectSlug)
        assertEquals("https://cdn.localize.work", config.cdnBaseUrl)
        assertEquals(listOf("translation"), config.namespaces)
        assertEquals("translation", config.defaultNamespace)
        assertNull(config.locale)
        assertNull(config.fallbackLocale)
        assertNull(config.version)
        assertEquals(UpdatePolicy.IMMEDIATE, config.updatePolicy)
        assertTrue(config.refreshOnInit)
        assertEquals(10_000, config.connectTimeoutMillis)
        assertEquals(15_000, config.readTimeoutMillis)
        assertNull(config.transport)
        assertNull(config.cacheDirectory)
    }

    @Test
    fun everySettingCanBeChosen() {
        val transport = LocalizeTransport { LocalizeResponse(404, "") }
        val directory = File("cache")
        val config = LocalizeConfig("my-app") {
            cdnBaseUrl("https://translations.example.com/cdn//")
            namespaces(listOf("common", "checkout"))
            defaultNamespace("checkout")
            locale("en_fi")
            fallbackLocale("ET")
            version("v12")
            updatePolicy(UpdatePolicy.NEXT_LAUNCH)
            refreshOnInit(false)
            connectTimeoutMillis(1_000)
            readTimeoutMillis(2_000)
            transport(transport)
            cacheDirectory(directory)
        }

        assertEquals("https://translations.example.com/cdn", config.cdnBaseUrl)
        assertEquals(listOf("common", "checkout"), config.namespaces)
        assertEquals("checkout", config.defaultNamespace)
        assertEquals("en-FI", config.locale)
        assertEquals("et", config.fallbackLocale)
        assertEquals("v12", config.version)
        assertEquals(UpdatePolicy.NEXT_LAUNCH, config.updatePolicy)
        assertEquals(false, config.refreshOnInit)
        assertEquals(1_000, config.connectTimeoutMillis)
        assertEquals(2_000, config.readTimeoutMillis)
        assertSame(transport, config.transport)
        assertSame(directory, config.cacheDirectory)
    }

    @Test
    fun theDefaultNamespaceIsTheFirstNamespace() {
        assertEquals("common", LocalizeConfig("demo") { namespaces("common", "checkout") }.defaultNamespace)
    }

    @Test
    fun latestMeansNoPinnedVersion() {
        assertNull(LocalizeConfig("demo") { version("latest") }.version)
        assertNull(LocalizeConfig("demo") { version(null) }.version)
    }

    @Test
    fun settingsThatCannotWorkAreRejected() {
        assertRejected({}, slug = "")
        assertRejected({}, slug = "  ")
        assertRejected({}, slug = ".")
        assertRejected({}, slug = "..")
        assertRejected({ cdnBaseUrl("") })
        assertRejected({ cdnBaseUrl("cdn.localize.work") })
        assertRejected({ cdnBaseUrl("ftp://cdn.localize.work") })
        assertRejected({ cdnBaseUrl("https://") })
        assertRejected({ namespaces() })
        assertRejected({ namespaces(emptyList()) })
        assertRejected({ namespaces("common", "") })
        assertRejected({ namespaces("common", "common") })
        assertRejected({ defaultNamespace("checkout") })
        assertRejected({ locale("") })
        assertRejected({ locale("en/FI") })
        assertRejected({ locale("../en") })
        assertRejected({ fallbackLocale("en FI") })
        for (version in listOf("", "12", "v0", "v01", "V1", "v1.2", "v1 ", "v", "release")) {
            assertRejected({ version(version) })
        }
        assertRejected({ connectTimeoutMillis(0) })
        assertRejected({ readTimeoutMillis(-1) })
    }
}

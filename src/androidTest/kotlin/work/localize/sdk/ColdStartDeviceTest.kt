package work.localize.sdk

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A cold start in a process of its own. One run of the tests is one process,
 * so this takes two runs with the test app left installed in between:
 *
 *     adb shell am instrument -w -e class work.localize.sdk.ColdStartDeviceTest \
 *         -e coldStartPhase store work.localize.sdk.test/androidx.test.runner.AndroidJUnitRunner
 *     adb shell am instrument -w -e class work.localize.sdk.ColdStartDeviceTest \
 *         -e coldStartPhase read work.localize.sdk.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The first downloads a release from a server on the device and stores it in
 * the default folder. The second starts with no server anywhere and reads it.
 * In an ordinary run of all tests it is skipped;
 * [WholeFlowDeviceTest.aColdStartOfflineReadsTheStoredRelease] does the same
 * inside one process.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartDeviceTest {
    private val context = Device.context
    private val note = File(context.filesDir, "cold-start-note.txt")

    private fun config(cdnBaseUrl: String) = LocalizeConfig("cold") {
        cdnBaseUrl(cdnBaseUrl)
        locale("et")
        fallbackLocale("en")
    }

    @Test
    fun aReleaseStoredByOneProcessIsReadByTheNext() {
        val phase = InstrumentationRegistry.getArguments().getString("coldStartPhase")
        assumeTrue("needs two runs with -e coldStartPhase store, then read", phase != null)
        when (phase) {
            "store" -> store()
            "read" -> read()
            else -> throw IllegalArgumentException("coldStartPhase is store or read, not $phase")
        }
    }

    private fun store() {
        File(context.noBackupFilesDir, "localize-work").deleteRecursively()
        LocalCdn().use { cdn ->
            cdn.file("cold", "v7", "et", """{ "step1": "Samm 1 (release et v7)" }""")
            cdn.file("cold", "v7", "en", """{ "step3": "Step 3 (release en v7)" }""")
            cdn.pointer("cold", "v7")
            Device.onMainThread { Localize.init(context, config(cdn.baseUrl)) }
            assertTrue("the release was not put in use", Device.waitFor { Localize.version == "v7" })
            assertEquals("Samm 1 (release et v7)", Localize.string("step1"))
            note.writeText("${cdn.baseUrl}\n${Process.myPid()}")
            Report.line("cold-start", "stored v7 in process ${Process.myPid()} from ${cdn.baseUrl}")
        }
    }

    private fun read() {
        assertTrue("run the store phase first", note.isFile)
        val (cdnBaseUrl, earlierProcess) = note.readText().lines()
        assertNotEquals("this is the process that stored the release", earlierProcess, Process.myPid().toString())
        Device.onMainThread {
            // No server is running: whatever is read here is from the device.
            Localize.init(context, config(cdnBaseUrl))
            assertEquals("v7", Localize.version)
            assertEquals("Samm 1 (release et v7)", Localize.string("step1"))
            assertEquals("Samm 2 (bundled et)", Localize.string("step2"))
            assertEquals("Step 3 (release en v7)", Localize.string("step3"))
        }
        var result: RefreshResult? = null
        Localize.refresh { result = it }
        assertTrue(Device.waitFor(60_000) { result != null })
        assertEquals(RefreshResult.Status.FAILED, result!!.status)
        assertEquals("v7", Localize.version)
        assertEquals("Samm 1 (release et v7)", Localize.string("step1"))
        Report.line("cold-start", "read v7 in process ${Process.myPid()}, stored by process $earlierProcess; refresh offline: ${result!!.status}")
        note.delete()
        File(context.noBackupFilesDir, "localize-work").deleteRecursively()
    }
}

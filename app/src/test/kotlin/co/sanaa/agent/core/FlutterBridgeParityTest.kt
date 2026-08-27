package co.sanaa.agent.core

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Every Flutter feature invocation must have a production MainActivity handler. */
class FlutterBridgeParityTest {
    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle").isFile }

    @Test
    fun `every Flutter method channel feature is connected to native production code`() {
        val root = repoRoot()
        val dart = File(root, "flutter_ui/lib/bridge/agent_channel.dart").readText()
        val native = File(root, "app/src/main/kotlin/co/sanaa/agent/MainActivity.kt").readText()
        // Generic return types may be nested (`List<Map<...>>`), so consume up
        // to the call parenthesis rather than stopping at the first `>`.
        val invoked = Regex("""invokeMethod(?:<[^\n(]+>)?\s*\(\s*['\"]([^'\"]+)['\"]""")
            .findAll(dart).map { it.groupValues[1] }.toSortedSet()
        val handled = Regex("""\"([A-Za-z][A-Za-z0-9]+)\"\s*->""")
            .findAll(native).map { it.groupValues[1] }.toSet()
        val missing = invoked - handled
        assertTrue("Flutter methods without native production handlers: $missing", missing.isEmpty())
        assertTrue("bridge inventory unexpectedly small: ${invoked.size}", invoked.size >= 40)
    }

    @Test
    fun `durable runtime initialization cannot block Flutter launch thread`() {
        val activity = File(repoRoot(), "app/src/main/kotlin/co/sanaa/agent/MainActivity.kt").readText()
        assertTrue(activity.contains("private val runtime: AgentRuntime by lazy"))
        val configure = activity.substringAfter("override fun configureFlutterEngine")
            .substringBefore("MethodChannel(flutterEngine.dartExecutor.binaryMessenger")
        assertTrue(configure.contains("CoroutineScope(Dispatchers.IO).launch"))
        assertTrue(configure.indexOf("CoroutineScope(Dispatchers.IO).launch") < configure.indexOf("val initialized = runtime"))
        assertFalse(configure.contains("val runtime = AgentRuntime.get"))

        val statusHandler = activity.substringAfter("\"autonomyStatus\" ->")
            .substringBefore("\"missionControl\" ->")
        assertFalse("two-second status polling must not acquire the durable runtime", statusHandler.contains("runtime."))
        assertTrue("chat status must expose real overlay activity", statusHandler.contains("\"active\""))

        val service = File(repoRoot(), "app/src/main/kotlin/co/sanaa/agent/services/AgentService.kt").readText()
        val onCreate = service.substringAfter("override fun onCreate()")
            .substringBefore("private suspend fun accessibilityWatchdog")
        assertTrue(onCreate.indexOf("scope.launch") < onCreate.indexOf("AgentRuntime.get"))
    }
}

package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagerCommandThreadTest {
    @Test fun restartPreservesOutcomeButSeparatesManagerAndShopAndExpiresContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("manager_command_threads", 0).edit().clear().commit()
        ManagerCommandThread(context).record("manager-a", "shop-a", "Ask Jane about delivery",
            CommandResult(false, "uncertain", "Delivery not verified"), 100)
        val restarted = ManagerCommandThread(context)
        assertTrue(restarted.context("manager-a", "shop-a", 101).contains("Delivery not verified"))
        assertEquals("", restarted.context("manager-b", "shop-a", 101))
        assertEquals("", restarted.context("manager-a", "shop-b", 101))
        assertEquals("", restarted.context("manager-a", "shop-a", 86_400_101))
        assertEquals("", restarted.context("manager-a", "shop-a", 99))
    }

    @Test fun boundsHistoryAndRedactsCredentialsInBothDirections() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("manager_command_threads", 0).edit().clear().commit()
        val thread = ManagerCommandThread(context)
        repeat(6) { thread.record("manager", "shop", "old-$it", CommandResult(true, "answered", "ok"), it.toLong()) }
        thread.record("manager", "shop", "Soko PIN is 4829",
            CommandResult(true, "answered", "Soko PIN is 7391"), 10)
        val text = thread.context("manager", "shop", 11)
        assertFalse(text.contains("old-0"))
        assertTrue(text.contains("old-5"))
        assertFalse(text.contains("4829"))
        assertFalse(text.contains("7391"))
        assertTrue(text.length <= 2400)
    }
}

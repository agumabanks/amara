package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.shorts.ShortsQueue
import co.sanaa.agent.core.shorts.ShortsMediaPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionIdentityRecoveryTest {
    private val key = "tiktok-due-1789888723000"
    private fun receipt(key: String, state: SideEffectState = SideEffectState.VERIFIED) =
        SideEffectTransaction(key, CapabilityIds.POST_TIKTOK, "tiktok", "abcdef12345678901234567890abcdef", null,
            state, 1, 2, "Proof; pin is 483920")

    @Test fun scrubPreservesTransactionIdentityAndDigestButRemovesSecretEvidence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AmaraMemory(context).let { memory ->
            assertTrue(memory.upsertSideEffectTransaction(receipt(key)))
            memory.scrubSecrets()
            val stored = memory.findSideEffectTransaction(key)!!
            assertEquals(key, stored.idempotencyKey)
            assertEquals(receipt(key).contentHash, stored.contentHash)
            assertFalse(stored.evidence.contains("483920"))
        }
    }

    @Test fun legacyVerifiedSourceResolvesWithoutRewritingOrFabricatingReceipt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AmaraMemory(context).let { memory ->
            val legacy = Redactor.redact(key)
            assertNotEquals(key, legacy)
            assertTrue(memory.upsertSideEffectTransaction(receipt(legacy)))
            ShortsQueue(context).use { queue ->
                queue.offer(key, JSONObject().put("shop_scope", "1:2").put("caption", "Exact ad"))
                assertEquals(key, queue.latestVerifiedSource(memory)!!.first)
                assertEquals(legacy, memory.findSideEffectTransaction(key)!!.idempotencyKey)
                assertEquals(1, memory.allSideEffectTransactions().size)
            }
        }
    }

    @Test fun legacyUncertainSourceIsHeldAndUploadRemainsNonReplayable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AmaraMemory(context).let { memory ->
            memory.upsertSideEffectTransaction(receipt(Redactor.redact(key), SideEffectState.UNCERTAIN))
            ShortsQueue(context).use { queue ->
                queue.offer(key, JSONObject().put("shop_scope", "1:2").put("caption", "Exact ad"))
                assertNull(queue.latestVerifiedSource(memory))
            }
            val upload=receipt(Redactor.redact("youtube:$key:@sanaa"),SideEffectState.UNCERTAIN)
                .copy(capability=CapabilityIds.POST_YOUTUBE_SHORT)
            assertEquals(upload,ShortsMediaPolicy.priorDispatch(key,listOf(upload)))
        }
    }

    @Test fun competingLegacyAndCurrentIdentitiesCannotClaimVerifiedSuccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AmaraMemory(context).let { memory ->
            // Simulate preexisting corruption bypassing the production insertion guard.
            memory.upsertSideEffectTransaction(receipt(key))
            memory.upsertSideEffectTransaction(receipt(Redactor.redact(key)))
            assertEquals(SideEffectState.UNCERTAIN,memory.findSideEffectTransaction(key)!!.state)
        }
    }
}

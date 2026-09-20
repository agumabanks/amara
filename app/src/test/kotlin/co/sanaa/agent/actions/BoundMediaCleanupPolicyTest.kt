package co.sanaa.agent.actions

import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.SideEffectState
import co.sanaa.agent.core.SideEffectTransaction
import co.sanaa.agent.core.work.WorkQueue
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoundMediaCleanupPolicyTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var memory: AmaraMemory
    private lateinit var queue: WorkQueue
    private lateinit var root: File
    private lateinit var policy: BoundMediaCleanupPolicy

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        memory = AmaraMemory(context)
        queue = WorkQueue(context)
        root = temp.newFolder()
        policy = BoundMediaCleanupPolicy(root, memory, queue)
    }

    @After fun tearDown() {
        queue.close()
        memory.close()
    }

    private fun row(key: String, status: String = "COMPLETED", payload: String = "{}", kind: String = "TIKTOK_POST_PUBLISH") {
        val arguments = arrayOf(key, kind, payload, status)
        queue.writableDatabase.execSQL("INSERT OR REPLACE INTO work_items (dedupe_key,domain,kind,payload,base_value_kes,urgency_half_life_hours,estimated_screen_seconds,created_at,status) VALUES (?, 'TIKTOK', ?, ?, 1, 1, 1, 1, ?)",
            arguments)
    }

    private fun binding(key: String, state: SideEffectState = SideEffectState.VERIFIED, status: String = "COMPLETED", bytes: ByteArray = byteArrayOf(1, 2, 3), video: Boolean = false): File {
        val caption = "Test caption $key"
        row(key, status, org.json.JSONObject().put("caption", caption).toString())
        assertTrue(memory.upsertSideEffectTransaction(SideEffectTransaction(key, CapabilityIds.POST_TIKTOK, "tiktok",
            ContentHashing.hash("$caption\nmedia-sha256:${BoundTikTokMedia.sha256(bytes)}"), null, state, 1L, 1L, "test")))
        return BoundTikTokMedia.prepare(File(root, if (video) "tiktok-bound-video" else "tiktok-bound-media"), key,
            extension = if (video) "mp4" else "jpg") { bytes }
    }

    private fun ids(preview: Map<String, Any> = policy.previewMediaCleanup()): List<String> =
        (preview.getValue("candidates") as List<*>).map { (it as Map<*, *>)["id"] as String }

    @Test fun previewDoesNotDeleteAndConfirmationPreservesBindingAndPermanentTombstone() {
        val file = binding("post")
        val preview = policy.previewMediaCleanup()
        assertEquals(3L, preview["totalReclaimableBytes"])
        assertEquals(2, (preview["stores"] as List<*>).size)
        assertTrue(file.exists())
        assertEquals(mapOf<String, Any>("reclaimedBytes" to 3L, "removedCount" to 1), policy.confirmMediaCleanup(ids(preview)))
        assertFalse(file.exists())
        val hash = BoundTikTokMedia.sha256("post".toByteArray())
        assertTrue(File(file.parentFile, "$hash.binding").exists())
        assertTrue(File(file.parentFile, "$hash.reclaimed").exists())
        BoundTikTokMedia.prepare(file.parentFile!!, "new-post") { byteArrayOf(1, 2, 3) }
        assertThrows(IllegalStateException::class.java) { BoundTikTokMedia.prepare(file.parentFile!!, "post") { error("No rebind") } }
        assertEquals(1, memory.allSideEffectTransactions().size)
        assertEquals("COMPLETED", queue.withMediaCleanupReferences { it.single().status })
    }

    @Test fun everyPreactingOrUncertainStateIsRetainedEvenWhenOwnerClosed() {
        SideEffectState.entries.filter { it !in setOf(SideEffectState.VERIFIED, SideEffectState.FAILED, SideEffectState.CANCELLED, SideEffectState.EXPIRED) }.forEachIndexed { i, state ->
            binding("state-$state", state, "OWNER_CLOSED", byteArrayOf(i.toByte()))
        }
        assertTrue(ids().isEmpty())
    }

    @Test fun activeQueueStatusesProtectSafeLedgerMedia() {
        listOf("PENDING", "IN_FLIGHT", "NEEDS_REVIEW").forEachIndexed { i, status ->
            binding("queue-$status", status = status, bytes = byteArrayOf(i.toByte()))
        }
        assertTrue(ids().isEmpty())
    }

    @Test fun safeTerminalStatesAndVideoAreEligible() {
        listOf(SideEffectState.VERIFIED, SideEffectState.FAILED, SideEffectState.CANCELLED, SideEffectState.EXPIRED).forEachIndexed { i, state ->
            binding("safe-$state", state, "OWNER_CLOSED", byteArrayOf(i.toByte()), video = i == 0)
        }
        assertEquals(4, ids().size)
    }

    @Test fun unknownBindingAndSharedDigestProtectMedia() {
        val file = binding("known")
        BoundTikTokMedia.prepare(file.parentFile!!, "unknown") { file.readBytes() }
        assertTrue(ids().isEmpty())
        assertTrue(file.exists())
    }

    @Test fun sharedSafeDigestIsCountedAndRemovedOnlyOnce() {
        binding("first")
        binding("second")
        val preview = policy.previewMediaCleanup()
        assertEquals(1, ids(preview).size)
        assertEquals(3L, preview["totalReclaimableBytes"])
        assertEquals(1, policy.confirmMediaCleanup(ids(preview) + ids(preview))["removedCount"])
    }

    @Test fun missingQueueOrLedgerAndUnboundFilesRemain() {
        val file = binding("missing-queue")
        queue.writableDatabase.delete("work_items", null, null)
        BoundTikTokMedia.prepare(file.parentFile!!, "no-ledger") { byteArrayOf(4) }
        File(file.parentFile, BoundTikTokMedia.sha256(byteArrayOf(5)) + ".jpg").writeBytes(byteArrayOf(5))
        assertTrue(ids().isEmpty())
    }

    @Test fun corruptManifestBlocksStoreAndUnknownQueueStatusFailsClosed() {
        val file = binding("post")
        val manifest = File(file.parentFile, BoundTikTokMedia.sha256("bad".toByteArray()) + ".binding")
        manifest.writeText("corrupt")
        assertTrue(ids().isEmpty())
        manifest.delete()
        queue.writableDatabase.execSQL("UPDATE work_items SET status='UNKNOWN'")
        assertThrows(IllegalStateException::class.java) { policy.previewMediaCleanup() }
        assertTrue(file.exists())
    }

    @Test fun corruptQueueAndLedgerFailClosed() {
        val file = binding("post")
        queue.writableDatabase.execSQL("UPDATE work_items SET payload='broken'")
        assertThrows(org.json.JSONException::class.java) { policy.previewMediaCleanup() }
        queue.writableDatabase.execSQL("UPDATE work_items SET payload='{}'")
        memory.writableDatabase.execSQL("UPDATE side_effect_transactions SET content_hash='broken'")
        assertTrue(ids().isEmpty())
        assertTrue(file.exists())
    }

    @Test fun legitimateBlankTargetLedgerRowsDoNotAbortPreview() {
        val file = binding("post")
        assertTrue(memory.upsertSideEffectTransaction(SideEffectTransaction("owner-login", "account_login", "",
            "", null, SideEffectState.VERIFIED, 1L, 1L, "test")))
        assertEquals(1, ids().size)
        assertTrue(file.exists())
    }

    @Test fun provenNeverDispatchedFailuresAreReclaimable() {
        val file = binding("failed-post", SideEffectState.FAILED, "COMPLETED")
        val preview = policy.previewMediaCleanup()
        assertEquals(3L, preview["totalReclaimableBytes"])
        assertEquals(mapOf<String, Any>("reclaimedBytes" to 3L, "removedCount" to 1), policy.confirmMediaCleanup(ids(preview)))
        assertFalse(file.exists())
    }

    @Test fun uncertainPublicationEvidenceStaysProtected() {
        val file = binding("uncertain-post", SideEffectState.UNCERTAIN, "COMPLETED")
        assertTrue(ids().isEmpty())
        assertTrue(file.exists())
    }

    @Test fun confirmationRejectsForgedReplayedRestartedAndStaleApprovals() {
        val file = binding("post")
        assertEquals(0, policy.confirmMediaCleanup(listOf(file.absolutePath))["removedCount"])
        val selected = ids()
        assertEquals(0, BoundMediaCleanupPolicy(root, memory, queue).confirmMediaCleanup(selected)["removedCount"])
        queue.requeue("post", 0, 1)
        assertEquals(0, policy.confirmMediaCleanup(selected)["removedCount"])
        queue.complete("post")
        assertEquals(0, policy.confirmMediaCleanup(selected)["removedCount"])
        assertTrue(file.exists())
    }

    @Test fun confirmationRechecksNewSharedBindingAndMediaIntegrity() {
        val file = binding("post")
        val selected = ids()
        BoundTikTokMedia.prepare(file.parentFile!!, "unknown") { file.readBytes() }
        assertEquals(0, policy.confirmMediaCleanup(selected)["removedCount"])
        File(file.parentFile, BoundTikTokMedia.sha256("unknown".toByteArray()) + ".binding").delete()
        val next = ids()
        file.writeBytes(byteArrayOf(9, 9, 9))
        assertEquals(0, policy.confirmMediaCleanup(next)["removedCount"])
    }

    @Test fun activeIndirectPayloadReferenceProtectsMedia() {
        binding("post")
        row("story", "PENDING", "{\"source_post_key\":\"post\"}", "TIKTOK_STORY_PUBLISH")
        assertTrue(ids().isEmpty())
    }

    @Test fun pendingAtomicFilesAndSymlinksAreNeverCandidates() {
        val file = binding("post")
        val partial = File(file.parentFile, "${file.name}.new")
        partial.writeBytes(byteArrayOf(1))
        assertTrue(ids().isEmpty())
        partial.delete()
        val outside = temp.newFile()
        java.nio.file.Files.createSymbolicLink(File(file.parentFile, "untrusted").toPath(), outside.toPath())
        assertTrue(ids().isEmpty())
    }

    @Test fun mismatchedLedgerHashAndCaptionCannotAuthorizeDeletion() {
        val file = binding("post")
        val selected = ids()
        assertEquals(1, selected.size)
        memory.writableDatabase.execSQL("UPDATE side_effect_transactions SET content_hash=?",
            arrayOf(ContentHashing.hash("other-content")))
        assertEquals(0, policy.confirmMediaCleanup(selected)["removedCount"])
        assertTrue(ids().isEmpty())
        memory.writableDatabase.execSQL("UPDATE side_effect_transactions SET content_hash=?",
            arrayOf(ContentHashing.hash("Test caption post\nmedia-sha256:${file.nameWithoutExtension}")))
        assertEquals(1, ids().size)
        queue.writableDatabase.execSQL("UPDATE work_items SET payload='{\"caption\":\"changed\"}'")
        assertTrue(ids().isEmpty())
        assertTrue(file.exists())
    }

    @Test fun terminalIndirectReferencesWithoutLedgerStayProtected() {
        val file = binding("post")
        row("unknown-reference", "OWNER_CLOSED", "{\"source_post_key\":\"post\"}")
        assertTrue(ids().isEmpty())
        queue.complete("unknown-reference")
        assertTrue(ids().isEmpty())
        assertTrue(file.exists())
    }

    @Test fun retiredPhotoBindingCannotRebindAsVideo() {
        binding("post")
        assertEquals(1, policy.confirmMediaCleanup(ids())["removedCount"])
        assertThrows(IllegalStateException::class.java) {
            BoundTikTokMedia.prepare(File(root, "tiktok-bound-video"), "post", extension = "mp4") {
                error("Retired binding must never download")
            }
        }
    }

    @Test fun prepareAndCleanupAreSerialized() {
        val file = binding("post")
        val selected = ids()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val prepare = executor.submit<File> {
                BoundTikTokMedia.prepare(file.parentFile!!, "new-sharer") {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    byteArrayOf(1, 2, 3)
                }
            }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val confirm = executor.submit<Map<String, Any>> { policy.confirmMediaCleanup(selected) }
            assertFalse(confirm.isDone)
            release.countDown()
            prepare.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(0, confirm.get(10, java.util.concurrent.TimeUnit.SECONDS)["removedCount"])
            assertTrue(file.exists())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun trustedRootAliasWorksWithoutFollowingChildLinks() {
        binding("post")
        val alias = File(temp.root, "alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), root.toPath())
        val aliased = BoundMediaCleanupPolicy(alias, memory, queue)
        assertEquals(1, ids(aliased.previewMediaCleanup()).size)
    }

    @Test fun autoReclaimFreesTerminalSafeMediaOldestFirst() {
        val older = binding("older", bytes = byteArrayOf(1, 2, 3))
        val newer = binding("newer", bytes = byteArrayOf(4, 5, 6, 7))
        older.setLastModified(1_000_000L)
        newer.setLastModified(2_000_000L)
        val store = older.parentFile!!
        assertEquals(3L, policy.autoReclaimSpace(store, 3))
        assertFalse(older.exists())
        assertTrue(newer.exists())
        assertTrue(File(store, BoundTikTokMedia.sha256("older".toByteArray()) + ".reclaimed").exists())
        val previewAfter = policy.previewMediaCleanup()
        assertEquals(1, ids(previewAfter).size)
    }

    @Test fun autoReclaimNeverTouchesUnprovenMediaOrOtherStores() {
        val uncertain = binding("uncertain-auto", SideEffectState.UNCERTAIN, "COMPLETED")
        assertEquals(0L, policy.autoReclaimSpace(uncertain.parentFile!!, 3))
        assertTrue(uncertain.exists())
        assertThrows(IllegalArgumentException::class.java) {
            policy.autoReclaimSpace(File(root, "whatsapp-bound-media"), 3)
        }
    }

    @Test fun fullStorePrepareReclaimsProvenEvidenceThenWrites() {
        // Media must exceed 64 bytes: reclaim deletes media but retains its
        // manifest and adds a 64-byte tombstone, so tiny files reclaim net-negative.
        val old = binding("old", bytes = ByteArray(100) { 7 })
        val store = old.parentFile!!
        val writes = store.listFiles()!!.sumOf { it.length() }
        assertThrows(BoundTikTokMedia.MediaStoreFullException::class.java) {
            BoundTikTokMedia.prepare(store, "fresh", storeLimitBytes = writes + 2, maxBytes = 10) { byteArrayOf(9, 9, 9) }
        }
        val prepared = BoundTikTokMedia.prepare(store, "fresh", storeLimitBytes = writes + 2, maxBytes = 10,
            reclaimSpace = { dir, needed -> policy.autoReclaimSpace(dir, needed) }) { byteArrayOf(9, 9, 9) }
        assertTrue(prepared.exists())
        assertFalse(old.exists())
    }

    @Test fun fullStoreWithoutProvenEvidenceStaysBlocked() {
        val guarded = binding("guarded", SideEffectState.UNCERTAIN, "COMPLETED", byteArrayOf(1, 2, 3))
        val store = guarded.parentFile!!
        val writes = store.listFiles()!!.sumOf { it.length() }
        assertThrows(BoundTikTokMedia.MediaStoreFullException::class.java) {
            BoundTikTokMedia.prepare(store, "fresh", storeLimitBytes = writes + 2, maxBytes = 10,
                reclaimSpace = { dir, needed -> policy.autoReclaimSpace(dir, needed) }) { byteArrayOf(9, 9, 9) }
        }
        assertTrue(guarded.exists())
    }
}

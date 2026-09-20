package co.sanaa.agent.modules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.*
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.notifications.NotificationReporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class WhatsAppReplyIdentityTest {
    @Test fun groupPermissionLookupUsesDirectoryIdentityWhileMemoryRemainsShopScoped() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val config=SecureConfig(context,useEncryptedPrefs=false).apply {
            whatsAppAutomationEnabled=true; whatsAppInboundEnabled=true; whatsAppGroupsEnabled=true
        }
        val store=ContactDirectoryStore(context)
        val directory=ContactDirectory(store)
        val entry=directory.upsert(DirectoryEntry("group-permission-id","Approved group",null,emptySet(),true,
            EntrySource.OWNER_CREATED,1L,Ambiguity.UNIQUE,Classification.UNKNOWN,CommercialConsent.UNKNOWN,
            ContactDirectoryStore.operationsForLevel(ContactPermission.REPLY),null,null))
        ContactDirectoryProvider.instance=directory
        val memory=AmaraMemory(context)
        val chats=ChatStore(context)
        var checkedId=""
        try {
            val engine=HumanConversationEngine(config,GroqClient(config),memory,chats,AccessibilityActions(context),
                SideEffectRunner(SideEffectLedger.from(memory)),NotificationReporter(context),
                shopIdentity={ TerminalShopIdentity(12,34,"Shop",Long.MAX_VALUE,"") },
                groupReplyAllowed={ id, _ -> checkedId=id;false })
            val result=engine.processMessage(entry.displayName,"Member","Do you print receipts?",true)
            assertEquals(entry.id,checkedId)
            assertTrue(result is ReplyResult.Escalate)
            assertTrue(memory.allSideEffectTransactions().isEmpty())
        } finally {
            ContactDirectoryProvider.instance=null
            chats.close();memory.close();store.close()
        }
    }
}

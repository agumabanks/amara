package co.sanaa.agent.core

import org.junit.Assert.*
import org.junit.Test

class BusinessChannelPolicyTest {
    @Test fun scopedWhatsAppCanResumeButOldDraftsAndOtherChannelsCannot() {
        for (channel in listOf(CapabilityIds.REPLY_WHATSAPP, CapabilityIds.BROADCAST_GROUP_WHATSAPP, CapabilityIds.POST_TIKTOK, CapabilityIds.POST_TIKTOK_STORY, CapabilityIds.APPLY_SOKO_EDIT)) {
            assertNull(BusinessChannelPolicy.shopBlocker(channel,mapOf("shop_scope" to "1:2"),"1:2"))
            assertNotNull(BusinessChannelPolicy.shopBlocker(channel,emptyMap(),"1:2"))
            assertNotNull(BusinessChannelPolicy.shopBlocker(channel,mapOf("shop_scope" to "1:3"),"1:2"))
        }
        assertNotNull(BusinessChannelPolicy.shopBlocker(CapabilityIds.POST_WHATSAPP_STATUS,mapOf("shop_scope" to "1:2"),"1:2"))
    }
    @Test fun managerAlertsRequireBothConfiguredDestinationAndReportProvenance() {
        assertNull(BusinessChannelPolicy.managerBlocker("+256700123456",mapOf("target" to "+256700123456","manager_report" to true)))
        assertNotNull(BusinessChannelPolicy.managerBlocker("+256700123456",mapOf("target" to "+256700123457","manager_report" to true)))
        assertNotNull(BusinessChannelPolicy.managerBlocker("+256700123456",mapOf("target" to "+256700123456")))
        assertNotNull(BusinessChannelPolicy.managerBlocker("",mapOf("target" to "","manager_report" to true)))
    }
}

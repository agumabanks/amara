package co.sanaa.agent.core

/**
 * Stable capability identifiers. Every runner call site, catalog entry, test, and UI
 * surface must reference these constants — never a free-form string literal — so the
 * static boundary check can mechanically prove no unregistered capability exists.
 */
object CapabilityIds {
    // Owner-facing and observation
    const val RESPOND = "respond"
    const val OPEN_APP = "open_app"
    const val READ_SCREEN = "read_screen"
    const val SCROLL_UP = "scroll_up"
    const val SCROLL_DOWN = "scroll_down"
    const val WAIT = "wait"
    const val ASK_OWNER = "ask_owner"
    const val UNSUPPORTED = "unsupported"

    // Soko reads
    const val SCAN_SOKO_INVENTORY = "scan_soko_inventory"
    const val SCAN_SOKO_BOOKINGS = "scan_soko_bookings"
    const val AUDIT_SOKO_SERVICES = "audit_soko_services"
    const val SCAN_SOKO_ALERTS = "scan_soko_alerts"
    const val AUDIT_SOKO_BUYER_SERVICES = "audit_soko_buyer_services"
    const val REPORT_SHOP_HEALTH = "report_shop_health"
    const val VISUAL_LISTING_AUDIT = "visual_listing_audit"
    const val READ_SOKO_DASHBOARD = "read_soko_dashboard"
    const val READ_SOKO_CUSTOMERS = "read_soko_customers"
    const val READ_SOKO_ORDERS = "read_soko_orders"
    const val READ_SOKO_REFUNDS = "read_soko_refunds"
    const val READ_SOKO_SUPPLIERS = "read_soko_suppliers"
    const val SOKO_FULL_REPORT = "soko_full_report"

    // Soko writes
    const val PROPOSE_SOKO_EDIT = "propose_soko_edit"
    const val APPLY_SOKO_EDIT = "apply_soko_edit"
    const val CANCEL_SOKO_BOOKING = "cancel_soko_booking"
    const val REMEMBER_SOKO_PIN = "remember_soko_pin"

    // WhatsApp reads
    const val LIST_WHATSAPP_CHATS = "list_whatsapp_chats"
    const val LIST_WHATSAPP_GROUPS = "list_whatsapp_groups"
    const val READ_GROUP_PARTICIPANTS = "read_group_participants"

    // WhatsApp writes
    const val SEND_WHATSAPP = "send_whatsapp"
    const val REPLY_WHATSAPP = "reply_whatsapp"
    const val FOLLOW_UP_WHATSAPP = "follow_up_whatsapp"
    const val NOTIFY_OWNER_WHATSAPP = "notify_owner_whatsapp"
    const val BROADCAST_GROUP_WHATSAPP = "broadcast_group_whatsapp"
    const val SEND_WHATSAPP_ATTACHMENT = "send_whatsapp_attachment"
    const val POST_WHATSAPP_STATUS = "post_whatsapp_status"
    const val POST_WHATSAPP_MEDIA_STATUS = "post_whatsapp_media_status"
    const val MONITOR_WHATSAPP = "monitor_whatsapp"
    const val STOP_MONITORING_WHATSAPP = "stop_monitoring_whatsapp"

    // Studio
    const val SHARE_SOKO_STUDIO_AD = "share_soko_studio_ad"
    const val SHARE_SOKO_STUDIO_CAPTION = "share_soko_studio_caption"

    // TikTok
    const val TIKTOK_PUBLIC_COMMENT = "tiktok_public_comment"
    const val POST_TIKTOK = "post_tiktok"
    const val TIKTOK_ANALYTICS = "tiktok_analytics"
    const val TIKTOK_COMMENTS = "tiktok_comments"
    const val TIKTOK_FEED = "tiktok_feed"
    const val TIKTOK_SEARCH = "tiktok_search"
    const val TIKTOK_SOUNDS = "tiktok_sounds"

    // Protected / financial
    const val DELETE = "delete"
    const val ACCOUNT_LOGIN = "account_login"
    const val ENTER_OTP = "enter_otp"
    const val PAY = "pay"
    const val REFUND = "refund"
}

/** All externally visible capability identifiers, derived from the catalog itself. */
val EXTERNAL_CAPABILITY_IDS: Set<String> =
    CapabilityCatalog.specs.values.filter { it.externalSideEffect }.map { it.id }.toSet()

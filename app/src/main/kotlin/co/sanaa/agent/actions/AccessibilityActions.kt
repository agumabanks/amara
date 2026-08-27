package co.sanaa.agent.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.Display
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import co.sanaa.agent.services.AccessibilityAgentService
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.ForegroundObservation
import co.sanaa.agent.core.ForegroundRecoveryPlanner
import co.sanaa.agent.core.HumanPacing
import co.sanaa.agent.core.InteractionKind
import co.sanaa.agent.core.ProtectedScreenClassifier
import co.sanaa.agent.core.ProtectedScreenKind
import co.sanaa.agent.core.RecoveryStep
import co.sanaa.agent.core.RequiresTransaction
import co.sanaa.agent.core.ScreenController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class WhatsAppScreenSnapshot(
    val packageName: String,
    val visibleText: List<String>,
    val signature: String,
) {
    val isWhatsApp: Boolean get() = packageName == "com.whatsapp"
    fun contains(value: String): Boolean {
        if (visibleText.any { it.contains(value, ignoreCase = true) }) return true
        val wanted = value.lowercase().filter(Char::isLetterOrDigit)
        return wanted.length >= 8 && visibleText.any {
            it.lowercase().filter(Char::isLetterOrDigit).contains(wanted)
        }
    }
}

data class WhatsAppChatContext(
    val target: String,
    val visibleLines: List<String>,
    val isGroup: Boolean,
    val deliveryState: String?,
) {
    fun asPrompt(): String = visibleLines.takeLast(60).joinToString("\n")
}

data class SokoStudioAd(
    val productName: String,
    val priceText: String,
    val productUrl: String,
    val creativeText: String,
)

data class ScreenshotEvidence(
    val supported: Boolean,
    val path: String? = null,
    val failure: String? = null,
)

class AccessibilityActions(private val context: Context, private val memory: AmaraMemory? = null) :
    WorkflowDeviceSurface {
    fun isAvailable() = AccessibilityAgentService.instance != null

    fun isScreenshotSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        AccessibilityAgentService.instance?.serviceInfo?.capabilities?.and(
            android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT,
        ) != 0

    suspend fun captureScreenshot(label: String): ScreenshotEvidence {
        val service = AccessibilityAgentService.instance
            ?: return ScreenshotEvidence(false, failure = "Accessibility is not connected.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotEvidence(false, failure = "Android ${Build.VERSION.RELEASE} does not expose Accessibility screenshot capture; owner-assisted MediaProjection is required.")
        }
        if (!isScreenshotSupported()) return ScreenshotEvidence(false, failure = "The connected Accessibility service does not have screenshot capability.")
        return captureScreenshotR(service, label)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenshotR(service: AccessibilityService, label: String): ScreenshotEvidence =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(Display.DEFAULT_DISPLAY, context.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (bitmap == null) {
                        continuation.resume(ScreenshotEvidence(true, failure = "Android returned an unreadable screenshot buffer."))
                        return
                    }
                    val safeLabel = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(50).ifBlank { "screen" }
                    val directory = File(context.filesDir, "evidence/screenshots").apply { mkdirs() }
                    val file = File(directory, "${System.currentTimeMillis()}_$safeLabel.png")
                    val saved = runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
                    bitmap.recycle()
                    continuation.resume(
                        if (saved) ScreenshotEvidence(true, file.absolutePath)
                        else ScreenshotEvidence(true, failure = "The screenshot could not be stored in the private evidence directory."),
                    )
                }

                override fun onFailure(errorCode: Int) {
                    continuation.resume(ScreenshotEvidence(true, failure = "Android screenshot capture failed with code $errorCode."))
                }
            })
        }

    fun openWhatsAppChat(phone: String, message: String): Boolean {
        if (!isAvailable()) return false
        val normalized = phone.filter(Char::isDigit).let { if (it.startsWith("0")) "256${it.drop(1)}" else it }
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$normalized?text=${Uri.encode(message)}")).apply {
                setPackage("com.whatsapp"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }

    @RequiresTransaction(reason = "owner-directed WhatsApp send")
    private suspend fun sendToWhatsAppPhone(phone: String, message: String): Boolean {
        if (!openWhatsAppChat(phone, message)) return failed("open owner WhatsApp chat")
        pause(InteractionKind.APP_LOAD)
        if (!typeAndSendInCurrentChat(message)) return failed("send owner WhatsApp message")
        return true
    }

    fun typeAndSendInCurrentChat(message: String): Boolean {
        val service = AccessibilityAgentService.instance ?: return false
        val root = service.rootInActiveWindow ?: return false
        val input = findFirst(root) { it.className == "android.widget.EditText" && it.isEditable } ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message) }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val send = findFirst(root) { node ->
            val label = "${node.contentDescription} ${node.text}".lowercase()
            node.isClickable && ("send" in label || "send message" in label)
        } ?: return false
        return send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    @RequiresTransaction(reason = "in-chat send dispatch")
    private suspend fun sendInCurrentChat(message: String): Boolean {
        val service = AccessibilityAgentService.instance ?: return false
        val root = service.rootInActiveWindow ?: return false
        val input = findFirst(root) { it.className == "android.widget.EditText" && it.isEditable } ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message) }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        pause(InteractionKind.TYPE_SETTLE)
        val refreshed = service.rootInActiveWindow ?: return false
        val send = findFirst(refreshed) { node ->
            val label = "${node.contentDescription} ${node.text}".lowercase()
            node.isClickable && (label.contains("send") || label.contains("send message"))
        } ?: return false
        val beforeSend = snapshot().signature
        if (!send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
        val changed = waitForScreenChange(beforeSend, 2_500)
        return changed || currentWindowContains(message.take(40))
    }

    override fun snapshot(): WhatsAppScreenSnapshot {
        val service = AccessibilityAgentService.instance
        val root = service?.rootInActiveWindow
        val lines = if (root == null) emptyList() else collectVisibleLabels(root).distinct()
        val packageName = root?.packageName?.toString().orEmpty()
        return WhatsAppScreenSnapshot(packageName, lines, "$packageName|${lines.joinToString("|")}".take(8_000))
    }

    fun readAllVisibleText(): List<String> = snapshot().visibleText

    suspend fun waitForScreenChange(previousSignature: String, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(150)
            if (snapshot().signature != previousSignature) return true
        }
        return false
    }

    suspend fun waitUntilContains(text: String, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (snapshot().contains(text)) return true
            delay(200)
        }
        return false
    }

    fun scrollDown(): Boolean = scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    fun scrollUp(): Boolean = scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    fun tapByPosition(x: Int, y: Int): Boolean {
        val service = AccessibilityAgentService.instance ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
    }

    fun packageNameForApp(name: String): String? = when (name.trim().lowercase()) {
        "whatsapp" -> "com.whatsapp"
        "tiktok" -> "com.zhiliaoapp.musically"
        "soko", "soko terminal", "soko seller terminal" -> SOKO_PACKAGE
        "soko buyer", "soko buyer app", "soko24", "soko24 buyer" -> SOKO_BUYER_PACKAGE
        else -> context.packageManager.getInstalledApplications(0)
            .firstOrNull { context.packageManager.getApplicationLabel(it).toString().equals(name, true) }
            ?.packageName
    }

    fun openAppByName(name: String): Boolean = packageNameForApp(name)?.let(::launchPackage) == true

    suspend fun waitForForegroundPackage(packageName: String, timeoutMs: Long = 8_000): WhatsAppScreenSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var candidate = snapshot()
        while (System.currentTimeMillis() < deadline) {
            candidate = snapshot()
            if (candidate.packageName == packageName) {
                // A package transition can complete before Flutter/native content has settled.
                pause(InteractionKind.APP_LOAD)
                return snapshot()
            }
            delay(200)
        }
        return null
    }

    suspend fun prepareSokoStudioAd(pin: String): SokoStudioAd? {
        return prepareSokoStudioAds(pin).firstOrNull()
    }

    /** Reads the visible Soko POS catalogue across scrolls without opening or editing a listing. */
    suspend fun crawlSokoInventory(pin: String, maxScrolls: Int = 30): SokoInventoryScan {
        if (!openSokoTerminal()) return SokoInventoryScan(emptyList(), 0, false, 0, "Soko Terminal could not be launched.")
        if (waitForForegroundPackage(SOKO_PACKAGE) == null) {
            return SokoInventoryScan(emptyList(), 0, false, 0, "Soko Terminal never became the foreground app.")
        }
        if (!recoverSokoHome(pin)) return SokoInventoryScan(emptyList(), 0, false, 0, sokoAccessFailure())

        val items = linkedMapOf<String, SokoInventoryItem>()
        val seenSignatures = mutableSetOf<String>()
        var duplicateCount = 0
        var screensRead = 0
        var reachedEnd = false
        val scrollLimit = maxScrolls.coerceIn(1, 30)
        var index = 0
        while (index <= scrollLimit) {
            val screen = snapshot()
            if (screen.packageName != SOKO_PACKAGE) {
                return SokoInventoryScan(items.values.toList(), screensRead, false, duplicateCount, "Another app interrupted the Soko scan.")
            }
            if (seenSignatures.add(screen.signature)) screensRead++
            val parsed = SokoInventoryParser.parse(screen.visibleText)
            for (item in parsed) {
                val key = item.name.lowercase().filter(Char::isLetterOrDigit)
                if (items.putIfAbsent(key, item) != null) duplicateCount++
            }
            if (index >= scrollLimit) break
            val before = screen.signature
            val accepted = scrollDown()
            if (!accepted) {
                reachedEnd = true
                break
            }
            pause(InteractionKind.SCROLL_SETTLE)
            val after = snapshot()
            if (after.signature == before || after.signature in seenSignatures) {
                reachedEnd = true
                break
            }
            index++
        }

        for (item in items.values) {
            memory?.recordProductSeen(
                "Soko Terminal screen",
                item.name,
                item.priceUgx,
                "${item.stockState}. Observed during a ${screensRead}-screen read-only inventory scan.",
                if (item.weakReasons.isEmpty()) 1.0 else 0.5,
            )
        }
        return SokoInventoryScan(items.values.toList(), screensRead, reachedEnd, duplicateCount)
    }

    suspend fun readSokoNeedsActionBookings(pin: String): SokoBookingsScan {
        if (!openSokoTerminal()) return SokoBookingsScan(emptyList(), "Soko Terminal could not be launched.")
        if (waitForForegroundPackage(SOKO_PACKAGE) == null || !recoverSokoHome(pin)) {
            return SokoBookingsScan(emptyList(), sokoAccessFailure())
        }
        if (!clickAndLearn(SOKO_PACKAGE, "open_alerts", "Alerts")) {
            return SokoBookingsScan(emptyList(), "The Terminal Alerts inbox could not be opened.")
        }
        if (!waitUntilContains("Inbox", 6_000) || !clickAndLearn(SOKO_PACKAGE, "filter_bookings", "Bookings")) {
            return SokoBookingsScan(emptyList(), "The Bookings filter could not be opened.")
        }
        // The filter changes immediately but its network-backed cards arrive
        // afterward. Give the list one human-paced settle window before reading.
        pause(InteractionKind.NETWORK_CONTENT)
        val found = linkedMapOf<String, SokoBooking>()
        val signatures = mutableSetOf<String>()
        var screenCount = 0
        while (screenCount < 9) {
            val screen = snapshot()
            if (!signatures.add(screen.signature)) break
            for (booking in SokoBookingParser.parse(screen.visibleText)) {
                val key = "${booking.service}|${booking.customer}".lowercase()
                found.putIfAbsent(key, booking)
            }
            val before = screen.signature
            if (!scrollDown()) break
            delay(500)
            if (snapshot().signature == before) break
            screenCount++
        }
        return SokoBookingsScan(found.values.toList())
    }

    suspend fun auditSokoServices(pin: String, maxScrolls: Int = 50): SokoServicesScan {
        if (!openSokoTerminal()) return SokoServicesScan(emptyList(), null, 0, false, "Soko Terminal could not be launched.")
        if (waitForForegroundPackage(SOKO_PACKAGE) == null || !recoverSokoHome(pin)) {
            return SokoServicesScan(emptyList(), null, 0, false, sokoAccessFailure())
        }
        if (!clickAndLearn(SOKO_PACKAGE, "open_more", "More") || !scrollUntil("Services", 10) ||
            !clickAndLearn(SOKO_PACKAGE, "open_services", "Services") || !waitUntilContains("services", 8_000)) {
            return SokoServicesScan(emptyList(), null, 0, false, "The Terminal Services manager could not be opened.")
        }
        val listings = linkedMapOf<String, SokoServiceListing>()
        val signatures = mutableSetOf<String>()
        var advertisedTotal: Int? = null
        var reachedEnd = false
        val limit = maxScrolls.coerceIn(1, 60)
        var index = 0
        while (index <= limit) {
            val screen = snapshot()
            if (screen.packageName != SOKO_PACKAGE) {
                return SokoServicesScan(listings.values.toList(), advertisedTotal, signatures.size, false, "Another app interrupted the service audit.")
            }
            signatures.add(screen.signature)
            advertisedTotal = advertisedTotal ?: screen.visibleText.firstNotNullOfOrNull { label ->
                Regex("(?i)^(\\d+) services$").find(label.trim())?.groupValues?.get(1)?.toIntOrNull()
            }
            for (item in SokoServiceParser.parse(screen.visibleText)) {
                listings.putIfAbsent(item.name.lowercase().filter(Char::isLetterOrDigit), item)
            }
            if (index >= limit) break
            val before = screen.signature
            if (!scrollDown()) { reachedEnd = true; break }
            pause(InteractionKind.SCROLL_SETTLE)
            val after = snapshot()
            if (after.signature == before || after.signature in signatures) { reachedEnd = true; break }
            index++
        }
        return SokoServicesScan(listings.values.toList(), advertisedTotal, signatures.size, reachedEnd)
    }

    suspend fun readSokoNeedsActionAlerts(pin: String): SokoAlertsScan {
        if (!openSokoTerminal()) return SokoAlertsScan(emptyList(), "Soko Terminal could not be launched.")
        if (waitForForegroundPackage(SOKO_PACKAGE) == null || !recoverSokoHome(pin)) {
            return SokoAlertsScan(emptyList(), sokoAccessFailure())
        }
        if (!clickAndLearn(SOKO_PACKAGE, "open_alerts", "Alerts") || !waitUntilContains("Inbox", 6_000)) {
            return SokoAlertsScan(emptyList(), "The Terminal Alerts inbox could not be opened.")
        }
        pause(InteractionKind.NETWORK_CONTENT)
        val found = linkedMapOf<String, SokoAlert>()
        val signatures = mutableSetOf<String>()
        repeat(10) {
            val screen = snapshot()
            if (!signatures.add(screen.signature)) return@repeat
            for (alert in SokoAlertParser.parse(screen.visibleText)) {
                found.putIfAbsent("${alert.type}|${alert.subject}|${alert.detail}".lowercase(), alert)
            }
            val before = screen.signature
            if (!scrollDown()) return@repeat
            delay(500)
            if (snapshot().signature == before) return@repeat
        }
        return SokoAlertsScan(found.values.toList())
    }

    suspend fun auditSokoBuyerServices(maxScrolls: Int = 50): SokoBuyerServicesScan {
        if (!launchPackage(SOKO_BUYER_PACKAGE)) return SokoBuyerServicesScan(emptyList(), null, 0, false, "Soko Buyer could not be launched.")
        if (waitForForegroundPackage(SOKO_BUYER_PACKAGE) == null) {
            return SokoBuyerServicesScan(emptyList(), null, 0, false, "Soko Buyer never became the foreground app.")
        }
        if (!snapshot().contains("results")) {
            if (!snapshot().contains("Services") || !clickAndLearn(SOKO_BUYER_PACKAGE, "open_services", "Services") ||
                !waitUntilContains("results", 8_000)) {
                return SokoBuyerServicesScan(emptyList(), null, 0, false, "The Buyer Services screen could not be opened.")
            }
        }
        pause(InteractionKind.NETWORK_CONTENT)
        val services = linkedMapOf<String, SokoBuyerService>()
        val signatures = mutableSetOf<String>()
        var advertisedTotal: Int? = null
        var reachedEnd = false
        var index = 0
        val limit = maxScrolls.coerceIn(1, 60)
        while (index <= limit) {
            val screen = snapshot()
            if (screen.packageName != SOKO_BUYER_PACKAGE) {
                return SokoBuyerServicesScan(services.values.toList(), advertisedTotal, signatures.size, false, "Another app interrupted the Buyer audit.")
            }
            signatures.add(screen.signature)
            advertisedTotal = advertisedTotal ?: screen.visibleText.firstNotNullOfOrNull {
                Regex("(?i)^(\\d+) results$").find(it.trim())?.groupValues?.get(1)?.toIntOrNull()
            }
            for (service in SokoBuyerServiceParser.parse(screen.visibleText)) {
                services.putIfAbsent("${service.seller}|${service.name}".lowercase().filter(Char::isLetterOrDigit), service)
            }
            if (index >= limit) break
            val before = screen.signature
            if (!scrollDown()) { reachedEnd = true; break }
            pause(InteractionKind.SCROLL_SETTLE)
            val after = snapshot()
            if (after.signature == before || after.signature in signatures) { reachedEnd = true; break }
            index++
        }
        return SokoBuyerServicesScan(services.values.toList(), advertisedTotal, signatures.size, reachedEnd)
    }

    suspend fun prepareSokoStudioAds(pin: String): List<SokoStudioAd> {
        if (!openSokoTerminal()) { Log.w(TAG, "Soko Studio: launch failed"); return emptyList() }
        pause(InteractionKind.APP_LOAD)
        if (!recoverSokoHome(pin)) { Log.w(TAG, "Soko Studio: home recovery failed; screen=${snapshot().visibleText.take(8)}"); return emptyList() }
        Log.i(TAG, "Soko Studio: recovered POS home")
        if (!clickAndLearn(SOKO_PACKAGE, "open_more", "More")) { Log.w(TAG, "Soko Studio: More transition failed"); return emptyList() }
        delay(500)
        if (!scrollUntil("Soko Studio", 5)) { Log.w(TAG, "Soko Studio: Studio was not found while scrolling"); return emptyList() }
        if (!clickAndLearn(SOKO_PACKAGE, "open_studio", "Soko Studio")) { Log.w(TAG, "Soko Studio: Studio transition failed"); return emptyList() }
        if (!waitUntilContains("SOKO STUDIO", 8_000)) { Log.w(TAG, "Soko Studio: title verification failed"); return emptyList() }
        return parseStudioAds().also { Log.i(TAG, "Soko Studio: parsed ${it.size} ads=$it") }
    }

    suspend fun sharePreparedSokoAdToWhatsApp(target: String): Boolean {
        if (!snapshot().contains("SOKO STUDIO")) return false
        if (!clickAndLearn(SOKO_PACKAGE, "open_ad_share", "Share now")) return false
        if (!waitUntilContains("Ready to Share", 10_000)) { Log.w(TAG, "Soko Studio: Ready to Share timed out"); return false }
        if (!clickAndLearn(SOKO_PACKAGE, "export_whatsapp", "Send on WhatsApp")) return false
        delay(1_300)
        if (!clickAndLearn("android", "choose_whatsapp", "WhatsApp")) return false
        delay(1_200)
        if (!setFirstEditable(target)) {
            clickExactLabel("Search") || clickLabel("Search")
            delay(300)
            if (!setFirstEditable(target)) return false
        }
        delay(700)
        if (!clickSearchResult(target)) return false
        delay(500)
        if (!clickLabel("Send", "Next")) return false
        delay(1_200)
        val screen = snapshot()
        return screen.isWhatsApp && screen.contains(target) && (screen.contains("Enlarge photo") || deliveryState(screen.visibleText) != null)
    }

    fun currentWindowContains(text: String): Boolean {
        return snapshot().contains(text)
    }

    /**
     * Node-role facts for one outgoing message: where does the normalized content live?
     * Editability and node class decide draft-vs-bubble — text alone cannot (Phase A3 fix).
     */
    override fun observeMessageNodes(content: String): MessageNodeFacts {
        val service = AccessibilityAgentService.instance ?: return MessageNodeFacts(false, false, null)
        val root = service.rootInActiveWindow ?: return MessageNodeFacts(false, false, null)
        var inEditable = false
        var inReadOnly = false
        walkNodes(root) { node ->
            val nodeText = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            if (co.sanaa.agent.core.ContentHashing.normalize(nodeText).contains(co.sanaa.agent.core.ContentHashing.normalize(content))) {
                val editable = node.isEditable || node.className?.toString()?.endsWith("EditText") == true
                if (editable) inEditable = true else inReadOnly = true
            }
        }
        return MessageNodeFacts(
            inReadOnlyBubble = inReadOnly,
            onlyInsideEditableField = inEditable && !inReadOnly,
            deliveryState = messageDeliveryState(content.take(40)),
        )
    }

    private fun walkNodes(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 40) return
        visit(node)
        for (index in 0 until node.childCount) {
            try {
                walkNodes(node.getChild(index), depth + 1, visit)
            } catch (_: Exception) {
                // A node can disappear mid-traversal; skip it rather than fail observation.
            }
        }
    }

    fun openSokoTerminal(): Boolean = launchPackage("com.soko24.soko_seller_terminal") || launchPackage("co.soko24.terminal") || launchPackage("com.soko24.seller")
    fun openTikTok(): Boolean = launchPackage("com.zhiliaoapp.musically")

    @RequiresTransaction(reason = "public Status publish")
    private suspend fun postWhatsAppTextStatus(message: String): Boolean {
        if (!launchPackage("com.whatsapp")) return false
        pause(InteractionKind.APP_LOAD)
        if (!ensureWhatsAppHome()) return false
        if (!clickExactLabel("Updates")) return false
        delay(1_200)
        if (!clickLabel("Add status", "My status", "Text")) return false
        delay(1_000)
        return sendInCurrentChat(message)
    }

    @RequiresTransaction(reason = "public media Status publish")
    private suspend fun postWhatsAppMediaStatus(uri: Uri, mimeType: String, caption: String = ""): Boolean {
        if (!isAvailable()) return false
        val started = runCatching {
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            true
        }.getOrDefault(false)
        if (!started) return false
        delay(1_200)
        if (!clickLabel("My status", "Status")) return false
        delay(500)
        clickLabel("Next")
        delay(500)
        if (caption.isNotBlank()) setFirstEditable(caption)
        return clickLabel("Send")
    }

    @RequiresTransaction(reason = "group broadcast send")
    private suspend fun sendToWhatsAppGroup(group: String, message: String): Boolean {
        return sendToWhatsAppContact(group, message)
    }

    @RequiresTransaction(reason = "customer-visible WhatsApp send")
    private suspend fun sendToWhatsAppContact(contact: String, message: String): Boolean {
        if (!openWhatsAppTarget(contact)) return failed("open verified target $contact")
        // Protected-screen fail-closed gate: OTP/biometric/CAPTCHA/login surfaces are
        // never typed into — the send refuses and the owner must act on the phone.
        ProtectedScreenClassifier.classify(snapshot().visibleText)?.let { screen ->
            return failed("protected screen (${screen.kind.name.lowercase()}) requires owner handoff: ${screen.ownerAction}")
        }
        if (!sendInCurrentChat(message)) return failed("type or send message")
        delay(500)
        if (!snapshot().contains(message.take(40))) return failed("verify sent message")
        Log.i(TAG, "WhatsApp contact send action completed")
        return true
    }

    override suspend fun openWhatsAppTarget(contact: String): Boolean {
        if (!launchPackage("com.whatsapp")) return false
        delay(1_200)
        if (!openWhatsAppSearch()) return failed("open Search")
        delay(400)
        if (!setFirstEditable(contact)) return failed("enter contact")
        delay(900)
        if (!clickSearchResult(contact)) return failed("open contact")
        delay(700)
        val screen = snapshot()
        return screen.isWhatsApp && screen.contains(contact) && hasEditableField()
    }

    suspend fun readWhatsAppConversation(target: String, maxScrolls: Int = 3): WhatsAppChatContext? {
        if (!openWhatsAppTarget(target)) return null
        val pages = mutableListOf<List<String>>()
        repeat(maxScrolls.coerceIn(0, 8) + 1) { pass ->
            pages += snapshot().visibleText
            if (pass < maxScrolls) {
                if (!scrollUp()) return@repeat
                delay(450)
            }
        }
        val all = pages.asReversed().flatten().distinct()
        val lines = all.filterNot(::isWhatsAppChrome)
        val joined = all.joinToString(" ").lowercase()
        val group = joined.contains("participants") || joined.contains("group info")
        return WhatsAppChatContext(target, lines, group, deliveryState(all))
    }

    suspend fun readWhatsAppGroupParticipants(group: String, maxScrolls: Int = 8): List<String> {
        if (!openWhatsAppTarget(group)) return emptyList()
        if (!clickViewId("com.whatsapp:id/conversation_contact") && !clickExactLabel(group, "Group info")) return emptyList()
        delay(600)
        val all = linkedSetOf<String>()
        repeat(maxScrolls.coerceIn(1, 12)) {
            all += snapshot().visibleText
            if (!scrollDown()) return@repeat
            delay(350)
        }
        return all.filterNot(::isWhatsAppChrome).filterNot { it == group }.distinct()
    }

    suspend fun discoverWhatsAppChats(maxScrolls: Int = 8, groupsOnly: Boolean = false): List<String> {
        if (!launchPackage("com.whatsapp")) return emptyList()
        delay(1_200)
        dismissCommonWhatsAppObstruction()
        if (!ensureWhatsAppHome()) return emptyList()
        clickExactLabel("Chats")
        delay(400)
        if (groupsOnly) {
            val groupFilterClicked = clickViewId("com.whatsapp:id/conversations_filter_debug_view_id_groups") ||
                clickExactLabel("Groups filter", "Groups") ||
                clickExactLabel("Filter")
            if (!groupFilterClicked) android.util.Log.w("SanaaA11y", "WhatsApp group filter not found")
            delay(350)
        } else {
            // A preceding group discovery leaves WhatsApp's Groups filter selected.
            // Explicitly return to All so unified discovery does not silently omit
            // one-to-one chats from the canonical directory.
            val allFilterClicked = clickViewId("com.whatsapp:id/conversations_filter_debug_view_id_all") ||
                clickExactLabel("All filter", "All")
            if (!allFilterClicked) android.util.Log.w("SanaaA11y", "WhatsApp all filter not found")
            delay(350)
        }
        val all = linkedSetOf<String>()
        repeat(maxScrolls.coerceIn(1, 15)) {
            dismissCommonWhatsAppObstruction()
            val root = AccessibilityAgentService.instance?.rootInActiveWindow
            val found = root?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name")
                ?.mapNotNull { it.text?.toString()?.trim() }
                ?.filter { it.isNotBlank() && it != "WhatsApp" && it.length > 1 }
                ?.toSet() ?: emptySet()
            if (found.isNotEmpty()) all.addAll(found)
            if (!scrollDown()) return@repeat
            delay(300)
        }
        android.util.Log.i("SanaaA11y", "Discovered ${all.size} WhatsApp ${if (groupsOnly) "groups" else "chats"}")
        return all.toList()
    }

    suspend fun discoverWhatsAppGroups(maxScrolls: Int = 8): List<String> = discoverWhatsAppChats(maxScrolls, groupsOnly = true)

    override fun messageDeliveryState(message: String): String? {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return null
        val matches = root.findAccessibilityNodeInfosByText(message.take(40))
        for (match in matches.asReversed()) {
            var container: AccessibilityNodeInfo? = match
            repeat(4) {
                val labels = container?.let(::collectVisibleLabels).orEmpty()
                deliveryState(labels)?.let { return it }
                container = container?.parent
            }
        }
        return null
    }

    @RequiresTransaction(reason = "attachment dispatch")
    private suspend fun sendWhatsAppAttachment(target: String, uri: Uri, mimeType: String, caption: String = ""): Boolean {
        if (!isAvailable()) return false
        val started = runCatching {
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                if (caption.isNotBlank()) putExtra(Intent.EXTRA_TEXT, caption)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            true
        }.getOrDefault(false)
        if (!started) return false
        delay(1_200)
        if (!setFirstEditable(target)) clickLabel("Search") && setFirstEditable(target)
        delay(700)
        if (!clickSearchResult(target)) return false
        delay(500)
        if (!clickLabel("Next", "Send")) return false
        delay(900)
        var screen = snapshot()
        if (screen.contains(target) && ((caption.isNotBlank() && screen.contains(caption)) || (caption.isBlank() && screen.contains("Enlarge photo")))) return true
        if (caption.isNotBlank()) setFirstEditable(caption)
        if (!clickLabel("Send")) return false
        delay(900)
        screen = snapshot()
        return screen.contains(target) && ((caption.isNotBlank() && screen.contains(caption)) || screen.contains("Enlarge photo"))
    }

    private suspend fun openWhatsAppSearch(): Boolean {
        if (!ensureWhatsAppHome()) return false
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return false
        val search = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/search_bar_inner_layout").firstOrNull()
        if (search?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        return clickExactLabel("Ask Meta AI or Search", "Search")
    }

    private suspend fun ensureWhatsAppHome(): Boolean {
        val service = AccessibilityAgentService.instance ?: return false
        repeat(7) {
            dismissCommonWhatsAppObstruction()
            val screen = snapshot()
            if (screen.isWhatsApp && screen.contains("Chats") && (screen.contains("Updates") || screen.contains("Calls"))) return true
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(500)
        }
        return false
    }

    internal suspend fun recoverSokoHome(pin: String): Boolean {
        val service = AccessibilityAgentService.instance ?: return false
        repeat(20) {
            val screen = snapshot()
            if (screen.packageName != SOKO_PACKAGE) {
                if (!openSokoTerminal()) return false
                delay(700)
                return@repeat
            }
            if (screen.contains("Staff Login")) {
                if (!setFirstEditable(pin)) return false
                if (!clickAndLearn(SOKO_PACKAGE, "staff_sign_in", "Sign in")) return false
                delay(1_500)
                return@repeat
            }
            // This is the account-authentication screen, not the remembered
            // staff-PIN lock. Never invent a phone number or trigger an OTP.
            if (screen.contains("Enter your phone number") ||
                (screen.contains("We’ll check if you already have an account") && screen.contains("Continue"))) return false
            if (screen.contains("Point of Sale")) return true
            if (screen.contains("SELLER TERMINAL") || screen.contains("Soko24")) {
                delay(600)
                return@repeat
            }
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(500)
        }
        return snapshot().contains("Point of Sale")
    }

    private fun sokoAccessFailure(): String {
        val screen = snapshot()
        return when {
            screen.contains("Enter your phone number") ->
                "Soko Terminal is signed out at the account phone-number screen. I need the owner to sign in or explicitly provide the account phone and approve any OTP step; the saved staff PIN cannot unlock this screen."
            screen.contains("Staff Login") ->
                "Soko Terminal is at Staff Login, but the saved PIN was not accepted. Check the stored Terminal PIN."
            !isAvailable() ->
                "Phone Accessibility is not enabled or bound, so I cannot inspect Soko Terminal."
            else -> "The Soko Point of Sale screen could not be recovered."
        }
    }

    internal suspend fun scrollUntil(label: String, maxScrolls: Int): Boolean {
        repeat(maxScrolls.coerceIn(1, 10) + 1) { attempt ->
            if (snapshot().contains(label)) return true
            if (attempt < maxScrolls) {
                val moved = scrollDown()
                memory?.recordSelectorOutcome(snapshot().packageName, "scroll_to_$label", "scroll_forward", moved, if (moved) "Scroll accepted" else "No scrollable node")
                if (!moved) return false
                delay(450)
            }
        }
        return false
    }

    internal suspend fun clickAndLearn(appPackage: String, actionName: String, vararg labels: String): Boolean {
        val ordered = labels.distinct().sortedByDescending { memory?.selectorScore(appPackage, actionName, "label:$it") ?: 0 }
        for (label in ordered) {
            val before = snapshot().signature
            val accepted = clickExactLabel(label) || clickLabel(label)
            val changed = accepted && waitForScreenChange(before, 4_000)
            Log.i(TAG, "Adaptive action=$actionName selector=label:$label accepted=$accepted changed=$changed package=${snapshot().packageName}")
            memory?.recordSelectorOutcome(appPackage, actionName, "label:$label", changed, if (changed) "Screen changed" else "Click was not verified")
            if (changed) {
                pause(InteractionKind.TAP_SETTLE)
                return true
            }
        }
        return false
    }

    private fun parseStudioAds(): List<SokoStudioAd> {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return emptyList()
        val images = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, images) { node ->
            node.className == "android.widget.ImageView" && node.contentDescription?.toString()?.contains("SHOP NOW", true) == true
        }
        return images.mapNotNull { node ->
            val raw = node.contentDescription?.toString()?.trim().orEmpty()
            if (raw.isBlank()) return@mapNotNull null
            val lines = raw.lines().map(String::trim).filter(String::isNotBlank)
            val offerIndex = lines.indexOfFirst { it.contains("offer", true) }
            val product = lines.getOrNull(offerIndex + 1)
                ?: lines.firstOrNull { !it.contains("sale", true) && !it.contains("offer", true) }
                ?: return@mapNotNull null
            val price = lines.firstOrNull { it.contains("/=") || it.matches(Regex(".*\\d[\\d, ]+.*")) }.orEmpty()
            val url = lines.firstOrNull { it.contains("soko24.co", true) }.orEmpty()
            SokoStudioAd(product, price, url, raw)
        }.distinctBy { "${it.productName}|${it.productUrl}" }
    }

    private suspend fun dismissCommonWhatsAppObstruction(): Boolean {
        val screen = snapshot()
        val informational = screen.contains("Set up your secret code") ||
            screen.contains("WhatsApp keeps stopping") || screen.contains("Update WhatsApp")
        if (!informational) return false
        val dismissed = clickLabel("OK", "Not now", "Later")
        if (dismissed) delay(350)
        return dismissed
    }

    private fun clickSearchResult(label: String): Boolean {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return false
        return root.findAccessibilityNodeInfosByText(label)
            .filterNot { it.className == "android.widget.EditText" }
            .sortedByDescending { it.text?.toString()?.trim()?.equals(label.trim(), true) == true }
            .any { node ->
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) true
                else {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    !bounds.isEmpty && tapByPosition(bounds.centerX(), bounds.centerY())
                }
            }
    }

    @RequiresTransaction(reason = "direct listing write")
    private suspend fun updateSokoListing(currentTitle: String, newTitle: String, newDescription: String): Boolean {
        if (!openSokoTerminal()) return false
        delay(1_500)
        if (!clickLabel(currentTitle)) return false
        delay(900)
        if (!clickLabel("Edit", "Edit listing")) return false
        delay(700)
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return false
        val fields = mutableListOf<AccessibilityNodeInfo>()
        collectEditable(root, fields)
        if (fields.size < 2) return false
        fun set(node: AccessibilityNodeInfo, value: String) = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
        if (!set(fields[0], newTitle) || !set(fields[1], newDescription)) return false
        return clickLabel("Save", "Update")
    }

    data class SokoEditForm(
        val productName: String,
        val sellingPrice: String,
        val stockQty: String,
        val description: String,
        val rawFields: Map<String, String>,
    )

    override suspend fun openSokoEditForm(productName: String): Boolean {
        if (!openSokoTerminal()) return false
        if (waitForForegroundPackage(SOKO_PACKAGE) == null) return false
        if (!recoverSokoHome("")) return false
        if (!clickAndLearn(SOKO_PACKAGE, "open_more", "More")) return false
        if (!scrollUntil("Products", 8)) return false
        if (!clickAndLearn(SOKO_PACKAGE, "open_products_catalog", "Products")) return false
        pause(InteractionKind.APP_LOAD)
        if (!clickLabel(productName)) return false
        delay(800)
        val hasActions = clickExactLabel("Product actions")
        if (!hasActions) return false
        delay(700)
        if (!clickExactLabel("Preview")) return false
        pause(InteractionKind.APP_LOAD)
        if (!clickExactLabel("Edit")) return false
        pause(InteractionKind.APP_LOAD)
        return snapshot().contains("Edit Product")
    }

    suspend fun readSokoEditForm(): SokoEditForm {
        val raw = mutableMapOf<String, String>()
        repeat(3) {
            val screen = snapshot()
            screen.visibleText.forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("Product Name", true) -> {}
                    trimmed.startsWith("Selling Price", true) -> {}
                    trimmed.startsWith("Stock Qty", true) -> {}
                    trimmed.startsWith("Product Description", true) -> {}
                    trimmed.startsWith("Edit Product", true) -> {}
                    trimmed.startsWith("Pricing", true) -> {}
                    trimmed.startsWith("Marketplace", true) -> {}
                    trimmed.startsWith("Gallery", true) -> {}
                    trimmed.startsWith("Category", true) -> {}
                    trimmed.startsWith("Brand", true) -> {}
                    trimmed.startsWith("Unit", true) -> {}
                    trimmed.startsWith("Weight", true) -> {}
                    trimmed.startsWith("Delivery", true) -> {}
                    trimmed.startsWith("Tags", true) -> {}
                    trimmed.startsWith("SKU", true) -> {}
                    trimmed.startsWith("Min Order", true) -> {}
                    trimmed.startsWith("Low stock", true) -> {}
                    trimmed.startsWith("Buying Price", true) -> {}
                    trimmed.startsWith("Discount", true) -> {}
                    trimmed.startsWith("Fee", true) -> {}
                    trimmed.startsWith("Delivery days", true) -> {}
                    trimmed == "Plain" || trimmed == "Rich" -> {}
                    trimmed == "Update Product" || trimmed == "Change photo" -> {}
                    trimmed == "Listed on Marketplace" -> {}
                    trimmed == "Visible on soko24.co to all buyers" -> {}
                    trimmed == "Required for your online listing" -> {}
                    trimmed == "Expand editor" -> {}
                    else -> {
                        if (trimmed.isNotEmpty() && !raw.containsValue(trimmed)) {
                            raw["field_${raw.size}"] = trimmed
                        }
                    }
                }
            }
            if (!scrollDown()) return@repeat
            delay(400)
        }
        return SokoEditForm(
            productName = findFieldByLabel("Product Name") ?: "",
            sellingPrice = findFieldByLabel("Selling Price") ?: "",
            stockQty = findFieldByLabel("Stock Qty") ?: "",
            description = findFieldByLabel("Product Description") ?: "",
            rawFields = raw,
        )
    }

    private fun findFieldByLabel(label: String): String? {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return null
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, allNodes) { it.isVisibleToUser }
        for (index in allNodes.indices) {
            val node = allNodes[index]
            val text = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim() ?: continue
            if (text.equals(label, true) || text.startsWith(label, true)) {
                for (offset in 1..3) {
                    val candidate = allNodes.getOrNull(index + offset) ?: continue
                    val value = candidate.text?.toString()?.trim() ?: continue
                    if (value.isNotEmpty() && !value.equals(label, true)) return value
                }
            }
        }
        return null
    }

    suspend fun setEditFieldByOrder(fieldIndex: Int, value: String): Boolean {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return false
        val fields = mutableListOf<AccessibilityNodeInfo>()
        collectEditable(root, fields)
        val target = fields.getOrNull(fieldIndex) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
    }

    suspend fun scrollToEditField(label: String, maxScrolls: Int = 5): Boolean {
        repeat(maxScrolls.coerceAtLeast(1)) {
            val screen = snapshot()
            if (screen.visibleText.any { it.contains(label, true) }) return true
            if (!scrollDown()) return false
            delay(400)
        }
        return snapshot().visibleText.any { it.contains(label, true) }
    }

    @RequiresTransaction(reason = "Soko listing save")
    private suspend fun saveEditForm(): Boolean {
        if (!scrollToEditField("Update Product", 8)) return false
        return clickExactLabel("Update Product")
    }

    override suspend fun setFirstEditableField(value: String): Boolean = setFirstEditable(value)
    override suspend fun saveSokoEditForm(): Boolean = transacted { saveEditForm() }

    override fun verifyEditFormFields(expected: Map<String, String>): Boolean {
        val form = runBlocking { readSokoEditForm() }
        return expected.all { (label, value) ->
            when (label.lowercase()) {
                "product name" -> form.productName.equals(value, true)
                "selling price" -> form.sellingPrice.filter(Char::isDigit) == value.filter(Char::isDigit)
                "stock qty" -> form.stockQty == value
                "description" -> form.description.contains(value, true)
                else -> form.rawFields.values.any { it.equals(value, true) || it.contains(value, true) }
            }
        }
    }

    @RequiresTransaction(reason = "public TikTok publish or draft creation")
    private suspend fun postTikTok(imageUrl: String, caption: String, publish: Boolean = false): Boolean {
        if (!isAvailable() || imageUrl.isBlank()) return false
        val directory = File(context.cacheDir, "agent-creatives").apply { mkdirs() }
        val image = File(directory, "broadcast-${System.currentTimeMillis()}.jpg")
        val response = OkHttpClient().newCall(Request.Builder().url(imageUrl).build()).execute()
        response.use { download ->
            if (!download.isSuccessful) return false
            val body = download.body ?: return false
            image.outputStream().use { body.byteStream().copyTo(it) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", image)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, caption)
            setPackage("com.zhiliaoapp.musically"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false).not()) return false
        delay(2_000)
        clickLabel("Next")
        delay(1_200)
        setFirstEditable(caption)
        return if (publish) clickExactLabel("Post") else clickLabel("Drafts", "Save draft")
    }

    fun clickLabel(vararg labels: String): Boolean {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return false
        labels.forEach { label ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull()?.let { node ->
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            }
            findFirst(root) { it.isClickable && it.contentDescription?.toString()?.contains(label, true) == true }
                ?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        }
        return false
    }

    fun clickExactLabel(vararg labels: String): Boolean {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes) { it.isVisibleToUser }
        for (label in labels) {
            val wanted = label.trim()
            val node = nodes.firstOrNull {
                val text = it.text?.toString()?.trim().orEmpty()
                val description = it.contentDescription?.toString()?.trim().orEmpty()
                text.equals(wanted, true) || description.equals(wanted, true) || description.startsWith("$wanted,", true) || description.startsWith("$wanted\n", true)
            } ?: continue
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        return false
    }

    fun clickViewId(viewId: String): Boolean {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return false
        val node = root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull() ?: return false
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    @RequiresTransaction(reason = "writes into whatever field is focused on screen")
    private fun setFirstEditable(text: String): Boolean {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return false
        val input = findFirst(root) { it.className == "android.widget.EditText" && it.isEditable } ?: return false
        return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
    }

    fun hasEditableField(): Boolean {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return false
        return findFirst(root) { it.className == "android.widget.EditText" && it.isEditable } != null
    }

    private fun scroll(action: Int): Boolean {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, candidates) { it.isScrollable }
        return candidates.asReversed().any { it.performAction(action) }
    }

    private fun launchPackage(packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        (AccessibilityAgentService.instance ?: context).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.onFailure { Log.e(TAG, "Could not launch $packageName", it) }.getOrDefault(false)

    private fun failed(step: String): Boolean {
        Log.w(TAG, "WhatsApp contact send failed at: $step")
        return false
    }

    internal suspend fun pause(kind: InteractionKind) = delay(HumanPacing.delayMillis(kind))

    private fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (index in 0 until root.childCount) root.getChild(index)?.let { findFirst(it, predicate)?.let { match -> return match } }
        return null
    }

    private fun collectEditable(root: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>) {
        if (root.className == "android.widget.EditText" && root.isEditable) output += root
        for (index in 0 until root.childCount) root.getChild(index)?.let { collectEditable(it, output) }
    }

    private fun collectVisibleLabels(root: AccessibilityNodeInfo): List<String> {
        val output = mutableListOf<Pair<Int, String>>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) {
                val bounds = Rect().also(node::getBoundsInScreen)
                listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .map { raw -> raw.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter(String::isNotBlank).joinToString("\n") }
                    .filter { it.isNotBlank() }
                    .forEach { output += bounds.top to it }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        walk(root)
        return output.sortedBy { it.first }.map { it.second }
    }

    private fun collectNodes(root: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>, predicate: (AccessibilityNodeInfo) -> Boolean) {
        if (predicate(root)) output += root
        for (index in 0 until root.childCount) root.getChild(index)?.let { collectNodes(it, output, predicate) }
    }

    private fun deliveryState(lines: Collection<String>): String? {
        val joined = lines.joinToString(" ").lowercase()
        return when {
            "read" in joined -> "read"
            "delivered" in joined -> "delivered"
            "sent" in joined -> "sent"
            "pending" in joined -> "pending"
            "failed" in joined -> "failed"
            else -> null
        }
    }

    private fun isWhatsAppChrome(line: String): Boolean {
        val value = line.trim().lowercase()
        return value in setOf("video call", "voice call", "more options", "emoji", "attach", "camera", "send", "message") ||
            value.startsWith("type a message") || value.startsWith("search")
    }

    // ================== FOREGROUND RECOVERY PRIMITIVES (Agent 2) ==================
    // Observation and navigation-only recovery. These helpers NEVER type text into
    // credential surfaces, NEVER dismiss anything carrying account/security/payment
    // semantics, and NEVER force-stop an app — leaving a blocking app means BACK/HOME.
    // launchTargetPackage/recoverToTarget start an app (externally visible) and are
    // therefore transaction-gated; pure reads need no annotation.

    /** Read-only observation of whatever currently owns the foreground window. */
    fun classifyForeground(): ForegroundObservation {
        val service = AccessibilityAgentService.instance
        val root = runCatching { service?.rootInActiveWindow }.getOrNull()
        val pkg = root?.packageName?.toString()?.takeIf { it.isNotBlank() }
        val activityName = root?.className?.toString()?.takeIf { it.isNotBlank() }
        if (runCatching { ScreenController.isSecurelyLocked(context) }.getOrDefault(false)) {
            // A secure keyguard is an owner surface: no detail beyond the fact itself.
            return ForegroundObservation(
                packageName = pkg,
                activityName = activityName,
                isLauncher = false,
                obstructingDialogDetected = false,
                protectedScreenKind = PROTECTED_SECURE_KEYGUARD,
            )
        }
        val labels = runCatching { snapshot().visibleText }.getOrDefault(emptyList())
        return ForegroundObservation(
            packageName = pkg,
            activityName = activityName,
            isLauncher = runCatching { pkg != null && isDefaultLauncher(pkg) }.getOrDefault(false),
            obstructingDialogDetected =
                runCatching { looksLikeObstructingDialog(service, root?.className?.toString()) }.getOrDefault(false),
            protectedScreenKind = ProtectedScreenClassifier.classify(labels)?.let(::protectedKindName),
        )
    }

    private fun protectedKindName(screen: co.sanaa.agent.core.ProtectedScreen): String? = when (screen.kind) {
        ProtectedScreenKind.OTP -> "otp"
        ProtectedScreenKind.CAPTCHA -> "captcha"
        ProtectedScreenKind.BIOMETRIC -> "biometric"
        ProtectedScreenKind.ACCOUNT_LOGIN -> "account_security"
        // Android permission prompts expose a vetted safe dismissal ("Allow" /
        // "While using the app"); they are not owner-handoff surfaces.
        ProtectedScreenKind.ANDROID_PERMISSION, ProtectedScreenKind.UNKNOWN -> null
    }

    /**
     * Semantic-only dismissal candidate: a clickable node labeled OK/Got it/Dismiss/
     * Close/Allow/"While using the app" (case-insensitive). Returns null whenever the
     * surrounding window carries account/security/payment semantics — those surfaces
     * belong to the owner, never to automation.
     */
    fun findDismissibleSafeDialogNode(): AccessibilityNodeInfo? {
        val root = runCatching { AccessibilityAgentService.instance?.rootInActiveWindow }.getOrNull() ?: return null
        if (windowCarriesSensitiveSemantics(root)) return null
        var match: AccessibilityNodeInfo? = null
        walkNodes(root) { node ->
            if (match != null) return@walkNodes
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map(::normalizeLabel)
            if (node.isClickable && labels.any { it in SAFE_DISMISS_LABELS }) match = node
        }
        return match
    }

    fun globalBack(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun globalHome(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    @RequiresTransaction(reason = "foregrounds a target app (externally visible launch)")
    fun launchTargetPackage(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Log.w(TAG, "launchTargetPackage: no launcher intent for $packageName")
            return false
        }
        return runCatching {
            (AccessibilityAgentService.instance ?: context).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.onFailure { Log.e(TAG, "Could not launch $packageName", it) }.getOrDefault(false)
    }

    /** Read-only confirmation that the expected package owns the foreground window. */
    fun confirmScreenState(expectedPackage: String): Boolean =
        classifyForeground().packageName == expectedPackage

    /**
     * Returns the foreground to [targetPackage] by executing [ForegroundRecoveryPlanner]
     * steps with bounded navigation only: one safe dialog dismissal, at most
     * [maxBackActions] BACK presses, HOME, then a launcher-intent launch confirmed by up
     * to ~8s of foreground polling. Protected screens route to HANDOFF_OWNER and refuse
     * without any interaction — the owner must act on the phone.
     */
    @RequiresTransaction(reason = "recovery launches the allowlisted target app")
    suspend fun recoverToTarget(targetPackage: String, maxBackActions: Int = 2): Boolean {
        if (!isAvailable()) {
            Log.w(TAG, "recoverToTarget: accessibility is not connected")
            return false
        }
        val initial = classifyForeground()
        val plan = ForegroundRecoveryPlanner.planRecoverySteps(
            obs = initial.copy(targetInForeground = initial.packageName == targetPackage),
            maxBackActions = maxBackActions,
        )
        Log.i(TAG, "recoverToTarget target=$targetPackage plan=$plan")
        for (step in plan) {
            when (step) {
                RecoveryStep.DONE -> return true
                RecoveryStep.HANDOFF_OWNER -> {
                    Log.w(TAG, "recoverToTarget: owner handoff required; no interaction attempted on this surface")
                    return false
                }
                RecoveryStep.DISMISS_SAFE_DIALOG -> {
                    dismissSafeDialogByNode(findDismissibleSafeDialogNode())
                    pause(InteractionKind.APP_LOAD)
                }
                RecoveryStep.GLOBAL_BACK -> { globalBack(); pause(InteractionKind.APP_LOAD) }
                RecoveryStep.GLOBAL_HOME -> { globalHome(); pause(InteractionKind.APP_LOAD) }
                RecoveryStep.LAUNCH_TARGET -> return launchAndConfirm(targetPackage)
            }
            if (classifyForeground().packageName == targetPackage) return true
        }
        return classifyForeground().packageName == targetPackage
    }

    private suspend fun launchAndConfirm(targetPackage: String): Boolean {
        if (!launchTargetPackage(targetPackage)) return false
        val settled = waitForForegroundPackage(targetPackage, FOREGROUND_CONFIRM_TIMEOUT_MS)
        return settled != null && settled.packageName == targetPackage
    }

    /** Taps an already-vetted dismissible node; no-op unless clickable. */
    private fun dismissSafeDialogByNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !node.isClickable) return false
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }

    private fun performGlobalAction(action: Int): Boolean {
        val service = AccessibilityAgentService.instance ?: return false
        return runCatching { service.performGlobalAction(action) }.getOrDefault(false)
    }

    private fun isDefaultLauncher(packageName: String): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?: return false
        return resolved.activityInfo.packageName == packageName
    }

    private fun looksLikeObstructingDialog(service: android.accessibilityservice.AccessibilityService?, rootClassName: String?): Boolean {
        if (rootClassName?.contains("dialog", ignoreCase = true) == true) return true
        val focusedAppWindows = service?.windows.orEmpty().count {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        }
        return focusedAppWindows > 1
    }

    private fun windowCarriesSensitiveSemantics(root: AccessibilityNodeInfo): Boolean =
        collectVisibleLabels(root).any { line ->
            val value = line.lowercase()
            SENSITIVE_DIALOG_TERMS.any { term -> value.contains(term) }
        }

    private fun normalizeLabel(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")



    /**
     * STRUCTURAL side-effect boundary: every device-writing primitive above is private;
     * the ONLY way production code may invoke them is inside [transacted], i.e. within a
     * universal transaction's act lambda. Wrapper/alias/callable-reference attempts from
     * arbitrary code fail to compile because these members are not on the public surface.
     */
    interface TransactionScope {
        suspend fun sendToWhatsAppPhone(phone: String, message: String): Boolean
        suspend fun sendToWhatsAppContact(contact: String, message: String): Boolean
        suspend fun sendToWhatsAppGroup(group: String, message: String): Boolean
        suspend fun sendWhatsAppAttachment(target: String, uri: Uri, mimeType: String, caption: String): Boolean
        suspend fun sendInCurrentChat(message: String): Boolean
        suspend fun postWhatsAppTextStatus(message: String): Boolean
        suspend fun postWhatsAppMediaStatus(uri: Uri, mimeType: String, caption: String): Boolean
        suspend fun postTikTok(imageUrl: String, caption: String, publish: Boolean): Boolean
        suspend fun updateSokoListing(currentTitle: String, newTitle: String, newDescription: String): Boolean
        suspend fun saveEditForm(): Boolean
        fun setFirstEditable(text: String): Boolean
    }

    /** Runs [block] with the private transaction primitives as receiver. */
    override suspend fun <T> transacted(block: suspend TransactionScope.() -> T): T = block(TransactionScopeImpl())

    private inner class TransactionScopeImpl : TransactionScope {
        override suspend fun sendToWhatsAppPhone(phone: String, message: String): Boolean =
            this@AccessibilityActions.sendToWhatsAppPhone(phone, message)
        override suspend fun sendToWhatsAppContact(contact: String, message: String): Boolean =
            this@AccessibilityActions.sendToWhatsAppContact(contact, message)
        override suspend fun sendToWhatsAppGroup(group: String, message: String): Boolean =
            this@AccessibilityActions.sendToWhatsAppGroup(group, message)
        override suspend fun sendWhatsAppAttachment(target: String, uri: Uri, mimeType: String, caption: String): Boolean =
            this@AccessibilityActions.sendWhatsAppAttachment(target, uri, mimeType, caption)
        override suspend fun sendInCurrentChat(message: String): Boolean =
            this@AccessibilityActions.sendInCurrentChat(message)
        override suspend fun postWhatsAppTextStatus(message: String): Boolean =
            this@AccessibilityActions.postWhatsAppTextStatus(message)
        override suspend fun postWhatsAppMediaStatus(uri: Uri, mimeType: String, caption: String): Boolean =
            this@AccessibilityActions.postWhatsAppMediaStatus(uri, mimeType, caption)
        override suspend fun postTikTok(imageUrl: String, caption: String, publish: Boolean): Boolean =
            this@AccessibilityActions.postTikTok(imageUrl, caption, publish)
        override suspend fun updateSokoListing(currentTitle: String, newTitle: String, newDescription: String): Boolean =
            this@AccessibilityActions.updateSokoListing(currentTitle, newTitle, newDescription)
        override suspend fun saveEditForm(): Boolean = this@AccessibilityActions.saveEditForm()
        override fun setFirstEditable(text: String): Boolean = this@AccessibilityActions.setFirstEditable(text)
    }

    companion object {
        private const val TAG = "SanaaAgentActions"
        private const val SOKO_PACKAGE = "com.soko24.soko_seller_terminal"
        private const val SOKO_BUYER_PACKAGE = "com.sanaa.soko24u.buyer"
        private const val PROTECTED_SECURE_KEYGUARD = "secure_keyguard"
        private const val FOREGROUND_CONFIRM_TIMEOUT_MS = 8_000L

        /** Labels considered safe to tap for dismissing a dialog. Anything else is not touched. */
        private val SAFE_DISMISS_LABELS = setOf("ok", "okay", "got it", "dismiss", "close", "allow", "while using the app")

        /** Surfaces containing these semantics are never dismissed by automation. */
        private val SENSITIVE_DIALOG_TERMS = listOf(
            "password", "passcode", "pin", "otp", "one-time", "verification code",
            "captcha", "sign in", "log in", "login", "bank", "payment", "card number",
            "security", "account", "fingerprint", "face unlock", "verify your identity",
        )
    }
}

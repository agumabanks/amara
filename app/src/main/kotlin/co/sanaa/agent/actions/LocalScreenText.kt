package co.sanaa.agent.actions

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device OCR for custom-rendered labels. Screenshots are deleted after reading. */
internal class LocalScreenText(private val actions: AccessibilityActions) {
    data class Line(val text: String, val bounds: Rect)
    private var lastCaptureAt=0L
    suspend fun read(expectedPackage: String): List<Line> {
        val wait=400L-(android.os.SystemClock.elapsedRealtime()-lastCaptureAt)
        if(wait>0) kotlinx.coroutines.delay(wait)
        if(actions.snapshot().packageName != expectedPackage) return emptyList()
        val window=co.sanaa.agent.services.AccessibilityAgentService.instance?.rootInActiveWindow?.windowId ?: return emptyList()
        lastCaptureAt=android.os.SystemClock.elapsedRealtime()
        val file = actions.captureScreenshot("local_text").path?.let(::File) ?: return emptyList()
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        file.delete()
        if(bitmap == null) return emptyList()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            // Keep bitmap alive until the recognizer has finished, even on coroutine cancellation.
            val result = suspendCoroutine<com.google.mlkit.vision.text.Text> { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            if(actions.snapshot().packageName != expectedPackage ||
                co.sanaa.agent.services.AccessibilityAgentService.instance?.rootInActiveWindow?.windowId != window ||
                android.os.SystemClock.elapsedRealtime()-lastCaptureAt>5_000) emptyList() else result.textBlocks.flatMap { it.lines }
                .mapNotNull { line -> line.boundingBox?.let { Line(line.text, Rect(it)) } }
        } finally { recognizer.close(); bitmap.recycle() }
    }
    suspend fun tap(expectedPackage: String, label: String): Boolean {
        val a = read(expectedPackage).singleOrNull { it.text.equals(label,true) } ?: return false
        val b = read(expectedPackage).singleOrNull { it.text.equals(label,true) } ?: return false
        if(kotlin.math.abs(a.bounds.centerX()-b.bounds.centerX())>8 || kotlin.math.abs(a.bounds.centerY()-b.bounds.centerY())>8) return false
        return actions.snapshot().packageName == expectedPackage && actions.tapByPosition(b.bounds.centerX(),b.bounds.centerY())
    }
}

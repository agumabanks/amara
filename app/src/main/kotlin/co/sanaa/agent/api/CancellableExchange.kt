package co.sanaa.agent.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Cancellation closes the socket even while the response body is still streaming. */
internal suspend fun cancellableExchange(call: Call): Pair<Response, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val body = response.use { it.body?.string().orEmpty() }
                if (continuation.isActive) continuation.resume(response to body)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

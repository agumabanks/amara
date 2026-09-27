package co.sanaa.agent.core.memory

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Lossless versioned transport; retained action claims must never be trimmed to fit a backup. */
object MemorySnapshotCodec {
    private const val MAX_EXPANDED_BYTES = 64 * 1024 * 1024

    fun encode(snapshot: JSONObject): JSONObject {
        val raw = snapshot.toString().toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_EXPANDED_BYTES) { "Memory exceeds the supported backup size; local memory is retained" }
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(raw) }
        }.toByteArray()
        val envelope = JSONObject().put("schema_version", 2).put("encoding", "gzip-base64")
            .put("data", Base64.getEncoder().encodeToString(compressed))
        require(envelope.toString().length < 1_800_000) { "Compressed memory exceeds the server limit; local memory is retained" }
        return envelope
    }

    fun decode(snapshot: JSONObject): JSONObject {
        if (snapshot.optInt("schema_version") == 1) return snapshot
        require(snapshot.optInt("schema_version") == 2 && snapshot.optString("encoding") == "gzip-base64") {
            "Unsupported backup version"
        }
        val bytes = Base64.getDecoder().decode(snapshot.getString("data"))
        val output = ByteArrayOutputStream()
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_EXPANDED_BYTES) { "Expanded backup exceeds the supported size" }
                output.write(buffer, 0, count)
            }
        }
        return JSONObject(output.toString("UTF-8")).also {
            require(it.optInt("schema_version") == 1) { "Unsupported inner backup version" }
        }
    }
}

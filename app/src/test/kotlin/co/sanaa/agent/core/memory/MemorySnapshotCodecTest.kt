package co.sanaa.agent.core.memory

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class MemorySnapshotCodecTest {
    @Test fun oversizedBackupRoundTripsUnicodeAndUncertainActionClaimsWithoutLoss() {
        val original = JSONObject().put("schema_version",1).put("memory","Customer résumé 📦 ".repeat(150000))
            .put("claims",JSONObject().put("key","uncertain-delivery").put("reserved_at",12345))
        val encoded = MemorySnapshotCodec.encode(original)
        assertTrue(encoded.toString().length < 1_800_000)
        val restored = MemorySnapshotCodec.decode(encoded)
        assertEquals(original.getString("memory"),restored.getString("memory"))
        assertEquals(12345,restored.getJSONObject("claims").getInt("reserved_at"))
    }
    @Test fun oldBackupsRemainReadable() {
        val original=JSONObject().put("schema_version",1).put("relationship_memory",JSONObject())
        assertSame(original,MemorySnapshotCodec.decode(original))
    }
    @Test(expected=IllegalArgumentException::class) fun unknownEncodingIsRejected() {
        MemorySnapshotCodec.decode(JSONObject().put("schema_version",2).put("encoding","unknown"))
    }
}

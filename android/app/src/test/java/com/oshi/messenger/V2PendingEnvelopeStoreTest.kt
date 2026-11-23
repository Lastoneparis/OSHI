package com.oshi.messenger

import com.oshi.messenger.network.v2.V2PendingEnvelopeStore
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.IOException

class V2PendingEnvelopeStoreTest {
    @Test fun `restart retries exact ciphertext and first message header`() {
        val root=Files.createTempDirectory("oshi-pending-").toFile()
        try {
            val body="pending 🔒".toByteArray()
            val packet=JSONObject().put("ciphertext","opaque").put("x3dh",JSONObject().put("ek","original"))
            V2PendingEnvelopeStore(root).save("alice","bob","id",body,packet)
            val restarted=V2PendingEnvelopeStore(root)
            assertEquals(packet.toString(),restarted.load("alice","bob","id",body)!!.toString())
            assertNull(restarted.load("other-identity","bob","id",body))
            assertNull(restarted.load("alice","other-peer","id",body))
            try { restarted.load("alice","bob","id","edited".toByteArray());fail("ID reused with changed content") }
            catch (_: IOException) { }
            restarted.remove("alice","bob","id")
            assertNull(restarted.load("alice","bob","id",body))
        } finally {root.deleteRecursively()}
    }
}

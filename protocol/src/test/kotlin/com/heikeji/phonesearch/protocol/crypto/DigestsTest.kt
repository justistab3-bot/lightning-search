package com.heikeji.phonesearch.protocol.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class DigestsTest {

    @Test
    fun `md5Lower matches well known digests`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Digests.md5Lower(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Digests.md5Lower("abc"))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Digests.md5Lower("a"))
    }

    @Test
    fun `md5Upper is the uppercase form over raw bytes`() {
        assertEquals(
            "900150983CD24FB0D6963F7D28E17F72",
            Digests.md5Upper("abc".toByteArray(StandardCharsets.UTF_8)),
        )
    }

    @Test
    fun `lower and upper agree apart from case`() {
        val sample = "8&%d*##Ab3xY9zQ1w##2fb53de6d38eff7109f19d68e047123b##cuid|0"
        assertEquals(
            Digests.md5Lower(sample).uppercase(),
            Digests.md5Upper(sample.toByteArray(StandardCharsets.US_ASCII)),
        )
    }
}

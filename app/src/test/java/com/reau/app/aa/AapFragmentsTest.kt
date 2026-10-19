package com.reau.app.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A message of the small channels put back together from its pieces, as the header flags say. */
class AapFragmentsTest {

    private val fragments = AapFragments(maxBytes = 64)

    /** A piece as AapMessageIncoming makes it: the first two bytes read as the type, the payload after them. */
    private fun piece(flags: Int, bytes: ByteArray): AapMessage {
        val type = if (bytes.size >= 2) ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF) else 0
        return AapMessage(Channel.ID_MPB, flags.toByte(), type, 2, bytes.size, bytes)
    }

    private fun typed(type: Int, payload: ByteArray) = byteArrayOf((type shr 8).toByte(), type.toByte()) + payload

    @Test
    fun `a whole message gives its type and payload at once`() {
        val whole = fragments.take(piece(0x0B, typed(0x8003, byteArrayOf(1, 2, 3))))
        assertNotNull(whole)
        assertEquals(0x8003, whole!!.type)
        assertArrayEquals(byteArrayOf(1, 2, 3), whole.payload)
    }

    @Test
    fun `first, middle and last pieces make one message with the first piece's type`() {
        assertNull(fragments.take(piece(0x09, typed(0x8003, byteArrayOf(1, 2)))))
        assertNull(fragments.take(piece(0x08, byteArrayOf(3, 4, 5))))
        val whole = fragments.take(piece(0x0A, byteArrayOf(6)))
        assertNotNull(whole)
        assertEquals(0x8003, whole!!.type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), whole.payload)
    }

    @Test
    fun `a middle or last piece without a first one is dropped`() {
        assertNull(fragments.take(piece(0x08, byteArrayOf(3, 4))))
        assertNull(fragments.take(piece(0x0A, byteArrayOf(5))))
        // and the next whole message is still read
        assertArrayEquals(byteArrayOf(9), fragments.take(piece(0x0B, typed(0x8001, byteArrayOf(9))))!!.payload)
    }

    @Test
    fun `a message larger than the limit is dropped, and the next one is read`() {
        assertNull(fragments.take(piece(0x09, typed(0x8003, ByteArray(40)))))
        assertNull(fragments.take(piece(0x08, ByteArray(40))))
        assertNull(fragments.take(piece(0x0A, ByteArray(1))))
        assertNull(fragments.take(piece(0x09, typed(0x8003, byteArrayOf(1)))))
        assertArrayEquals(byteArrayOf(1, 2), fragments.take(piece(0x0A, byteArrayOf(2)))!!.payload)
    }

    @Test
    fun `a new first piece starts over`() {
        assertNull(fragments.take(piece(0x09, typed(0x8003, byteArrayOf(1)))))
        assertNull(fragments.take(piece(0x09, typed(0x8003, byteArrayOf(7)))))
        assertArrayEquals(byteArrayOf(7, 8), fragments.take(piece(0x0A, byteArrayOf(8)))!!.payload)
    }
}

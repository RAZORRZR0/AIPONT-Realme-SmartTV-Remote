package dev.tvremote.ir

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class NecTest {
    @Test
    fun powerMatchesSpec() {
        val expected = ("9000,4500,562,562,562,562,562,562,562,562,562,562,562,562,562,1687,562,562,562,1687,562,1687,562,1687,562,1687,562,1687,562,1687,562,562,562,1687,562,562,562,1687,562,562,562,562,562,1687,562,562,562,562,562,562,562,1687,562,562,562,1687,562,1687,562,562,562,1687,562,1687,562,1687,562,41970,9000,2250,562,98188")
            .split(",").map { it.toInt() }.toIntArray()
        assertArrayEquals(expected, nec(0x12))
    }
}

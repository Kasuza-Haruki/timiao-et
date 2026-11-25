package app.timiao.et.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KernelTagTest {
    @Test
    fun `reads the first meaningful line`() {
        assertEquals("v2.7.1", KernelTag.parse("# pinned\n\n v2.7.1 \n"))
    }

    @Test
    fun `blank or comment only returns null`() {
        assertNull(KernelTag.parse(null))
        assertNull(KernelTag.parse("  \n# only comment"))
    }
}

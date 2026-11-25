package app.timiao.et.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TomlParseErrorTest {

    @Test
    fun reportsLineAndColumn() {
        val text = """
            instance_name = "phone"
            dhcp = true
            this is not valid
        """.trimIndent()

        val error = runCatching { TomlCodec.importToml(text) }.exceptionOrNull()
        assertTrue(error is TomlParseException)
        val typed = error as TomlParseException
        assertEquals(3, typed.line)
        assertTrue(typed.message!!.contains("第 3 行"))
    }

    @Test
    fun reportsUnterminatedStringLine() {
        val text = """
            instance_name = "phone"
            hostname = "pixel
        """.trimIndent()

        val typed = runCatching { TomlCodec.importToml(text) }.exceptionOrNull() as TomlParseException
        assertEquals(2, typed.line)
        assertTrue(typed.message!!.contains("未结束") || typed.message!!.contains("unterminated"))
    }

    @Test
    fun reportsBadAclLineAgainstFullDocument() {
        val text = """
            instance_name = "phone"

            [[acl.acl_v1.chains]]
            name = "protect"
            chain_type =
        """.trimIndent()

        val typed = runCatching { TomlCodec.importToml(text) }.exceptionOrNull() as TomlParseException
        assertEquals(5, typed.line)
    }
}

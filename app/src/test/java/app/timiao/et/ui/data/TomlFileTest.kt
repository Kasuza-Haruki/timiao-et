package app.timiao.et.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TomlFileTest {

    @Test
    fun stripsUtf8Bom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "instance_name = \"phone\"\n".toByteArray(Charsets.UTF_8)

        val text = decodeTomlBytes(bytes).getOrThrow()

        assertEquals("instance_name = \"phone\"\n", text)
        assertFalse(text.startsWith("﻿"))
    }

    @Test
    fun rejectsFileOverOneMegabyte() {
        val bytes = ByteArray(MAX_TOML_BYTES + 1) { 'a'.code.toByte() }

        val result = decodeTomlBytes(bytes)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("1 MB"))
    }

    @Test
    fun rejectsInvalidUtf8() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28, 0x0A)

        val result = decodeTomlBytes(bytes)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("UTF-8"))
    }

    @Test
    fun detectsCopiedInstanceId() {
        val existing = setOf("91c76673-b539-5e99-8e7b-5dd20fcec66f", "home")

        assertTrue(hasInstanceIdConflict("91c76673-b539-5e99-8e7b-5dd20fcec66f", existing))
        assertFalse(hasInstanceIdConflict("fresh-id", existing))
        assertFalse(hasInstanceIdConflict(null, existing))
        assertFalse(hasInstanceIdConflict("", existing))
    }

    @Test
    fun saveAsNewGeneratesAndDoesNotCollide() {
        val existing = setOf("existing-id")

        val kept = resolveImportId(
            importedId = "existing-id",
            existingIds = existing,
            saveAsNew = true,
            generatedId = "generated-id",
        )
        assertEquals("generated-id", kept)

        val collided = resolveImportId(
            importedId = "existing-id",
            existingIds = existing,
            saveAsNew = true,
            generatedId = "existing-id",
        )
        assertNotEquals("existing-id", collided)
        assertFalse(collided in existing)
    }

    @Test
    fun overwriteKeepsImportedIdAndMissingIdGetsNewOne() {
        assertEquals(
            "existing-id",
            resolveImportId("existing-id", setOf("existing-id"), saveAsNew = false, "generated"),
        )
        assertEquals(
            "generated",
            resolveImportId(null, setOf("existing-id"), saveAsNew = false, "generated"),
        )
    }

    @Test
    fun exportNameIsSafe() {
        assertEquals("home.toml", exportFileName("home"))
        assertEquals("a-b-c.toml", exportFileName("a/b:c"))
        assertEquals("easytier.toml", exportFileName("  "))
    }
}

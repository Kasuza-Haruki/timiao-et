package app.timiao.et.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun `newer patch and minor are detected`() {
        assertTrue(isNewerVersion("v2.7.2", "v2.7.1"))
        assertTrue(isNewerVersion("2.8.0", "2.7.9"))
        assertTrue(isNewerVersion("3.0.0", "2.99.99"))
        assertFalse(isNewerVersion("2.7.1", "2.7.1"))
        assertFalse(isNewerVersion("2.7.0", "2.7.1"))
    }

    @Test
    fun `numeric parts compare as numbers`() {
        assertTrue(compareVersions("2.7.10", "2.7.9") > 0)
        assertTrue(compareVersions("2.10.0", "2.9.0") > 0)
        assertTrue(compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(compareVersions("1.0.9", "1.0.10") < 0)
    }

    @Test
    fun `release candidate numbers compare as numbers`() {
        assertTrue(compareVersions("1.0.0-rc10", "1.0.0-rc9") > 0)
        assertTrue(compareVersions("1.0.0-rc9", "1.0.0-rc10") < 0)
        assertTrue(compareVersions("1.0.0-rc10", "1.0.0-rc2") > 0)
        assertTrue(compareVersions("1.0.0", "1.0.0-rc1") > 0)
        assertTrue(compareVersions("1.0.0-rc1", "1.0.0") < 0)
    }

    @Test
    fun `missing components count as zero`() {
        assertEquals(0, compareVersions("2.7", "2.7.0"))
        assertEquals(0, compareVersions("2", "2.0.0"))
    }

    @Test
    fun `pre-release is older than the stable release`() {
        assertTrue(compareVersions("2.7.1-rc1", "2.7.1") < 0)
        assertTrue(compareVersions("2.7.1", "2.7.1-rc1") > 0)
        assertTrue(compareVersions("2.7.1-rc1", "2.7.1-rc2") < 0)
    }

    @Test
    fun `build metadata is ignored`() {
        assertEquals(0, compareVersions("2.7.1+build5", "2.7.1"))
        assertEquals(0, compareVersions("v2.7.1", "2.7.1"))
    }

    @Test
    fun `normalize strips one v prefix and spaces`() {
        assertEquals("2.7.1", normalizeVersion(" v2.7.1 "))
        assertEquals("2.7.1", normalizeVersion("V2.7.1"))
    }
}

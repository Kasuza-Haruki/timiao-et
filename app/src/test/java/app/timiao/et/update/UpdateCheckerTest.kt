package app.timiao.et.update

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `404 degrades to unknown`() = runBlocking {
        val checker = UpdateChecker(http = { HttpResponse.Status(404) })
        assertEquals(UpdateResult.Unknown, checker.checkApp("0.1.0"))
    }

    @Test
    fun `network failure degrades to unknown`() = runBlocking {
        val checker = UpdateChecker(http = { throw IOException("offline") })
        assertEquals(UpdateResult.Unknown, checker.checkApp("0.1.0"))
        assertEquals(UpdateResult.Unknown, checker.checkKernel("v2.7.1"))
    }

    @Test
    fun `newer app release is available`() = runBlocking {
        val body = """
            {"tag_name":"v0.2.0","html_url":"https://github.test/timiao/0.2.0",
             "body":"fixes","draft":false,"prerelease":false}
        """.trimIndent()
        val checker = UpdateChecker(http = { HttpResponse.Ok(body) })
        val result = checker.checkApp("0.1.0")
        assertTrue(result is UpdateResult.Available)
        result as UpdateResult.Available
        assertEquals("0.2.0", result.latest)
        assertEquals("https://github.test/timiao/0.2.0", result.url)
        assertEquals("fixes", result.notes)
    }

    @Test
    fun `same or older release is up to date`() = runBlocking {
        val body = """{"tag_name":"v0.1.0"}"""
        val checker = UpdateChecker(http = { HttpResponse.Ok(body) })
        assertEquals(UpdateResult.UpToDate("0.1.0"), checker.checkApp("0.1.0"))
        assertEquals(UpdateResult.UpToDate("0.1.0"), checker.checkApp("0.2.0"))
    }

    @Test
    fun `kernel check compares official latest tag`() = runBlocking {
        val body = """{"tag_name":"v2.8.0"}"""
        val checker = UpdateChecker(http = { HttpResponse.Ok(body) })
        val result = checker.checkKernel("v2.7.1")
        assertTrue(result is UpdateResult.Available)
        assertEquals("2.8.0", (result as UpdateResult.Available).latest)
    }

    @Test
    fun `prerelease is ignored`() = runBlocking {
        val body = """{"tag_name":"v3.0.0-rc1","prerelease":true}"""
        val checker = UpdateChecker(http = { HttpResponse.Ok(body) })
        assertEquals(UpdateResult.Unknown, checker.checkKernel("v2.7.1"))
    }
}

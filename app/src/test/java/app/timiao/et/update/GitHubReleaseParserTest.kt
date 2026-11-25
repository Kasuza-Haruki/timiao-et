package app.timiao.et.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseParserTest {
    @Test
    fun `parses a release payload`() {
        val json = """
            {"url":"https://api.github.test/x","tag_name":"v2.8.0",
             "html_url":"https://github.test/release/v2.8.0",
             "name":"2.8.0","draft":false,"prerelease":false,
             "body":"Line one\nLine \"two\""}
        """.trimIndent()
        val release = GitHubReleaseParser.parse(json)!!
        assertEquals("v2.8.0", release.tagName)
        assertEquals("https://github.test/release/v2.8.0", release.htmlUrl)
        assertFalse(release.draft)
        assertFalse(release.prerelease)
        assertTrue(release.body!!.contains("Line one"))
        assertTrue(release.body.contains("\"two\""))
    }

    @Test
    fun `detects draft and prerelease`() {
        val release = GitHubReleaseParser.parse(
            """{"tag_name":"v1.0.0-rc1","draft":true,"prerelease":true}""",
        )!!
        assertTrue(release.draft)
        assertTrue(release.prerelease)
    }

    @Test
    fun `missing tag is not a release`() {
        assertNull(GitHubReleaseParser.parse("""{"message":"Not Found"}"""))
        assertNull(GitHubReleaseParser.parse("not json"))
    }

    @Test
    fun `escaped unicode is decoded`() {
        val json = """{"tag_name":"v1.0.0","body":"中文"}"""
        assertEquals("中文", GitHubReleaseParser.parse(json)!!.body)
    }
}

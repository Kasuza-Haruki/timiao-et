package app.timiao.et.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCodecTest {

    private val toml = """
        instance_name = "home"
        hostname = "nas-home"

        [network_identity]
        network_name = "timiao"
        network_secret = "s3cret"

        [[peer]]
        uri = "udp://public.easytier.top:11010"
    """.trimIndent()

    @Test
    fun roundTripKeepsToml() {
        val link = ShareCodec.encode(toml)
        val result = ShareCodec.decode(link)
        assertTrue(result is ShareCodec.DecodeResult.Success)
        result as ShareCodec.DecodeResult.Success
        assertEquals(toml, result.toml)
        assertTrue(result.hasSecret)
    }

    @Test
    fun roundTripHandlesUnicode() {
        val text = "instance_name = \"缇喵\"\n[network_identity]\nnetwork_name = \"家里\""
        val result = ShareCodec.decode(ShareCodec.encode(text))
        assertTrue(result is ShareCodec.DecodeResult.Success)
        assertEquals(text, (result as ShareCodec.DecodeResult.Success).toml)
    }

    @Test
    fun defaultShareDropsSecret() {
        val link = ShareCodec.buildLink(toml, includeSecret = false)
        val result = ShareCodec.decode(link) as ShareCodec.DecodeResult.Success
        assertFalse(result.hasSecret)
        assertFalse(result.toml.contains("s3cret"))
        assertTrue(result.toml.contains("network_name = \"timiao\""))
        assertTrue(result.toml.contains("uri = \"udp://public.easytier.top:11010\""))
    }

    @Test
    fun includeSecretKeepsSecret() {
        val link = ShareCodec.buildLink(toml, includeSecret = true)
        val result = ShareCodec.decode(link) as ShareCodec.DecodeResult.Success
        assertTrue(result.hasSecret)
        assertTrue(result.toml.contains("s3cret"))
    }

    @Test
    fun stripSecretsRemovesEveryKnownKey() {
        val text = """
            instance_name = "x"
            [network_identity]
            network_name = "n"
            network_secret = "a"
            [secure_mode]
            local_private_key = "b"
            [vpn_portal_config]
            wireguard_private_key = "c"
            [[managed_credentials]]
            credential_id = "id"
            credential_secret = "d"
        """.trimIndent()
        val stripped = ShareCodec.stripSecrets(text)
        assertFalse(stripped.contains("\"a\""))
        assertFalse(stripped.contains("\"b\""))
        assertFalse(stripped.contains("\"c\""))
        assertFalse(stripped.contains("\"d\""))
        assertTrue(stripped.contains("credential_id = \"id\""))
        assertFalse(ShareCodec.hasSecret(stripped))
    }

    @Test
    fun emptySecretIsNotASecret() {
        val text = "[network_identity]\nnetwork_name = \"n\"\nnetwork_secret = \"\""
        assertFalse(ShareCodec.hasSecret(text))
    }

    @Test
    fun rejectsWrongScheme() {
        val bad = ShareCodec.decode("https://example.com/import?v=1&d=abc")
        assertTrue(bad is ShareCodec.DecodeResult.Invalid)
    }

    @Test
    fun rejectsWrongVersion() {
        val link = ShareCodec.encode(toml).replace("v=1", "v=2")
        assertTrue(ShareCodec.decode(link) is ShareCodec.DecodeResult.Invalid)
    }

    @Test
    fun rejectsMissingData() {
        assertTrue(ShareCodec.decode("timiao://import?v=1") is ShareCodec.DecodeResult.Invalid)
        assertTrue(ShareCodec.decode("") is ShareCodec.DecodeResult.Invalid)
    }

    @Test
    fun rejectsInvalidBase64() {
        assertTrue(
            ShareCodec.decode("timiao://import?v=1&d=not*base64") is ShareCodec.DecodeResult.Invalid,
        )
    }

    @Test
    fun rejectsTruncatedPayload() {
        val link = ShareCodec.encode(toml)
        val cut = link.dropLast(8)
        assertTrue(ShareCodec.decode(cut) is ShareCodec.DecodeResult.Invalid)
    }

    @Test
    fun rejectsCompressionBomb() {
        val bomb = ShareCodec.encode("a".repeat(ShareCodec.MAX_INFLATED_BYTES + 10_000))
        val result = ShareCodec.decode(bomb)
        assertTrue(result is ShareCodec.DecodeResult.Invalid)
    }

    @Test
    fun rejectsOverlongLink() {
        val link = "timiao://import?v=1&d=" + "A".repeat(ShareCodec.MAX_LINK_CHARS + 1)
        assertTrue(ShareCodec.decode(link) is ShareCodec.DecodeResult.Invalid)
    }

    @Test
    fun parseImportTextAcceptsRawToml() {
        val result = ShareCodec.parseImportText(toml)
        assertTrue(result is ShareCodec.DecodeResult.Success)
        assertEquals(toml, (result as ShareCodec.DecodeResult.Success).toml)
    }

    @Test
    fun parseImportTextAcceptsLink() {
        val result = ShareCodec.parseImportText(ShareCodec.encode(toml))
        assertTrue(result is ShareCodec.DecodeResult.Success)
    }

    @Test
    fun parseImportTextRejectsGarbage() {
        assertTrue(ShareCodec.parseImportText("hello world") is ShareCodec.DecodeResult.Invalid)
        assertTrue(ShareCodec.parseImportText("   ") is ShareCodec.DecodeResult.Invalid)
    }
}

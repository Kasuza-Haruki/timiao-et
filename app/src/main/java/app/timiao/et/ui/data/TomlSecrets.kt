package app.timiao.et.ui.data

import app.timiao.et.core.NetworkConfig

/**
 * Placeholder shown in the TOML editor instead of a real secret.
 *
 * Trade-off: `<hidden>` is not valid Base64, so it cannot be mistaken for a
 * WireGuard key, and it is visually obvious. A user who really wants this
 * literal value as a secret cannot use it through the editor.
 */
const val HIDDEN_SECRET: String = "<hidden>"

/** Value-bearing secret keys that exist in [NetworkConfig] / EasyTier TOML. */
val SecretKeys: Set<String> = setOf(
    "network_secret",
    "credential_secret",
    "wireguard_private_key",
    "local_private_key",
)

private val Assignment = Regex("""^(\s*[A-Za-z0-9_]+\s*=\s*)"((?:[^"\\]|\\.)*)"(.*)$""")
private val TableHeader = Regex("""^\s*\[\[?\s*([A-Za-z0-9_.-]+)\s*]]?\s*$""")

private fun secretKeyOf(line: String): String? {
    val match = Assignment.matchEntire(line) ?: return null
    return match.groupValues[1].substringBefore('=').trim()
}

/**
 * Replaces every secret value with [HIDDEN_SECRET], keeping the document
 * byte-identical apart from the values. Layout and comments are preserved.
 */
fun maskTomlSecrets(toml: String): String = toml.lineSequence().joinToString("\n") { line ->
    if (secretKeyOf(line) !in SecretKeys) {
        line
    } else {
        val match = Assignment.matchEntire(line)!!
        "${match.groupValues[1]}\"$HIDDEN_SECRET\"${match.groupValues[3]}"
    }
}

/**
 * Puts the real values back into a masked editor text. Only placeholders are
 * replaced, so values the user typed are kept. The credential table is tracked
 * to match each `credential_secret` to its `credential_id`.
 */
fun revealTomlSecrets(toml: String, original: NetworkConfig): String {
    val credentialSecrets = original.managedCredentials.associate { it.credentialId to it.credentialSecret }
    var table = ""
    var credentialId: String? = null
    val out = ArrayList<String>()
    for (line in toml.split("\n")) {
        TableHeader.matchEntire(line)?.let {
            table = it.groupValues[1]
            credentialId = null
        }
        val match = Assignment.matchEntire(line)
        if (match == null) {
            out += line
            continue
        }
        val key = match.groupValues[1].substringBefore('=').trim()
        if (table == "managed_credentials" && key == "credential_id") {
            credentialId = match.groupValues[2]
        }
        if (match.groupValues[2] != HIDDEN_SECRET || key !in SecretKeys) {
            out += line
            continue
        }
        val real = when (key) {
            "network_secret" -> original.networkSecret
            "local_private_key" -> original.secureMode?.localPrivateKey
            "wireguard_private_key" -> original.vpnPortal?.wireguardPrivateKey
            "credential_secret" -> credentialId?.let(credentialSecrets::get)
            else -> null
        }
        out += if (real == null) {
            line
        } else {
            "${match.groupValues[1]}\"${escapeBasicString(real)}\"${match.groupValues[3]}"
        }
    }
    return out.joinToString("\n")
}

/**
 * Applies the placeholder rule after parsing:
 * - still `<hidden>` -> keep the previous real value
 * - changed by the user -> use the new value
 * - key/table deleted  -> treat as cleared
 */
fun restoreHiddenSecrets(parsed: NetworkConfig, original: NetworkConfig): NetworkConfig = parsed.copy(
    networkSecret = if (parsed.networkSecret == HIDDEN_SECRET) original.networkSecret else parsed.networkSecret,
    secureMode = parsed.secureMode?.let { mode ->
        if (mode.localPrivateKey == HIDDEN_SECRET) {
            mode.copy(localPrivateKey = original.secureMode?.localPrivateKey)
        } else {
            mode
        }
    },
    vpnPortal = parsed.vpnPortal?.let { portal ->
        if (portal.wireguardPrivateKey == HIDDEN_SECRET) {
            portal.copy(wireguardPrivateKey = original.vpnPortal?.wireguardPrivateKey)
        } else {
            portal
        }
    },
    managedCredentials = parsed.managedCredentials.map { credential ->
        if (credential.credentialSecret != HIDDEN_SECRET) {
            credential
        } else {
            val previous = original.managedCredentials.firstOrNull { it.credentialId == credential.credentialId }
            if (previous == null) credential else credential.copy(credentialSecret = previous.credentialSecret)
        }
    },
)

/** Escapes a value the same way the TOML writer does, for re-embedding. */
private fun escapeBasicString(value: String): String = buildString {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
    }
}

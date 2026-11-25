package app.timiao.et.core.diag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** One translated kernel event. */
internal data class DiagEvent(
    val type: String,
    val time: String?,
    val text: String,
    val fatal: Boolean = false,
    /** Original error text, used to pick advice. Not shown verbatim. */
    val errorText: String? = null,
)

internal data class InfoSnapshot(
    val events: List<DiagEvent>,
    val myNatType: String?,
)

/**
 * Parses `collectNetworkInfos` pbjson. Event entries are serde JSON strings like
 * `{"time":"...","event":{"ConnectError":["tcp://a:11010","V4","Connection refused"]}}`.
 */
internal object EventParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val clockPattern = Regex("""\d{2}:\d{2}:\d{2}""")

    fun parse(raw: String?, instanceName: String?): InfoSnapshot? {
        if (raw.isNullOrBlank()) return null
        val top = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        val root = (top["map"] as? JsonObject) ?: top
        val entry = (
            instanceName?.takeIf { it.isNotBlank() }?.let { root[it] }
                ?: root.values.firstOrNull()
            ) as? JsonObject ?: return null

        val events = (entry["events"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.mapNotNull(::parseEvent)
            .orEmpty()
        val nat = ((entry["my_node_info"] as? JsonObject)?.get("stun_info") as? JsonObject)
            ?.get("udp_nat_type")
            ?.let { display(it) }
        return InfoSnapshot(events, normalizeNat(nat))
    }

    private fun parseEvent(text: String): DiagEvent? {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        var payload = element
        var time: String? = null
        if (element is JsonObject && element.containsKey("event")) {
            time = (element["time"] as? JsonPrimitive)?.contentOrNull
            payload = element["event"] ?: return null
        }

        val name: String
        val args: List<JsonElement>
        val argObject: JsonObject?
        when (payload) {
            is JsonPrimitive -> {
                name = payload.contentOrNull ?: return null
                args = emptyList()
                argObject = null
            }
            is JsonObject -> {
                val entry = payload.entries.firstOrNull() ?: return null
                name = entry.key
                when (val value = entry.value) {
                    is JsonArray -> {
                        args = value
                        argObject = null
                    }
                    is JsonObject -> {
                        args = listOf(value)
                        argObject = value
                    }
                    else -> {
                        args = listOf(value)
                        argObject = null
                    }
                }
            }
            else -> return null
        }

        val translated = translate(name, args, argObject)
        return DiagEvent(
            type = name,
            time = time?.let { clockPattern.find(it)?.value ?: it },
            text = translated.first,
            fatal = translated.second,
            errorText = translated.third,
        )
    }

    /** (translated text, fatal, raw error text). */
    private fun translate(
        name: String,
        args: List<JsonElement>,
        obj: JsonObject?,
    ): Triple<String, Boolean, String?> {
        fun arg(index: Int): String = display(args.getOrNull(index))
        fun rawError(): String? = when (val element = args.lastOrNull()) {
            null, JsonNull -> null
            is JsonPrimitive -> element.contentOrNull
            else -> null
        }

        return when (name) {
            "Connecting" -> Triple("正在连接 ${arg(0)}", false, null)
            "ConnectError" -> {
                val dst = arg(0)
                val version = ipVersion(args.getOrNull(1))
                val error = rawError()
                val suffix = version?.let { "（$it）" }.orEmpty()
                Triple(
                    "连接 $dst 失败$suffix：${translateError(error.orEmpty())}",
                    false,
                    error,
                )
            }
            "ConnectionError" -> {
                val remote = arg(1)
                val error = rawError()
                Triple(
                    "与 $remote 的连接出错：${translateError(error.orEmpty())}",
                    false,
                    error,
                )
            }
            "ConnectionAccepted" -> Triple("已接受来自 ${arg(1)} 的连接", false, null)
            "PeerConnAdded" -> Triple("已建立节点连接", false, null)
            "PeerConnRemoved" -> Triple("节点连接已断开", false, null)
            "PeerAdded" -> Triple("发现节点 ${arg(0)}", false, null)
            "PeerRemoved" -> Triple("节点 ${arg(0)} 离开", false, null)
            "ListenerAdded" -> Triple("监听已启动：${arg(0)}", false, null)
            "ListenerAddFailed" -> {
                val error = rawError()
                Triple("监听启动失败：${arg(0)}（${translateError(error.orEmpty())}）", false, error)
            }
            "ListenerAcceptFailed" -> {
                val error = rawError()
                Triple("接受连接失败：${arg(0)}（${translateError(error.orEmpty())}）", false, error)
            }
            "ListenerPortMappingEstablished" -> {
                val local = obj?.get("local_listener")?.let(::display).orEmpty()
                val mapped = obj?.get("mapped_listener")?.let(::display).orEmpty()
                val backend = obj?.get("backend")?.let(::display).orEmpty()
                Triple("端口映射已建立：$local → $mapped（$backend）", false, null)
            }
            "TunDeviceReady" -> Triple("虚拟网卡已就绪：${arg(0)}", false, null)
            "TunDeviceError" -> Triple("虚拟网卡创建失败：${arg(0)}", true, arg(0))
            "DhcpIpv4Changed" -> Triple("DHCP IPv4：${arg(0)} → ${arg(1)}", false, null)
            "DhcpIpv6Changed" -> Triple("DHCP IPv6：${arg(0)} → ${arg(1)}", false, null)
            "DhcpIpv4Conflicted" -> Triple("DHCP IPv4 地址冲突：${arg(0)}", true, null)
            "DhcpIpv6Conflicted" -> Triple("DHCP IPv6 地址冲突：${arg(0)}", true, null)
            "PublicIpv6Changed" -> Triple("公网 IPv6 变化：${arg(0)} → ${arg(1)}", false, null)
            "PublicIpv6RoutesUpdated" -> Triple("公网 IPv6 路由已更新", false, null)
            "VpnPortalStarted" -> Triple("VPN 门户已启动：${arg(0)}", false, null)
            "VpnPortalClientConnected" -> Triple("VPN 门户接入客户端：${arg(1)}", false, null)
            "VpnPortalClientDisconnected" -> Triple("VPN 门户客户端断开：${arg(1)}", false, null)
            "PortForwardAdded" -> Triple("已添加端口转发", false, null)
            "ProxyCidrsUpdated" -> Triple("代理网段已更新", false, null)
            "ConfigPatched" -> Triple("配置已热更新", false, null)
            "CredentialChanged" -> Triple("网络凭据已更新", false, null)
            "UdpBroadcastRelayStartResult" -> {
                val error = obj?.get("error")?.let(::display)
                if (error.isNullOrBlank() || error == "无") {
                    Triple("UDP 广播中继已启动", false, null)
                } else {
                    Triple("UDP 广播中继启动失败：${translateError(error)}", false, error)
                }
            }
            else -> Triple(name, false, null)
        }
    }

    private fun ipVersion(element: JsonElement?): String? {
        val raw = element?.let(::display)?.lowercase(Locale.US) ?: return null
        return when {
            raw.contains("4") -> "IPv4"
            raw.contains("6") -> "IPv6"
            raw == "both" -> "IPv4/IPv6"
            else -> null
        }
    }

    /** Common tunnel error strings to actionable Chinese. */
    internal fun translateError(raw: String): String {
        val lower = raw.lowercase(Locale.US)
        return when {
            raw.isBlank() -> "未知错误"
            "refused" in lower -> "连接被拒绝（端口未开放或防火墙拦截）"
            "timed out" in lower || "timeout" in lower -> "连接超时（服务器不可达或丢包严重）"
            "no route" in lower -> "主机不可达（地址或路由错误）"
            "unreachable" in lower || "network is down" in lower -> "网络不可达（检查本机网络或 VPN 冲突）"
            "resolve" in lower || "dns" in lower || "lookup" in lower ||
                "name or service not known" in lower -> "域名解析失败（检查域名拼写或 DNS）"
            "handshake" in lower || "decrypt" in lower || "auth" in lower ->
                "握手或认证失败（网络名、密钥或公钥可能不一致）"
            "connection reset" in lower -> "连接被重置（服务器关闭或中间设备拦截）"
            "permission" in lower -> "权限被拒绝（检查防火墙或系统权限）"
            else -> raw
        }
    }

    private fun display(element: JsonElement?): String = when (element) {
        null, JsonNull -> "无"
        is JsonPrimitive -> element.contentOrNull ?: "无"
        is JsonArray -> element.joinToString(", ") { display(it) }
        is JsonObject -> element.inetText()
            ?: element.entries.joinToString(", ") { (key, value) -> "$key=${display(value)}" }.take(160)
    }

    private fun JsonObject.inetText(): String? {
        val table = this["address"] as? JsonObject ?: return null
        val raw = (table["addr"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
        val prefix = (this["network_length"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val ip = listOf(
            (raw shr 24) and 0xFF,
            (raw shr 16) and 0xFF,
            (raw shr 8) and 0xFF,
            raw and 0xFF,
        ).joinToString(".")
        return if (prefix != null) "$ip/$prefix" else ip
    }

    private fun normalizeNat(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it != "无" } ?: return null
        val numeric = value.toIntOrNull() ?: return value
        return when (numeric) {
            1 -> "OpenInternet"
            2 -> "NoPAT"
            3 -> "FullCone"
            4 -> "Restricted"
            5 -> "PortRestricted"
            6 -> "Symmetric"
            7 -> "SymUdpFirewall"
            8 -> "SymmetricEasyInc"
            9 -> "SymmetricEasyDec"
            else -> "Unknown"
        }
    }
}

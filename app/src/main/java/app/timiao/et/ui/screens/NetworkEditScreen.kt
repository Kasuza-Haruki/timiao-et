package app.timiao.et.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.PortForward
import app.timiao.et.core.ProxyNetwork
import app.timiao.et.core.SecureMode
import app.timiao.et.core.TomlCodec
import app.timiao.et.core.TomlParseException
import app.timiao.et.core.VpnPortal
import app.timiao.et.core.VpnPortalClient
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.ConfirmDialog
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.ExpandableHeader
import app.timiao.et.ui.components.GlassDropdownRow
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassSwitchRow
import app.timiao.et.ui.components.GlassTextField
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.SegmentedGlass
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.data.CompressionAlgos
import app.timiao.et.ui.data.DefaultListeners
import app.timiao.et.ui.data.DefaultProtocols
import app.timiao.et.ui.data.EncryptionAlgorithms
import app.timiao.et.ui.data.FlagGroups
import app.timiao.et.ui.data.PendingImport
import app.timiao.et.ui.data.PortForwardDraft
import app.timiao.et.ui.data.UiNetwork
import app.timiao.et.ui.data.WireGuardKeys
import app.timiao.et.ui.data.error
import app.timiao.et.ui.data.formatProxyNetwork
import app.timiao.et.ui.data.isValidCidr
import app.timiao.et.ui.data.isValidHostPort
import app.timiao.et.ui.data.joinLines
import app.timiao.et.ui.data.newNetwork
import app.timiao.et.ui.data.exportFileName
import app.timiao.et.ui.data.hasInstanceIdConflict
import app.timiao.et.ui.data.parseLines
import app.timiao.et.ui.data.parseProxyNetwork
import app.timiao.et.ui.data.readTomlUri
import app.timiao.et.ui.data.resolveImportId
import app.timiao.et.ui.data.staticIpEditable
import app.timiao.et.ui.data.toDraft
import app.timiao.et.ui.data.toUi
import app.timiao.et.ui.data.toConfig
import app.timiao.et.ui.data.toPortForward
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.util.copyToClipboard
import androidx.compose.foundation.shape.RoundedCornerShape
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EditMode { Form, Toml }

/** Multi-line editors keep raw text, so an empty line is not lost while typing. */
private data class Texts(
    val servers: String = "",
    val listeners: String = "",
    val mappedListeners: String = "",
    val stunServers: String = "",
    val routes: String = "",
    val whitelist: String = "",
    val exitNodes: String = "",
) {
    companion object {
        fun of(network: UiNetwork): Texts = Texts(
            servers = joinLines(network.servers),
            listeners = joinLines(network.listeners),
            mappedListeners = joinLines(network.mappedListeners),
            stunServers = joinLines(network.stunServers),
            routes = joinLines(network.routes),
            whitelist = joinLines(network.relayNetworkWhitelist),
            exitNodes = joinLines(network.exitNodes),
        )
    }
}

private fun UiNetwork.withTexts(texts: Texts): UiNetwork = copy(    servers = parseLines(texts.servers),
    listeners = parseLines(texts.listeners),
    mappedListeners = parseLines(texts.mappedListeners),
    stunServers = parseLines(texts.stunServers),
    routes = parseLines(texts.routes),
    relayNetworkWhitelist = parseLines(texts.whitelist),
    exitNodes = parseLines(texts.exitNodes),
)

private fun UiNetwork.withoutBase(): UiNetwork = copy(base = null)

private val UrlPattern = Regex("""^[a-z][a-z0-9+.-]*://\S+$""")
private val ListenerPattern = Regex("""^(tcp|udp|wg|ws|wss|quic|faketcp|http|https)://\S+:\d+$""")
private val SocksPattern = Regex("""^socks5://\S+:\d+$""")
private val ClientNamePattern = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$""")

private fun firstBadUrl(lines: List<String>): Boolean =
    lines.any { it.isNotBlank() && !UrlPattern.matches(it) }

private fun firstBadListener(lines: List<String>): Boolean =
    lines.any { it.isNotBlank() && !ListenerPattern.matches(it) }

@Composable
fun NetworkEditScreen(
    vm: AppViewModel,
    networkId: String,
    openAction: String,
    onBack: () -> Unit,
    perAppSlot: @Composable () -> Unit = {},
) {
    val networks by vm.networks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val g = LocalGlassColors.current

    var form by remember(networkId) { mutableStateOf<UiNetwork?>(null) }
    var initialForm by remember(networkId) { mutableStateOf<UiNetwork?>(null) }
    var texts by remember(networkId) { mutableStateOf(Texts()) }
    var initialTexts by remember(networkId) { mutableStateOf(Texts()) }

    LaunchedEffect(networkId, networks) {
        if (form == null) {
            val pending = PendingImport.config
            if (pending != null) {
                PendingImport.config = null
                val targetId = pending.instanceId ?: UUID.randomUUID().toString()
                val ui = pending.toUi().copy(id = targetId)
                form = ui
                texts = Texts.of(ui)
                Toast.makeText(context, "已从文件导入，检查后点保存", Toast.LENGTH_SHORT).show()
                return@LaunchedEffect
            }
            val loaded = if (networkId == "new") {
                newNetwork()
            } else {
                networks.firstOrNull { it.id == networkId }
            }
            if (loaded != null) {
                form = loaded
                initialForm = loaded
                texts = Texts.of(loaded)
                initialTexts = texts
            }
        }
    }

    val current = form
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载中…", color = g.muted)
        }
        return
    }

    var mode by rememberSaveable(networkId) { mutableStateOf(EditMode.Form) }
    var tomlText by rememberSaveable(networkId) { mutableStateOf("") }
    var tomlError by rememberSaveable(networkId) { mutableStateOf<String?>(null) }
    var tomlBaseline by rememberSaveable(networkId) { mutableStateOf("") }
    var saveAttempted by rememberSaveable(networkId) { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(openAction == "import") }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var importText by rememberSaveable { mutableStateOf("") }
    var openGroups by rememberSaveable { mutableStateOf(listOf("基础", "连接")) }
    var showSecurePrivate by rememberSaveable { mutableStateOf(false) }
    var showPortalPrivate by rememberSaveable { mutableStateOf(false) }
    var clientConfig by remember { mutableStateOf<String?>(null) }
    var importError by rememberSaveable { mutableStateOf<String?>(null) }
    var exportWithoutSecret by rememberSaveable { mutableStateOf(false) }
    var pendingExportText by remember { mutableStateOf("") }
    var pendingExportName by remember { mutableStateOf("easytier.toml") }
    var conflictImport by remember { mutableStateOf<NetworkConfig?>(null) }

    fun toggleGroup(name: String) {
        openGroups = if (openGroups.contains(name)) openGroups - name else openGroups + name
    }

    fun currentUi(): UiNetwork = (form ?: current).withTexts(texts)

    fun applyParsed(parsed: NetworkConfig) {
        val ui = parsed.toUi().copy(id = current.id)
        form = ui
        texts = Texts.of(ui)
    }

    fun applyImported(parsed: NetworkConfig, saveAsNew: Boolean) {
        val targetId = when {
            saveAsNew -> if (networkId == "new") current.id else UUID.randomUUID().toString()
            parsed.instanceId.isNullOrBlank() -> current.id
            else -> parsed.instanceId!!
        }
        val ui = parsed.toUi().copy(id = targetId)
        form = ui
        texts = Texts.of(ui)
        tomlError = null
        importError = null
    }

    fun beginImport(parsed: NetworkConfig) {
        val otherIds = networks.map { it.id }.filter { it != current.id }.toSet()
        if (hasInstanceIdConflict(parsed.instanceId, otherIds)) {
            showImport = false
            conflictImport = parsed
        } else {
            applyImported(parsed, saveAsNew = false)
            showImport = false
            importText = ""
            Toast.makeText(context, "已导入，检查后点保存", Toast.LENGTH_SHORT).show()
        }
    }

    fun buildExportText(excludeSecret: Boolean): String {
        if (mode == EditMode.Toml) {
            val parsed = runCatching { TomlCodec.importToml(tomlText) }.getOrNull() ?: return tomlText
            return TomlCodec.exportToml(
                if (excludeSecret) parsed.copy(networkSecret = "") else parsed,
            )
        }
        val ui = currentUi()
        return vm.exportToml(if (excludeSecret) ui.copy(secret = "") else ui)
    }

    val openTomlFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                readTomlUri(context, uri)
                    .onSuccess { text ->
                        importText = text
                        importError = runCatching { TomlCodec.importToml(text) }.exceptionOrNull()?.message
                        showImport = true
                    }
                    .onFailure { error ->
                        importText = ""
                        importError = error.message ?: "读取文件失败"
                        showImport = true
                    }
            }
        }
    }

    val saveTomlFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/toml"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("无法写入文件")
                        stream.use { it.write(pendingExportText.toByteArray(Charsets.UTF_8)) }
                    }
                }
                Toast.makeText(
                    context,
                    if (result.isSuccess) "已保存 ${pendingExportName}" else "保存失败：${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun enterTomlMode() {
        val ui = currentUi()
        if (ui.name.isBlank()) {
            saveAttempted = true
            Toast.makeText(context, "网络名称不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        tomlText = vm.exportToml(ui)
        tomlBaseline = tomlText
        tomlError = null
        mode = EditMode.Toml
    }

    fun leaveTomlMode() {
        val parsed = try {
            TomlCodec.importToml(tomlText)
        } catch (error: TomlParseException) {
            tomlError = error.message
            return
        } catch (error: Exception) {
            tomlError = "解析失败：${error.message}"
            return
        }
        applyParsed(parsed)
        tomlError = null
        mode = EditMode.Form
    }

    fun performSave() {
        if (mode == EditMode.Toml) {
            val parsed = try {
                TomlCodec.importToml(tomlText)
            } catch (error: TomlParseException) {
                tomlError = error.message
                return
            } catch (error: Exception) {
                tomlError = "解析失败：${error.message}"
                return
            }
            if (parsed.networkName.isBlank()) {
                tomlError = "网络名称不能为空"
                return
            }
            val ui = parsed.toUi().copy(id = current.id)
            vm.saveNetwork(ui)
            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            onBack()
            return
        }
        saveAttempted = true
        val ui = currentUi()
        val errors = validationErrors(ui)
        if (errors.isNotEmpty()) {
            Toast.makeText(context, "有 ${errors.size} 处输入无效，请检查提示", Toast.LENGTH_SHORT).show()
            return
        }
        vm.saveNetwork(ui)
        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
        onBack()
    }

    val dirty = current.withoutBase() != initialForm?.withoutBase() || texts != initialTexts ||
        (mode == EditMode.Toml && tomlText != tomlBaseline)
    val saveEnabled = dirty || networkId == "new"

    fun requestBack() {
        if (dirty) showDiscard = true else onBack()
    }

    BackHandler(enabled = dirty) { showDiscard = true }

    LaunchedEffect(openAction) {
        if (openAction == "scan") {
            Toast.makeText(context, "扫码导入入口已预留，等 ML Kit 接入后可用", Toast.LENGTH_SHORT).show()
        }
    }

    val nameError = if (form!!.name.isBlank()) "网络名称不能为空" else null
    val ipv4Error = form!!.ipv4.takeIf { it.isNotBlank() && !isValidCidr(it) }?.let { "格式应为 10.126.126.5/24" }
    val ipv6Error = form!!.ipv6.takeIf { it.isNotBlank() && !isValidCidr(it) }?.let { "格式应为 fd00::5/64" }
    val mtuError = form!!.mtu.takeIf { it.isNotBlank() }?.let {
        val value = it.toIntOrNull()
        if (value == null || value !in 400..1380) "MTU 范围 400-1380" else null
    }
    val recvLimitError = form!!.instanceRecvBpsLimit.takeIf { it.isNotBlank() && it.toLongOrNull() == null }
        ?.let { "请输入整数（字节/秒）" }
    val relayLimitError = form!!.foreignRelayBpsLimit.takeIf { it.isNotBlank() && it.toLongOrNull() == null }
        ?.let { "请输入整数（字节/秒）" }
    val threadCountError = form!!.multiThreadCount.takeIf { it.isNotBlank() }?.let {
        val value = it.toIntOrNull()
        if (value == null || value < 1) "请输入正整数" else null
    }
    val socketMarkError = form!!.socketMark.takeIf { it.isNotBlank() && it.toIntOrNull() == null }
        ?.let { "请输入整数" }
    val socksError = form!!.socks5.takeIf { it.isNotBlank() && !SocksPattern.matches(it) }
        ?.let { "格式应为 socks5://0.0.0.0:1080" }
    val serversError = if (firstBadUrl(parseLines(texts.servers))) "地址需带协议，例如 udp://host:11010" else null
    val listenersError = if (firstBadListener(parseLines(texts.listeners))) "监听器格式：udp://0.0.0.0:11010" else null
    val mappedError = if (firstBadListener(parseLines(texts.mappedListeners))) "格式：tcp://公网地址:端口" else null
    val portal = form!!.vpnPortal
    val portalListenError = if (portal != null && !isValidHostPort(portal.wireguardListen)) {
        "格式应为 host:port，例如 0.0.0.0:22022"
    } else {
        null
    }
    val secureKeyError = form!!.secureMode?.localPrivateKey?.takeIf { it.isNotBlank() }?.let {
        if (base64Size(it) != 32) "私钥需为 32 字节 Base64" else null
    }
    val portalKeyError = portal?.wireguardPrivateKey?.takeIf { it.isNotBlank() }?.let {
        if (base64Size(it) != 32) "私钥需为 32 字节 Base64" else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = if (networkId == "new") "新建网络" else current.name.ifBlank { "编辑网络" },
                subtitle = if (dirty) "有未保存的修改" else "改动在点保存后生效",
                onBack = ::requestBack,
                action = {
                    Box(Modifier.width(88.dp)) {
                        PillButton(
                            "保存",
                            onClick = ::performSave,
                            enabled = saveEnabled,
                            modifier = Modifier.fillMaxWidth().alpha(if (saveEnabled) 1f else 0.5f),
                        )
                    }
                },
            )
        }

        item {
            SegmentedGlass(
                options = listOf("表单", "TOML"),
                selected = if (mode == EditMode.Form) 0 else 1,
                onSelect = { index ->
                    when {
                        index == 0 && mode == EditMode.Toml -> leaveTomlMode()
                        index == 1 && mode == EditMode.Form -> enterTomlMode()
                    }
                },
            )
        }

        if (mode == EditMode.Toml) {
            item {
                GlassTextField(
                    value = tomlText,
                    onValueChange = { tomlText = it; tomlError = null },
                    label = "TOML 原文（保存时以这里为准）",
                    placeholder = "[network_identity]\nnetwork_name = \"home-mesh\"",
                    singleLine = false,
                    minLines = 18,
                    maxLines = 24,
                    mono = true,
                )
            }
            item {
                if (tomlError != null) {
                    Text(
                        text = tomlError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                } else {
                    Text(
                        text = "切回表单会先解析；解析失败时保留原文并显示行号。",
                        color = g.muted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
            item {
                PillButton(
                    "解析并切回表单",
                    onClick = ::leaveTomlMode,
                    secondary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            item { SectionLabel("基础") }
            item {
                Field(
                    label = "网络名称",
                    value = form!!.name,
                    onChange = { form = form!!.copy(name = it) },
                    placeholder = "home-mesh",
                    error = nameError,
                    focus = focus,
                )
            }
            item {
                Field(
                    label = "网络密钥",
                    value = form!!.secret,
                    onChange = { form = form!!.copy(secret = it) },
                    placeholder = "留空表示无密钥",
                    mono = true,
                    visualTransformation = PasswordVisualTransformation(),
                    focus = focus,
                )
            }
            item {
                Field(
                    label = "主机名",
                    value = form!!.hostname,
                    onChange = { form = form!!.copy(hostname = it) },
                    placeholder = "留空使用系统主机名",
                    focus = focus,
                )
            }
            item {
                Field(
                    label = "服务器（每行一个）",
                    value = texts.servers,
                    onChange = { texts = texts.copy(servers = it) },
                    placeholder = "tcp://public.easytier.top:11010",
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    mono = true,
                    error = serversError,
                    keyboardType = KeyboardType.Uri,
                    focus = focus,
                )
            }
            item {
                GlassSwitchRow(
                    title = "自动分配 IP（DHCP）",
                    subtitle = "关闭后手动填写静态地址",
                    checked = form!!.dhcp,
                    onCheckedChange = { form = form!!.copy(dhcp = it) },
                )
            }
            item {
                if (!staticIpEditable(form!!.dhcp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        Text("静态 IPv4 / 前缀", style = MaterialTheme.typography.labelSmall, color = g.muted)
                        Text(
                            "DHCP 已开启，地址由网络自动分配",
                            style = MaterialTheme.typography.bodyMedium,
                            color = g.muted,
                        )
                    }
                } else {
                    Field(
                        label = "静态 IPv4 / 前缀",
                        value = form!!.ipv4,
                        onChange = { form = form!!.copy(ipv4 = it) },
                        placeholder = "10.126.126.5/24",
                        mono = true,
                        error = ipv4Error,
                        keyboardType = KeyboardType.Uri,
                        focus = focus,
                    )
                }
            }
            item {
                Field(
                    label = "静态 IPv6 / 前缀（可留空）",
                    value = form!!.ipv6,
                    onChange = { form = form!!.copy(ipv6 = it) },
                    placeholder = "fd00::5/64",
                    mono = true,
                    error = ipv6Error,
                    keyboardType = KeyboardType.Uri,
                    focus = focus,
                )
            }

            item { SectionLabel("高级") }
            item { perAppSlot() }

            // 身份与安全
            item {
                ExpandableHeader(
                    title = "身份与安全",
                    subtitle = "安全模式、Socks5、IPv6 公网",
                    expanded = openGroups.contains("身份与安全"),
                    onToggle = { toggleGroup("身份与安全") },
                )
            }
            if (openGroups.contains("身份与安全")) {
                item {
                    GlassSwitchRow(
                        title = "安全模式",
                        subtitle = "节点间使用 Noise 加密握手与身份认证",
                        checked = form!!.secureMode?.enabled == true,
                        onCheckedChange = { enabled ->
                            form = form!!.copy(
                                secureMode = if (enabled) {
                                    SecureMode(enabled = true, localPrivateKey = WireGuardKeys.generatePrivateKey())
                                } else {
                                    null
                                },
                            )
                        },
                    )
                }
                if (form!!.secureMode != null) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Field(
                                label = "本机私钥（默认隐藏）",
                                value = form!!.secureMode?.localPrivateKey.orEmpty(),
                                onChange = { key ->
                                    form = form!!.copy(
                                        secureMode = form!!.secureMode?.copy(
                                            localPrivateKey = key.ifBlank { null },
                                            localPublicKey = key.takeIf { it.isNotBlank() }?.let {
                                                WireGuardKeys.publicKey(it)
                                            },
                                        ),
                                    )
                                },
                                placeholder = "Base64 32 字节",
                                mono = true,
                                visualTransformation = if (showSecurePrivate) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                error = secureKeyError,
                                focus = focus,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            PillButton(
                                if (showSecurePrivate) "隐藏" else "显示",
                                onClick = { showSecurePrivate = !showSecurePrivate },
                                secondary = true,
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(
                                "生成密钥",
                                onClick = {
                                    val key = WireGuardKeys.generatePrivateKey()
                                    form = form!!.copy(
                                        secureMode = form!!.secureMode?.copy(
                                            localPrivateKey = key,
                                            localPublicKey = WireGuardKeys.publicKey(key),
                                        ),
                                    )
                                },
                                secondary = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                item {
                    Field(
                        label = "Socks5 服务器（留空关闭）",
                        value = form!!.socks5,
                        onChange = { form = form!!.copy(socks5 = it) },
                        placeholder = "socks5://0.0.0.0:1080",
                        mono = true,
                        error = socksError,
                        keyboardType = KeyboardType.Uri,
                        focus = focus,
                    )
                }
                item {
                    GlassSwitchRow(
                        title = "自动获取公网 IPv6",
                        subtitle = "从共享 IPv6 子网的对等节点获取地址",
                        checked = form!!.ipv6PublicAddrAuto,
                        onCheckedChange = { form = form!!.copy(ipv6PublicAddrAuto = it) },
                    )
                }
            }

            // 网络与路由
            item {
                ExpandableHeader(
                    title = "网络与路由",
                    subtitle = "监听器、映射、出口节点、自定义路由、白名单",
                    expanded = openGroups.contains("网络与路由"),
                    onToggle = { toggleGroup("网络与路由") },
                )
            }
            if (openGroups.contains("网络与路由")) {
                item {
                    Field(
                        label = "监听器（每行一个）",
                        value = texts.listeners,
                        onChange = { texts = texts.copy(listeners = it) },
                        placeholder = "udp://0.0.0.0:11010",
                        singleLine = false,
                        minLines = 1,
                        maxLines = 4,
                        mono = true,
                        error = listenersError,
                        keyboardType = KeyboardType.Uri,
                        focus = focus,
                    )
                }
                item {
                    Field(
                        label = "监听映射（每行一个公网地址）",
                        value = texts.mappedListeners,
                        onChange = { texts = texts.copy(mappedListeners = it) },
                        placeholder = "tcp://123.123.123.123:11223",
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        mono = true,
                        error = mappedError,
                        keyboardType = KeyboardType.Uri,
                        focus = focus,
                    )
                }
                item {
                    Field(
                        label = "出口节点（每行一个虚拟 IP，按顺序优先）",
                        value = texts.exitNodes,
                        onChange = { texts = texts.copy(exitNodes = it) },
                        placeholder = "10.126.126.1",
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        mono = true,
                        keyboardType = KeyboardType.Uri,
                        focus = focus,
                    )
                }
                item {
                    GlassSwitchRow(
                        title = "自定义路由",
                        subtitle = "手动分配路由 CIDR，将禁用子网代理传播",
                        checked = form!!.manualRoutesEnabled,
                        onCheckedChange = { form = form!!.copy(manualRoutesEnabled = it) },
                    )
                }
                if (form!!.manualRoutesEnabled) {
                    item {
                        Field(
                            label = "路由 CIDR（每行一个）",
                            value = texts.routes,
                            onChange = { texts = texts.copy(routes = it) },
                            placeholder = "192.168.0.0/16",
                            singleLine = false,
                            minLines = 1,
                            maxLines = 4,
                            mono = true,
                            error = parseLines(texts.routes).firstOrNull { !isValidCidr(it) }?.let {
                                "无效 CIDR：$it"
                            },
                            keyboardType = KeyboardType.Uri,
                            focus = focus,
                        )
                    }
                }
                item {
                    GlassSwitchRow(
                        title = "中继网络白名单",
                        subtitle = "关闭时转发所有网络；开启后为空表示禁止转发",
                        checked = form!!.relayNetworkWhitelistEnabled,
                        onCheckedChange = { form = form!!.copy(relayNetworkWhitelistEnabled = it) },
                    )
                }
                if (form!!.relayNetworkWhitelistEnabled) {
                    item {
                        Field(
                            label = "白名单网络名（每行一个，支持 * 后缀）",
                            value = texts.whitelist,
                            onChange = { texts = texts.copy(whitelist = it) },
                            placeholder = "net1\ndef*",
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            mono = true,
                            focus = focus,
                        )
                    }
                }
                item {
                    GlassSwitchRow(
                        title = "禁用 UPnP",
                        subtitle = "不自动映射监听端口",
                        checked = form!!.flags["disable_upnp"] ?: false,
                        onCheckedChange = { form = form!!.copy(flags = form!!.flags + ("disable_upnp" to it)) },
                    )
                }
            }

            // STUN
            item {
                ExpandableHeader(
                    title = "STUN 服务器",
                    subtitle = "自定义 NAT 探测服务器",
                    expanded = openGroups.contains("STUN"),
                    onToggle = { toggleGroup("STUN") },
                )
            }
            if (openGroups.contains("STUN")) {
                item {
                    Field(
                        label = "STUN 服务器（每行一个）",
                        value = texts.stunServers,
                        onChange = { texts = texts.copy(stunServers = it) },
                        placeholder = "stun.example.com:3478",
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        mono = true,
                        keyboardType = KeyboardType.Uri,
                        focus = focus,
                    )
                }
            }

            // 子网代理
            item {
                ExpandableHeader(
                    title = "子网代理",
                    subtitle = "CIDR 与可选映射 CIDR",
                    expanded = openGroups.contains("子网代理"),
                    onToggle = { toggleGroup("子网代理") },
                )
            }
            if (openGroups.contains("子网代理")) {
                items(form!!.proxyNetworks.size) { index ->
                    val proxy = form!!.proxyNetworks[index]
                    val cidrError = if (!isValidCidr(proxy.cidr)) "无效 CIDR" else null
                    val mappedError = proxy.mappedCidr?.takeIf { !isValidCidr(it) }?.let { "无效 CIDR" }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Field(
                            label = "代理 CIDR",
                            value = proxy.cidr,
                            onChange = { value ->
                                form = form!!.copy(
                                    proxyNetworks = form!!.proxyNetworks.toMutableList().also {
                                        it[index] = proxy.copy(cidr = value)
                                    },
                                )
                            },
                            placeholder = "192.168.10.0/24",
                            mono = true,
                            error = cidrError,
                            keyboardType = KeyboardType.Uri,
                            focus = focus,
                        )
                        Field(
                            label = "映射 CIDR（可留空）",
                            value = proxy.mappedCidr.orEmpty(),
                            onChange = { value ->
                                form = form!!.copy(
                                    proxyNetworks = form!!.proxyNetworks.toMutableList().also {
                                        it[index] = proxy.copy(mappedCidr = value.ifBlank { null })
                                    },
                                )
                            },
                            placeholder = "10.9.0.0/24",
                            mono = true,
                            error = mappedError,
                            keyboardType = KeyboardType.Uri,
                            focus = focus,
                        )
                        TextButton(onClick = {
                            form = form!!.copy(
                                proxyNetworks = form!!.proxyNetworks.filterIndexed { i, _ -> i != index },
                            )
                        }) { Text("删除此条") }
                    }
                }
                item {
                    PillButton(
                        "添加子网代理",
                        onClick = {
                            form = form!!.copy(proxyNetworks = form!!.proxyNetworks + ProxyNetwork(cidr = ""))
                        },
                        secondary = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 端口转发
            item {
                ExpandableHeader(
                    title = "端口转发",
                    subtitle = "协议 + 绑定地址 + 目标地址",
                    expanded = openGroups.contains("端口转发"),
                    onToggle = { toggleGroup("端口转发") },
                )
            }
            if (openGroups.contains("端口转发")) {
                items(form!!.portForwards.size) { index ->
                    val forward = form!!.portForwards[index].toDraft()
                    val error = if (saveAttempted) forward.error() else forward.error()?.takeIf {
                        forward.bindIp.isNotBlank() || forward.dstIp.isNotBlank()
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassDropdownRow(
                            label = "协议",
                            current = forward.proto,
                            options = listOf("tcp", "udp"),
                            optionLabel = { it },
                            onSelect = { proto ->
                                form = form!!.copy(
                                    portForwards = form!!.portForwards.toMutableList().also {
                                        it[index] = forward.copy(proto = proto).toPortForward()
                                    },
                                )
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(
                                label = "绑定地址",
                                value = forward.bindIp,
                                onChange = { value ->
                                    form = form!!.copy(
                                        portForwards = form!!.portForwards.toMutableList().also {
                                            it[index] = forward.copy(bindIp = value).toPortForward()
                                        },
                                    )
                                },
                                placeholder = "0.0.0.0",
                                mono = true,
                                keyboardType = KeyboardType.Uri,
                                focus = focus,
                                modifier = Modifier.weight(1.6f),
                            )
                            Field(
                                label = "端口",
                                value = forward.bindPort,
                                onChange = { value ->
                                    form = form!!.copy(
                                        portForwards = form!!.portForwards.toMutableList().also {
                                            it[index] = forward.copy(bindPort = value).toPortForward()
                                        },
                                    )
                                },
                                placeholder = "8080",
                                mono = true,
                                keyboardType = KeyboardType.Number,
                                focus = focus,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(
                                label = "目标地址",
                                value = forward.dstIp,
                                onChange = { value ->
                                    form = form!!.copy(
                                        portForwards = form!!.portForwards.toMutableList().also {
                                            it[index] = forward.copy(dstIp = value).toPortForward()
                                        },
                                    )
                                },
                                placeholder = "10.126.126.1",
                                mono = true,
                                keyboardType = KeyboardType.Uri,
                                focus = focus,
                                modifier = Modifier.weight(1.6f),
                            )
                            Field(
                                label = "端口",
                                value = forward.dstPort,
                                onChange = { value ->
                                    form = form!!.copy(
                                        portForwards = form!!.portForwards.toMutableList().also {
                                            it[index] = forward.copy(dstPort = value).toPortForward()
                                        },
                                    )
                                },
                                placeholder = "80",
                                mono = true,
                                keyboardType = KeyboardType.Number,
                                focus = focus,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (error != null) InlineError(error)
                        TextButton(onClick = {
                            form = form!!.copy(
                                portForwards = form!!.portForwards.filterIndexed { i, _ -> i != index },
                            )
                        }) { Text("删除此条") }
                    }
                }
                item {
                    PillButton(
                        "添加端口转发",
                        onClick = {
                            form = form!!.copy(portForwards = form!!.portForwards + PortForward("", "", "tcp"))
                        },
                        secondary = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // VPN portal
            item {
                ExpandableHeader(
                    title = "VPN portal（WireGuard）",
                    subtitle = "监听地址、私钥、客户端配置",
                    expanded = openGroups.contains("VPN"),
                    onToggle = { toggleGroup("VPN") },
                )
            }
            if (openGroups.contains("VPN")) {
                item {
                    GlassSwitchRow(
                        title = "启用 WireGuard",
                        subtitle = "启动节点后，客户端可用生成的配置连接",
                        checked = form!!.vpnPortal != null,
                        onCheckedChange = { enabled ->
                            form = form!!.copy(
                                vpnPortal = if (enabled) {
                                    VpnPortal(
                                        wireguardListen = "0.0.0.0:22022",
                                        enabled = true,
                                        wireguardPrivateKey = WireGuardKeys.generatePrivateKey(),
                                    )
                                } else {
                                    null
                                },
                            )
                        },
                    )
                }
                form!!.vpnPortal?.let { portal ->
                    item {
                        Field(
                            label = "监听地址 host:port",
                            value = portal.wireguardListen,
                            onChange = {
                                form = form!!.copy(vpnPortal = portal.copy(wireguardListen = it))
                            },
                            placeholder = "0.0.0.0:22022",
                            mono = true,
                            error = portalListenError,
                            keyboardType = KeyboardType.Uri,
                            focus = focus,
                        )
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Field(
                                label = "服务端私钥（默认隐藏）",
                                value = portal.wireguardPrivateKey.orEmpty(),
                                onChange = {
                                    form = form!!.copy(
                                        vpnPortal = portal.copy(wireguardPrivateKey = it.ifBlank { null }),
                                    )
                                },
                                placeholder = "Base64 32 字节",
                                mono = true,
                                visualTransformation = if (showPortalPrivate) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                error = portalKeyError,
                                focus = focus,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            PillButton(
                                if (showPortalPrivate) "隐藏" else "显示",
                                onClick = { showPortalPrivate = !showPortalPrivate },
                                secondary = true,
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(
                                "生成私钥",
                                onClick = {
                                    form = form!!.copy(
                                        vpnPortal = portal.copy(
                                            wireguardPrivateKey = WireGuardKeys.generatePrivateKey(),
                                        ),
                                    )
                                },
                                secondary = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (!WireGuardKeys.canDerivePublicKey()) {
                        item {
                            Text(
                                text = "当前系统无法派生 X25519 公钥，请在桌面端生成客户端配置。",
                                style = MaterialTheme.typography.labelSmall,
                                color = g.warn,
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                    }
                    items(portal.clients.size) { index ->
                        val client = portal.clients[index]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Field(
                                label = "设备名称",
                                value = client.name,
                                onChange = { value ->
                                    form = form!!.copy(
                                        vpnPortal = portal.copy(
                                            clients = portal.clients.toMutableList().also {
                                                it[index] = client.copy(name = value)
                                            },
                                        ),
                                    )
                                },
                                placeholder = "alice-phone",
                                focus = focus,
                            )
                            val nameError = when {
                                !ClientNamePattern.matches(client.name) -> "限 1-63 个字母、数字、连字符"
                                portal.clients.count { it.name == client.name } > 1 -> "名称不能重复"
                                else -> null
                            }
                            InlineError(nameError)
                            Field(
                                label = "虚拟 IP / 前缀",
                                value = client.virtualIp,
                                onChange = { value ->
                                    form = form!!.copy(
                                        vpnPortal = portal.copy(
                                            clients = portal.clients.toMutableList().also {
                                                it[index] = client.copy(virtualIp = value)
                                            },
                                        ),
                                    )
                                },
                                placeholder = "10.126.126.10/24",
                                mono = true,
                                error = if (!isValidCidr(client.virtualIp)) "请输入有效 CIDR" else null,
                                keyboardType = KeyboardType.Uri,
                                focus = focus,
                            )
                            Field(
                                label = "ACL 组（空格分隔，可留空）",
                                value = client.groups.joinToString(" "),
                                onChange = { value ->
                                    form = form!!.copy(
                                        vpnPortal = portal.copy(
                                            clients = portal.clients.toMutableList().also {
                                                it[index] = client.copy(
                                                    groups = value.split(' ', ',').filter { g -> g.isNotBlank() },
                                                )
                                            },
                                        ),
                                    )
                                },
                                placeholder = "staff",
                                mono = true,
                                focus = focus,
                            )
                            TextButton(onClick = {
                                form = form!!.copy(
                                    vpnPortal = portal.copy(
                                        clients = portal.clients.filterIndexed { i, _ -> i != index },
                                    ),
                                )
                            }) { Text("删除此设备") }
                            TextButton(onClick = {
                                val key = portal.wireguardPrivateKey
                                clientConfig = if (key.isNullOrBlank()) {
                                    "请先生成服务端私钥"
                                } else {
                                    WireGuardKeys.clientConfig(
                                        masterPrivateKeyBase64 = key,
                                        listen = portal.wireguardListen,
                                        clientName = client.name,
                                        virtualIpCidr = client.virtualIp,
                                        proxyCidrs = form!!.proxyNetworks.map { formatProxyNetwork(it) },
                                    ) ?: "无法生成：请检查监听地址、设备名称，且系统需支持 X25519"
                                }
                            }) { Text("生成客户端配置") }
                        }
                    }
                    item {
                        PillButton(
                            "添加设备",
                            onClick = {
                                form = form!!.copy(
                                    vpnPortal = portal.copy(
                                        clients = portal.clients + VpnPortalClient(
                                            name = "device-${portal.clients.size + 1}",
                                            virtualIp = "10.126.126.${10 + portal.clients.size}/24",
                                        ),
                                    ),
                                )
                            },
                            secondary = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Scalar flags
            item {
                ExpandableHeader(
                    title = "传输与限速",
                    subtitle = "MTU、协议、算法、线程、限速",
                    expanded = openGroups.contains("传输"),
                    onToggle = { toggleGroup("传输") },
                )
            }
            if (openGroups.contains("传输")) {
                item {
                    Field(
                        label = "MTU（400-1380，留空默认 1380）",
                        value = form!!.mtu,
                        onChange = { form = form!!.copy(mtu = it) },
                        placeholder = "1380",
                        mono = true,
                        error = mtuError,
                        keyboardType = KeyboardType.Number,
                        focus = focus,
                    )
                }
                item {
                    GlassDropdownRow(
                        label = "默认传输协议",
                        current = form!!.defaultProtocol,
                        options = DefaultProtocols,
                        optionLabel = { it },
                        onSelect = { form = form!!.copy(defaultProtocol = it) },
                    )
                }
                item {
                    GlassDropdownRow(
                        label = "压缩算法",
                        current = form!!.compressionAlgo,
                        options = CompressionAlgos,
                        optionLabel = { it },
                        onSelect = { form = form!!.copy(compressionAlgo = it) },
                    )
                }
                item {
                    GlassDropdownRow(
                        label = "加密算法",
                        current = form!!.encryptionAlgorithm,
                        options = EncryptionAlgorithms,
                        optionLabel = { it },
                        onSelect = { form = form!!.copy(encryptionAlgorithm = it) },
                    )
                }
                item {
                    Field(
                        label = "TUN 接口名称（留空自动生成）",
                        value = form!!.devName,
                        onChange = { form = form!!.copy(devName = it) },
                        placeholder = "tun0",
                        mono = true,
                        focus = focus,
                    )
                }
                item {
                    Field(
                        label = "多线程数量",
                        value = form!!.multiThreadCount,
                        onChange = { form = form!!.copy(multiThreadCount = it) },
                        placeholder = "2",
                        mono = true,
                        error = threadCountError,
                        keyboardType = KeyboardType.Number,
                        focus = focus,
                    )
                }
                item {
                    Field(
                        label = "实例接收限速（字节/秒，留空不限）",
                        value = form!!.instanceRecvBpsLimit,
                        onChange = { form = form!!.copy(instanceRecvBpsLimit = it) },
                        placeholder = "留空表示不限速",
                        mono = true,
                        error = recvLimitError,
                        keyboardType = KeyboardType.Number,
                        focus = focus,
                    )
                }
                item {
                    Field(
                        label = "外网中继限速（字节/秒，留空不限）",
                        value = form!!.foreignRelayBpsLimit,
                        onChange = { form = form!!.copy(foreignRelayBpsLimit = it) },
                        placeholder = "留空表示不限速",
                        mono = true,
                        error = relayLimitError,
                        keyboardType = KeyboardType.Number,
                        focus = focus,
                    )
                }
                item {
                    Field(
                        label = "SO_MARK（仅 Linux，留空不设置）",
                        value = form!!.socketMark,
                        onChange = { form = form!!.copy(socketMark = it) },
                        placeholder = "0",
                        mono = true,
                        error = socketMarkError,
                        keyboardType = KeyboardType.Number,
                        focus = focus,
                    )
                }
            }

            // Boolean flag groups
            FlagGroups.forEach { (group, flags) ->
                item {
                    ExpandableHeader(
                        title = group,
                        subtitle = "${flags.size} 个官方开关",
                        expanded = openGroups.contains(group),
                        onToggle = { toggleGroup(group) },
                    )
                }
                if (openGroups.contains(group)) {
                    items(flags.size) { index ->
                        val flag = flags[index]
                        GlassSwitchRow(
                            title = flag.label,
                            subtitle = flag.hint,
                            checked = form!!.flags[flag.key] ?: flag.default,
                            onCheckedChange = { checked ->
                                form = form!!.copy(flags = form!!.flags + (flag.key to checked))
                            },
                        )
                    }
                }
            }

            // ACL
            item {
                ExpandableHeader(
                    title = "ACL 原文",
                    subtitle = "按原文保留，导出时原样写入",
                    expanded = openGroups.contains("ACL"),
                    onToggle = { toggleGroup("ACL") },
                )
            }
            if (openGroups.contains("ACL")) {
                item {
                    GlassTextField(
                        value = form!!.aclText,
                        onValueChange = { form = form!!.copy(aclText = it) },
                        label = "ACL TOML（可留空）",
                        placeholder = "[[acl.acl_v1.chains]]\nname = \"protect\"",
                        singleLine = false,
                        minLines = 4,
                        maxLines = 14,
                        mono = true,
                    )
                }
            }
        }

        item { SectionLabel("导入 / 导出") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton(
                    "导入 TOML",
                    onClick = { showImport = true },
                    secondary = true,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    "导出 TOML",
                    onClick = { showExport = true },
                    secondary = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            PillButton(
                "恢复默认监听器",
                onClick = {
                    texts = texts.copy(listeners = joinLines(DefaultListeners))
                },
                secondary = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (networkId != "new") {
            item {
                PillButton(
                    "删除网络",
                    onClick = { showDelete = true },
                    secondary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }

    if (showImport) {
        val parseError = importError ?: if (importText.isBlank()) {
            null
        } else {
            runCatching { TomlCodec.importToml(importText) }.exceptionOrNull()?.message
        }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入 TOML") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GlassTextField(
                        value = importText,
                        onValueChange = { importText = it; importError = null },
                        label = "TOML",
                        placeholder = "[network_identity]…",
                        singleLine = false,
                        minLines = 6,
                        maxLines = 14,
                        mono = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    PillButton(
                        "选择文件",
                        onClick = {
                            openTomlFile.launch(
                                arrayOf(
                                    "text/*",
                                    "application/toml",
                                    "application/octet-stream",
                                    "*/*",
                                ),
                            )
                        },
                        secondary = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (parseError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = parseError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        Text(
                            text = "最多 1 MB，按 UTF-8 读取。选择文件后先预览，确认后再导入。",
                            color = g.muted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = parseError == null && importText.isNotBlank(),
                    onClick = {
                        val parsed = runCatching { TomlCodec.importToml(importText) }
                            .getOrElse {
                                importError = it.message
                                null
                            }
                        if (parsed != null) beginImport(parsed)
                    },
                ) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showExport) {
        val text = buildExportText(exportWithoutSecret)
        val hasSecret = currentUi().secret.isNotBlank() ||
            (mode == EditMode.Toml && tomlText.contains("network_secret"))
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("导出 TOML") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (hasSecret) {
                        Text(
                            text = "文件包含网络密钥，请妥善保管。",
                            color = g.warn,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    GlassSwitchRow(
                        title = "导出时不含密钥",
                        subtitle = "开启后清空 network_secret",
                        checked = exportWithoutSecret,
                        onCheckedChange = { exportWithoutSecret = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        pendingExportText = buildExportText(exportWithoutSecret)
                        pendingExportName = exportFileName(currentUi().name)
                        saveTomlFile.launch(pendingExportName)
                    }) { Text("保存为文件") }
                    TextButton(onClick = {
                        copyToClipboard(context, "TOML", buildExportText(exportWithoutSecret))
                    }) { Text("复制") }
                }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("关闭") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    conflictImport?.let { parsed ->
        AlertDialog(
            onDismissRequest = { conflictImport = null },
            title = { Text("已有同 ID 网络") },
            text = {
                Text(
                    "本机已有 instance_id 为「${parsed.instanceId}」的网络。覆盖会替换该网络；" +
                        "另存为新网络会生成新的 instance_id，避免与对方实例冲突。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    applyImported(parsed, saveAsNew = true)
                    conflictImport = null
                    importText = ""
                    Toast.makeText(context, "已另存为新网络，检查后点保存", Toast.LENGTH_SHORT).show()
                }) { Text("另存为新网络") }
            },
            dismissButton = {
                TextButton(onClick = {
                    applyImported(parsed, saveAsNew = false)
                    conflictImport = null
                    importText = ""
                    Toast.makeText(context, "将覆盖同 ID 网络", Toast.LENGTH_SHORT).show()
                }) { Text("覆盖") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (clientConfig != null) {
        AlertDialog(
            onDismissRequest = { clientConfig = null },
            title = { Text("客户端 WireGuard 配置") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = clientConfig!!,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { copyToClipboard(context, "WireGuard", clientConfig!!) }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { clientConfig = null }) { Text("关闭") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showDiscard) {
        ConfirmDialog(
            title = "放弃修改",
            text = "有未保存的修改，确定返回？",
            confirmText = "放弃",
            onConfirm = {
                showDiscard = false
                onBack()
            },
            onDismiss = { showDiscard = false },
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = "删除网络",
            text = "确定删除「${current.name}」？",
            confirmText = "删除",
            onConfirm = {
                vm.deleteNetwork(networkId)
                showDelete = false
                onBack()
            },
            onDismiss = { showDelete = false },
        )
    }
}

private fun validationErrors(network: UiNetwork): List<String> {
    val errors = mutableListOf<String>()
    if (network.name.isBlank()) errors.add("网络名称不能为空")
    if (!network.dhcp && network.ipv4.isNotBlank() && !isValidCidr(network.ipv4)) errors.add("静态 IPv4 格式错误")
    if (network.ipv6.isNotBlank() && !isValidCidr(network.ipv6)) errors.add("静态 IPv6 格式错误")
    if (network.mtu.isNotBlank()) {
        val mtu = network.mtu.toIntOrNull()
        if (mtu == null || mtu !in 400..1380) errors.add("MTU 超出范围")
    }
    if (network.instanceRecvBpsLimit.isNotBlank() && network.instanceRecvBpsLimit.toLongOrNull() == null) {
        errors.add("实例限速不是整数")
    }
    if (network.foreignRelayBpsLimit.isNotBlank() && network.foreignRelayBpsLimit.toLongOrNull() == null) {
        errors.add("中继限速不是整数")
    }
    if (network.multiThreadCount.toIntOrNull()?.let { it < 1 } != false) errors.add("多线程数量无效")
    if (network.socketMark.isNotBlank() && network.socketMark.toIntOrNull() == null) errors.add("SO_MARK 不是整数")
    if (network.socks5.isNotBlank() && !SocksPattern.matches(network.socks5)) errors.add("Socks5 地址格式错误")
    if (network.servers.any { it.isNotBlank() && !UrlPattern.matches(it) }) errors.add("服务器地址格式错误")
    if (network.listeners.any { it.isNotBlank() && !ListenerPattern.matches(it) }) errors.add("监听器格式错误")
    if (network.mappedListeners.any { it.isNotBlank() && !ListenerPattern.matches(it) }) errors.add("监听映射格式错误")
    if (network.proxyNetworks.any { parseProxyNetwork(formatProxyNetwork(it)) == null }) errors.add("子网代理 CIDR 错误")
    network.portForwards.forEach { if (it.toDraft().error() != null) errors.add("端口转发不完整") }
    if (network.relayNetworkWhitelistEnabled && network.relayNetworkWhitelist.any { it.isBlank() }) {
        errors.add("白名单含空项")
    }
    val portal = network.vpnPortal
    if (portal != null) {
        if (!isValidHostPort(portal.wireguardListen)) errors.add("VPN portal 监听地址错误")
        val portalKey = portal.wireguardPrivateKey
        if (portalKey != null && portalKey.isNotBlank() && base64Size(portalKey) != 32) {
            errors.add("VPN portal 私钥应为 32 字节 Base64")
        }
        if (portal.clients.size > 64) errors.add("VPN 客户端最多 64 个")
        if (portal.clients.any { !ClientNamePattern.matches(it.name) }) errors.add("VPN 客户端名称无效")
        if (portal.clients.map { it.name }.toSet().size != portal.clients.size) errors.add("VPN 客户端名称重复")
        if (portal.clients.any { !isValidCidr(it.virtualIp) }) errors.add("VPN 客户端虚拟 IP 无效")
    }
    network.secureMode?.localPrivateKey?.takeIf { it.isNotBlank() }?.let {
        if (base64Size(it) != 32) errors.add("安全模式私钥应为 32 字节 Base64")
    }
    return errors
}

private fun base64Size(text: String): Int = runCatching {
    java.util.Base64.getDecoder().decode(text.trim()).size
}.getOrDefault(-1)

@Composable
private fun InlineError(text: String?) {
    if (text == null) return
    Text(
        text = "错误：$text",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 1.dp),
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    focus: androidx.compose.ui.focus.FocusManager,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    mono: Boolean = false,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 8,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier) {
        GlassTextField(
            value = value,
            onValueChange = onChange,
            label = label,
            placeholder = placeholder,
            mono = mono,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focus.moveFocus(FocusDirection.Down) },
            ),
            visualTransformation = visualTransformation,
        )
        InlineError(error)
    }
}

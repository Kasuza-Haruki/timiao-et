package app.timiao.et.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.ConfirmDialog
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.ExpandableHeader
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassSwitchRow
import app.timiao.et.ui.components.GlassTextField
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.data.OfficialFlagGroups
import app.timiao.et.ui.data.UiNetwork
import app.timiao.et.ui.data.newNetwork
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.util.copyToClipboard
import app.timiao.et.ui.util.joinLines
import app.timiao.et.ui.util.parseLines
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch

private fun composeNetwork(
    draft: UiNetwork,
    serversText: String,
    ipv4Text: String,
    proxyText: String,
    forwardText: String,
    whitelistText: String,
    listenersText: String,
): UiNetwork = draft.copy(
    name = draft.name.trim(),
    servers = parseLines(serversText),
    ipv4 = ipv4Text.trim(),
    proxyNetworks = parseLines(proxyText),
    portForwards = parseLines(forwardText),
    relayNetworkWhitelist = parseLines(whitelistText),
    listeners = parseLines(listenersText),
)

@Composable
fun NetworkEditScreen(
    vm: AppViewModel,
    networkId: String,
    openAction: String,
    onBack: () -> Unit,
) {
    val networks by vm.networks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = LocalGlassColors.current

    var draft by remember(networkId) { mutableStateOf<UiNetwork?>(null) }
    LaunchedEffect(networkId, networks) {
        if (draft == null) {
            draft = if (networkId == "new") {
                newNetwork()
            } else {
                networks.firstOrNull { it.id == networkId }
            }
        }
    }

    var showImport by rememberSaveable { mutableStateOf(openAction == "import") }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var importText by rememberSaveable { mutableStateOf("") }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var rawOpen by rememberSaveable { mutableStateOf(false) }

    val current = draft
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载中…", color = g.muted)
        }
        return
    }

    var serversText by rememberSaveable(networkId) { mutableStateOf(joinLines(current.servers)) }
    var ipv4Text by rememberSaveable(networkId) { mutableStateOf(current.ipv4) }
    var proxyText by rememberSaveable(networkId) { mutableStateOf(joinLines(current.proxyNetworks)) }
    var forwardText by rememberSaveable(networkId) { mutableStateOf(joinLines(current.portForwards)) }
    var whitelistText by rememberSaveable(networkId) { mutableStateOf(joinLines(current.relayNetworkWhitelist)) }
    var listenersText by rememberSaveable(networkId) { mutableStateOf(joinLines(current.listeners)) }

    LaunchedEffect(openAction) {
        if (openAction == "scan") {
            Toast.makeText(context, "扫码导入入口已预留，等 ML Kit 接入后可用", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = if (networkId == "new") "新建网络" else current.name.ifBlank { "编辑网络" },
                subtitle = "改动在点保存后生效",
                onBack = onBack,
                action = {
                    Box(Modifier.width(88.dp)) {
                        PillButton(
                            "保存",
                            onClick = {
                                val toSave = composeNetwork(
                                    draft ?: current,
                                    serversText,
                                    ipv4Text,
                                    proxyText,
                                    forwardText,
                                    whitelistText,
                                    listenersText,
                                )
                                if (toSave.name.isBlank()) {
                                    Toast.makeText(context, "网络名称不能为空", Toast.LENGTH_SHORT).show()
                                } else {
                                    vm.saveNetwork(toSave)
                                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                    onBack()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }

        item { SectionLabel("基础") }
        item {
            GlassTextField(
                value = current.name,
                onValueChange = { draft = draft!!.copy(name = it) },
                label = "网络名称",
                placeholder = "home-mesh",
            )
        }
        item {
            GlassTextField(
                value = current.secret,
                onValueChange = { draft = draft!!.copy(secret = it) },
                label = "网络密钥",
                placeholder = "留空表示无密钥",
                mono = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        item {
            GlassTextField(
                value = serversText,
                onValueChange = { serversText = it },
                label = "服务器（每行一个）",
                placeholder = "tcp://public.easytier.top:11010",
                singleLine = false,
                minLines = 1,
                maxLines = 4,
                mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        item {
            GlassSwitchRow(
                title = "自动分配 IP（DHCP）",
                subtitle = "关闭后手动填写静态地址",
                checked = current.dhcp,
                onCheckedChange = { draft = draft!!.copy(dhcp = it) },
            )
        }
        if (!current.dhcp) {
            item {
                GlassTextField(
                    value = ipv4Text,
                    onValueChange = { ipv4Text = it },
                    label = "静态 IPv4 / 前缀",
                    placeholder = "10.126.126.5/24",
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        }

        item { SectionLabel("选项") }
        OfficialFlagGroups.forEach { (groupName, flags) ->
            key(groupName) {
                var expanded by remember { mutableStateOf(groupName == "连接") }
                item {
                    ExpandableHeader(
                        title = groupName,
                        subtitle = "${flags.size} 个官方选项",
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                    )
                }
                if (expanded) {
                    items(flags.size) { flagIndex ->
                        val flag = flags[flagIndex]
                        GlassSwitchRow(
                            title = flag.label,
                            subtitle = flag.hint,
                            checked = current.flags[flag.key] ?: false,
                            onCheckedChange = { checked ->
                                draft = draft!!.copy(flags = draft!!.flags + (flag.key to checked))
                            },
                        )
                    }
                }
            }
        }

        item { SectionLabel("高级") }
        item {
            ExpandableHeader(
                title = "子网代理 / 端口转发 / VPN",
                subtitle = "子网、转发、portal、白名单、监听器",
                expanded = advancedOpen,
                onToggle = { advancedOpen = !advancedOpen },
            )
        }
        if (advancedOpen) {
            item {
                GlassTextField(
                    value = proxyText,
                    onValueChange = { proxyText = it },
                    label = "子网代理（每行一个 CIDR）",
                    placeholder = "192.168.10.0/24",
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    mono = true,
                )
            }
            item {
                GlassTextField(
                    value = forwardText,
                    onValueChange = { forwardText = it },
                    label = "端口转发（每行一条）",
                    placeholder = "tcp://0.0.0.0:8080/10.126.126.1:80",
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            item {
                GlassTextField(
                    value = current.vpnPortal,
                    onValueChange = { draft = draft!!.copy(vpnPortal = it) },
                    label = "VPN portal",
                    placeholder = "10.14.14.0/24",
                    mono = true,
                )
            }
            item {
                GlassTextField(
                    value = whitelistText,
                    onValueChange = { whitelistText = it },
                    label = "中继网络白名单（每行一个，* 表示全部）",
                    placeholder = "*",
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    mono = true,
                )
            }
            item {
                GlassTextField(
                    value = listenersText,
                    onValueChange = { listenersText = it },
                    label = "监听器（每行一个）",
                    placeholder = "udp://0.0.0.0:11010",
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        }
        item {
            ExpandableHeader(
                title = "ACL / TOML 原文",
                subtitle = "直接编辑原始配置",
                expanded = rawOpen,
                onToggle = { rawOpen = !rawOpen },
            )
        }
        if (rawOpen) {
            item {
                GlassTextField(
                    value = current.aclText,
                    onValueChange = { draft = draft!!.copy(aclText = it) },
                    label = "ACL 规则",
                    placeholder = "示例：in 127.0.0.1/32 drop",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 10,
                    mono = true,
                )
            }
            item {
                GlassTextField(
                    value = current.tomlText.orEmpty(),
                    onValueChange = { draft = draft!!.copy(tomlText = it.ifBlank { null }) },
                    label = "TOML 原文（保存后优先使用）",
                    placeholder = "[network_identity]\nnetwork_name = \"home-mesh\"",
                    singleLine = false,
                    minLines = 5,
                    maxLines = 16,
                    mono = true,
                )
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
                "扫码导入（稍后支持）",
                onClick = {
                    Toast.makeText(context, "扫码导入入口已预留，等 ML Kit 接入后可用", Toast.LENGTH_SHORT).show()
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
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入 TOML") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GlassTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = "TOML",
                        placeholder = "[network_identity]…",
                        singleLine = false,
                        minLines = 6,
                        maxLines = 14,
                        mono = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.importToml(importText).onSuccess { imported ->
                            showImport = false
                            importText = ""
                            serversText = joinLines(imported.servers)
                            ipv4Text = imported.ipv4
                            proxyText = joinLines(imported.proxyNetworks)
                            forwardText = joinLines(imported.portForwards)
                            whitelistText = joinLines(imported.relayNetworkWhitelist)
                            listenersText = joinLines(imported.listeners)
                            draft = imported.copy(id = draft!!.id)
                            Toast.makeText(context, "已导入，检查后点保存", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showExport) {
        val text = vm.exportToml(
            composeNetwork(current, serversText, ipv4Text, proxyText, forwardText, whitelistText, listenersText)
        )
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("导出 TOML") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { copyToClipboard(context, "TOML", text) }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("关闭") } },
            containerColor = MaterialTheme.colorScheme.surface,
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

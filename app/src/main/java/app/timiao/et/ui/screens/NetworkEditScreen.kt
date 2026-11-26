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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.R
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
import app.timiao.et.ui.data.flagGroupTitleRes
import app.timiao.et.ui.data.HIDDEN_SECRET
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
import app.timiao.et.ui.data.maskTomlSecrets
import app.timiao.et.ui.data.parseLines
import app.timiao.et.ui.data.parseProxyNetwork
import app.timiao.et.ui.data.readTomlUri
import app.timiao.et.ui.data.resolveImportId
import app.timiao.et.ui.data.restoreHiddenSecrets
import app.timiao.et.ui.data.revealTomlSecrets
import app.timiao.et.ui.data.staticIpEditable
import app.timiao.et.ui.data.toDraft
import app.timiao.et.ui.data.toUi
import app.timiao.et.ui.data.toConfig
import app.timiao.et.ui.data.toPortForward
import app.timiao.et.ui.data.tomlErrorMessage
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
    onShare: (String) -> Unit = {},
    onScan: () -> Unit = {},
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
    val secretFocus = remember(networkId) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var secretFocusDone by rememberSaveable(networkId) { mutableStateOf(false) }

    LaunchedEffect(networkId, networks) {
        if (form == null) {
            val pending = PendingImport.config
            if (pending != null) {
                PendingImport.config = null
                val targetId = pending.instanceId ?: UUID.randomUUID().toString()
                val ui = pending.toUi().copy(id = targetId)
                form = ui
                texts = Texts.of(ui)
                Toast.makeText(context, context.getString(R.string.edit_imported_from_file), Toast.LENGTH_SHORT).show()
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
            Text(stringResource(R.string.edit_load), color = g.muted)
        }
        return
    }

    var mode by rememberSaveable(networkId) { mutableStateOf(EditMode.Form) }
    var tomlText by rememberSaveable(networkId) { mutableStateOf("") }
    // Unmasked export the editor text came from; used to replay hidden values.
    var tomlOriginText by rememberSaveable(networkId) { mutableStateOf("") }
    var showTomlSecrets by rememberSaveable(networkId) { mutableStateOf(false) }
    var tomlError by rememberSaveable(networkId) { mutableStateOf<String?>(null) }
    var tomlBaseline by rememberSaveable(networkId) { mutableStateOf("") }
    var saveAttempted by rememberSaveable(networkId) { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(openAction == "import") }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var importText by rememberSaveable { mutableStateOf("") }
    var openGroups by rememberSaveable { mutableStateOf(listOf("basic", "connection")) }
    var showSecurePrivate by rememberSaveable { mutableStateOf(false) }
    var showPortalPrivate by rememberSaveable { mutableStateOf(false) }
    var clientConfig by remember { mutableStateOf<String?>(null) }
    var importError by rememberSaveable { mutableStateOf<String?>(null) }
    var exportWithoutSecret by rememberSaveable { mutableStateOf(false) }
    var pendingExportText by remember { mutableStateOf("") }
    var pendingExportName by remember { mutableStateOf("easytier.toml") }
    var conflictImport by remember { mutableStateOf<NetworkConfig?>(null) }
    val generateKeyFirstText = stringResource(R.string.edit_generate_key_first)
    val generateFailedText = stringResource(R.string.edit_generate_failed)

    fun toggleGroup(name: String) {
        openGroups = if (openGroups.contains(name)) openGroups - name else openGroups + name
    }

    fun currentUi(): UiNetwork = (form ?: current).withTexts(texts)

    fun applyParsed(parsed: NetworkConfig) {
        val ui = parsed.toUi().copy(id = current.id)
        form = ui
        texts = Texts.of(ui)
    }

    /** Unmasked config behind the current editor text, if it is still readable. */
    fun tomlOrigin(): NetworkConfig? = tomlOriginText
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { TomlCodec.importToml(it) }.getOrNull() }

    /** Parses the editor text and puts hidden placeholders back to their real values. */
    fun parseTomlRestored(): NetworkConfig {
        val parsed = TomlCodec.importToml(tomlText)
        return tomlOrigin()?.let { restoreHiddenSecrets(parsed, it) } ?: parsed
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
            Toast.makeText(context, context.getString(R.string.edit_imported), Toast.LENGTH_SHORT).show()
        }
    }

    fun buildExportText(excludeSecret: Boolean): String {
        if (mode == EditMode.Toml) {
            val parsed = runCatching { parseTomlRestored() }.getOrNull() ?: return tomlText
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
                        importError = tomlErrorMessage(context, runCatching { TomlCodec.importToml(text) }.exceptionOrNull())
                        showImport = true
                    }
                    .onFailure { error ->
                        importText = ""
                        importError = error.message ?: context.getString(R.string.networks_read_failed)
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
                            ?: throw IllegalStateException(context.getString(R.string.edit_cannot_write_file))
                        stream.use { it.write(pendingExportText.toByteArray(Charsets.UTF_8)) }
                    }
                }
                Toast.makeText(
                    context,
                    if (result.isSuccess) context.getString(R.string.edit_saved_file, pendingExportName) else context.getString(R.string.edit_save_failed, result.exceptionOrNull()?.message),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun enterTomlMode() {
        val ui = currentUi()
        if (ui.name.isBlank()) {
            saveAttempted = true
            Toast.makeText(context, context.getString(R.string.edit_name_required), Toast.LENGTH_SHORT).show()
            return
        }
        val real = vm.exportToml(ui)
        tomlOriginText = real
        tomlText = maskTomlSecrets(real)
        showTomlSecrets = false
        tomlBaseline = tomlText
        tomlError = null
        mode = EditMode.Toml
    }

    fun leaveTomlMode() {
        val parsed = try {
            parseTomlRestored()
        } catch (error: TomlParseException) {
            tomlError = tomlErrorMessage(context, error)
            return
        } catch (error: Exception) {
            tomlError = context.getString(R.string.edit_parse_failed, error.message)
            return
        }
        applyParsed(parsed)
        tomlError = null
        mode = EditMode.Form
    }

    fun performSave() {
        if (mode == EditMode.Toml) {
            val parsed = try {
                parseTomlRestored()
            } catch (error: TomlParseException) {
                tomlError = tomlErrorMessage(context, error)
                return
            } catch (error: Exception) {
                tomlError = context.getString(R.string.edit_parse_failed, error.message)
                return
            }
            if (parsed.networkName.isBlank()) {
                tomlError = context.getString(R.string.edit_name_required)
                return
            }
            val ui = parsed.toUi().copy(id = current.id)
            vm.saveNetwork(ui)
            Toast.makeText(context, context.getString(R.string.edit_saved), Toast.LENGTH_SHORT).show()
            onBack()
            return
        }
        saveAttempted = true
        val ui = currentUi()
        val errors = validationErrors(ui)
        if (errors.isNotEmpty()) {
            Toast.makeText(context, context.getString(R.string.edit_invalid_count, errors.size), Toast.LENGTH_SHORT).show()
            return
        }
        vm.saveNetwork(ui)
        Toast.makeText(context, context.getString(R.string.edit_saved), Toast.LENGTH_SHORT).show()
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
        if (openAction == "scan") onScan()
    }

    val nameError = if (form!!.name.isBlank()) stringResource(R.string.edit_name_required) else null
    val ipv4Error = form!!.ipv4.takeIf { it.isNotBlank() && !isValidCidr(it) }?.let { stringResource(R.string.edit_err_ipv4_format) }
    val ipv6Error = form!!.ipv6.takeIf { it.isNotBlank() && !isValidCidr(it) }?.let { stringResource(R.string.edit_err_ipv6_format) }
    val mtuError = form!!.mtu.takeIf { it.isNotBlank() }?.let {
        val value = it.toIntOrNull()
        if (value == null || value !in 400..1380) stringResource(R.string.edit_err_mtu_range) else null
    }
    val recvLimitError = form!!.instanceRecvBpsLimit.takeIf { it.isNotBlank() && it.toLongOrNull() == null }
        ?.let { stringResource(R.string.edit_err_integer_bps) }
    val relayLimitError = form!!.foreignRelayBpsLimit.takeIf { it.isNotBlank() && it.toLongOrNull() == null }
        ?.let { stringResource(R.string.edit_err_integer_bps) }
    val threadCountError = form!!.multiThreadCount.takeIf { it.isNotBlank() }?.let {
        val value = it.toIntOrNull()
        if (value == null || value < 1) stringResource(R.string.edit_err_positive_int) else null
    }
    val socketMarkError = form!!.socketMark.takeIf { it.isNotBlank() && it.toIntOrNull() == null }
        ?.let { stringResource(R.string.edit_err_integer) }
    val socksError = form!!.socks5.takeIf { it.isNotBlank() && !SocksPattern.matches(it) }
        ?.let { stringResource(R.string.edit_err_socks5) }
    val serversError = if (firstBadUrl(parseLines(texts.servers))) stringResource(R.string.edit_err_server_protocol) else null
    val listenersError = if (firstBadListener(parseLines(texts.listeners))) stringResource(R.string.edit_err_listener) else null
    val mappedError = if (firstBadListener(parseLines(texts.mappedListeners))) stringResource(R.string.edit_err_mapped) else null
    val portal = form!!.vpnPortal
    val portalListenError = if (portal != null && !isValidHostPort(portal.wireguardListen)) {
        stringResource(R.string.edit_err_host_port)
    } else {
        null
    }
    val secureKeyError = form!!.secureMode?.localPrivateKey?.takeIf { it.isNotBlank() }?.let {
        if (base64Size(it) != 32) stringResource(R.string.edit_err_key_base64) else null
    }
    val portalKeyError = portal?.wireguardPrivateKey?.takeIf { it.isNotBlank() }?.let {
        if (base64Size(it) != 32) stringResource(R.string.edit_err_key_base64) else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = if (networkId == "new") stringResource(R.string.edit_title_new) else current.name.ifBlank { stringResource(R.string.edit_title_edit) },
                subtitle = stringResource(if (dirty) R.string.edit_dirty else R.string.edit_clean),
                onBack = ::requestBack,
                action = {
                    Box(Modifier.width(88.dp)) {
                        PillButton(
                            stringResource(R.string.action_save),
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
                options = listOf(stringResource(R.string.edit_tab_form), "TOML"),
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
                GlassSwitchRow(
                    title = stringResource(R.string.edit_show_secrets),
                    subtitle = stringResource(R.string.edit_hide_secrets_sub, HIDDEN_SECRET),
                    checked = showTomlSecrets,
                    onCheckedChange = { checked ->
                        if (checked) {
                            tomlOrigin()?.let { origin ->
                                tomlText = revealTomlSecrets(tomlText, origin)
                            }
                        } else {
                            // Fold edits typed while shown into the origin first,
                            // so hiding them does not drop them on save.
                            runCatching { TomlCodec.importToml(tomlText) }.getOrNull()?.let { parsed ->
                                val origin = tomlOrigin()
                                val restored = origin?.let { restoreHiddenSecrets(parsed, it) } ?: parsed
                                tomlOriginText = TomlCodec.exportToml(restored)
                            }
                            tomlText = maskTomlSecrets(tomlText)
                        }
                        showTomlSecrets = checked
                    },
                )
            }
            item {
                GlassTextField(
                    value = tomlText,
                    onValueChange = { tomlText = it; tomlError = null },
                    label = stringResource(R.string.edit_toml_source_label),
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
                        text = stringResource(R.string.edit_toml_hint),
                        color = g.muted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
            item {
                PillButton(
                    stringResource(R.string.edit_parse_back),
                    onClick = ::leaveTomlMode,
                    secondary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            item { SectionLabel(stringResource(R.string.edit_section_basic)) }
            item {
                Field(
                    label = stringResource(R.string.edit_name),
                    value = form!!.name,
                    onChange = { form = form!!.copy(name = it) },
                    placeholder = "home-mesh",
                    error = nameError,
                    focus = focus,
                )
            }
            item {
                SecretField(
                    value = form!!.secret,
                    onChange = { form = form!!.copy(secret = it) },
                    focus = focus,
                    focusRequester = secretFocus,
                )
                // A link/file without a secret opens here with the field ready to type.
                LaunchedEffect(Unit) {
                    if (openAction == "secret" && !secretFocusDone) {
                        secretFocusDone = true
                        runCatching { secretFocus.requestFocus() }
                        keyboard?.show()
                    }
                }
            }
            item {
                Field(
                    label = stringResource(R.string.edit_hostname),
                    value = form!!.hostname,
                    onChange = { form = form!!.copy(hostname = it) },
                    placeholder = stringResource(R.string.edit_hostname_placeholder),
                    focus = focus,
                )
            }
            item {
                Field(
                    label = stringResource(R.string.edit_servers),
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
                    title = stringResource(R.string.edit_dhcp),
                    subtitle = stringResource(R.string.edit_dhcp_sub),
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
                        Text(stringResource(R.string.edit_static_ipv4), style = MaterialTheme.typography.labelSmall, color = g.muted)
                        Text(
                            stringResource(R.string.edit_dhcp_on_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = g.muted,
                        )
                    }
                } else {
                    Field(
                        label = stringResource(R.string.edit_static_ipv4),
                        value = form!!.ipv4,
                        onChange = { form = form!!.copy(ipv4 = it) },
                        placeholder = "10.144.144.5/24",
                        mono = true,
                        error = ipv4Error,
                        keyboardType = KeyboardType.Uri,
                        focus = focus,
                    )
                }
            }
            item {
                Field(
                    label = stringResource(R.string.edit_static_ipv6),
                    value = form!!.ipv6,
                    onChange = { form = form!!.copy(ipv6 = it) },
                    placeholder = "fd00::5/64",
                    mono = true,
                    error = ipv6Error,
                    keyboardType = KeyboardType.Uri,
                    focus = focus,
                )
            }

            item { SectionLabel(stringResource(R.string.edit_section_advanced)) }
            item { perAppSlot() }

            // 身份与安全
            item {
                ExpandableHeader(
                    title = stringResource(R.string.edit_group_security),
                    subtitle = stringResource(R.string.edit_group_security_sub),
                    expanded = openGroups.contains("security"),
                    onToggle = { toggleGroup("security") },
                )
            }
            if (openGroups.contains("security")) {
                item {
                    GlassSwitchRow(
                        title = stringResource(R.string.edit_secure_mode),
                        subtitle = stringResource(R.string.edit_secure_mode_sub),
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
                                label = stringResource(R.string.edit_local_private_key),
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
                                placeholder = "Base64 (32 bytes)",
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
                                stringResource(if (showSecurePrivate) R.string.action_hide else R.string.action_show),
                                onClick = { showSecurePrivate = !showSecurePrivate },
                                secondary = true,
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(
                                stringResource(R.string.edit_generate_key),
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
                        label = stringResource(R.string.edit_socks5),
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
                        title = stringResource(R.string.edit_ipv6_public),
                        subtitle = stringResource(R.string.edit_ipv6_public_sub),
                        checked = form!!.ipv6PublicAddrAuto,
                        onCheckedChange = { form = form!!.copy(ipv6PublicAddrAuto = it) },
                    )
                }
            }

            // 网络与路由
            item {
                ExpandableHeader(
                    title = stringResource(R.string.edit_group_network),
                    subtitle = stringResource(R.string.edit_group_network_sub),
                    expanded = openGroups.contains("network"),
                    onToggle = { toggleGroup("network") },
                )
            }
            if (openGroups.contains("network")) {
                item {
                    Field(
                        label = stringResource(R.string.edit_listeners),
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
                        label = stringResource(R.string.edit_mapped),
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
                        label = stringResource(R.string.edit_exit_nodes),
                        value = texts.exitNodes,
                        onChange = { texts = texts.copy(exitNodes = it) },
                        placeholder = "10.144.144.1",
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
                        title = stringResource(R.string.edit_manual_routes),
                        subtitle = stringResource(R.string.edit_manual_routes_sub),
                        checked = form!!.manualRoutesEnabled,
                        onCheckedChange = { form = form!!.copy(manualRoutesEnabled = it) },
                    )
                }
                if (form!!.manualRoutesEnabled) {
                    item {
                        Field(
                            label = stringResource(R.string.edit_routes),
                            value = texts.routes,
                            onChange = { texts = texts.copy(routes = it) },
                            placeholder = "192.168.0.0/16",
                            singleLine = false,
                            minLines = 1,
                            maxLines = 4,
                            mono = true,
                            error = parseLines(texts.routes).firstOrNull { !isValidCidr(it) }?.let {
                                stringResource(R.string.edit_invalid_cidr_value, it)
                            },
                            keyboardType = KeyboardType.Uri,
                            focus = focus,
                        )
                    }
                }
                item {
                    GlassSwitchRow(
                        title = stringResource(R.string.edit_whitelist),
                        subtitle = stringResource(R.string.edit_whitelist_sub),
                        checked = form!!.relayNetworkWhitelistEnabled,
                        onCheckedChange = { form = form!!.copy(relayNetworkWhitelistEnabled = it) },
                    )
                }
                if (form!!.relayNetworkWhitelistEnabled) {
                    item {
                        Field(
                            label = stringResource(R.string.edit_whitelist_names),
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
                        title = stringResource(R.string.edit_disable_upnp),
                        subtitle = stringResource(R.string.edit_disable_upnp_sub),
                        checked = form!!.flags["disable_upnp"] ?: false,
                        onCheckedChange = { form = form!!.copy(flags = form!!.flags + ("disable_upnp" to it)) },
                    )
                }
            }

            // STUN
            item {
                ExpandableHeader(
                    title = stringResource(R.string.edit_stun_title),
                    subtitle = stringResource(R.string.edit_stun_sub),
                    expanded = openGroups.contains("stun"),
                    onToggle = { toggleGroup("stun") },
                )
            }
            if (openGroups.contains("stun")) {
                item {
                    Field(
                        label = stringResource(R.string.edit_stun_servers),
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
                    title = stringResource(R.string.edit_group_proxy),
                    subtitle = stringResource(R.string.edit_group_proxy_sub),
                    expanded = openGroups.contains("proxy"),
                    onToggle = { toggleGroup("proxy") },
                )
            }
            if (openGroups.contains("proxy")) {
                items(form!!.proxyNetworks.size) { index ->
                    val proxy = form!!.proxyNetworks[index]
                    val cidrError = if (!isValidCidr(proxy.cidr)) stringResource(R.string.edit_invalid_cidr) else null
                    val mappedError = proxy.mappedCidr?.takeIf { !isValidCidr(it) }?.let { stringResource(R.string.edit_invalid_cidr) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Field(
                            label = stringResource(R.string.edit_proxy_cidr),
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
                            label = stringResource(R.string.edit_mapped_cidr),
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
                        }) { Text(stringResource(R.string.edit_remove_row)) }
                    }
                }
                item {
                    PillButton(
                        stringResource(R.string.edit_add_proxy),
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
                    title = stringResource(R.string.edit_group_portforward),
                    subtitle = stringResource(R.string.edit_group_portforward_sub),
                    expanded = openGroups.contains("portforward"),
                    onToggle = { toggleGroup("portforward") },
                )
            }
            if (openGroups.contains("portforward")) {
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
                            label = stringResource(R.string.edit_protocol),
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
                                label = stringResource(R.string.edit_bind_address),
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
                                label = stringResource(R.string.edit_port),
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
                                label = stringResource(R.string.edit_target_address),
                                value = forward.dstIp,
                                onChange = { value ->
                                    form = form!!.copy(
                                        portForwards = form!!.portForwards.toMutableList().also {
                                            it[index] = forward.copy(dstIp = value).toPortForward()
                                        },
                                    )
                                },
                                placeholder = "10.144.144.1",
                                mono = true,
                                keyboardType = KeyboardType.Uri,
                                focus = focus,
                                modifier = Modifier.weight(1.6f),
                            )
                            Field(
                                label = stringResource(R.string.edit_port),
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
                        if (error != null) InlineErrorRes(error)
                        TextButton(onClick = {
                            form = form!!.copy(
                                portForwards = form!!.portForwards.filterIndexed { i, _ -> i != index },
                            )
                        }) { Text(stringResource(R.string.edit_remove_row)) }
                    }
                }
                item {
                    PillButton(
                        stringResource(R.string.edit_add_portforward),
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
                    title = stringResource(R.string.edit_group_vpn),
                    subtitle = stringResource(R.string.edit_group_vpn_sub),
                    expanded = openGroups.contains("vpn"),
                    onToggle = { toggleGroup("vpn") },
                )
            }
            if (openGroups.contains("vpn")) {
                item {
                    GlassSwitchRow(
                        title = stringResource(R.string.edit_enable_wireguard),
                        subtitle = stringResource(R.string.edit_enable_wireguard_sub),
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
                            label = stringResource(R.string.edit_listen_host_port),
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
                                label = stringResource(R.string.edit_server_private_key),
                                value = portal.wireguardPrivateKey.orEmpty(),
                                onChange = {
                                    form = form!!.copy(
                                        vpnPortal = portal.copy(wireguardPrivateKey = it.ifBlank { null }),
                                    )
                                },
                                placeholder = "Base64 (32 bytes)",
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
                                stringResource(if (showPortalPrivate) R.string.action_hide else R.string.action_show),
                                onClick = { showPortalPrivate = !showPortalPrivate },
                                secondary = true,
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(
                                stringResource(R.string.edit_generate_private_key),
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
                                text = stringResource(R.string.edit_x25519_unavailable),
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
                                label = stringResource(R.string.edit_device_name),
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
                                !ClientNamePattern.matches(client.name) -> R.string.edit_name_rule
                                portal.clients.count { it.name == client.name } > 1 -> R.string.edit_name_duplicate
                                else -> null
                            }
                            InlineErrorRes(nameError)
                            Field(
                                label = stringResource(R.string.edit_virtual_ip),
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
                                placeholder = "10.144.144.10/24",
                                mono = true,
                                error = if (!isValidCidr(client.virtualIp)) stringResource(R.string.edit_invalid_cidr_input) else null,
                                keyboardType = KeyboardType.Uri,
                                focus = focus,
                            )
                            Field(
                                label = stringResource(R.string.edit_acl_groups),
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
                            }) { Text(stringResource(R.string.edit_remove_device)) }
                            TextButton(onClick = {
                                val key = portal.wireguardPrivateKey
                                clientConfig = if (key.isNullOrBlank()) {
                                    generateKeyFirstText
                                } else {
                                    WireGuardKeys.clientConfig(
                                        masterPrivateKeyBase64 = key,
                                        listen = portal.wireguardListen,
                                        clientName = client.name,
                                        virtualIpCidr = client.virtualIp,
                                        proxyCidrs = form!!.proxyNetworks.map { formatProxyNetwork(it) },
                                    ) ?: generateFailedText
                                }
                            }) { Text(stringResource(R.string.edit_generate_client_config)) }
                        }
                    }
                    item {
                        PillButton(
                            stringResource(R.string.edit_add_device),
                            onClick = {
                                form = form!!.copy(
                                    vpnPortal = portal.copy(
                                        clients = portal.clients + VpnPortalClient(
                                            name = "device-${portal.clients.size + 1}",
                                            virtualIp = "10.144.144.${10 + portal.clients.size}/24",
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
                    title = stringResource(R.string.edit_group_transport),
                    subtitle = stringResource(R.string.edit_group_transport_sub),
                    expanded = openGroups.contains("transport"),
                    onToggle = { toggleGroup("transport") },
                )
            }
            if (openGroups.contains("transport")) {
                item {
                    Field(
                        label = stringResource(R.string.edit_mtu),
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
                        label = stringResource(R.string.edit_default_protocol),
                        current = form!!.defaultProtocol,
                        options = DefaultProtocols,
                        optionLabel = { it },
                        onSelect = { form = form!!.copy(defaultProtocol = it) },
                    )
                }
                item {
                    GlassDropdownRow(
                        label = stringResource(R.string.edit_compression),
                        current = form!!.compressionAlgo,
                        options = CompressionAlgos,
                        optionLabel = { it },
                        onSelect = { form = form!!.copy(compressionAlgo = it) },
                    )
                }
                item {
                    GlassDropdownRow(
                        label = stringResource(R.string.edit_encryption),
                        current = form!!.encryptionAlgorithm,
                        options = EncryptionAlgorithms,
                        optionLabel = { it },
                        onSelect = { form = form!!.copy(encryptionAlgorithm = it) },
                    )
                }
                item {
                    Field(
                        label = stringResource(R.string.edit_dev_name),
                        value = form!!.devName,
                        onChange = { form = form!!.copy(devName = it) },
                        placeholder = "tun0",
                        mono = true,
                        focus = focus,
                    )
                }
                item {
                    Field(
                        label = stringResource(R.string.edit_threads),
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
                        label = stringResource(R.string.edit_recv_limit),
                        value = form!!.instanceRecvBpsLimit,
                        onChange = { form = form!!.copy(instanceRecvBpsLimit = it) },
                        placeholder = stringResource(R.string.edit_unlimited_placeholder),
                        mono = true,
                        error = recvLimitError,
                        keyboardType = KeyboardType.Number,
                        focus = focus,
                    )
                }
                item {
                    Field(
                        label = stringResource(R.string.edit_relay_limit),
                        value = form!!.foreignRelayBpsLimit,
                        onChange = { form = form!!.copy(foreignRelayBpsLimit = it) },
                        placeholder = stringResource(R.string.edit_unlimited_placeholder),
                        mono = true,
                        error = relayLimitError,
                        keyboardType = KeyboardType.Number,
                        focus = focus,
                    )
                }
                item {
                    Field(
                        label = stringResource(R.string.edit_socket_mark),
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
                        title = stringResource(flagGroupTitleRes(group)),
                        subtitle = stringResource(R.string.edit_flag_count, flags.size),
                        expanded = openGroups.contains(group),
                        onToggle = { toggleGroup(group) },
                    )
                }
                if (openGroups.contains(group)) {
                    items(flags.size) { index ->
                        val flag = flags[index]
                        GlassSwitchRow(
                            title = stringResource(flag.labelRes),
                            subtitle = stringResource(flag.hintRes),
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
                    title = stringResource(R.string.edit_acl_title),
                    subtitle = stringResource(R.string.edit_acl_sub),
                    expanded = openGroups.contains("acl"),
                    onToggle = { toggleGroup("acl") },
                )
            }
            if (openGroups.contains("acl")) {
                item {
                    GlassTextField(
                        value = form!!.aclText,
                        onValueChange = { form = form!!.copy(aclText = it) },
                        label = stringResource(R.string.edit_acl_label),
                        placeholder = "[[acl.acl_v1.chains]]\nname = \"protect\"",
                        singleLine = false,
                        minLines = 4,
                        maxLines = 14,
                        mono = true,
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.edit_section_import_export)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton(
                    stringResource(R.string.edit_import_toml),
                    onClick = { showImport = true },
                    secondary = true,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    stringResource(R.string.edit_export_toml),
                    onClick = { showExport = true },
                    secondary = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (networkId != "new") {
            item {
                PillButton(
                    stringResource(R.string.edit_share_qr),
                    onClick = { onShare(networkId) },
                    secondary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            PillButton(
                stringResource(R.string.edit_restore_listeners),
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
                    stringResource(R.string.edit_delete_network),
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
            tomlErrorMessage(context, runCatching { TomlCodec.importToml(importText) }.exceptionOrNull())
        }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text(stringResource(R.string.edit_import_toml)) },
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
                        stringResource(R.string.edit_choose_file),
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
                            text = stringResource(R.string.edit_import_hint),
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
                                importError = tomlErrorMessage(context, it)
                                null
                            }
                        if (parsed != null) beginImport(parsed)
                    },
                ) { Text(stringResource(R.string.action_import)) }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text(stringResource(R.string.action_cancel)) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showExport) {
        val text = buildExportText(exportWithoutSecret)
        val hasSecret = currentUi().secret.isNotBlank() ||
            (mode == EditMode.Toml && tomlText.contains("network_secret"))
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text(stringResource(R.string.edit_export_toml)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (hasSecret) {
                        Text(
                            text = stringResource(R.string.edit_export_warning),
                            color = g.warn,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    GlassSwitchRow(
                        title = stringResource(R.string.edit_export_no_secret),
                        subtitle = stringResource(R.string.edit_export_no_secret_sub),
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
                    }) { Text(stringResource(R.string.edit_save_as_file)) }
                    TextButton(onClick = {
                        // Clipboard defaults to the masked form even when the file keeps secrets.
                        copyToClipboard(
                            context,
                            "TOML",
                            maskTomlSecrets(buildExportText(exportWithoutSecret)),
                        )
                    }) { Text(stringResource(R.string.action_copy)) }
                }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text(stringResource(R.string.action_close)) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    conflictImport?.let { parsed ->
        AlertDialog(
            onDismissRequest = { conflictImport = null },
            title = { Text(stringResource(R.string.edit_conflict_title)) },
            text = {
                Text(
                    stringResource(R.string.edit_conflict_text, parsed.instanceId.orEmpty()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    applyImported(parsed, saveAsNew = true)
                    conflictImport = null
                    importText = ""
                    Toast.makeText(context, context.getString(R.string.edit_saved_as_new), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.networks_save_as_new)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    applyImported(parsed, saveAsNew = false)
                    conflictImport = null
                    importText = ""
                    Toast.makeText(context, context.getString(R.string.edit_will_overwrite), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.action_overwrite)) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (clientConfig != null) {
        AlertDialog(
            onDismissRequest = { clientConfig = null },
            title = { Text(stringResource(R.string.edit_client_config_title)) },
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
                TextButton(onClick = { copyToClipboard(context, "WireGuard", clientConfig!!) }) { Text(stringResource(R.string.action_copy)) }
            },
            dismissButton = { TextButton(onClick = { clientConfig = null }) { Text(stringResource(R.string.action_close)) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.edit_discard_title),
            text = stringResource(R.string.edit_discard_text),
            confirmText = stringResource(R.string.edit_discard),
            onConfirm = {
                showDiscard = false
                onBack()
            },
            onDismiss = { showDiscard = false },
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = stringResource(R.string.edit_delete_network),
            text = stringResource(R.string.edit_delete_text, current.name),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                vm.deleteNetwork(networkId)
                showDelete = false
                onBack()
            },
            onDismiss = { showDelete = false },
        )
    }
}

private fun validationErrors(network: UiNetwork): List<Int> {
    val errors = mutableListOf<Int>()
    if (network.name.isBlank()) errors.add(R.string.edit_validation_name_required)
    if (!network.dhcp && network.ipv4.isNotBlank() && !isValidCidr(network.ipv4)) errors.add(R.string.edit_validation_static_ipv4)
    if (network.ipv6.isNotBlank() && !isValidCidr(network.ipv6)) errors.add(R.string.edit_validation_static_ipv6)
    if (network.mtu.isNotBlank()) {
        val mtu = network.mtu.toIntOrNull()
        if (mtu == null || mtu !in 400..1380) errors.add(R.string.edit_validation_mtu)
    }
    if (network.instanceRecvBpsLimit.isNotBlank() && network.instanceRecvBpsLimit.toLongOrNull() == null) {
        errors.add(R.string.edit_validation_recv_limit)
    }
    if (network.foreignRelayBpsLimit.isNotBlank() && network.foreignRelayBpsLimit.toLongOrNull() == null) {
        errors.add(R.string.edit_validation_relay_limit)
    }
    if (network.multiThreadCount.toIntOrNull()?.let { it < 1 } != false) errors.add(R.string.edit_validation_threads)
    if (network.socketMark.isNotBlank() && network.socketMark.toIntOrNull() == null) errors.add(R.string.edit_validation_socket_mark)
    if (network.socks5.isNotBlank() && !SocksPattern.matches(network.socks5)) errors.add(R.string.edit_validation_socks5)
    if (network.servers.any { it.isNotBlank() && !UrlPattern.matches(it) }) errors.add(R.string.edit_validation_servers)
    if (network.listeners.any { it.isNotBlank() && !ListenerPattern.matches(it) }) errors.add(R.string.edit_validation_listeners)
    if (network.mappedListeners.any { it.isNotBlank() && !ListenerPattern.matches(it) }) errors.add(R.string.edit_validation_mapped)
    if (network.proxyNetworks.any { parseProxyNetwork(formatProxyNetwork(it)) == null }) errors.add(R.string.edit_validation_proxy_cidr)
    network.portForwards.forEach { if (it.toDraft().error() != null) errors.add(R.string.edit_validation_portforward) }
    if (network.relayNetworkWhitelistEnabled && network.relayNetworkWhitelist.any { it.isBlank() }) {
        errors.add(R.string.edit_validation_whitelist)
    }
    val portal = network.vpnPortal
    if (portal != null) {
        if (!isValidHostPort(portal.wireguardListen)) errors.add(R.string.edit_validation_portal_listen)
        val portalKey = portal.wireguardPrivateKey
        if (portalKey != null && portalKey.isNotBlank() && base64Size(portalKey) != 32) {
            errors.add(R.string.edit_validation_portal_key)
        }
        if (portal.clients.size > 64) errors.add(R.string.edit_validation_clients_max)
        if (portal.clients.any { !ClientNamePattern.matches(it.name) }) errors.add(R.string.edit_validation_client_name)
        if (portal.clients.map { it.name }.toSet().size != portal.clients.size) errors.add(R.string.edit_validation_client_duplicate)
        if (portal.clients.any { !isValidCidr(it.virtualIp) }) errors.add(R.string.edit_validation_client_ip)
    }
    network.secureMode?.localPrivateKey?.takeIf { it.isNotBlank() }?.let {
        if (base64Size(it) != 32) errors.add(R.string.edit_validation_secure_key)
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
        text = stringResource(R.string.edit_error_prefix, text),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 1.dp),
    )
}

@Composable
private fun InlineErrorRes(@androidx.annotation.StringRes textRes: Int?) {
    if (textRes == null) return
    InlineError(stringResource(textRes))
}

/**
 * Network secret input. A local copy of GlassTextField (the shared component cannot
 * take a FocusRequester) so an import without a secret can focus this field.
 */
@Composable
private fun SecretField(
    value: String,
    onChange: (String) -> Unit,
    focus: androidx.compose.ui.focus.FocusManager,
    focusRequester: FocusRequester,
) {
    val g = LocalGlassColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(stringResource(R.string.edit_secret), style = MaterialTheme.typography.labelSmall, color = g.muted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            ),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            visualTransformation = PasswordVisualTransformation(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.edit_secret_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = g.muted.copy(alpha = 0.7f),
                        )
                    }
                    inner()
                }
            },
        )
    }
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

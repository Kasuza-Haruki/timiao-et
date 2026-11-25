package app.timiao.et.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.R
import app.timiao.et.share.QrCode
import app.timiao.et.share.ShareCodec
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.EmptyGlassCard
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassSwitchRow
import app.timiao.et.ui.components.InfoRow
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.theme.LocalGlassColors

/**
 * Share one network as a `timiao://import` link plus a QR code.
 * Secrets are removed by default; the toggle is the only way to include them.
 */
@Composable
fun ShareScreen(
    vm: AppViewModel,
    networkId: String,
    onBack: () -> Unit,
) {
    val networks by vm.networks.collectAsStateWithLifecycle()
    val network = networks.firstOrNull { it.id == networkId }
    var includeSecret by rememberSaveable(networkId) { mutableStateOf(false) }
    val context = LocalContext.current
    val g = LocalGlassColors.current

    val toml = remember(network, includeSecret) {
        network?.let { raw ->
            val full = vm.exportToml(raw)
            if (includeSecret) full else ShareCodec.stripSecrets(full)
        }
    }
    val link = remember(toml) { toml?.takeIf { it.isNotBlank() }?.let(ShareCodec::encode) }
    val qrFits = link != null && link.length <= ShareCodec.MAX_QR_CHARS

    fun copyLink() {
        val value = link ?: return
        val manager = context.getSystemService(ClipboardManager::class.java)
        if (manager == null) {
            Toast.makeText(context, context.getString(R.string.label_clipboard_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.share_clipboard_link), value))
        Toast.makeText(context, context.getString(R.string.share_copied), Toast.LENGTH_SHORT).show()
    }

    fun systemShare() {
        val value = link ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
        runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser))) }
            .onFailure { Toast.makeText(context, context.getString(R.string.share_no_app), Toast.LENGTH_SHORT).show() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.share_title),
                subtitle = network?.name ?: stringResource(R.string.share_not_found),
                onBack = onBack,
            )
        }

        if (network == null) {
            item { EmptyGlassCard(stringResource(R.string.share_deleted)) }
            return@LazyColumn
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                InfoRow(stringResource(R.string.share_net_name), network.name)
                InfoRow(stringResource(R.string.share_node_count), network.servers.size.toString())
                InfoRow(stringResource(R.string.share_secret), stringResource(if (includeSecret) R.string.share_secret_included else R.string.share_secret_removed))
                InfoRow(stringResource(R.string.share_link_length), stringResource(R.string.share_chars, link?.length ?: 0))
            }
        }

        item {
            GlassSwitchRow(
                title = stringResource(R.string.share_include_secret),
                subtitle = stringResource(R.string.share_include_secret_sub),
                checked = includeSecret,
                onCheckedChange = { includeSecret = it },
            )
        }

        if (includeSecret) {
            item {
                val error = MaterialTheme.colorScheme.error
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
                        .padding(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.share_warning_title),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.share_warning_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.share_qr_section)) }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(26.dp), GlassLevel.Regular)
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (qrFits && link != null) {
                    Box(
                        modifier = Modifier
                            .size(236.dp)
                            .clip(RoundedCornerShape(18.dp)),
                    ) {
                        QrCode.QrCanvas(text = link, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.share_qr_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = g.muted,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.share_qr_too_large),
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.muted,
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.share_link_section)) }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = link ?: stringResource(R.string.share_no_link),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = stringResource(R.string.share_copy_link),
                    onClick = ::copyLink,
                    enabled = link != null,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = stringResource(R.string.share_system_share),
                    onClick = ::systemShare,
                    enabled = link != null,
                    modifier = Modifier.weight(1f),
                    secondary = true,
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.share_footer),
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

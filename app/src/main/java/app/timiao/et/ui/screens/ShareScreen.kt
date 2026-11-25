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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            Toast.makeText(context, "剪贴板不可用", Toast.LENGTH_SHORT).show()
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText("缇喵配置链接", value))
        Toast.makeText(context, "分享链接已复制", Toast.LENGTH_SHORT).show()
    }

    fun systemShare() {
        val value = link ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "分享网络配置")) }
            .onFailure { Toast.makeText(context, "没有可分享的应用", Toast.LENGTH_SHORT).show() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "分享网络",
                subtitle = network?.name ?: "网络不存在",
                onBack = onBack,
            )
        }

        if (network == null) {
            item { EmptyGlassCard("这个网络已被删除。") }
            return@LazyColumn
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                InfoRow("网络名称", network.name)
                InfoRow("节点数", network.servers.size.toString())
                InfoRow("密钥", if (includeSecret) "包含" else "已移除")
                InfoRow("链接长度", "${link?.length ?: 0} 字符")
            }
        }

        item {
            GlassSwitchRow(
                title = "包含密钥",
                subtitle = "默认关闭。关闭时会从分享内容中删除 network_secret 等密钥字段。",
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
                        text = "警告：链接包含密钥",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "任何拿到链接或二维码的人都能加入这个网络。只发给可信的人，不要发到公开群组。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        item { SectionLabel("二维码") }
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
                        text = "让另一台设备打开「扫码导入」并对准二维码",
                        style = MaterialTheme.typography.labelSmall,
                        color = g.muted,
                    )
                } else {
                    Text(
                        text = "配置过大，二维码放不下。请改用复制链接或系统分享。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.muted,
                    )
                }
            }
        }

        item { SectionLabel("分享链接") }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = link ?: "无法生成链接",
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
                    text = "复制链接",
                    onClick = ::copyLink,
                    enabled = link != null,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = "系统分享",
                    onClick = ::systemShare,
                    enabled = link != null,
                    modifier = Modifier.weight(1f),
                    secondary = true,
                )
            }
        }
        item {
            Text(
                text = "接收方用缇喵的「扫码导入」，或直接打开链接即可导入。缺少密钥时，导入后需要补填。",
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

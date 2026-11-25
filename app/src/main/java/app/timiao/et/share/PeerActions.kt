package app.timiao.et.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.timiao.et.R
import app.timiao.et.core.PeerInfo
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.theme.LocalGlassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** System actions for one peer. All failures are reported, never thrown at the UI. */
class PeerActions(private val context: Context) {

    fun copy(peer: PeerInfo, copyIp: Boolean): Boolean {
        val value = if (copyIp) peer.hostAddress() else peer.hostname.takeIf { it.isNotBlank() }
        if (value == null) {
            toast(context.getString(R.string.peer_no_copy))
            return false
        }
        val manager = context.getSystemService(ClipboardManager::class.java)
        if (manager == null) {
            toast(context.getString(R.string.label_clipboard_unavailable))
            return false
        }
        manager.setPrimaryClip(ClipData.newPlainText(context.getString(if (copyIp) R.string.clip_peer_ip else R.string.peer_hostname), value))
        toast(context.getString(R.string.label_copied, value))
        return true
    }

    fun openSsh(peer: PeerInfo): LaunchResult = open("ssh", peer)
    fun openRdp(peer: PeerInfo): LaunchResult = open("rdp", peer)

    private fun open(scheme: String, peer: PeerInfo): LaunchResult {
        val host = peer.hostAddress() ?: peer.hostname.takeIf { it.isNotBlank() }
        if (host == null || !isSafeHost(host)) {
            toast(context.getString(R.string.peer_invalid_address))
            return LaunchResult.NoHandler
        }
        val authority = if (host.contains(':')) "[$host]" else host
        val uri = Uri.parse("$scheme://$authority")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            LaunchResult.Opened
        } catch (_: ActivityNotFoundException) {
            toast(context.getString(R.string.peer_open_no_app, scheme))
            LaunchResult.NoHandler
        } catch (_: SecurityException) {
            toast(context.getString(R.string.peer_open_denied, scheme))
            LaunchResult.NoHandler
        }
    }

    /** Runs `/system/bin/ping -c 3` and returns the three reply lines (or an error). */
    suspend fun ping(peer: PeerInfo, timeoutSec: Int = 10): PingResult = withContext(Dispatchers.IO) {
        val host = peer.hostAddress() ?: peer.hostname.takeIf { it.isNotBlank() }
        if (host == null || !isSafeHost(host)) {
            return@withContext PingResult(false, emptyList(), context.getString(R.string.peer_invalid_address))
        }
        val process = try {
            ProcessBuilder("/system/bin/ping", "-c", "3", "-W", "2", host)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return@withContext PingResult(false, emptyList(), e.message ?: context.getString(R.string.peer_ping_start_failed))
        }
        try {
            if (!process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) {
                process.destroy()
                process.waitFor(1, TimeUnit.SECONDS)
                return@withContext PingResult(false, emptyList(), context.getString(R.string.peer_ping_timeout))
            }
            val output = process.inputStream.bufferedReader().use { it.readLines() }
            val replies = output.filter { it.contains("bytes from") || it.contains("time=") }
            val lines = if (replies.isNotEmpty()) replies.take(3) else output.filter { it.isNotBlank() }.take(4)
            PingResult(process.exitValue() == 0, lines, if (lines.isEmpty()) context.getString(R.string.peer_ping_no_reply) else null)
        } catch (e: Exception) {
            process.destroy()
            PingResult(false, emptyList(), e.message ?: context.getString(R.string.peer_ping_failed))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Bottom-sheet content for peer actions. The host app embeds this in its existing
 * node detail sheet; [onDone] closes the sheet.
 */
@Composable
fun PeerActionsSheetContent(
    peer: PeerInfo,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val actions = remember(context) { PeerActions(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var ping by remember(peer) { mutableStateOf<PingResult?>(null) }
    var running by remember(peer) { mutableStateOf(false) }
    val g = LocalGlassColors.current
    val address = peer.hostAddress()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.peer_actions_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.peers_subtitle_with_net, peer.hostname, address ?: stringResource(R.string.label_no_ip)),
            style = MaterialTheme.typography.labelMedium,
            color = g.muted,
        )
        Spacer(Modifier.height(2.dp))

        ActionRow(stringResource(R.string.peer_copy_ip), enabled = address != null) {
            actions.copy(peer, copyIp = true)
            onDone()
        }
        ActionRow(stringResource(R.string.peer_copy_hostname), enabled = peer.hostname.isNotBlank()) {
            actions.copy(peer, copyIp = false)
            onDone()
        }
        ActionRow(stringResource(R.string.peer_open_ssh)) {
            if (actions.openSsh(peer) == LaunchResult.Opened) onDone()
        }
        ActionRow(stringResource(R.string.peer_open_rdp)) {
            if (actions.openRdp(peer) == LaunchResult.Opened) onDone()
        }
        ActionRow(stringResource(R.string.peer_ping_three), enabled = !running) {
            running = true
            ping = null
            scope.launch {
                ping = actions.ping(peer)
                running = false
            }
        }

        when {
            running -> Text(stringResource(R.string.peer_pinging), style = MaterialTheme.typography.bodySmall, color = g.muted)
            ping != null -> {
                val result = ping
                if (result != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(16.dp), GlassLevel.Subtle)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = stringResource(if (result.ok) R.string.peer_ping_ok else R.string.peer_ping_fail),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (result.ok) g.good else MaterialTheme.colorScheme.error,
                        )
                        if (result.error != null) {
                            Text(result.error, style = MaterialTheme.typography.bodySmall, color = g.muted)
                        }
                        result.results.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        PillButton(stringResource(R.string.action_close), onClick = onDone, modifier = Modifier.fillMaxWidth(), secondary = true)
    }
}

@Composable
private fun ActionRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val g = LocalGlassColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp), GlassLevel.Subtle)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else g.muted,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleMedium, color = g.muted)
    }
}

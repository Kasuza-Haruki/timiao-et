package app.timiao.et.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.timiao.et.R
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.components.pageInset
import app.timiao.et.ui.theme.LocalGlassColors

/** Shows the bundled third-party notices, copied from THIRD-PARTY-NOTICES.md. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val body = remember {
        runCatching {
            context.assets.open("licenses.md").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    val inset = pageInset()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = inset,
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.licenses_title),
                subtitle = stringResource(R.string.licenses_subtitle),
                onBack = onBack,
            )
        }
        item {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .glass(RoundedCornerShape(22.dp), GlassLevel.Subtle)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    NoticeText(body)
                }
            }
        }
    }
}

@Composable
private fun NoticeText(body: String) {
    val g = LocalGlassColors.current
    body.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> Spacer(Modifier.height(7.dp))
            line.startsWith("```") -> Unit
            line.startsWith("# ") -> Text(
                text = line.removePrefix("# "),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            line.startsWith("## ") -> Text(
                text = line.removePrefix("## "),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 10.dp, bottom = 3.dp),
            )
            line.startsWith("### ") -> Text(
                text = line.removePrefix("### "),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 7.dp, bottom = 2.dp),
            )
            line.startsWith("|") -> Text(
                text = line,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = g.muted,
            )
            else -> Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

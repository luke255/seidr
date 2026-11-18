package eu.bradshaw.seidr.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.LogExport
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.LocalIsTv
import eu.bradshaw.seidr.ui.components.BackTopBar
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.primaryFocus
import java.io.File

/** Settings → About → Share a log: the scrubbed log, shown in full before it's shared. */
@Composable
fun LogScreen() {
    val actions = LocalActions.current
    val context = LocalContext.current
    val tv = LocalIsTv.current
    val log by produceState<String?>(null) { value = LogExport.collect(actions.graph, tv) }
    val lines = remember(log) { log?.lines()?.dropLastWhile { it.isBlank() }.orEmpty() }
    val copied = stringResource(R.string.log_copied)
    val noApp = stringResource(R.string.log_no_app)
    val subject = stringResource(R.string.log_subject)

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.log_title))
        Text(
            stringResource(R.string.log_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                enabled = log != null,
                onClick = {
                    val text = log ?: return@FilledTonalButton
                    val file = File(context.cacheDir, "logs").apply { mkdirs() }.resolve(LogExport.FILE_NAME)
                    file.writeText(text)
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, subject)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }.onFailure { actions.toast(noApp) }
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.primaryFocus().focusRing(CircleShape),
            ) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.log_share))
            }
            OutlinedButton(
                enabled = log != null,
                onClick = {
                    val text = log ?: return@OutlinedButton
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(subject, text))
                    actions.toast(copied)
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.log_copy))
            }
        }
        if (log == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            // A row per line, focusable, so a remote can scroll through it.
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
                items(lines) { line ->
                    Text(
                        line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().focusable().padding(horizontal = 16.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

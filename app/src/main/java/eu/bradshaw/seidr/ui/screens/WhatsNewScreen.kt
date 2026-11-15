package eu.bradshaw.seidr.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.BuildConfig
import eu.bradshaw.seidr.ui.components.BackTopBar
import eu.bradshaw.seidr.ui.components.CardLabel
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import eu.bradshaw.seidr.update.Changelog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings → About → What's new: every version's changes, from the bundled CHANGELOG.md. */
@Composable
fun WhatsNewScreen() {
    val context = LocalContext.current
    val releases by produceState(emptyList<Changelog.Release>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(Changelog.ASSET).bufferedReader().use { Changelog.parse(it.readText()) } }
                .getOrDefault(emptyList())
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("What's new")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            for (r in releases) {
                item(key = r.version) {
                    CardLabel(if (r.version == BuildConfig.VERSION_NAME) "${r.version} (this version)" else r.version)
                }
                // An item per change, focusable, so a remote can step through them.
                items(r.items) { change ->
                    Row(Modifier.fillMaxWidth().focusable().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(change, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

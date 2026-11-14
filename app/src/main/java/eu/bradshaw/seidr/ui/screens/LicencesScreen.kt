package eu.bradshaw.seidr.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.components.BackTopBar
import eu.bradshaw.seidr.ui.components.CardDivider
import eu.bradshaw.seidr.ui.components.CardGroup
import eu.bradshaw.seidr.ui.components.CardItem
import eu.bradshaw.seidr.ui.components.CardLabel
import eu.bradshaw.seidr.ui.components.Chevron
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One thing Seiðr ships, and the licence text for it in `assets/licences` (null: none). */
private class Credit(val name: String, val by: String, val licence: String, val file: String?)

private val APP = Credit("Seiðr", "github.com/luke255/seidr", "GNU GPL 3.0", "GPL-3.0.txt")

private val CREDITS = listOf(
    Credit("projectM 4.1.8", "The projectM team. Shipped unmodified; its source is in Seiðr's repository", "GNU LGPL 2.1", "LGPL-2.1.txt"),
    Credit("MilkDrop presets (Cream of the Crop)", "Curated by Jason Fletcher; each preset by the author in its name", "Shared freely; authors may ask for removal", null),
    Credit("MilkDrop texture pack", "Collected by the projectM team", "Shared freely; authors may ask for removal", null),
    Credit("AndroidX: Compose, Material 3, Media3, Room, WorkManager, CameraX", "Google", "Apache 2.0", "Apache-2.0.txt"),
    Credit("Material icons", "Google", "Apache 2.0", "Apache-2.0.txt"),
    Credit("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains", "Apache 2.0", "Apache-2.0.txt"),
    Credit("OkHttp and Retrofit", "Square", "Apache 2.0", "Apache-2.0.txt"),
    Credit("Coil", "Coil contributors", "Apache 2.0", "Apache-2.0.txt"),
    Credit("ZXing", "ZXing authors", "Apache 2.0", "Apache-2.0.txt"),
    Credit("OpenXR loader", "The Khronos Group", "Apache 2.0", "Apache-2.0.txt"),
)

/** Settings → About → Open-source licences. */
@Composable
fun LicencesScreen() {
    val actions = LocalActions.current
    @Composable
    fun item(c: Credit) = CardItem(
        Icons.Outlined.Description, c.name, "${c.licence} · ${c.by}",
        trailing = if (c.file != null) ({ Chevron() }) else null,
        onClick = c.file?.let { f -> { actions.open(Route.LicenceText(c.licence, f)) } },
    )
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Open-source licences")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            item { CardGroup { item(APP) } }
            item { CardLabel("Seiðr includes") }
            item {
                CardGroup {
                    CREDITS.forEachIndexed { i, c ->
                        if (i > 0) CardDivider()
                        item(c)
                    }
                }
            }
        }
    }
}

/** A licence's full text, a paragraph per item so a remote can step through it. */
@Composable
fun LicenceTextScreen(title: String, file: String) {
    val context = LocalContext.current
    val paragraphs by produceState(emptyList<String>(), file) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("licences/$file").bufferedReader().use { it.readText() }
        }.split(Regex("\n\\s*\n")).map { it.trimEnd() }.filter { it.isNotBlank() }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(title)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            items(paragraphs) { p ->
                Text(
                    p, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().focusable().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

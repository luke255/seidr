package eu.bradshaw.seidr.ui.screens

import androidx.annotation.StringRes
import eu.bradshaw.seidr.R
import androidx.compose.ui.res.stringResource
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
private class Credit(@StringRes val name: Int, @StringRes val by: Int, @StringRes val licence: Int, val file: String?)

private val APP = Credit(R.string.licences_app_name, R.string.licences_app_by, R.string.licences_gpl3, "GPL-3.0.txt")

private val CREDITS = listOf(
    Credit(R.string.licences_projectm, R.string.licences_projectm_by, R.string.licences_lgpl21, "LGPL-2.1.txt"),
    Credit(R.string.licences_presets, R.string.licences_presets_by, R.string.licences_shared_freely, null),
    Credit(R.string.licences_textures, R.string.licences_textures_by, R.string.licences_shared_freely, null),
    Credit(R.string.licences_androidx, R.string.licences_google, R.string.licences_apache2, "Apache-2.0.txt"),
    Credit(R.string.licences_material_icons, R.string.licences_google, R.string.licences_apache2, "Apache-2.0.txt"),
    Credit(R.string.licences_kotlin, R.string.licences_jetbrains, R.string.licences_apache2, "Apache-2.0.txt"),
    Credit(R.string.licences_okhttp, R.string.licences_square, R.string.licences_apache2, "Apache-2.0.txt"),
    Credit(R.string.licences_coil, R.string.licences_coil_by, R.string.licences_apache2, "Apache-2.0.txt"),
    Credit(R.string.licences_openxr, R.string.licences_khronos, R.string.licences_apache2, "Apache-2.0.txt"),
)

/** Settings → About → Open-source licences. */
@Composable
fun LicencesScreen() {
    val actions = LocalActions.current
    @Composable
    fun item(c: Credit) {
        val licence = stringResource(c.licence)
        CardItem(
            Icons.Outlined.Description, stringResource(c.name), stringResource(R.string.licences_licence_by, licence, stringResource(c.by)),
            trailing = if (c.file != null) ({ Chevron() }) else null,
            onClick = c.file?.let { f -> { actions.open(Route.LicenceText(licence, f)) } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.licences_title))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            item { CardGroup { item(APP) } }
            item { CardLabel(stringResource(R.string.licences_includes)) }
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
        }.split(Regex("\n\\s*\n"))
            // The files are hard-wrapped at ~70 columns; rewrap each paragraph to the screen.
            .map { it.trim().replace(Regex("\\s*\n\\s*"), " ") }
            .filter { it.isNotBlank() }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(title)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            items(paragraphs) { p ->
                Text(
                    p, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().focusable().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

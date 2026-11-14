package eu.bradshaw.seidr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.selectToEdit
import eu.bradshaw.seidr.update.Updater

/**
 * How much of the bottom of the screen the floating nav bar covers. On the
 * cover screen content scrolls under it, so scrolling screens end with this
 * much padding. Zero on the wide layout, where nothing
 * overlaps.
 */
val LocalBottomChrome = compositionLocalOf { 0.dp }

/** True on the wide layout, where the nav rail carries the Settings button. */
val LocalWideShell = staticCompositionLocalOf { false }

/** Bottom padding for a scrolling screen: clear of the floating chrome, plus [extra]. */
@Composable
fun bottomContentPadding(extra: Dp = 16.dp): PaddingValues = PaddingValues(bottom = LocalBottomChrome.current + extra)

/**
 * Opens Settings: a cog, dotted while an update is waiting (Updater). With
 * [label] it's a rail item (cog over "Settings"); otherwise an icon button.
 */
@Composable
fun SettingsButton(modifier: Modifier = Modifier, label: Boolean = false) {
    val actions = LocalActions.current
    val update by Updater.available.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val cog = @Composable { description: String?, tint: Color ->
        Box {
            Icon(Icons.Outlined.Settings, description, tint = tint)
            if (update != null) Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .size(10.dp)
                    .background(cs.tertiary, CircleShape),
            )
        }
    }
    if (label) {
        Column(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { actions.open(Route.Settings) }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            cog(if (update != null) "Update available" else null, cs.onSurfaceVariant)
            Text("Settings", color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    } else {
        IconButton(onClick = { actions.open(Route.Settings) }, modifier) {
            cog(if (update != null) "Settings, update available" else "Settings", LocalContentColor.current)
        }
    }
}

/**
 * The header of each tab's root screen: big title, an Offline pill while the
 * server is out of reach, then [actions].
 */
@Composable
fun TabHeader(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val online by LocalActions.current.graph.status.online.collectAsStateWithLifecycle()
    Row(
        modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Title and pill take the leftover width, so the actions sit at the end.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (!online) {
                Spacer(Modifier.width(10.dp))
                OfflinePill()
            }
        }
        actions()
    }
}

@Composable
fun OfflinePill() {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).background(cs.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CloudOff, null, Modifier.size(14.dp), tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("Offline", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

/** The filled, rounded search field (Search tab, Library). */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    androidx.compose.material3.TextField(
        value, onValueChange,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { onValueChange("") }) { Icon(Icons.Filled.Clear, "Clear") }
        },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = cs.surfaceContainerHighest,
            unfocusedContainerColor = cs.surfaceContainerHighest,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        shape = shape,
        modifier = modifier.selectToEdit(shape),
    )
}

/** A pill filter chip, filled with the theme colour when selected (Library, Search). */
@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit, icon: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label) },
        leadingIcon = icon,
        shape = CircleShape,
        border = null,
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            containerColor = cs.surfaceContainerHighest,
            labelColor = cs.onSurface,
            selectedContainerColor = cs.primary,
            selectedLabelColor = cs.onPrimary,
        ),
        modifier = Modifier.focusRing(CircleShape),
    )
}

package eu.bradshaw.seidr.ui.components

import eu.bradshaw.seidr.ui.highlightClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import eu.bradshaw.seidr.ui.rememberedFocus

/** Square-art card for horizontal shelves (Home, artist albums). */
@Composable
fun CoverCard(
    title: String,
    subtitle: String?,
    coverId: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    art: (@Composable (Modifier) -> Unit)? = null,
    /** Before the subtitle, on its line (Listening now's bars). */
    subtitleLead: (@Composable () -> Unit)? = null,
) {
    Column(modifier.width(140.dp).rememberedFocus().highlightClickable(onClick = onClick).padding(eu.bradshaw.seidr.ui.CardPadding)) {
        val m = Modifier.fillMaxWidth().aspectRatio(1f).clip(if (round) CircleShape else eu.bradshaw.seidr.ui.CardArtShape)
        when {
            art != null -> art(m)
            round -> Cover(coverId, m, shape = CircleShape, artist = true)
            else -> Cover(coverId, m)
        }
        // The text block always takes two title lines plus a subtitle line (an
        // invisible copy sets the height), so a shelf's height doesn't change as
        // cards scroll into view. The visible text sits at the top of it, so a
        // one-line title has its subtitle right under it, not a line further down.
        val align = if (round) TextAlign.Center else TextAlign.Start
        Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.alpha(0f).clearAndSetSemantics {}) {
                Text("", minLines = 2, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                Text("", minLines = 1, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.fillMaxWidth()) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    textAlign = align, modifier = Modifier.fillMaxWidth())
                if (!subtitle.isNullOrEmpty()) androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (subtitleLead != null) { subtitleLead(); androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp)) }
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = align, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * A list row with art, title and subtitle (Library, search results). [badge]
 * sits inline before the subtitle (downloaded, pinned).
 */
@Composable
fun ListRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    art: @Composable (Modifier) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    artSize: androidx.compose.ui.unit.Dp = 56.dp,
    badge: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).then(modifier).rememberedFocus().highlightClickable(onClick = onClick)
            .padding(eu.bradshaw.seidr.ui.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art(Modifier.size(artSize).clip(eu.bradshaw.seidr.ui.RowArtShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Row(verticalAlignment = Alignment.CenterVertically) {
                // The badge follows the text, and stays put when a long subtitle is cut short.
                Text(subtitle.orEmpty(), minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Spacer(Modifier.width(5.dp))
                    badge()
                }
            }
        }
        trailing?.invoke()
    }
}

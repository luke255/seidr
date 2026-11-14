package eu.bradshaw.seidr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner of the highlight around a clickable card or row that holds art. */
val HighlightCorner = 14.dp

/** Padding between a card's highlight and its art. */
val CardPadding = 6.dp

/** Padding between a row's highlight and its art. */
val RowPadding = 8.dp

/**
 * Corner for art sitting [padding] inside a highlight, so the two curves are
 * concentric and the art looks like it belongs to the selection box.
 */
fun artCorner(padding: Dp): Dp = (HighlightCorner - padding).coerceAtLeast(2.dp)

val CardArtShape = RoundedCornerShape(artCorner(CardPadding))
val RowArtShape = RoundedCornerShape(artCorner(RowPadding))

/**
 * Clickable for cards and rows with art. With a remote or keyboard the focus
 * highlight is drawn *behind* the content, so it rings the art instead of
 * greying it out (the ripple's focus layer sits on top). Touch keeps the ripple.
 */
@Composable
fun Modifier.highlightClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val keys = LocalInputModeManager.current.keys
    val fill = if (focused && keys) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f) else Color.Transparent
    return this
        .clip(RoundedCornerShape(HighlightCorner))
        .background(fill)
        .clickable(source, indication = if (keys) null else ripple(), enabled = enabled, onClick = onClick)
}

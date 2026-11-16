package eu.bradshaw.seidr.ui.player

import android.os.SystemClock
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.ui.LocalActions
import kotlinx.coroutines.flow.first

/** The cover sliding to the playing track, timed with the colours ([ART_CHANGE_MS]). Swipes settle at the pager's own pace. */
private val coverSlide = tween<Float>(ART_CHANGE_MS, easing = EaseOutCubic)

/**
 * The playing track's cover, a page per track: dragged, it follows the
 * finger and skips to the track it comes to rest on; when the track changes
 * any other way, it slides there itself (a cover at a time, however many
 * tracks share it). Now Playing's artwork, and the visualiser's track page.
 * [content] draws a page's cover (null: none). [justBack]: see
 * [rememberJustBack]; a change then shows without a slide.
 */
@Composable
internal fun ArtworkPager(justBack: () -> Boolean, modifier: Modifier, content: @Composable (cover: String?) -> Unit) {
    val player = LocalActions.current.player
    val ui by player.ui.collectAsStateWithLifecycle()
    var pages by remember { mutableStateOf(trackPages(ui.queue)) }
    val pager = rememberPagerState(initialPage = ui.index.coerceAtLeast(0)) { pages.size }
    val index by rememberUpdatedState(ui.index)
    // The pager is the user's from the moment they drag it until it comes to rest:
    // only then does the page it rests on play, and only then do we move it
    // ourselves. Moving it mid-fling (or skipping on a page it's still passing
    // through) has the two scrolls cancel each other and leaves it stuck mid-way.
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember(pager) { mutableStateOf(false) }
    // A skip the user's swipe asked for that the player hasn't reported yet.
    val pending = remember(pager) { longArrayOf(-1, 0) }
    // A slide was under way when the track changed again: it carries on to the new
    // cover from where it is (so the art settles ART_CHANGE_MS after the last skip).
    val sliding = remember(pager) { booleanArrayOf(false) }
    LaunchedEffect(ui.queue, ui.index) {
        snapshotFlow { userScrolling }.first { !it }
        val skipping = pending[0]
        if (skipping >= 0 && skipping != ui.index.toLong() && SystemClock.uptimeMillis() - pending[1] < 2000) return@LaunchedEffect
        pending[0] = -1
        val rest = trackPages(ui.queue)
        val cur = ui.index.coerceAtLeast(0)
        val art = rest.getOrNull(cur)?.art
        val shown = pages.getOrNull(pager.currentPage)
        val fraction = pager.currentPageOffsetFraction
        // Already showing this cover, at rest: nothing to see, just step over. Likewise
        // when the change happened while the app was away: the new cover is just there.
        if (shown == null || art == null || (shown.art == art && fraction == 0f) || justBack()) {
            pages = rest
            pager.requestScrollToPage(cur)
            sliding[0] = false
            return@LaunchedEffect
        }
        val slide = artPages(ui.queue, ui.index)
        val target = slide.indexOfFirst { it.land == ui.index }.coerceAtLeast(0)
        // The page on screen in the sliding pages: the same page if it's there (so a
        // slide carries on from exactly where it is), else the nearest with its cover.
        val from = slide.indexOfFirst { it.key == shown.key }.takeIf { it >= 0 }
            ?: slide.indices.filter { slide[it].art == shown.art }.minByOrNull { kotlin.math.abs(it - target) }
        // The first page of the new cover on the way from there.
        val to = when {
            from == null -> null
            slide[from].art == art -> from
            from < target -> (from + 1..target).first { slide[it].art == art }
            else -> (target until from).last { slide[it].art == art }
        }
        if (from == null || to == null || (kotlin.math.abs(to - from) > 1 && !sliding[0])) {
            // A jump, not a step: straight there.
            pages = rest
            pager.requestScrollToPage(cur)
            sliding[0] = false
            return@LaunchedEffect
        }
        pages = slide
        pager.requestScrollToPage(from, fraction)
        sliding[0] = true
        pager.animateScrollToPage(to, animationSpec = coverSlide)
        sliding[0] = false
        // Back to a page per track, on the same cover: no change on screen.
        pages = rest
        pager.requestScrollToPage(cur)
    }
    LaunchedEffect(dragged) { if (dragged) { userScrolling = true; sliding[0] = false } }
    LaunchedEffect(pager) {
        snapshotFlow { userScrolling && !dragged && !pager.isScrollInProgress && pager.currentPageOffsetFraction == 0f }
            .collect { atRest ->
                if (!atRest) return@collect
                val land = pages.getOrNull(pager.currentPage)?.land
                if (land != null && land != index && index >= 0) {
                    pending[0] = land.toLong(); pending[1] = SystemClock.uptimeMillis()
                    player.skipTo(land)
                }
                userScrolling = false
            }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        // Hidden from accessibility: the covers say nothing the title doesn't, and
        // skip is the buttons' job. Its scroll position changes every frame of a
        // slide, and with any accessibility service on (password managers, remote
        // helpers) each change had Compose re-walk the whole screen's semantics,
        // which on a TV cost more than drawing the frame.
        HorizontalPager(pager, Modifier.fillMaxSize().clearAndSetSemantics {}, beyondViewportPageCount = 1,
            key = { pages.getOrNull(it)?.key ?: it }) { page ->
            content(pages.getOrNull(page)?.cover)
        }
    }
}

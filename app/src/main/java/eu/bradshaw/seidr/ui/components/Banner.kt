package eu.bradshaw.seidr.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.R

/**
 * The app's banner (the TV banner's design) in the current theme: the
 * wordmark in onPrimary on a primary tile. For the screens around sign-in.
 */
@Composable
fun SeidrBanner(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(colors.primary).padding(horizontal = 28.dp, vertical = 14.dp)) {
        Image(
            painterResource(R.drawable.wordmark), contentDescription = "Seiðr",
            colorFilter = ColorFilter.tint(colors.onPrimary), modifier = Modifier.height(40.dp),
        )
    }
}

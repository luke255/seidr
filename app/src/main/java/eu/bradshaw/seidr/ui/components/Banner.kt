package eu.bradshaw.seidr.ui.components

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.R

/** The app's banner, as on the TV: the yellow wordmark on brand red. For the screens around sign-in. */
@Composable
fun SeidrBanner(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(colorResource(R.color.icon_bg)).padding(horizontal = 28.dp, vertical = 14.dp)) {
        Image(
            painterResource(R.drawable.wordmark), contentDescription = stringResource(R.string.app_name),
            colorFilter = ColorFilter.tint(colorResource(R.color.brand_yellow)), modifier = Modifier.height(40.dp),
        )
    }
}

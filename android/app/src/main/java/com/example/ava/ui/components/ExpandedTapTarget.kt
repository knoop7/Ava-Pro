package com.example.ava.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout-neutral enlarged tap target for a small visual (icons well under the 48dp
 * touch-target minimum). Budget panels with flaky digitizers drop taps that land a few
 * dp off a tiny icon, so the clickable area is inflated to [hitSize] via requiredSize
 * overflow — the caller-visible footprint ([modifier], typically `Modifier.size(…)`)
 * and everything around it stay pixel-identical.
 *
 * The hit circle uses the default indication, so presses show an IconButton-style
 * ripple halo. [content] must NOT carry its own clickable; semantics merge into the
 * single clickable node here.
 */
@Composable
fun ExpandedTapTarget(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hitSize: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .requiredSize(hitSize)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

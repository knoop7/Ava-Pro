package com.example.ava.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ava.ui.theme.AccentRed

/** Diameter shared by header toggles and minimal-launcher onboarding hints. */
val NewFeatureBadgeSize = 6.dp

/**
 * Small red onboarding dot (same visual as [com.example.ava.ui.screens.home.SunMoonToggle]).
 * Place inside a [Box] parent; defaults to the top-trailing corner with a slight outward offset.
 */
@Composable
fun BoxScope.NewFeatureBadge(
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier = modifier
            .align(Alignment.TopEnd)
            .offset(x = 2.dp, y = (-2).dp)
            .size(NewFeatureBadgeSize)
            .background(AccentRed, CircleShape),
    )
}

package com.example.ava.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.services.WebViewService
import com.example.ava.ui.ImmersiveMode
import com.example.ava.ui.rememberCompactSquareScreen
import com.example.ava.ui.avaContentWindowInsets
import com.example.ava.ui.avaTopBarWindowInsets
import com.example.ava.ui.Screen
import com.example.ava.ui.safePopBackStack
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.BottomSheetHandle
import com.example.ava.ui.screens.settings.components.SettingsHeaderBar
import com.example.ava.ui.screens.settings.components.settingsNavBarEdgeFade
import com.example.ava.ui.screens.settings.components.rememberSettingsFlingBehavior
import com.example.ava.ui.screens.settings.components.rememberSettingsLazyListState
import com.example.ava.ui.screens.settings.components.rememberUnconsumedNavigationBarBottom
import com.example.ava.ui.screens.settings.components.settingsDetailListContentPadding
import com.example.ava.ui.screens.settings.components.settingsListVerticalPadding


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDetailScreen(
    navController: NavController,
    title: String,
    listModifier: Modifier = Modifier,
    /**
     * Optional override. When null, scroll position is restored per nav route so
     * returning from a subpage (e.g. banner / scene edit) keeps the prior offset.
     */
    listState: LazyListState? = null,
    showBottomHandle: Boolean = false,
    onBottomHandleClick: () -> Unit = {},
    /** When set, replaces the default detail list content padding. */
    contentPadding: PaddingValues? = null,
    /**
     * Extra scroll clearance under the last item (e.g. floating dock height).
     * Added on top of the bottom-handle clearance when [contentPadding] is null.
     */
    listExtraBottom: Dp = 0.dp,
    /** Drawn above the list (aligned by the caller), e.g. a floating save dock. */
    bottomOverlay: (@Composable BoxScope.() -> Unit)? = null,
    userScrollEnabled: Boolean = true,
    /**
     * When set, skips `width > height`. Square panes (680×680) are not
     * landscape by that test but still need the one-screen landscape chrome.
     */
    landscapeLayout: Boolean? = null,
    /**
     * Shared leave hook for the header back button and the system back gesture.
     * Called once before the page pops. Diagnostic sensors use this to restart
     * the voice service after a batch of toggles, not on every switch.
     */
    onLeave: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)

    // Prefer this destination's route (not currentBackStackEntry) so fade-out
    // while a child is on top still saves under the correct key.
    val ownerEntry = LocalViewModelStoreOwner.current as? NavBackStackEntry
    val scrollMemoryKey = ownerEntry?.destination?.route ?: title
    val resolvedListState = listState ?: rememberSettingsLazyListState(scrollMemoryKey)
    
    val backgroundColor = if (isDarkMode) androidx.compose.ui.graphics.Color.Black else PureWhiteBackground
    val textColor = if (isDarkMode) DarkTextPrimary else SlateTextDark
    val splitActive = LocalSettingsSplitActive.current
    val configuration = LocalConfiguration.current
    val squarePanel = rememberCompactSquareScreen()
    val isLandscape = landscapeLayout
        ?: (splitActive || (
            !squarePanel && configuration.screenWidthDp > configuration.screenHeightDp
            ))
    ImmersiveMode(isLandscape = isLandscape)
    val currentRoute = navController.currentDestination?.route
    val hideBack = shouldHideSettingsSplitBack(
        splitActive = splitActive,
        ownerRoute = scrollMemoryKey,
        currentRoute = currentRoute,
    )
    val leadingIconRes = if (hideBack) {
        settingsSplitGroupLeadingIcon(scrollMemoryKey, currentRoute)
    } else {
        null
    }
    
    val topBarColor = backgroundColor.copy(alpha = 0.85f)
    val flingBehavior = rememberSettingsFlingBehavior()
    val leave = {
        onLeave?.invoke()
        if (hideBack) {
            WebViewService.exitSettings(context)
            if (!navController.popBackStack(Screen.SETTINGS, inclusive = true)) {
                navController.safePopBackStack()
            }
        } else {
            navController.safePopBackStack()
        }
    }
    BackHandler(enabled = onLeave != null || hideBack, onBack = leave)

    val handleListExtra = when {
        !showBottomHandle -> 0.dp
        splitActive -> (40.dp - settingsListVerticalPadding()).coerceAtLeast(0.dp)
        else -> 20.dp
    }

    val page: @Composable () -> Unit = {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = backgroundColor,
        contentWindowInsets = avaContentWindowInsets(isLandscape),
        topBar = {
            SettingsHeaderBar(
                title = title,
                titleColor = textColor,
                containerColor = topBarColor,
                onBack = leave,
                isLandscape = isLandscape,
                showBack = !hideBack,
                leadingIconRes = leadingIconRes,
                windowInsets = avaTopBarWindowInsets(isLandscape)
            )
        }
    ) { innerPadding ->
        val unconsumedNav = rememberUnconsumedNavigationBarBottom(
            innerPadding.calculateBottomPadding(),
        )
        val extraNav = if (isLandscape) unconsumedNav else 0.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (extraNav > 0.dp) Modifier.padding(bottom = extraNav) else Modifier
                )
        ) {
            LazyColumn(
                state = resolvedListState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .padding(innerPadding)
                    .settingsNavBarEdgeFade(backgroundColor)
                    .then(listModifier),
                contentPadding = contentPadding ?: settingsDetailListContentPadding(
                    extraBottom = handleListExtra + listExtraBottom,
                ),
                userScrollEnabled = userScrollEnabled,
                flingBehavior = flingBehavior,
                content = content
            )

            bottomOverlay?.invoke(this)

            // Fade so the pill doesn't pop in/out when a stats sheet hides the page handle.
            AnimatedVisibility(
                visible = showBottomHandle,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (splitActive) Modifier.offset(y = 10.dp) else Modifier),
                enter = fadeIn(animationSpec = tween(280)),
                exit = fadeOut(animationSpec = tween(200)),
            ) {
                BottomSheetHandle(
                    isDarkMode = isDarkMode,
                    onClick = onBottomHandleClick,
                )
            }
        }
    }
    }
    SettingsSidebarDrawerWrapper(navController, content = page)
}

/**
 * Same chrome as a split L2 page (header + card wells) with no live data.
 * NavHost Crossfade keeps this underneath; the real [ConnectionSettingsScreen]
 * paints on top when the default right pane is showing.
 */
@Composable
fun SettingsSplitDetailHold() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val backgroundColor = if (isDarkMode) androidx.compose.ui.graphics.Color.Black else PureWhiteBackground
    val textColor = if (isDarkMode) DarkTextPrimary else SlateTextDark
    val topBarColor = backgroundColor.copy(alpha = 0.85f)
    ImmersiveMode(isLandscape = true)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = backgroundColor,
        contentWindowInsets = avaContentWindowInsets(isLandscape = true),
        topBar = {
            SettingsHeaderBar(
                title = stringResource(R.string.settings_group_connection),
                titleColor = textColor,
                containerColor = topBarColor,
                onBack = {},
                isLandscape = true,
                showBack = false,
                leadingIconRes = settingsSplitGroupLeadingIcon(
                    Screen.SETTINGS_CONNECTION,
                    Screen.SETTINGS_CONNECTION,
                ),
                windowInsets = avaTopBarWindowInsets(isLandscape = true),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(innerPadding)
                .settingsNavBarEdgeFade(backgroundColor)
                .padding(settingsDetailListContentPadding()),
        ) {
            SimpleCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                )
            }
            SimpleCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(128.dp),
                )
            }
        }
    }
}

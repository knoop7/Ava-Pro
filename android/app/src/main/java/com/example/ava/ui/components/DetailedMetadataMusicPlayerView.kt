package com.example.ava.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.lyrics.LrcLine
import com.example.ava.lyrics.LrcParser
import com.example.ava.lyrics.LrcWord
import com.example.ava.lyrics.LyricDisplayRuntime
import com.example.ava.lyrics.LyricSource
import com.example.ava.lyrics.LyricsRepository
import com.example.ava.massapi.MassApiClient
import com.example.ava.massapi.MassApiManager
import com.example.ava.services.DashboardOverlayChrome
import com.example.ava.ui.OverlayLogoBadge
import com.example.ava.ui.rememberCompactSquareScreen
import com.example.ava.ui.stripParenthetical
import com.example.ava.ui.screens.settings.components.BottomSheetHandle
import com.example.ava.ui.screens.settings.components.MediaPlayerStatsPanel
import com.example.ava.utils.AmbientBitmapBlur
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Last-valid ("sticky") media metadata, held while live props briefly blank out
 * (partial HA/Sendspin packets, track-change seams).
 *
 * Intentionally plain fields, NOT snapshot state. Every mutation happens either
 * (a) during composition, from incoming props (which already drive that
 * recomposition), textually before all reads below, or (b) inside event lambdas
 * that also write real snapshot state (so recomposition still follows). Keeping
 * these out of the snapshot system avoids the Compose "backwards write"
 * (state written during composition) and the extra recomposition pass it forced
 * on every metadata change.
 */
private class StickyMediaMetadata(
    var title: String,
    var matchTitle: String,
    var artist: String,
    var album: String,
    var coverBitmap: Bitmap?,
    var totalTimeMs: Long,
    var progressMs: Long,
)

/**
 * Full-metadata music overlay (iOS Now Playing style).
 *
 * Data: Sendspin metadata@v1 (title/artist/album/artwork_url/progress.*) and
 * HA media_player attributes (media_title/media_artist/media_album_name/
 * media_position/media_duration). Selected via
 * [com.example.ava.settings.MediaOverlayStyle]; does not replace [GlassMusicPlayerView].
 *
 * Layout notes:
 * - Cover sizes itself from the remaining space (weight + aspectRatio), so any
 *   screen size / orientation fits without clipping.
 * - QQ Music LRC lyrics sync to playback position; portrait stacks title / artist.
 * - Cover tap is an internal lyrics-focus switch (default OFF). ON hides bottom
 *   playback; portrait puts title beside the cover, landscape uses a larger
 *   cover with title under it; lyric wall keeps DstIn edge dissolve and the
 *   focus cover keeps drop-shadow. Focus stays across track changes until the
 *   user leaves the full player (FAB / dismiss); next open starts collapsed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailedMetadataMusicPlayerView(
    coverUrl: String?,
    coverBitmap: Bitmap?,
    songTitle: String,
    artistName: String,
    albumName: String,
    isPlaying: Boolean,
    currentTimeMs: Long,
    totalTimeMs: Long,
    volumeLevel: Float = 1.0f,
    repeatMode: String = "off",
    shuffleEnabled: Boolean = false,
    isSendspinSource: Boolean = false,
    /** Sendspin: subtract from lyric clock only (progress bar keeps raw currentTimeMs). */
    lyricAudibleLagMs: Long = 0L,
    /**
     * VinylCoverService bumps this on an intentional 0:00 seat (dead stream).
     * Sticky progress and the finger-scrub seat otherwise keep the last mid-track
     * value when [currentTimeMs] is 0.
     */
    playheadForceEpoch: Int = 0,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onVolumeChange: (Float) -> Unit = {},
    onRepeatClick: () -> Unit = {},
    onShuffleClick: () -> Unit = {},
    /** Absolute seek via tap on the progress bar (Sendspin [seek] / position_ms). */
    onSeekClick: ((Long) -> Unit)? = null,
    /** Touch wakes [DashboardOverlayChrome] (same as weather); back button dismisses. */
    onRevealChrome: () -> Unit,
    onInvalidData: (() -> Unit)? = null,
    /**
     * True while the full detailed player is the active surface (not FAB / hidden).
     * When this becomes false, lyrics-focus resets so the next open starts collapsed.
     */
    fullPlayerActive: Boolean = true,
    /** Karaoke-style emphasized current lyric line on the expanded wall (beta). */
    karaokeLyrics: Boolean = false,
    modifier: Modifier = Modifier,
) {
    fun isNullLike(s: String?): Boolean {
        if (s == null) return true
        val lower = s.lowercase().trim()
        return lower == "null" || lower.isBlank()
    }

    val rawTitle = if (isNullLike(songTitle)) "" else songTitle.trim()
    val displayTitle = if (rawTitle.isBlank()) "" else stripParenthetical(rawTitle)
    val displayArtist = if (isNullLike(artistName)) "" else artistName
    val displayAlbum = if (isNullLike(albumName)) "" else albumName

    val hasValidNow = displayTitle.isNotBlank()
    val sticky = remember {
        StickyMediaMetadata(
            title = displayTitle,
            matchTitle = rawTitle,
            artist = displayArtist,
            album = displayAlbum,
            coverBitmap = coverBitmap,
            totalTimeMs = totalTimeMs.coerceAtLeast(0L),
            progressMs = currentTimeMs.coerceAtLeast(0L),
        )
    }
    if (hasValidNow) {
        val titleChanged = displayTitle != sticky.title
        sticky.title = displayTitle
        sticky.matchTitle = rawTitle
        // Title change: blank artist/album clears sticky credits (not Song A + Song B).
        // Same title + blank credit: keep sticky (partial HA/Sendspin packet).
        sticky.artist = when {
            displayArtist.isNotBlank() -> displayArtist
            titleChanged -> displayArtist
            else -> sticky.artist
        }
        sticky.album = when {
            displayAlbum.isNotBlank() -> displayAlbum
            titleChanged -> displayAlbum
            else -> sticky.album
        }
        sticky.coverBitmap = coverBitmap
        val nextTotalMs = totalTimeMs.coerceAtLeast(0L)
        if (nextTotalMs > 0L) {
            sticky.totalTimeMs = nextTotalMs
        } else if (titleChanged) {
            sticky.totalTimeMs = 0L
        }
        val nextProgressMs = currentTimeMs.coerceAtLeast(0L)
        if (nextProgressMs > 0L) {
            sticky.progressMs = nextProgressMs
        } else if (titleChanged) {
            sticky.progressMs = 0L
        }
    }

    LaunchedEffect(hasValidNow) {
        if (!hasValidNow) {
            kotlinx.coroutines.delay(2000)
            onInvalidData?.invoke()
        }
    }

    if (sticky.title.isBlank()) return

    val waitingForMediaLabel = stringResource(R.string.media_overlay_waiting_for_media)
    val effectiveTitle = if (hasValidNow) displayTitle else sticky.title
    val isWaitingForMedia = effectiveTitle == waitingForMediaLabel
    val effectiveMatchTitle =
        if (hasValidNow) rawTitle else sticky.matchTitle.ifBlank { effectiveTitle }
    // Prefer live artist; if title arrived first with blank credits, keep sticky
    // (Mass Up Next already seeded the row's artist before protocol catch-up).
    // Waiting-for-media shell: never keep previous track credits/art.
    val effectiveArtist = when {
        isWaitingForMedia -> ""
        displayArtist.isNotBlank() -> displayArtist
        else -> sticky.artist
    }
    val effectiveAlbum = when {
        isWaitingForMedia -> ""
        displayAlbum.isNotBlank() -> displayAlbum
        else -> sticky.album
    }
    val effectiveCoverBitmap = when {
        isWaitingForMedia -> null
        else -> (if (hasValidNow) coverBitmap else sticky.coverBitmap)
            ?.takeUnless { it.isRecycled }
    }

    // Modifier.blur is API 31+ only; pre-blur a tiny software copy on older devices.
    val supportsNativeBlur = AmbientBitmapBlur.supportsNativeComposeBlur
    val ambientBackdropBitmap = remember(effectiveCoverBitmap, supportsNativeBlur) {
        if (supportsNativeBlur) {
            null
        } else {
            effectiveCoverBitmap
                ?.takeUnless { it.isRecycled }
                ?.let { AmbientBitmapBlur.create(it) }
        }
    }
    // Never recycle the previous backdrop on key change: the AnimatedContent
    // backdrop below crossfades the old track's Image for a few hundred ms, so
    // its painter may still draw that bitmap (recycled-bitmap crash pre-API 31).
    // The blurred copy is tiny (≤72px) — let GC reclaim it, same policy as
    // VinylCoverService cover bitmaps.

    val titleKey = remember(effectiveTitle) { effectiveTitle.trim().lowercase() }
    val trackKey = remember(effectiveTitle, effectiveArtist) {
        "${effectiveTitle.trim().lowercase()}|${effectiveArtist.trim().lowercase()}"
    }
    // Finger scrub seat (progress tap / lyric double-tap). Independent of play/pause.
    var userSeekSeatMs by remember(titleKey) { mutableStateOf<Long?>(null) }
    var userSeekToken by remember(titleKey) { mutableLongStateOf(0L) }
    LaunchedEffect(playheadForceEpoch) {
        if (playheadForceEpoch <= 0) return@LaunchedEffect
        userSeekSeatMs = null
        sticky.progressMs = currentTimeMs.coerceAtLeast(0L)
    }
    val displayPositionMs = when {
        userSeekSeatMs != null -> {
            val seat = userSeekSeatMs!!
            val live = currentTimeMs.coerceAtLeast(0L)
            // Bar: optimistic seat until protocol ACK is on the same 1s grid, then follow live.
            if (live > 0L && kotlin.math.abs(live - seat) <= USER_SEEK_LYRIC_SEAT_CATCHUP_MS) {
                live
            } else {
                seat
            }
        }
        currentTimeMs > 0L -> currentTimeMs.coerceAtLeast(0L)
        else -> sticky.progressMs
    }
    val effectiveTotalTimeMs = when {
        totalTimeMs > 0L -> totalTimeMs.coerceAtLeast(0L)
        else -> sticky.totalTimeMs
    }
    // Manager lag is authoritative per paint: 0 means the bar is already the
    // presentation (speaker) clock, positive means the bar is on a send /
    // write clock and lyrics must undo pipeline + fill-in. It is never 0 for
    // "AudioTrack restarting" any more (reportedAudioLatencyMs has a floor),
    // so holding the last positive value across a seam turned the speaker
    // clock's 0 into a stale subtract — lyrics sat a pipeline late for the
    // rest of the track after every next/prev or seek wait.
    // Upstream value is protocol audio_latency (Manager) — clamp must not clip
    // typical 200–500ms pipeline back down to 150.
    val effectiveLyricLagMs =
        if (isSendspinSource) lyricAudibleLagMs.coerceIn(0L, LYRIC_AUDIBLE_LAG_UI_MAX_MS) else 0L
    // Progress bar keeps raw [displayPositionMs]. Lyrics subtract lag =
    // pipeline latency + bar fill-in lead (Manager), so highlight tracks the
    // audible tail / speaker rather than the smoothed bar playhead.
    //
    // Finger scrub (progress tap / lyric double-tap → onSeekClick) is a separate
    // channel from play/pause: hard-seat lyrics on the tapped second immediately,
    // including SP-only sessions. Hold briefly so lag subtract cannot yank the
    // highlight off the seat before SP ACK / audible remap lands.
    val boundOnSeekClick = onSeekClick?.let { sendSeek ->
        { rawMs: Long ->
            val seat = alignUserSeekSeatMs(rawMs)
            userSeekSeatMs = seat
            userSeekToken += 1L
            sticky.progressMs = seat
            sendSeek(rawMs)
        }
    }
    LaunchedEffect(userSeekToken, userSeekSeatMs) {
        val seat = userSeekSeatMs ?: return@LaunchedEffect
        kotlinx.coroutines.delay(USER_SEEK_LYRIC_SEAT_HOLD_MS)
        if (userSeekSeatMs == seat) userSeekSeatMs = null
    }
    val lyricSourceMs = when {
        // Keep lyrics on the tapped second (no lag subtract) while the seat holds.
        userSeekSeatMs != null -> userSeekSeatMs!!
        effectiveLyricLagMs > 0L ->
            (displayPositionMs - effectiveLyricLagMs).coerceAtLeast(0L)
        else -> displayPositionMs
    }

    var lyricLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var lyricSource by remember { mutableStateOf<LyricSource?>(null) }
    // Key the lyric clock on title only — empty→artist fill must not reset the
    // playhead (that looked like "unstable / early" scroll on every credit packet).
    val lyricPositionMs = rememberLyricSyncPositionMs(
        positionMs = lyricSourceMs,
        isPlaying = isPlaying,
        durationMs = effectiveTotalTimeMs,
        trackKey = titleKey,
        userSeekToken = userSeekToken,
        userSeekSeatMs = userSeekSeatMs,
    )
    var loadedLyricTitleKey by remember { mutableStateOf("") }
    // Bucket duration so tiny HA/Sendspin jitter does not cancel in-flight fetches.
    val durationBucket = (effectiveTotalTimeMs / 5_000L).coerceAtLeast(0L)
    // Cover tap = internal lyrics-focus switch (default OFF). Keep while this
    // full-player session is alive (incl. track changes); clear only when the
    // user leaves the surface (FAB / hide) so the next open starts collapsed.
    var lyricsFocus by remember { mutableStateOf(false) }
    var massRailOpen by remember { mutableStateOf(false) }
    // Portrait NP lyric band: compact (1-line) while the overlay is up.
    // Close restores 3-line first (snap, no up tween) then fades the overlay.
    var portraitNpCompact by remember { mutableStateOf(false) }
    var portraitRailDismissAfterRestore by remember { mutableStateOf(false) }
    var lastLyricsFocusToggleAt by remember { mutableLongStateOf(0L) }
    // Same-title / in-flight hold — while focus is on, keep last wall across
    // track fetch so the centered "middle" wait state is not forced every skip.
    var heldFocusLyricLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var heldFocusLyricSource by remember { mutableStateOf<LyricSource?>(null) }
    var heldFocusLyricTitleKey by remember { mutableStateOf("") }
    var lastFetchKaraokeLyrics by remember { mutableStateOf(false) }
    var lastExpandWordSyncKey by remember { mutableStateOf("") }
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(fullPlayerActive) {
        if (!fullPlayerActive) {
            lyricsFocus = false
            massRailOpen = false
            portraitNpCompact = false
            portraitRailDismissAfterRestore = false
            heldFocusLyricLines = emptyList()
            heldFocusLyricSource = null
            heldFocusLyricTitleKey = ""
        } else if (lyricLines.isEmpty() && effectiveTitle.isNotBlank()) {
            // Expanding FAB: paint from process memory before any network work.
            LyricsRepository.peekCached(
                title = effectiveMatchTitle,
                artist = effectiveArtist,
                album = effectiveAlbum,
                durationMs = effectiveTotalTimeMs.coerceAtLeast(0L),
                preferWordSync = karaokeLyrics,
            )?.takeIf { it.lines.isNotEmpty() }?.let { cached ->
                lyricLines = cached.lines
                lyricSource = cached.source
                loadedLyricTitleKey = titleKey
            }
        }
    }

    // Right rail = Mass API only. Empty shell / HA cold-start OK while MA is up;
    // SP-only (no MA session) must not show the pull tab.
    // Read-only accessor: the UI must never create/connect the Mass
    // side-channel — VoiceSatelliteService owns its lifecycle (cold-start /
    // low-end battery). No remember: picks up the instance once the service
    // creates it.
    val massManager by MassApiManager.instanceFlow.collectAsState()
    val massConnState by (massManager?.connectionState ?: emptyFlow()).collectAsState(initial = null)
    val massRailAvailable = massConnState is MassApiClient.ConnectionState.Connected
    LaunchedEffect(massRailAvailable) {
        if (!massRailAvailable) massRailOpen = false
    }

    // MA queue-clear → waiting-for-media: drop lyrics / karaoke focus immediately.
    LaunchedEffect(isWaitingForMedia) {
        if (!isWaitingForMedia) return@LaunchedEffect
        lyricsFocus = false
        lyricLines = emptyList()
        lyricSource = null
        heldFocusLyricLines = emptyList()
        heldFocusLyricSource = null
        heldFocusLyricTitleKey = ""
        loadedLyricTitleKey = ""
        lastExpandWordSyncKey = ""
    }

    // Album is part of identity — late album fill must rematch (same title/artist
    // can point at a different release once album arrives).
    LaunchedEffect(titleKey, effectiveArtist, effectiveAlbum, durationBucket, karaokeLyrics) {
        if (effectiveTitle.isBlank() || isWaitingForMedia) {
            lyricLines = emptyList()
            lyricSource = null
            heldFocusLyricLines = emptyList()
            heldFocusLyricSource = null
            heldFocusLyricTitleKey = ""
            loadedLyricTitleKey = ""
            lastExpandWordSyncKey = ""
            return@LaunchedEffect
        }
        val titleChanged = titleKey != loadedLyricTitleKey
        // Instant memory paint (FAB collapsed skip / remount) before clear+network.
        val peeked = LyricsRepository.peekCached(
            title = effectiveMatchTitle,
            artist = effectiveArtist,
            album = effectiveAlbum,
            durationMs = effectiveTotalTimeMs.coerceAtLeast(0L),
            preferWordSync = karaokeLyrics,
        )
        if (peeked != null && peeked.lines.isNotEmpty()) {
            lyricLines = peeked.lines
            lyricSource = peeked.source
            if (titleChanged) {
                loadedLyricTitleKey = titleKey
                lastFetchKaraokeLyrics = karaokeLyrics
                lastExpandWordSyncKey = ""
                if (!lyricsFocus) {
                    heldFocusLyricLines = emptyList()
                    heldFocusLyricSource = null
                    heldFocusLyricTitleKey = ""
                }
            }
        } else if (titleChanged) {
            loadedLyricTitleKey = titleKey
            lyricLines = emptyList()
            lyricSource = null
            lastFetchKaraokeLyrics = false
            lastExpandWordSyncKey = ""
            if (!lyricsFocus) {
                // Collapsed: drop hold immediately.
                heldFocusLyricLines = emptyList()
                heldFocusLyricSource = null
                heldFocusLyricTitleKey = ""
            }
            // Focused: keep held wall until this title's fetch settles (suppress
            // middle flash on every skip). Confirmed miss clears hold below.
            if (effectiveArtist.isBlank()) {
                kotlinx.coroutines.delay(450)
            }
        }
        val karaokeJustEnabled = karaokeLyrics && !lastFetchKaraokeLyrics
        // Memory hit already painted — only force word-sync network when karaoke
        // just turned on or we still lack word spans.
        val hasWordSync = lyricLines.any { it.isWordSynced }
        val forceWordSync = karaokeLyrics &&
            (karaokeJustEnabled || (titleChanged && !hasWordSync && peeked == null))
        // Second line of defense: an exception escaping this LaunchedEffect
        // crashes the app; a failed lyric fetch must degrade to "no lyrics".
        val loaded = try {
            LyricsRepository.load(
                title = effectiveMatchTitle,
                artist = effectiveArtist,
                durationMs = effectiveTotalTimeMs.coerceAtLeast(0L),
                album = effectiveAlbum,
                preferWordSync = karaokeLyrics,
                forceWordSync = forceWordSync,
                preserveLineCache = !titleChanged || peeked != null,
                context = appContext,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            com.example.ava.lyrics.LyricsLoadResult(emptyList(), null)
        }
        lastFetchKaraokeLyrics = karaokeLyrics
        // Stale response after another skip — ignore.
        if (titleKey != loadedLyricTitleKey) return@LaunchedEffect
        if (loaded.lines.isNotEmpty()) {
            lyricLines = loaded.lines
            lyricSource = loaded.source
        } else if (lyricsFocus && peeked == null) {
            // Settled miss for this titleKey. Focus keeps the previous wall during
            // fetch, then clears here so karaoke can enter the no-lyrics middle.
            //
            // Do not rely on titleChanged alone: artist/album/duration rematch
            // reruns this effect after loadedLyricTitleKey was already pinned,
            // which made titleChanged=false and skipped the clear.
            // heldFocusLyricTitleKey is the title the wall actually belongs to
            // (see hold refresh below — must not be retagged early).
            val holdIsStale = heldFocusLyricTitleKey.isNotEmpty() &&
                heldFocusLyricTitleKey != titleKey
            if (titleChanged || holdIsStale) {
                lyricLines = emptyList()
                lyricSource = null
                heldFocusLyricLines = emptyList()
                heldFocusLyricSource = null
                heldFocusLyricTitleKey = ""
            }
        }
        // Same-title miss / upgrade miss: never clear lines already on screen.
    }

    // Expanded karaoke wall: try word-sync upgrade once per title+focus session.
    // Never clears lyrics on miss — only replaces when upgrade succeeds.
    LaunchedEffect(titleKey, effectiveAlbum, lyricsFocus, karaokeLyrics, durationBucket) {
        if (!lyricsFocus || !karaokeLyrics || effectiveTitle.isBlank()) return@LaunchedEffect
        if (titleKey != loadedLyricTitleKey) return@LaunchedEffect
        val expandKey = "$titleKey|${effectiveAlbum.trim().lowercase()}|$durationBucket"
        if (expandKey == lastExpandWordSyncKey) return@LaunchedEffect
        // Only treat *this* title's wall as word-sync. Stale hold from the
        // previous track must not skip the upgrade / miss path.
        val hasWordSync =
            lyricLines.any { it.isWordSynced } ||
                (
                    heldFocusLyricTitleKey == titleKey &&
                        heldFocusLyricLines.any { it.isWordSynced }
                    )
        if (hasWordSync) {
            lastExpandWordSyncKey = expandKey
            return@LaunchedEffect
        }

        val upgraded = try {
            LyricsRepository.load(
                title = effectiveMatchTitle,
                artist = effectiveArtist,
                durationMs = effectiveTotalTimeMs.coerceAtLeast(0L),
                album = effectiveAlbum,
                preferWordSync = true,
                forceWordSync = true,
                preserveLineCache = true,
                context = appContext,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            com.example.ava.lyrics.LyricsLoadResult(emptyList(), null)
        }
        lastExpandWordSyncKey = expandKey
        if (titleKey != loadedLyricTitleKey) return@LaunchedEffect
        if (upgraded.lines.isNotEmpty()) {
            lyricLines = upgraded.lines
            lyricSource = upgraded.source
        }
    }

    // Refresh hold from live lines **only when they belong to the current
    // title**. On a skip, Compose can recompose with the new titleKey while
    // lyricLines still briefly hold the previous track — retagging the hold
    // key there made holdIsStale impossible and stuck the old wall forever.
    if (lyricLines.isNotEmpty() && loadedLyricTitleKey == titleKey) {
        heldFocusLyricLines = lyricLines
        heldFocusLyricSource = lyricSource
        heldFocusLyricTitleKey = titleKey
    }
    val displayLyricLines =
        if (lyricLines.isNotEmpty() && loadedLyricTitleKey == titleKey) {
            lyricLines
        } else if (lyricsFocus && heldFocusLyricLines.isNotEmpty()) {
            // Keep last wall while loading a new title, or same-title empty glitch.
            heldFocusLyricLines
        } else {
            emptyList()
        }
    val displayLyricSource =
        if (lyricLines.isNotEmpty() && loadedLyricTitleKey == titleKey) {
            lyricSource
        } else if (lyricsFocus && heldFocusLyricLines.isNotEmpty()) {
            heldFocusLyricSource
        } else {
            null
        }

    val logoLayout = OverlayLogoBadge.rememberLayoutDp()
    // Mass rail owns the top edge while open — hide «返回» so it cannot steal taps.
    LaunchedEffect(massRailOpen) {
        if (massRailOpen) {
            DashboardOverlayChrome.hide(animated = true)
        } else {
            portraitNpCompact = false
            portraitRailDismissAfterRestore = false
        }
    }

    val toggleLyricsFocus: () -> Unit = {
        // No line gate on entry: the focus tree already owns a no-lyrics middle state
        // (centered cover + meta), so a tap opens that instead of dying silently on the
        // tracks both sources miss. Suppression of the middle state stays where it
        // belongs — automatic track changes, not a deliberate tap.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLyricsFocusToggleAt >= LYRICS_FOCUS_TOGGLE_GUARD_MS) {
            lastLyricsFocusToggleAt = now
            lyricsFocus = !lyricsFocus
        }
    }

    val context = LocalContext.current
    val logoFileName = if (isSendspinSource) "sendspin_logo.png" else "ha_logo.png"
    val logoBitmap = remember(isSendspinSource) {
        try {
            context.assets.open(logoFileName).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
    // Waiting cover uses [WaitingCoverImage] (zoomed launcher mark).
    // Wake Mass pull-tab fade-in on any overlay press (does not open the rail).
    var massPushRevealTick by remember { mutableLongStateOf(0L) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // ~3s long-press wakes «返回» (auto-hide 3s). Skip while Mass rail is open.
            .then(
                if (!massRailOpen) {
                    Modifier.revealMediaOverlayChromeOnLongPress(onRevealChrome)
                } else {
                    Modifier
                },
            )
            .then(
                if (massRailAvailable && !massRailOpen) {
                    Modifier.pointerInput(massRailAvailable, massRailOpen) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) {
                                    massPushRevealTick += 1L
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val view = LocalView.current
        val realPx = android.graphics.Point()
        val display = view.display
        @Suppress("DEPRECATION")
        display?.getRealSize(realPx)
        var realW = realPx.x
        var realH = realPx.y
        val rotation = display?.rotation ?: 0
        if (
            (rotation == android.view.Surface.ROTATION_90 ||
                rotation == android.view.Surface.ROTATION_270) &&
            realW > 1 && realH > 1 && realW < realH
        ) {
            val swapped = realW
            realW = realH
            realH = swapped
        }
        val density = LocalDensity.current
        val screenW = if (realW > 1) with(density) { realW.toDp().value } else {
            LocalConfiguration.current.screenWidthDp.toFloat()
        }
        val screenH = if (realH > 1) with(density) { realH.toDp().value } else {
            LocalConfiguration.current.screenHeightDp.toFloat()
        }
        // Overlay windows follow display rotation, not the pane. `width >= height`
        // counted a square panel (often rotation 90) as landscape, so the Mass
        // rail opened the side-push instead of the portrait overlay. Landscape
        // is a clearly wide pane only — same 1.2 gate as the clock.
        val compactSquare = rememberCompactSquareScreen()
        val deviceWide = !compactSquare && screenW > screenH * 1.2f
        val sideBySidePane = deviceWide &&
            maxWidth.value < screenW * 0.92f &&
            maxHeight.value > screenH * 0.72f
        val paneWide = !compactSquare && maxWidth.value > maxHeight.value * 1.2f
        val isLandscape = !compactSquare && !sideBySidePane && paneWide
        val paneW = if (sideBySidePane) maxWidth.value else null
        val paneH = if (sideBySidePane) maxHeight.value else null
        val metrics = rememberDetailOverlayMetrics(
            isLandscape,
            paneW,
            paneH,
            splitHero = sideBySidePane,
        )
        val basePortraitMetrics = rememberDetailOverlayMetrics(
            false,
            paneW,
            paneH,
            splitHero = sideBySidePane,
        )
        val massPushFraction by animateFloatAsState(
            targetValue = if (massRailOpen && massRailAvailable && isLandscape) 0.58f else 0f,
            animationSpec = tween(MASS_PUSH_FRACTION_MS, easing = OverlaySoftEasing),
            label = "massRailPush",
        )
        val massPortraitOpen = massRailOpen && massRailAvailable && !isLandscape
        val landscapeMassPushed = massRailOpen && massRailAvailable && isLandscape
        val dismissPortraitMassRail: () -> Unit = {
            if (portraitNpCompact) {
                portraitNpCompact = false
                portraitRailDismissAfterRestore = true
            } else {
                massRailOpen = false
            }
        }
        LaunchedEffect(portraitRailDismissAfterRestore) {
            if (!portraitRailDismissAfterRestore) return@LaunchedEffect
            yield()
            delay(32)
            if (portraitRailDismissAfterRestore) {
                massRailOpen = false
                portraitRailDismissAfterRestore = false
            }
        }
        LaunchedEffect(lyricsFocus, isLandscape) {
            if (lyricsFocus && !isLandscape) {
                massRailOpen = false
                portraitNpCompact = false
                portraitRailDismissAfterRestore = false
            }
        }
        // Blurred ambient backdrop from the artwork.
        // API 31+: Compose RenderEffect blur. Below: pre-blurred software bitmap.
        val backdropSource = when {
            supportsNativeBlur ->
                effectiveCoverBitmap?.takeUnless { it.isRecycled }
            ambientBackdropBitmap != null && !ambientBackdropBitmap.isRecycled ->
                ambientBackdropBitmap
            else ->
                // Soft blur failed: still show the cover (scaled + dimmed) rather than empty.
                effectiveCoverBitmap?.takeUnless { it.isRecycled }
        }
        // targetState carries the bitmap so the exiting layer keeps drawing the
        // OLD artwork through the fade (reading the live var from both layers made
        // every "crossfade" a self-dissolve). Artwork arriving after trackKey
        // (async cover load) re-targets and gets its own proper dissolve.
        val backdropSettled = rememberBirthSettled()
        AnimatedContent(
            targetState = trackKey to backdropSource,
            transitionSpec = {
                if (!backdropSettled) {
                    // Birth: the cover lands moments after the title. Fading a
                    // full-screen blurred layer in on top of the shell fade is both
                    // the most expensive draw on screen and a second competing fade.
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    fadeIn(
                        tween(
                            OVERLAY_BACKDROP_IN_MS,
                            delayMillis = 60,
                            easing = OverlaySoftEasing,
                        ),
                    ) togetherWith
                        fadeOut(tween(OVERLAY_BACKDROP_OUT_MS, easing = OverlaySoftEasing))
                }
            },
            label = "overlayAmbientBackdrop",
            modifier = Modifier
                .fillMaxSize()
                .scale(1.35f)
                .alpha(0.4f)
                .then(if (supportsNativeBlur) Modifier.blur(48.dp) else Modifier),
        ) { (_, sourceBitmap) ->
            if (sourceBitmap != null && !sourceBitmap.isRecycled) {
                val backdropImage = remember(sourceBitmap) {
                    runCatching { sourceBitmap.asImageBitmap() }.getOrNull()
                }
                if (backdropImage != null && !sourceBitmap.isRecycled) {
                    Image(
                        bitmap = backdropImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF141414)))
                }
            } else if (isWaitingForMedia) {
                // Soft brand wash from the zoomed launcher mark (not a black plate).
                WaitingCoverImage()
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF141414)))
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.42f),
                        Color.Black.copy(alpha = 0.5f),
                        Color.Black.copy(alpha = 0.72f),
                    ),
                ),
            )
        }

        logoBitmap?.let { bitmap ->
            // Square panes: match the flat content frame (not the 32dp logo floor).
            val logoEdge = if (metrics.compactLyricBandSingleLine) {
                metrics.contentPaddingTop
            } else {
                logoLayout.edgeInsetDp
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = if (isSendspinSource) "Music Assistant" else "Home Assistant",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = logoEdge, end = logoEdge)
                    .size(logoLayout.sizeDp)
                    .alpha(0.25f),
            )
        }

        // Mass rail: landscape = side push + left NP morphs to portrait column;
        // portrait = full-bleed translucent overlay (NP stays under).
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight((1f - massPushFraction).coerceAtLeast(0.01f))
                        .fillMaxHeight(),
                ) {
                    AnimatedContent(
                        targetState = landscapeMassPushed,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (
                                fadeIn(tween(MASS_PUSH_MORPH_IN_MS, easing = OverlaySoftEasing)) +
                                    scaleIn(
                                        initialScale = 0.985f,
                                        animationSpec = tween(
                                            MASS_PUSH_MORPH_IN_MS,
                                            easing = OverlaySoftEasing,
                                        ),
                                    )
                                ) togetherWith (
                                fadeOut(tween(MASS_PUSH_MORPH_OUT_MS, easing = OverlaySoftEasing)) +
                                    scaleOut(
                                        targetScale = 0.985f,
                                        animationSpec = tween(
                                            MASS_PUSH_MORPH_OUT_MS,
                                            easing = OverlaySoftEasing,
                                        ),
                                    )
                                ) using SizeTransform(clip = true)
                        },
                        label = "landscapeMassPushNpMorph",
                    ) { pushed ->
                        if (pushed) {
                            // Same PortraitDetailContent tree; metrics + compact layout
                            // scale to the squeezed left pane (HTML is-landscape.pushed).
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                // Metrics scale from the equal-inset content box, not the raw pane.
                                val innerW = (maxWidth - MassPushInset * 2).coerceAtLeast(1.dp)
                                val innerH = (maxHeight - MassPushInset * 2).coerceAtLeast(1.dp)
                                val stableW = ((innerW.value / 8f).toInt() * 8f).dp
                                    .coerceAtLeast(1.dp)
                                val stableH = ((innerH.value / 8f).toInt() * 8f).dp
                                    .coerceAtLeast(1.dp)
                                val pushMetrics = remember(
                                    stableW,
                                    stableH,
                                    basePortraitMetrics,
                                ) {
                                    basePortraitMetrics.scaledForMassPushPane(stableW, stableH)
                                }
                                PortraitDetailContent(
                                    metrics = pushMetrics,
                                    title = effectiveTitle,
                                    artist = effectiveArtist,
                                    album = effectiveAlbum,
                                    coverBitmap = effectiveCoverBitmap,
                                    lyricLines = displayLyricLines,
                                    lyricSource = displayLyricSource,
                                    trackKey = trackKey,
                                    lyricUiKey = titleKey,
                                    lyricsFocus = lyricsFocus,
                                    onToggleLyricsFocus = toggleLyricsFocus,
                                    isPlaying = isPlaying,
                                    positionMs = displayPositionMs,
                                    lyricPositionMs = lyricPositionMs,
                                    durationMs = effectiveTotalTimeMs,
                                    repeatMode = repeatMode,
                                    shuffleEnabled = shuffleEnabled,
                                    onPlayPauseClick = onPlayPauseClick,
                                    onPreviousClick = onPreviousClick,
                                    onNextClick = onNextClick,
                                    onRepeatClick = onRepeatClick,
                                    onShuffleClick = onShuffleClick,
                                    onSeekClick = boundOnSeekClick,
                                    karaokeLyrics = karaokeLyrics,
                                    massPushCompact = true,
                                    massRailExpanded = true,
                                    waitingForMedia = isWaitingForMedia,
                                )
                            }
                        } else {
                            LandscapeDetailContent(
                                metrics = metrics,
                                title = effectiveTitle,
                                artist = effectiveArtist,
                                album = effectiveAlbum,
                                coverBitmap = effectiveCoverBitmap,
                                lyricLines = displayLyricLines,
                                lyricSource = displayLyricSource,
                                trackKey = trackKey,
                                lyricUiKey = titleKey,
                                lyricsFocus = lyricsFocus,
                                onToggleLyricsFocus = toggleLyricsFocus,
                                isPlaying = isPlaying,
                                positionMs = displayPositionMs,
                                lyricPositionMs = lyricPositionMs,
                                durationMs = effectiveTotalTimeMs,
                                repeatMode = repeatMode,
                                shuffleEnabled = shuffleEnabled,
                                onPlayPauseClick = onPlayPauseClick,
                                onPreviousClick = onPreviousClick,
                                onNextClick = onNextClick,
                                onRepeatClick = onRepeatClick,
                                onShuffleClick = onShuffleClick,
                                onSeekClick = boundOnSeekClick,
                                karaokeLyrics = karaokeLyrics,
                                waitingForMedia = isWaitingForMedia,
                            )
                        }
                    }
                }
                if (massPushFraction > 0.01f) {
                    MassApiOverlayRail(
                        onClose = { massRailOpen = false },
                        railModifier = Modifier
                            .weight(massPushFraction.coerceAtLeast(0.01f))
                            .fillMaxHeight(),
                        waitingForMedia = isWaitingForMedia,
                    )
                }
            }
        } else {
            PortraitDetailContent(
                metrics = metrics,
                title = effectiveTitle,
                artist = effectiveArtist,
                album = effectiveAlbum,
                coverBitmap = effectiveCoverBitmap,
                lyricLines = displayLyricLines,
                lyricSource = displayLyricSource,
                trackKey = trackKey,
                lyricUiKey = titleKey,
                lyricsFocus = lyricsFocus,
                onToggleLyricsFocus = toggleLyricsFocus,
                isPlaying = isPlaying,
                positionMs = displayPositionMs,
                lyricPositionMs = lyricPositionMs,
                durationMs = effectiveTotalTimeMs,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                onPlayPauseClick = onPlayPauseClick,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                onRepeatClick = onRepeatClick,
                onShuffleClick = onShuffleClick,
                onSeekClick = boundOnSeekClick,
                karaokeLyrics = karaokeLyrics,
                // Portrait overlay: compact band is [portraitNpCompact], not overlay
                // visibility — close snaps 3-line back before the panel fades.
                massRailExpanded = portraitNpCompact,
                waitingForMedia = isWaitingForMedia,
                fitCoverToSeat = sideBySidePane,
            )
            AnimatedVisibility(
                visible = massPortraitOpen,
                enter = fadeIn(animationSpec = tween(220, easing = OverlaySoftEasing)),
                exit = fadeOut(animationSpec = tween(160, easing = OverlaySoftEasing)),
            ) {
                MassApiOverlayRail(
                    onClose = dismissPortraitMassRail,
                    translucent = true,
                    railModifier = Modifier.fillMaxSize(),
                    waitingForMedia = isWaitingForMedia,
                )
            }
        }

        // Karaoke (lyricsFocus) keeps the pull tab — only hide while the rail is open.
        // Portrait still auto-closes an open rail on focus enter (see LaunchedEffect above).
        if (massRailAvailable && !massRailOpen) {
            MassApiRailPushTab(
                onClick = {
                    portraitRailDismissAfterRestore = false
                    massRailOpen = true
                    if (!isLandscape) portraitNpCompact = true
                    massManager?.refreshRail()
                },
                externalRevealTick = massPushRevealTick,
                // Portrait: never use the full-height edge hot zone — only the
                // visible pull handle opens the rail (karaoke wall + NP alike).
                handleOnlyClick = !isLandscape,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .zIndex(6f),
            )
        }

        // Same branch pattern as settings Playback stats card, but in-tree only:
        // VinylCoverService has no Activity token — ModalBottomSheet Dialog crashes
        // (BadTokenException). Use [MediaPlayerStatsPanel] inside this ComposeView.
        // Poll runs only while the panel is composed (incl. brief exit animation).
        // Handle visibility locks to [DashboardOverlayChrome.stripVisible] — same
        // touch-reveal / auto-hide as the «返回» strip (quick fade only).
        var showMediaPlayerStatsSheet by remember { mutableStateOf(false) }
        val chromeStripVisible by DashboardOverlayChrome.stripVisible.collectAsState()
        LaunchedEffect(isSendspinSource, fullPlayerActive) {
            if (!isSendspinSource || !fullPlayerActive) {
                showMediaPlayerStatsSheet = false
            }
        }
        val statsSheetVisible =
            isSendspinSource && fullPlayerActive && showMediaPlayerStatsSheet
        // Scrim: fade only. Card: slide up + fade, inset from edges (not edge-snapped).
        AnimatedVisibility(
            visible = statsSheetVisible,
            enter = fadeIn(animationSpec = tween(180, easing = OverlaySoftEasing)),
            exit = fadeOut(animationSpec = tween(140, easing = OverlaySoftEasing)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showMediaPlayerStatsSheet = false },
                    ),
            )
        }
        AnimatedVisibility(
            visible = statsSheetVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(animationSpec = tween(220, easing = OverlaySoftEasing)) +
                slideInVertically(
                    animationSpec = tween(280, easing = OverlaySoftEasing),
                    initialOffsetY = { (it * 0.18f).toInt().coerceAtLeast(48) },
                ),
            exit = fadeOut(animationSpec = tween(160, easing = OverlaySoftEasing)) +
                slideOutVertically(
                    animationSpec = tween(200, easing = OverlaySoftEasing),
                    targetOffsetY = { (it * 0.12f).toInt().coerceAtLeast(32) },
                ),
        ) {
            val fillStatsPane = rememberCompactSquareScreen()
            MediaPlayerStatsPanel(
                onDismiss = { showMediaPlayerStatsSheet = false },
                isDarkMode = true,
                showDragHandle = true,
                floating = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (fillStatsPane) {
                            Modifier.fillMaxHeight()
                        } else {
                            Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp)
                        },
                    ),
            )
        }
        AnimatedVisibility(
            visible = isSendspinSource &&
                fullPlayerActive &&
                !showMediaPlayerStatsSheet &&
                chromeStripVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(140)),
        ) {
            BottomSheetHandle(
                isDarkMode = true,
                onClick = { showMediaPlayerStatsSheet = true },
                gradientHeight = 36.dp,
                showGradient = false,
            )
        }
    }
}

/**
 * Lyrics-focus morph. Fast fade + tiny horizontal dissolve toward the left
 * (expanded cover sits on the left). No scale — avoids double-cover ghosts.
 */
private fun AnimatedContentTransitionScope<Boolean>.lyricsFocusMorphTransition(): ContentTransform {
    val expanding = targetState
    return if (expanding) {
        // Settling left: outgoing eases left while dissolving; chrome enters from right.
        (
            fadeIn(tween(LYRICS_FOCUS_MORPH_IN_MS, easing = OverlaySoftEasing)) +
                slideInHorizontally(
                    animationSpec = tween(LYRICS_FOCUS_MORPH_IN_MS, easing = OverlaySoftEasing),
                    initialOffsetX = { (it * LYRICS_FOCUS_MORPH_SLIDE_FRAC).toInt().coerceIn(8, 24) },
                )
            ) togetherWith (
            fadeOut(tween(LYRICS_FOCUS_MORPH_OUT_MS, easing = OverlaySoftEasing)) +
                slideOutHorizontally(
                    animationSpec = tween(LYRICS_FOCUS_MORPH_OUT_MS, easing = OverlaySoftEasing),
                    targetOffsetX = { -(it * LYRICS_FOCUS_MORPH_SLIDE_FRAC).toInt().coerceIn(8, 24) },
                )
            ) using SizeTransform(clip = true)
    } else {
        // Back to centered player: chrome dissolves right; player enters from left.
        (
            fadeIn(tween(LYRICS_FOCUS_MORPH_IN_MS, easing = OverlaySoftEasing)) +
                slideInHorizontally(
                    animationSpec = tween(LYRICS_FOCUS_MORPH_IN_MS, easing = OverlaySoftEasing),
                    initialOffsetX = { -(it * LYRICS_FOCUS_MORPH_SLIDE_FRAC).toInt().coerceIn(8, 24) },
                )
            ) togetherWith (
            fadeOut(tween(LYRICS_FOCUS_MORPH_OUT_MS, easing = OverlaySoftEasing)) +
                slideOutHorizontally(
                    animationSpec = tween(LYRICS_FOCUS_MORPH_OUT_MS, easing = OverlaySoftEasing),
                    targetOffsetX = { (it * LYRICS_FOCUS_MORPH_SLIDE_FRAC).toInt().coerceIn(8, 24) },
                )
            ) using SizeTransform(clip = true)
    }
}

/**
 * Middle wait → left chrome + lyric wall. Leftward slide dissolve, ~120ms
 * slower than the focus morph so the cover handoff reads clearly.
 */
private fun AnimatedContentTransitionScope<Boolean>.lyricsFocusWallRevealTransition(): ContentTransform {
    val toLeftChrome = targetState
    return if (toLeftChrome) {
        (
            fadeIn(tween(LYRICS_FOCUS_WALL_IN_MS, easing = OverlaySoftEasing)) +
                slideInHorizontally(
                    animationSpec = tween(LYRICS_FOCUS_WALL_IN_MS, easing = OverlaySoftEasing),
                    initialOffsetX = { (it * LYRICS_FOCUS_WALL_SLIDE_FRAC).toInt().coerceIn(16, 40) },
                )
            ) togetherWith (
            fadeOut(tween(LYRICS_FOCUS_WALL_OUT_MS, easing = OverlaySoftEasing)) +
                slideOutHorizontally(
                    animationSpec = tween(LYRICS_FOCUS_WALL_OUT_MS, easing = OverlaySoftEasing),
                    targetOffsetX = { -(it * LYRICS_FOCUS_WALL_SLIDE_FRAC).toInt().coerceIn(16, 40) },
                )
            ) using SizeTransform(clip = true)
    } else {
        (
            fadeIn(tween(LYRICS_FOCUS_WALL_IN_MS, easing = OverlaySoftEasing)) +
                slideInHorizontally(
                    animationSpec = tween(LYRICS_FOCUS_WALL_IN_MS, easing = OverlaySoftEasing),
                    initialOffsetX = { -(it * LYRICS_FOCUS_WALL_SLIDE_FRAC).toInt().coerceIn(16, 40) },
                )
            ) togetherWith (
            fadeOut(tween(LYRICS_FOCUS_WALL_OUT_MS, easing = OverlaySoftEasing)) +
                slideOutHorizontally(
                    animationSpec = tween(LYRICS_FOCUS_WALL_OUT_MS, easing = OverlaySoftEasing),
                    targetOffsetX = { (it * LYRICS_FOCUS_WALL_SLIDE_FRAC).toInt().coerceIn(16, 40) },
                )
            ) using SizeTransform(clip = true)
    }
}

/**
 * Compact cover + title/artist for lyrics-focus morph (exit via cover tap).
 * [metaBesideCover]: portrait — title/artist to the right of the cover.
 * Landscape keeps title under the (larger) cover.
 */
@Composable
private fun LyricsFocusChrome(
    metrics: DetailOverlayMetrics,
    title: String,
    artist: String,
    coverBitmap: Bitmap?,
    trackKey: String,
    coverSize: Dp,
    onCoverClick: () -> Unit,
    modifier: Modifier = Modifier,
    centeredVertically: Boolean = false,
    metaBesideCover: Boolean = false,
) {
    val cover = @Composable {
        CoverCard(
            coverBitmap = coverBitmap,
            trackKey = trackKey,
            cornerRadius = metrics.coverCornerRadius,
            shadowElevation = metrics.coverShadowElevation * 0.55f,
            onClick = onCoverClick,
            modifier = Modifier
                .size(coverSize)
                .aspectRatio(1f),
        )
    }
    if (metaBesideCover) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.lyricsFocusCoverBesideGap),
        ) {
            cover()
            TrackMeta(
                title = title,
                artist = artist,
                trackKey = trackKey,
                metrics = metrics,
                textAlign = TextAlign.Start,
                stackArtistBelow = true,
                compact = true,
                // Remaining width beside cover; overflow → shared marquee.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
            )
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (centeredVertically) {
                Arrangement.Center
            } else {
                Arrangement.Top
            },
        ) {
            cover()
            // Cover ↔ title only; title↔artist keeps titleArtistGap inside TrackMeta.
            Spacer(modifier = Modifier.height(metrics.lyricsFocusCoverTitleGap))
            // Match cover left/right edges — wider column must not stretch meta.
            // Overflow uses the same CautiousMarquee path as collapsed TrackMeta.
            TrackMeta(
                title = title,
                artist = artist,
                trackKey = trackKey,
                metrics = metrics,
                textAlign = TextAlign.Center,
                stackArtistBelow = true,
                compact = true,
                modifier = Modifier
                    .width(coverSize)
                    .clipToBounds(),
            )
        }
    }
}

/**
 * Portrait. Default = original cover → meta → 3-line lyrics → progress → transport.
 * Cover tap (lyrics present) opens lyrics-focus: title beside cover, bottom
 * playback hidden; lyric wall with no black edge scrims.
 */
@Composable
private fun PortraitDetailContent(
    metrics: DetailOverlayMetrics,
    title: String,
    artist: String,
    album: String,
    coverBitmap: Bitmap?,
    lyricLines: List<LrcLine>,
    lyricSource: LyricSource? = null,
    trackKey: String,
    lyricUiKey: String,
    lyricsFocus: Boolean,
    onToggleLyricsFocus: () -> Unit,
    isPlaying: Boolean,
    positionMs: Long,
    lyricPositionMs: State<Long>,
    durationMs: Long,
    repeatMode: String,
    shuffleEnabled: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onSeekClick: ((Long) -> Unit)? = null,
    karaokeLyrics: Boolean = false,
    /** Landscape Mass side-push: tight column, no cover weight void. */
    massPushCompact: Boolean = false,
    /**
     * Mass rail open (side-push or portrait overlay). Forces the compact lyric
     * band to **1 line**; when false, the band is 3-line unless the pane is square
     * ([DetailOverlayMetrics.compactLyricBandSingleLine]). Do not infer from source —
     * only these three inputs (this flag, [massPushCompact], the metrics flag).
     */
    massRailExpanded: Boolean = false,
    /**
     * MA queue-clear / waiting-for-media shell: cover + title only.
     * Hide progress + transport so idle taps cannot seek / toggle play.
     */
    waitingForMedia: Boolean = false,
    /**
     * Left/right landscape pane. Cover squares use the measured seat, not a
     * full-screen fraction.
     */
    fitCoverToSeat: Boolean = false,
) {
    // Lines are not required to open the page — the focus tree falls back to its
    // no-lyrics middle state. Only the idle shell keeps the cover inert.
    val canFocusLyrics = !waitingForMedia
    // Stay in focus chrome while the switch is on — do not drop to transport when
    // lines briefly empty (track seam / fetch). Sticky lines are supplied upstream.
    val focus = lyricsFocus && !waitingForMedia
    // Forced lyric-band mode for the shared SyncedLyricsSection:
    // rail open → singleLine; rail closed → three-line window.
    // Square panes stay single-line either way (see [compactLyricBandSingleLine]).
    val forceSingleLineLyric = massPushCompact ||
        massRailExpanded ||
        fitCoverToSeat ||
        metrics.compactLyricBandSingleLine
    val padMod = Modifier
        .fillMaxSize()
        .padding(
            start = metrics.contentPaddingH,
            end = metrics.contentPaddingH,
            top = metrics.contentPaddingTop,
            bottom = metrics.contentPaddingBottom,
        )
    if (waitingForMedia) {
        WaitingForMediaCenteredShell(
            metrics = metrics,
            title = title,
            artist = artist,
            coverBitmap = coverBitmap,
            trackKey = trackKey,
            lyricUiKey = lyricUiKey,
            massPushCompact = massPushCompact,
            padMod = padMod,
            fitCoverToSeat = fitCoverToSeat,
        )
        return
    }
    AnimatedContent(
        targetState = focus,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { lyricsFocusMorphTransition() },
        label = "portraitLyricsFocusSwitch",
    ) { focused ->
        if (focused) {
            AnimatedContent(
                targetState = lyricLines.isNotEmpty(),
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { lyricsFocusWallRevealTransition() },
                label = "portraitFocusWallReveal",
            ) { hasWall ->
                if (!hasWall) {
                    // Focus wait middle: cover + title/artist as one centered block
                    // under the art — never weight-push meta to the screen bottom.
                    BoxWithConstraints(
                        modifier = if (massPushCompact) {
                            Modifier
                                .fillMaxSize()
                                .padding(MassPushInset)
                        } else {
                            padMod.fillMaxSize()
                        },
                    ) {
                        val coverSide = if (massPushCompact) {
                            massPushCoverSide(
                                maxWidth = maxWidth,
                                maxHeight = maxHeight,
                                metrics = metrics,
                                reserveLyricLine = false,
                                stackMeta = true,
                                hasArtist = artist.isNotBlank(),
                            )
                        } else if (fitCoverToSeat) {
                            val meta = metrics.lyricsFocusCoverTitleGap +
                                metrics.titleLineHeightSolo.value.dp +
                                metrics.titleArtistGap +
                                metrics.artistLineHeightSolo.value.dp
                            defaultCoverSide(
                                maxWidth = maxWidth,
                                maxHeight = (maxHeight - meta).coerceAtLeast(0.dp),
                                fillFraction = 1f,
                            )
                        } else {
                            (minOf(maxWidth, maxHeight) * metrics.coverFillFractionSolo)
                                .coerceAtMost(maxWidth * 0.82f)
                                .coerceAtMost(maxHeight * 0.56f)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (massPushCompact) {
                                        Modifier.offset(x = MassPushKaraokeChromeNudge)
                                    } else {
                                        Modifier
                                    },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CoverCard(
                                coverBitmap = coverBitmap,
                                trackKey = lyricUiKey,
                                cornerRadius = metrics.coverCornerRadius,
                                shadowElevation = if (massPushCompact) {
                                    metrics.coverShadowElevation * 0.65f
                                } else {
                                    metrics.coverShadowElevation
                                },
                                onClick = onToggleLyricsFocus,
                                modifier = Modifier
                                    .size(coverSide)
                                    .aspectRatio(1f),
                            )
                            Spacer(modifier = Modifier.height(metrics.lyricsFocusCoverTitleGap))
                            TrackMeta(
                                title = title,
                                artist = artist,
                                trackKey = trackKey,
                                metrics = metrics,
                                textAlign = TextAlign.Center,
                                stackArtistBelow = true,
                                prominent = true,
                                modifier = Modifier.width(coverSide),
                            )
                        }
                    }
                } else {
                    // Lyrics-focus ON: cover | title/artist beside; lyric wall below.
                    // Outer frame owns the equal inset (square pane / MassPushInset) —
                    // chrome and wall must not re-apply half-width side pads.
                    Column(
                        modifier = if (massPushCompact) {
                            Modifier
                                .fillMaxSize()
                                .padding(MassPushInset)
                        } else {
                            padMod
                        },
                    ) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val focusCoverSize = if (fitCoverToSeat) {
                            minOf(maxWidth * 0.36f, maxHeight * 0.30f)
                                .coerceIn(56.dp, 140.dp)
                        } else {
                            metrics.lyricsFocusCoverSize
                        }
                        LyricsFocusChrome(
                            metrics = metrics,
                            title = title,
                            artist = artist,
                            coverBitmap = coverBitmap,
                            trackKey = lyricUiKey,
                            coverSize = focusCoverSize,
                            onCoverClick = onToggleLyricsFocus,
                            metaBesideCover = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    // Side-push left pane only: keep cover+title clear of the left edge.
                                    start = if (massPushCompact) {
                                        MassPushKaraokeChromeNudge
                                    } else {
                                        0.dp
                                    },
                                    bottom = if (massPushCompact) {
                                        8.dp
                                    } else {
                                        metrics.metaLyricsGap
                                    },
                                ),
                        )
                        }
                        SyncedLyricsSection(
                            lines = lyricLines,
                            lyricSource = lyricSource,
                            positionMsState = lyricPositionMs,
                            metrics = metrics,
                            textAlign = TextAlign.Center,
                            trackKey = lyricUiKey,
                            expanded = true,
                            karaokeLyrics = karaokeLyrics,
                            onSeekClick = onSeekClick,
                            // Parent already applied the equal frame — don't half-pad again.
                            expandedHorizontalEdgePad = 0.dp,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        } else if (massPushCompact) {
            // Mass-push: one bottom stack — cover → meta → lyric → progress → transport.
            // Spare height stays ABOVE the cover; never a void between meta and the foot
            // (that looked like “cover floated up” while the play bar sat alone).
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(MassPushInset),
            ) {
                val hasLyrics = lyricLines.isNotEmpty()
                val footApprox = metrics.progressBarHeight +
                    metrics.progressTimeTopGap +
                    (metrics.timeSp.value * 1.25f).dp +
                    metrics.progressTransportGap +
                    metrics.playButtonSize +
                    metrics.metaProgressGap
                val heroForCover = (maxHeight - footApprox).coerceAtLeast(1.dp)
                // No lyrics: title / artist stay stacked (never · merge).
                val stackMeta = !hasLyrics
                val coverSide = massPushCoverSide(
                    maxWidth = maxWidth,
                    maxHeight = heroForCover,
                    metrics = metrics,
                    reserveLyricLine = hasLyrics,
                    stackMeta = stackMeta,
                    hasArtist = artist.isNotBlank(),
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Spare air above only — stack stays glued to the play bar.
                    // Larger cover (see massPushCoverSide) lifts the art without
                    // opening a void between meta/lyric and the foot.
                    Spacer(modifier = Modifier.weight(1f, fill = true))
                    CoverCard(
                        coverBitmap = coverBitmap,
                        trackKey = lyricUiKey,
                        cornerRadius = metrics.coverCornerRadius,
                        shadowElevation = metrics.coverShadowElevation * 0.65f,
                        onClick = if (canFocusLyrics) onToggleLyricsFocus else null,
                        modifier = Modifier
                            .size(coverSide)
                            .aspectRatio(1f),
                    )
                    Spacer(modifier = Modifier.height(metrics.portraitCoverBottomGap))
                    TrackMeta(
                        title = title,
                        artist = artist,
                        trackKey = trackKey,
                        metrics = metrics,
                        textAlign = TextAlign.Center,
                        stackArtistBelow = stackMeta,
                        prominent = stackMeta,
                        modifier = Modifier.width(coverSide),
                    )
                    if (hasLyrics) {
                        Spacer(modifier = Modifier.height(6.dp))
                        // Rail expanded (side-push): one-line band via shared lyric system.
                        SyncedLyricsSection(
                            lines = lyricLines,
                            lyricSource = null,
                            positionMsState = lyricPositionMs,
                            metrics = metrics,
                            textAlign = TextAlign.Center,
                            trackKey = lyricUiKey,
                            singleLine = true,
                            modifier = Modifier.width(coverSide),
                        )
                    }
                    Spacer(modifier = Modifier.height(metrics.metaProgressGap))
                    ProgressSection(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        metrics = metrics,
                        onSeekClick = onSeekClick,
                    )
                    Spacer(modifier = Modifier.height(metrics.progressTransportGap))
                    TransportRow(
                        isPlaying = isPlaying,
                        repeatMode = repeatMode,
                        shuffleEnabled = shuffleEnabled,
                        metrics = metrics,
                        onPlayPauseClick = onPlayPauseClick,
                        onPreviousClick = onPreviousClick,
                        onNextClick = onNextClick,
                        onRepeatClick = onRepeatClick,
                        onShuffleClick = onShuffleClick,
                    )
                }
            }
        } else {
            // Default OFF — original player layout. Cover size is static (no
            // morph / fraction animation); only solo vs with-lyrics fraction.
            Column(modifier = padMod) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(if (fitCoverToSeat) 1.55f else 1f)
                        .fillMaxWidth()
                        .padding(bottom = metrics.portraitCoverBottomGap),
                    contentAlignment = Alignment.Center,
                ) {
                    CoverCard(
                        coverBitmap = coverBitmap,
                        trackKey = lyricUiKey,
                        cornerRadius = metrics.coverCornerRadius,
                        shadowElevation = metrics.coverShadowElevation,
                        onClick = if (canFocusLyrics) onToggleLyricsFocus else null,
                        // Unexpanded: fixed fraction — never animate / never solo-swap.
                        // Split pane: this box is the hero share (~60%). Fill it.
                        // Do not use the square-device 0.56 air fraction here.
                        modifier = Modifier
                            .size(
                                defaultCoverSide(
                                    maxWidth = maxWidth,
                                    maxHeight = maxHeight,
                                    fillFraction = if (fitCoverToSeat) 0.96f else metrics.coverFillFraction,
                                ),
                            )
                            .aspectRatio(1f),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (fitCoverToSeat) Modifier.weight(1f) else Modifier),
                ) {
                TrackMeta(
                    title = title,
                    artist = artist,
                    trackKey = trackKey,
                    metrics = metrics,
                    textAlign = TextAlign.Center,
                    // Square + lyrics: one "title · artist" line (same as landscape).
                    // No lyrics / phone portrait: keep stacked rows.
                    stackArtistBelow = lyricLines.isEmpty() ||
                        !metrics.compactLyricBandSingleLine,
                )

                if (lyricLines.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(metrics.metaLyricsGap))
                    // Same SyncedLyricsSection instance: rail open → 1-line, closed → 3-line.
                    // Keeps lead / latch / highlight across the mode flip (no dual lyric systems).
                    // Portrait overlay: tween height down only; restore uses snap() so
                    // close does not play the reverse (NP chrome bouncing up).
                    val railShrinksBand = massRailExpanded &&
                        !metrics.compactLyricBandSingleLine
                    val bandTargetH = if (forceSingleLineLyric) {
                        metrics.lyricsLineHeightDp
                    } else {
                        metrics.lyricsBlockHeight
                    }
                    val bandH by animateDpAsState(
                        targetValue = bandTargetH,
                        animationSpec = if (railShrinksBand) {
                            tween(PORTRAIT_RAIL_NP_DOWN_MS, easing = OverlaySoftEasing)
                        } else {
                            snap()
                        },
                        label = "portraitRailNpLyricBand",
                    )
                    SyncedLyricsSection(
                        lines = lyricLines,
                        lyricSource = null,
                        positionMsState = lyricPositionMs,
                        metrics = metrics,
                        textAlign = TextAlign.Center,
                        trackKey = lyricUiKey,
                        singleLine = forceSingleLineLyric,
                        modifier = if (metrics.compactLyricBandSingleLine) {
                            Modifier
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .height(bandH)
                                .clipToBounds()
                        },
                    )
                    Spacer(modifier = Modifier.height(metrics.lyricsProgressGap))
                } else {
                    Spacer(modifier = Modifier.height(metrics.metaProgressGap))
                }

                ProgressSection(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    metrics = metrics,
                    onSeekClick = onSeekClick,
                )

                Spacer(modifier = Modifier.height(metrics.progressTransportGap))

                TransportRow(
                    isPlaying = isPlaying,
                    repeatMode = repeatMode,
                    shuffleEnabled = shuffleEnabled,
                    metrics = metrics,
                    onPlayPauseClick = onPlayPauseClick,
                    onPreviousClick = onPreviousClick,
                    onNextClick = onNextClick,
                    onRepeatClick = onRepeatClick,
                    onShuffleClick = onShuffleClick,
                )
                }
            }
        }
    }
}

/**
 * Landscape. Default = original cover | meta/3-line/progress/transport.
 * Cover tap opens lyrics-focus: larger cover + title under it, playback hidden,
 * lyrics on the right. Expanded list only when ON; no black edge scrims.
 */
@Composable
private fun LandscapeDetailContent(
    metrics: DetailOverlayMetrics,
    title: String,
    artist: String,
    album: String,
    coverBitmap: Bitmap?,
    lyricLines: List<LrcLine>,
    lyricSource: LyricSource? = null,
    trackKey: String,
    lyricUiKey: String,
    lyricsFocus: Boolean,
    onToggleLyricsFocus: () -> Unit,
    isPlaying: Boolean,
    positionMs: Long,
    lyricPositionMs: State<Long>,
    durationMs: Long,
    repeatMode: String,
    shuffleEnabled: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onSeekClick: ((Long) -> Unit)? = null,
    karaokeLyrics: Boolean = false,
    /**
     * MA queue-clear / waiting-for-media shell: cover + title only.
     * Hide progress + transport so idle taps cannot seek / toggle play.
     */
    waitingForMedia: Boolean = false,
) {
    // Same as portrait: the no-lyrics middle state is a valid destination.
    val canFocusLyrics = !waitingForMedia
    // Stay in focus chrome while the switch is on — do not drop to transport when
    // lines briefly empty (track seam / fetch). Sticky lines are supplied upstream.
    val focus = lyricsFocus && !waitingForMedia
    // +5 end only when the visual left-shift is active (phones). Square panes keep
    // a flat equal frame — do not invent an asymmetric end inset.
    val landscapeEndExtra =
        if (metrics.landscapeVisualShiftLeft > 0.dp) 5.dp else 0.dp
    val padMod = Modifier
        .fillMaxSize()
        .padding(
            start = (metrics.contentPaddingH - metrics.landscapeVisualShiftLeft)
                .coerceAtLeast(0.dp),
            end = metrics.contentPaddingH + metrics.landscapeVisualShiftLeft + landscapeEndExtra,
            top = metrics.contentPaddingTop,
            bottom = metrics.contentPaddingBottom,
        )
    if (waitingForMedia) {
        WaitingForMediaCenteredShell(
            metrics = metrics,
            title = title,
            artist = artist,
            coverBitmap = coverBitmap,
            trackKey = trackKey,
            lyricUiKey = lyricUiKey,
            massPushCompact = false,
            padMod = padMod,
        )
        return
    }
    AnimatedContent(
        targetState = focus,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { lyricsFocusMorphTransition() },
        label = "landscapeLyricsFocusSwitch",
    ) { focused ->
        if (focused) {
            AnimatedContent(
                targetState = lyricLines.isNotEmpty(),
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { lyricsFocusWallRevealTransition() },
                label = "landscapeFocusWallReveal",
            ) { hasWall ->
                if (!hasWall) {
                    // Same as portrait wait: cover + meta centered as one block.
                    BoxWithConstraints(modifier = padMod.fillMaxSize()) {
                        val coverSide = (minOf(maxWidth, maxHeight) * metrics.landscapeCoverFillFractionSolo)
                            .coerceAtMost(maxWidth * 0.72f)
                            .coerceAtMost(maxHeight * 0.56f)
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CoverCard(
                                coverBitmap = coverBitmap,
                                trackKey = lyricUiKey,
                                cornerRadius = metrics.coverCornerRadius,
                                shadowElevation = metrics.coverShadowElevation,
                                onClick = onToggleLyricsFocus,
                                modifier = Modifier
                                    .size(coverSide)
                                    .aspectRatio(1f),
                            )
                            Spacer(modifier = Modifier.height(metrics.lyricsFocusCoverTitleGap))
                            TrackMeta(
                                title = title,
                                artist = artist,
                                trackKey = trackKey,
                                metrics = metrics,
                                textAlign = TextAlign.Center,
                                stackArtistBelow = true,
                                prominent = true,
                                modifier = Modifier.width(coverSide),
                            )
                        }
                    }
                } else {
                    // Lyrics-focus ON: larger cover + title under it; lyric wall on the right.
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LyricsFocusChrome(
                            metrics = metrics,
                            title = title,
                            artist = artist,
                            coverBitmap = coverBitmap,
                            trackKey = lyricUiKey,
                            coverSize = metrics.lyricsFocusCoverLandscapeSize,
                            onCoverClick = onToggleLyricsFocus,
                            centeredVertically = true,
                            modifier = Modifier
                                .weight(0.72f)
                                .fillMaxHeight()
                                .padding(
                                    start = (metrics.contentPaddingH - metrics.landscapeVisualShiftLeft)
                                        .coerceAtLeast(0.dp),
                                    end = metrics.landscapeGutter * 0.5f,
                                    top = metrics.contentPaddingTop,
                                    bottom = metrics.contentPaddingBottom,
                                ),
                        )
                        SyncedLyricsSection(
                            lines = lyricLines,
                            lyricSource = lyricSource,
                            positionMsState = lyricPositionMs,
                            metrics = metrics,
                            textAlign = TextAlign.Start,
                            trackKey = lyricUiKey,
                            expanded = true,
                            karaokeLyrics = karaokeLyrics,
                            onSeekClick = onSeekClick,
                            modifier = Modifier
                                .weight(1.28f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        } else {
            // Default OFF — cover size static; no fraction morph on this tree.
            Row(
                modifier = padMod,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(metrics.landscapeCoverWeight)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    CoverCard(
                        coverBitmap = coverBitmap,
                        trackKey = lyricUiKey,
                        cornerRadius = metrics.coverCornerRadius,
                        shadowElevation = metrics.coverShadowElevation,
                        onClick = if (canFocusLyrics) onToggleLyricsFocus else null,
                        // Unexpanded: fixed fraction — never animate / never solo-swap.
                        modifier = Modifier
                            .size(
                                defaultCoverSide(
                                    maxWidth = maxWidth,
                                    maxHeight = maxHeight,
                                    fillFraction = metrics.landscapeCoverFillFraction,
                                ),
                            )
                            .aspectRatio(1f),
                    )
                }

                Spacer(modifier = Modifier.size(metrics.landscapeGutter))

                Box(
                    modifier = Modifier
                        .weight(metrics.landscapeContentWeight)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TrackMeta(
                            title = title,
                            artist = artist,
                            trackKey = trackKey,
                            metrics = metrics,
                            // With lyrics: title · artist one line + shared marquee.
                            stackArtistBelow = lyricLines.isEmpty(),
                        )

                        if (lyricLines.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(metrics.metaLyricsGap))
                            // Rail closed (this composable only mounts when not side-pushed):
                            // force 3-line band — never source caption / 1-line.
                            SyncedLyricsSection(
                                lines = lyricLines,
                                lyricSource = null,
                                positionMsState = lyricPositionMs,
                                metrics = metrics,
                                trackKey = lyricUiKey,
                            )
                            Spacer(modifier = Modifier.height(metrics.lyricsProgressGap))
                        } else {
                            Spacer(modifier = Modifier.height(metrics.metaProgressGap))
                        }

                        ProgressSection(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            metrics = metrics,
                            onSeekClick = onSeekClick,
                        )

                        Spacer(modifier = Modifier.height(metrics.progressTransportGap))

                        TransportRow(
                            isPlaying = isPlaying,
                            repeatMode = repeatMode,
                            shuffleEnabled = shuffleEnabled,
                            metrics = metrics,
                            onPlayPauseClick = onPlayPauseClick,
                            onPreviousClick = onPreviousClick,
                            onNextClick = onNextClick,
                            onRepeatClick = onRepeatClick,
                            onShuffleClick = onShuffleClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * MA clear-queue / waiting-for-media: cover + title centered, no progress or
 * transport (avoids accidental seek / play while the queue is empty).
 */
@Composable
private fun WaitingForMediaCenteredShell(
    metrics: DetailOverlayMetrics,
    title: String,
    artist: String,
    coverBitmap: Bitmap?,
    trackKey: String,
    lyricUiKey: String,
    massPushCompact: Boolean,
    padMod: Modifier,
    fitCoverToSeat: Boolean = false,
) {
    BoxWithConstraints(
        modifier = if (massPushCompact) {
            Modifier
                .fillMaxSize()
                .padding(MassPushInset)
        } else {
            padMod.fillMaxSize()
        },
    ) {
        // Waiting shell: only cover + title — size like a real solo cover so
        // landscape does not collapse to a tiny badge in the middle of the pane.
        val isLandscapePane = maxWidth > maxHeight
        val coverSide = if (massPushCompact) {
            // Side-push left pane: full massPushCoverSide (no lyrics foot) reads a
            // bit large — trim gently by short-side class, keep it clearly a hero.
            val base = massPushCoverSide(
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                metrics = metrics,
                reserveLyricLine = false,
                stackMeta = true,
                hasArtist = artist.isNotBlank(),
            )
            val shortSide = minOf(maxWidth, maxHeight)
            val trim = when {
                shortSide >= 400.dp -> 0.86f
                shortSide >= 320.dp -> 0.88f
                else -> 0.90f
            }
            (base * trim)
                .coerceAtMost(maxWidth * 0.78f)
                .coerceAtMost(maxHeight * 0.58f)
                .coerceAtLeast(120.dp)
        } else if (fitCoverToSeat) {
            val meta = metrics.lyricsFocusCoverTitleGap +
                metrics.titleLineHeightSolo.value.dp +
                metrics.titleArtistGap +
                if (artist.isNotBlank()) metrics.artistLineHeightSolo.value.dp else 0.dp
            defaultCoverSide(
                maxWidth = maxWidth,
                maxHeight = (maxHeight - meta).coerceAtLeast(0.dp),
                fillFraction = 1f,
            )
        } else if (isLandscapePane) {
            // Short side drives the square; keep ~2/3 of pane height so the
            // placeholder reads as the hero (controls are already hidden).
            (minOf(maxWidth, maxHeight) * 0.78f)
                .coerceAtMost(maxWidth * 0.42f)
                .coerceAtMost(maxHeight * 0.68f)
                .coerceAtLeast(if (minOf(maxWidth, maxHeight) < 680.dp) 120.dp else 180.dp)
        } else {
            (minOf(maxWidth, maxHeight) * metrics.coverFillFractionSolo)
                .coerceAtMost(maxWidth * 0.82f)
                .coerceAtMost(maxHeight * if (minOf(maxWidth, maxHeight) < 680.dp) 0.46f else 0.52f)
                .coerceAtLeast(if (minOf(maxWidth, maxHeight) < 680.dp) 120.dp else 160.dp)
        }
        val metaMinWidth = if (maxWidth < 680.dp) 160.dp else 240.dp
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CoverCard(
                coverBitmap = coverBitmap,
                trackKey = lyricUiKey,
                cornerRadius = metrics.coverCornerRadius,
                shadowElevation = if (massPushCompact) {
                    metrics.coverShadowElevation * 0.65f
                } else {
                    metrics.coverShadowElevation
                },
                onClick = null,
                modifier = Modifier
                    .size(coverSide)
                    .aspectRatio(1f),
            )
            Spacer(modifier = Modifier.height(metrics.lyricsFocusCoverTitleGap))
            TrackMeta(
                title = title,
                artist = artist,
                trackKey = trackKey,
                metrics = metrics,
                textAlign = TextAlign.Center,
                stackArtistBelow = true,
                prominent = true,
                // At least as wide as the cover so 「等待媒体」 never clips mid-label.
                modifier = Modifier.width(coverSide.coerceAtLeast(metaMinWidth)),
            )
        }
    }
}

@Composable
private fun CoverCard(
    coverBitmap: Bitmap?,
    trackKey: String,
    cornerRadius: Dp,
    shadowElevation: Dp,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clickMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.5f),
            )
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color(0xFF1A1A1C))
            .then(clickMod),
    ) {
        // Bitmap rides in targetState: the exiting layer keeps the OLD artwork
        // during fade+scale (a live coverBitmap read would repaint both layers
        // with the new art), and covers that load after the trackKey switch get
        // their own dissolve instead of an instant snap.
        val coverSettled = rememberBirthSettled()
        AnimatedContent(
            targetState = trackKey to coverBitmap,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (!coverSettled) {
                    // Birth: artwork decodes on IO and arrives after the first paint.
                    // Let it land instantly under the shell fade instead of adding a
                    // fade+scale of its own.
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    (
                        fadeIn(
                            tween(
                                OVERLAY_COVER_IN_MS,
                                delayMillis = 40,
                                easing = OverlaySoftEasing,
                            ),
                        ) +
                            scaleIn(
                                initialScale = OVERLAY_COVER_SCALE_FROM,
                                animationSpec = tween(OVERLAY_COVER_IN_MS, easing = OverlaySoftEasing),
                            )
                        ) togetherWith (
                        fadeOut(tween(OVERLAY_COVER_OUT_MS, easing = OverlaySoftEasing)) +
                            scaleOut(
                                targetScale = OVERLAY_COVER_SCALE_FROM,
                                animationSpec = tween(OVERLAY_COVER_OUT_MS, easing = OverlaySoftEasing),
                            )
                        )
                }
            },
            label = "overlayCoverCard",
        ) { (_, cardBitmap) ->
            val bitmap = cardBitmap?.takeUnless { it.isRecycled }
            val coverImage = remember(bitmap) {
                bitmap?.let { runCatching { it.asImageBitmap() }.getOrNull() }
            }
            if (coverImage != null && bitmap != null && !bitmap.isRecycled) {
                Image(
                    bitmap = coverImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Waiting / no-art: Ava logo zoomed past adaptive-icon padding.
                WaitingCoverImage()
            }
        }
    }
}

/** Title + artist; [stackArtistBelow] splits rows (each may marquee) vs one · line. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackMeta(
    title: String,
    artist: String,
    trackKey: String,
    metrics: DetailOverlayMetrics,
    textAlign: TextAlign = TextAlign.Start,
    stackArtistBelow: Boolean = false,
    /** Lyrics-focus morph: smaller type under the compact cover. */
    compact: Boolean = false,
    /** No-lyrics middle state: slightly larger title/artist. */
    prominent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val baseTitleSp = when {
        compact -> metrics.lyricsFocusTitleSp
        prominent -> metrics.titleSpSolo
        else -> metrics.titleSp
    }
    val baseTitleLineHeight = when {
        compact -> metrics.lyricsFocusTitleLineHeight
        prominent -> metrics.titleLineHeightSolo
        else -> metrics.titleLineHeight
    }
    val titleLetterSpacing =
        if (compact) metrics.lyricsFocusLetterSpacing else metrics.titleLetterSpacing
    val baseArtistSp = when {
        compact -> metrics.lyricsFocusArtistSp
        prominent -> metrics.artistSpSolo
        else -> metrics.artistSp
    }
    val baseArtistLineHeight = when {
        compact -> metrics.lyricsFocusArtistLineHeight
        prominent -> metrics.artistLineHeightSolo
        else -> metrics.artistLineHeight
    }
    val artistAlpha = if (compact) 0.55f else 0.62f
    val textMeasurer = rememberTextMeasurer()
    val metaSettled = rememberBirthSettled()
    AnimatedContent(
        targetState = Triple(trackKey, title, artist),
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            if (!metaSettled) {
                // Birth: credits often trail the title by one protocol packet, and
                // sliding the whole row for that is the most obvious of the competing
                // opening animations.
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                (
                    fadeIn(tween(OVERLAY_MOTION_MS, easing = FastOutSlowInEasing)) +
                        slideInVertically(
                            animationSpec = tween(OVERLAY_MOTION_MS, easing = FastOutSlowInEasing),
                            initialOffsetY = { (it * 0.12f).toInt().coerceAtLeast(4) },
                        )
                    ) togetherWith (
                    fadeOut(tween(OVERLAY_MOTION_FAST_MS, easing = FastOutSlowInEasing)) +
                        slideOutVertically(
                            animationSpec = tween(OVERLAY_MOTION_FAST_MS, easing = FastOutSlowInEasing),
                            targetOffsetY = { -(it * 0.08f).toInt().coerceAtLeast(3) },
                        )
                    )
            }
        },
        label = "overlayTrackMeta",
    ) { (_, animTitle, animArtist) ->
        val columnAlignment = when (textAlign) {
            TextAlign.Center, TextAlign.Justify -> Alignment.CenterHorizontally
            TextAlign.End, TextAlign.Right -> Alignment.End
            else -> Alignment.Start
        }
        if (stackArtistBelow) {
            // Narrow panes (MA side-push) can dual-marquee — relieve only then.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val relief = remember(
                    animTitle,
                    animArtist,
                    constraints.maxWidth,
                    baseTitleSp,
                    baseArtistSp,
                    baseTitleLineHeight,
                    baseArtistLineHeight,
                    titleLetterSpacing,
                    metrics.titleArtistGap,
                ) {
                    resolveStackedMetaDualMarqueeRelief(
                        title = animTitle,
                        artist = animArtist,
                        maxWidthPx = constraints.maxWidth,
                        titleSp = baseTitleSp,
                        artistSp = baseArtistSp,
                        titleLineHeight = baseTitleLineHeight,
                        artistLineHeight = baseArtistLineHeight,
                        titleLetterSpacing = titleLetterSpacing,
                        titleArtistGap = metrics.titleArtistGap,
                        textMeasurer = textMeasurer,
                    )
                }
                val titleLine = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = Color.White,
                            fontSize = relief.titleSp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = titleLetterSpacing,
                        ),
                    ) {
                        append(animTitle)
                    }
                }
                val artistLine = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = Color.White.copy(alpha = artistAlpha),
                            fontSize = relief.artistSp,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append(animArtist)
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = columnAlignment,
                ) {
                    CautiousMarqueeAnnotatedText(
                        text = titleLine,
                        lineHeight = relief.titleLineHeight,
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (animArtist.isNotBlank()) {
                        Spacer(modifier = Modifier.height(relief.gap))
                        CautiousMarqueeAnnotatedText(
                            text = artistLine,
                            lineHeight = relief.artistLineHeight,
                            textAlign = textAlign,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        } else {
            val titleLine = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = Color.White,
                        fontSize = baseTitleSp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = titleLetterSpacing,
                    ),
                ) {
                    append(animTitle)
                }
            }
            val line = buildAnnotatedString {
                append(titleLine)
                if (animArtist.isNotBlank()) {
                    withStyle(
                        SpanStyle(
                            color = Color.White.copy(alpha = artistAlpha),
                            fontSize = baseArtistSp,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append(" · ")
                        append(animArtist)
                    }
                }
            }
            CautiousMarqueeAnnotatedText(
                text = line,
                lineHeight = baseTitleLineHeight,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * When stacked title + artist would **both** marquee in a narrow width (MA
 * side-push), nudge type down a few steps so at least one line can sit still.
 * If both still overflow after the floor, widen the gap slightly so the two
 * moving bands are less cramped. Single-line overflow is left alone.
 */
private data class StackedMetaRelief(
    val titleSp: TextUnit,
    val artistSp: TextUnit,
    val titleLineHeight: TextUnit,
    val artistLineHeight: TextUnit,
    val gap: Dp,
)

private fun resolveStackedMetaDualMarqueeRelief(
    title: String,
    artist: String,
    maxWidthPx: Int,
    titleSp: TextUnit,
    artistSp: TextUnit,
    titleLineHeight: TextUnit,
    artistLineHeight: TextUnit,
    titleLetterSpacing: TextUnit,
    titleArtistGap: Dp,
    textMeasurer: TextMeasurer,
): StackedMetaRelief {
    val untouched = StackedMetaRelief(
        titleSp = titleSp,
        artistSp = artistSp,
        titleLineHeight = titleLineHeight,
        artistLineHeight = artistLineHeight,
        gap = titleArtistGap,
    )
    if (maxWidthPx <= 0 || title.isBlank() || artist.isBlank()) return untouched

    fun lineOverflows(text: String, size: TextUnit, weight: FontWeight, letterSpacing: TextUnit): Boolean {
        val layout = textMeasurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(
                fontSize = size,
                fontWeight = weight,
                letterSpacing = letterSpacing,
            ),
            maxLines = 1,
            softWrap = false,
        )
        return layout.size.width > maxWidthPx
    }

    fun bothOverflow(tSize: TextUnit, aSize: TextUnit): Boolean =
        lineOverflows(title, tSize, FontWeight.Bold, titleLetterSpacing) &&
            lineOverflows(artist, aSize, FontWeight.Medium, 0.sp)

    if (!bothOverflow(titleSp, artistSp)) return untouched

    // Small steps only — stop as soon as one line fits.
    var scale = 1f
    var tSp = titleSp
    var aSp = artistSp
    for (step in floatArrayOf(0.94f, 0.90f, 0.86f, 0.82f)) {
        scale = step
        tSp = (titleSp.value * step).sp
        aSp = (artistSp.value * step).sp
        if (!bothOverflow(tSp, aSp)) break
    }
    val stillBoth = bothOverflow(tSp, aSp)
    // Still dual-scrolling: open the seam a little (keep numeric values bold/busy).
    val gap = if (stillBoth) {
        (titleArtistGap.value + 5f).coerceAtMost(14f).dp
    } else {
        titleArtistGap
    }
    return StackedMetaRelief(
        titleSp = tSp,
        artistSp = aSp,
        titleLineHeight = (titleLineHeight.value * scale).sp,
        artistLineHeight = (artistLineHeight.value * scale).sp,
        gap = gap,
    )
}

/**
 * Same overflow loop as collapsed meta: [basicMarquee] only animates when the
 * line is wider than the container (framework-gated). Seamless repeat via
 * [repeatDelayMillis] = 0 and [MarqueeSpacing(20.dp)].
 *
 * When overflowing, mirror the lyric-wall DstIn edge dissolve (horizontal) so
 * hard clipToBounds edges do not flash while the line scrolls. Short lines that
 * fit stay unmasked — start-aligned focus meta must not permanently soft-clip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CautiousMarqueeAnnotatedText(
    text: AnnotatedString,
    lineHeight: TextUnit,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
) {
    // Latch overflow for this [text]. Writing widths every layout (and toggling
    // end-pad from those widths) re-entered layout in the same frame — the
    // AndroidComposeView "requestLayout during layout" storm after lyrics load.
    var overflowing by remember(text) { mutableStateOf(false) }
    Box(
        modifier = modifier.then(
            when {
                overflowing && !LegacySoftRenderLyrics ->
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colorStops = LyricListEdgeMaskStops,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                overflowing ->
                    Modifier.drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = legacyRowEdgeVignetteStops(leftFade = true),
                            ),
                        )
                    }
                else -> Modifier
            },
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (overflowing) {
                        Modifier.padding(end = LyricOverflowDissolveEndPad)
                    } else {
                        Modifier
                    },
                )
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 2_000,
                    repeatDelayMillis = 0,
                    spacing = MarqueeSpacing(20.dp),
                    velocity = 24.dp,
                ),
            lineHeight = lineHeight,
            maxLines = 1,
            softWrap = false,
            textAlign = textAlign,
            overflow = TextOverflow.Visible,
            onTextLayout = { layout ->
                if (overflowing) return@Text
                val maxW = layout.layoutInput.constraints.maxWidth
                if (maxW == Constraints.Infinity) return@Text
                val lineW = layout.measuredLineWidthPx()
                if (lineW > maxW) overflowing = true
            },
        )
    }
}

/** Karaoke: keep the active word near this fraction of the viewport width. */
private const val KARAOKE_LINE_SCROLL_ANCHOR_FRAC = 0.30f

/**
 * End inset while edge-dissolve is on, so the last glyph sits inside the clear
 * band. Right-only — never pad the start (left dissolve must not shift the line).
 */
private val LyricOverflowDissolveEndPad = 10.dp

/** Intrinsic first-line width; [TextLayoutResult.size] follows constraints and oscillates with padding. */
private fun TextLayoutResult.measuredLineWidthPx(): Int {
    if (lineCount <= 0) return size.width
    return (getLineRight(0) - getLineLeft(0)).toInt()
}

/** True when swapping [this] into state would not change overflow / pan math. */
private fun TextLayoutResult.sameLayoutMetrics(other: TextLayoutResult?): Boolean {
    if (other == null) return false
    if (size != other.size || lineCount != other.lineCount) return false
    if (lineCount == 0) return true
    return getLineLeft(0) == other.getLineLeft(0) &&
        getLineRight(0) == other.getLineRight(0)
}

/**
 * Pre-Android 10 soft-render branch for the vinyl WM overlay (no
 * FLAG_HARDWARE_ACCELERATED): Offscreen+DstIn masks and graphicsLayer pans do not
 * draw. Android 10+ (Q+) keeps the hardware DstIn look.
 *
 * Extra-soft square dissolve (lighter fake top under the header) is only for
 * **Android 8 below** ([Build.VERSION_CODES.O]); Android 8+ keep the normal mask
 * for their render path.
 */
private val LegacySoftRenderLyrics = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

/** Android 7.x and below — square karaoke wall gets the lighter fake vignette. */
private val SoftSquareLyricWall =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O

/**
 * Legacy (software) stand-in for the wall's vertical DstIn mask.
 *
 * An alpha mask multiplies the *content*, so [LyricListEdgeMaskStops]' wide 24% ramp
 * costs nothing. The software stand-in paints black OVER the pane, so reusing those
 * positions blacked out a quarter of the ambient backdrop at each end — two dark bars
 * with a visible seam against the focus chrome, worst on a square pane where 24% + 24%
 * is half the wall.
 *
 * Lines already carry their own distance ramp (active 1f → 0.22f), so all this has to
 * swallow is the row the viewport cuts in half at each edge. Hence a short vignette
 * measured off the row height rather than a fraction of the pane.
 *
 * [softSquare]: even lighter — weak top under the header, modest bottom for cut rows.
 */
private fun legacyWallEdgeVignetteStops(
    viewportPx: Int,
    itemHeightPx: Int,
    softSquare: Boolean = false,
): Array<Pair<Float, Color>> {
    val span = if (viewportPx > 0) {
        (itemHeightPx.toFloat() / viewportPx).coerceIn(
            if (softSquare) 0.05f else 0.06f,
            if (softSquare) 0.12f else 0.18f,
        )
    } else {
        if (softSquare) 0.08f else 0.10f
    }
    if (softSquare) {
        return arrayOf(
            0f to Color.Black.copy(alpha = 0.20f),
            span * 0.5f to Color.Black.copy(alpha = 0.07f),
            span to Color.Transparent,
            1f - span to Color.Transparent,
            1f - span * 0.45f to Color.Black.copy(alpha = 0.18f),
            1f to Color.Black.copy(alpha = 0.48f),
        )
    }
    return arrayOf(
        0f to Color.Black.copy(alpha = 0.86f),
        span * 0.45f to Color.Black.copy(alpha = 0.34f),
        span to Color.Transparent,
        1f - span to Color.Transparent,
        1f - span * 0.45f to Color.Black.copy(alpha = 0.34f),
        1f to Color.Black.copy(alpha = 0.86f),
    )
}

/**
 * Legacy (software) stand-in for a single row's horizontal DstIn mask — panning karaoke
 * line, three-line band, marquee meta.
 *
 * Same trap as [legacyWallEdgeVignetteStops]. Multiplying content is free, so those
 * masks ramp over 15-24% of the row; painted as black they turn both ends of the row
 * into dark blocks over the ambient backdrop, and the left one sits exactly where the
 * karaoke head starts, so the first words open already muddy. The row is hard-clipped by
 * [clipToBounds] anyway, so all this owes is a sliver that softens the cut.
 */
private fun legacyRowEdgeVignetteStops(
    leftFade: Boolean,
    rightFade: Boolean = true,
): Array<Pair<Float, Color>> {
    val edge = Color.Black.copy(alpha = 0.58f)
    val mid = Color.Black.copy(alpha = 0.22f)
    val stops = mutableListOf<Pair<Float, Color>>()
    if (leftFade) {
        stops += 0f to edge
        stops += 0.025f to mid
        stops += 0.06f to Color.Transparent
    } else {
        stops += 0f to Color.Transparent
    }
    if (rightFade) {
        stops += 0.94f to Color.Transparent
        stops += 0.975f to mid
        stops += 1f to edge
    } else {
        stops += 1f to Color.Transparent
    }
    return stops.toTypedArray()
}

/** Shared edge mask: focus-meta marquee (horizontal) and lyric wall (vertical). */
private val LyricListEdgeMaskStops = arrayOf(
    0.0f to Color.Transparent,
    0.12f to Color.White.copy(alpha = 0.38f),
    0.24f to Color.White,
    0.76f to Color.White,
    0.88f to Color.White.copy(alpha = 0.38f),
    1.0f to Color.Transparent,
)

/**
 * Overflow edge mask for long lyric pan (karaoke + three-line).
 * - [leftFade]: both edges (karaoke portrait / default dual).
 * - [softBoth]: narrower dual edges — portrait three-line (wide band ate the band).
 * - [softRight]: landscape three-line right-only, narrow.
 * - else: fuller right-only (landscape karaoke).
 */
private fun lyricOverflowDissolveStops(
    leftFade: Boolean,
    softRight: Boolean = false,
    softBoth: Boolean = false,
): Array<Pair<Float, Color>> = when {
    softBoth -> arrayOf(
        0.0f to Color.Transparent,
        0.04f to Color.White.copy(alpha = 0.40f),
        0.10f to Color.White,
        0.88f to Color.White,
        0.94f to Color.White.copy(alpha = 0.50f),
        0.98f to Color.White.copy(alpha = 0.20f),
        1.0f to Color.Transparent,
    )
    leftFade -> arrayOf(
        0.0f to Color.Transparent,
        0.07f to Color.White.copy(alpha = 0.42f),
        0.15f to Color.White,
        0.74f to Color.White,
        0.82f to Color.White.copy(alpha = 0.62f),
        0.90f to Color.White.copy(alpha = 0.30f),
        0.96f to Color.White.copy(alpha = 0.10f),
        1.0f to Color.Transparent,
    )
    softRight -> arrayOf(
        0.0f to Color.White,
        0.90f to Color.White,
        0.95f to Color.White.copy(alpha = 0.50f),
        0.98f to Color.White.copy(alpha = 0.20f),
        1.0f to Color.Transparent,
    )
    else -> arrayOf(
        0.0f to Color.White,
        0.05f to Color.White,
        0.72f to Color.White,
        0.80f to Color.White.copy(alpha = 0.62f),
        0.88f to Color.White.copy(alpha = 0.32f),
        0.94f to Color.White.copy(alpha = 0.12f),
        1.0f to Color.Transparent,
    )
}

/**
 * Karaoke expanded wall: one line, progress-linked horizontal pan (not loop marquee).
 * Word-sync lines follow the active syllable; line-only LRC pans with [sweepFraction].
 */
@Composable
private fun KaraokeTrackingLyricLine(
    text: AnnotatedString,
    words: List<LrcWord>?,
    lineClockMs: Long,
    metrics: DetailOverlayMetrics,
    textAlign: TextAlign,
    active: Boolean,
    lineColor: Color,
    trackByWord: Boolean,
    sweepKaraoke: Boolean,
    sweepFraction: Float,
    modifier: Modifier = Modifier,
) {
    val isPortrait =
        LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    var containerWidthPx by remember(text) { mutableIntStateOf(0) }
    var textLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val textWidthPx = textLayout?.measuredLineWidthPx() ?: 0
    val rawOverflowPx = (textWidthPx - containerWidthPx).toFloat()
    val overflowing = rawOverflowPx > 0.5f
    // Extra scroll so the last glyph stops before the right dissolve (no start pad).
    val dissolveEndPadPx =
        if (overflowing) with(density) { LyricOverflowDissolveEndPad.toPx() } else 0f
    val maxScrollPx =
        if (overflowing) (rawOverflowPx + dissolveEndPadPx).coerceAtLeast(0f) else 0f

    // Sweep head: soft between ~50ms ticks; hard-align at line open / seek-back
    // so each verse still lands on the stamp (no soft lag at 开头).
    val drawnSweepAnim = remember { Animatable(0f) }
    LaunchedEffect(active, sweepKaraoke, sweepFraction) {
        if (!active || !sweepKaraoke) {
            drawnSweepAnim.snapTo(0f)
            return@LaunchedEffect
        }
        val target = sweepFraction.coerceIn(0f, 1f)
        if (target <= 0.001f || target < drawnSweepAnim.value - 0.02f) {
            drawnSweepAnim.snapTo(target)
        } else {
            drawnSweepAnim.animateTo(
                target,
                animationSpec = tween(KARAOKE_SWEEP_SOFT_MS, easing = LinearEasing),
            )
        }
    }
    val drawnSweepFraction =
        if (active && sweepKaraoke) drawnSweepAnim.value else sweepFraction.coerceIn(0f, 1f)

    val targetScrollPx = when {
        !active || !overflowing -> 0f
        trackByWord && !words.isNullOrEmpty() && textLayout != null -> {
            val layout = textLayout!!
            val fraction = wordSyncSweepFraction(words, lineClockMs)
            val left = (0 until layout.lineCount).minOf { layout.getLineLeft(it) }
            val right = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
            val headX = left + (right - left) * fraction
            val anchorX = containerWidthPx * KARAOKE_LINE_SCROLL_ANCHOR_FRAC
            (headX - anchorX).coerceIn(0f, maxScrollPx)
        }
        // Same soft head as the DstIn brush — no second chase tween.
        sweepKaraoke -> maxScrollPx * drawnSweepFraction
        else -> 0f
    }

    // Word-sync pan still soft-follows the syllable head; line-only pan is already
    // soft via [drawnSweepFraction]. Snap on deactivate / rewind (140ms restart stutter).
    val scrollPxAnim = remember { Animatable(0f) }
    val scrollFollowsSoftSweep = active && overflowing && sweepKaraoke && !trackByWord
    LaunchedEffect(active, overflowing, targetScrollPx, scrollFollowsSoftSweep) {
        if (!active || !overflowing) {
            scrollPxAnim.snapTo(0f)
            return@LaunchedEffect
        }
        if (scrollFollowsSoftSweep || targetScrollPx < scrollPxAnim.value - 0.5f) {
            scrollPxAnim.snapTo(targetScrollPx)
        } else {
            scrollPxAnim.animateTo(
                targetScrollPx,
                animationSpec = tween(KARAOKE_SWEEP_SOFT_MS, easing = LinearEasing),
            )
        }
    }
    val scrollPx = scrollPxAnim.value

    val effectiveTextAlign = if (overflowing) TextAlign.Start else textAlign

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.lyricsExpandedLineHeightDp)
            .clipToBounds()
            .onSizeChanged { containerWidthPx = it.width }
            .then(
                if (LegacySoftRenderLyrics) {
                    Modifier
                } else {
                    Modifier.graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                },
            )
            .drawWithContent {
                drawContent()
                if (overflowing) {
                    // Portrait: both edges whenever the line overflows.
                    // Landscape: right only (no scroll gate — that delayed the fade).
                    if (LegacySoftRenderLyrics) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = legacyRowEdgeVignetteStops(leftFade = isPortrait),
                            ),
                        )
                    } else {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = lyricOverflowDissolveStops(leftFade = isPortrait),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                // Legacy soft render has no offscreen layer for this mask — it wipes
                // the same head with a clip on the glyph layers below instead.
                if (sweepKaraoke && !LegacySoftRenderLyrics) {
                    // TextLayoutResult is local to the Text; Box may center/end-align
                    // and apply scroll. Map into Box x so progress 0→1 brushes the
                    // glyphs themselves (short centered lines were stuck on the left).
                    val layout = textLayout
                    val textW = (layout?.size?.width ?: 0).toFloat()
                    val originX = when {
                        layout == null || textW <= 0f -> 0f
                        overflowing -> -scrollPx
                        textAlign == TextAlign.Center || textAlign == TextAlign.Justify ->
                            (size.width - textW) * 0.5f - scrollPx
                        textAlign == TextAlign.End || textAlign == TextAlign.Right ->
                            size.width - textW - scrollPx
                        else -> -scrollPx
                    }
                    val left: Float
                    val right: Float
                    if (layout != null && layout.lineCount > 0) {
                        left = originX +
                            (0 until layout.lineCount).minOf { layout.getLineLeft(it) }
                        right = originX +
                            (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
                    } else {
                        left = 0f
                        right = size.width
                    }
                    val width = right - left
                    if (width > 1f) {
                        val frac = drawnSweepFraction.coerceIn(0f, 1f)
                        if (frac >= 1f) {
                            // Progress finished — leave glyphs fully lit (no trailing feather).
                        } else {
                            val head = left + width * frac
                            val feather = (width * KARAOKE_SWEEP_FEATHER_FRAC)
                                .coerceIn(6f, (width * 0.35f).coerceAtLeast(6f))
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.White,
                                        1f to Color.White.copy(alpha = KARAOKE_UNSUNG_ALPHA),
                                    ),
                                    startX = head - feather,
                                    endX = head + feather,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    }
                }
            },
        contentAlignment = when {
            overflowing -> Alignment.CenterStart
            textAlign == TextAlign.Center || textAlign == TextAlign.Justify -> Alignment.Center
            textAlign == TextAlign.End || textAlign == TextAlign.Right -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        },
    ) {
        val reportLayout: (TextLayoutResult) -> Unit = { layout ->
            if (!layout.sameLayoutMetrics(textLayout)) textLayout = layout
        }
        // Legacy soft render can't draw the DstIn head above, so the active line used to
        // sit fully lit — the page panned but never sang. Wipe the same head with a clip
        // instead: a dim copy carries the unsung tail, a lit copy on top is clipped to
        // the sung span. Both layers share the layout and the pan, so the wipe edge lands
        // on the glyph the feather would have caught.
        val legacyWipe = LegacySoftRenderLyrics && active && sweepKaraoke
        if (legacyWipe) {
            KaraokeGlyphLayer(
                text = text,
                color = lineColor.copy(alpha = lineColor.alpha * KARAOKE_UNSUNG_ALPHA),
                metrics = metrics,
                textAlign = effectiveTextAlign,
                active = active,
                scrollPx = scrollPx,
                sungFraction = null,
                onTextLayout = reportLayout,
            )
        }
        KaraokeGlyphLayer(
            text = text,
            color = lineColor,
            metrics = metrics,
            textAlign = effectiveTextAlign,
            active = active,
            scrollPx = scrollPx,
            sungFraction = if (legacyWipe) drawnSweepFraction else null,
            onTextLayout = if (legacyWipe) null else reportLayout,
        )
    }
}

/**
 * One glyph layer of the karaoke line. Layers stack inside the line box and take the
 * same style, layout and pan, so the glyphs land on top of each other exactly.
 *
 * [sungFraction] marks the lit layer of the legacy wipe: clip to the sung span rather
 * than mask with [BlendMode.DstIn], which software rendering drops. The edge is hard —
 * a feather needs the alpha mask — but the head tracks the clock the same way.
 */
@Composable
private fun KaraokeGlyphLayer(
    text: AnnotatedString,
    color: Color,
    metrics: DetailOverlayMetrics,
    textAlign: TextAlign,
    active: Boolean,
    scrollPx: Float,
    sungFraction: Float?,
    onTextLayout: ((TextLayoutResult) -> Unit)?,
) {
    Text(
        text = text,
        color = color,
        fontSize = metrics.lyricsExpandedCurrentSp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        lineHeight = metrics.lyricsExpandedCurrentLineHeight,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        onTextLayout = { layout -> onTextLayout?.invoke(layout) },
        modifier = Modifier
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            .then(
                if (LegacySoftRenderLyrics) {
                    // Layout-time placement — graphicsLayer never draws here.
                    Modifier.offset { IntOffset(-scrollPx.roundToInt(), 0) }
                } else {
                    Modifier.graphicsLayer { translationX = -scrollPx }
                },
            )
            .then(
                if (sungFraction == null) {
                    Modifier
                } else {
                    Modifier.drawWithContent {
                        // Node is wrap-content unbounded, so its width is the glyph span
                        // and x = 0 its first glyph — the head needs no scroll fixup.
                        val frac = sungFraction.coerceIn(0f, 1f)
                        when {
                            frac <= 0.001f -> Unit
                            frac >= 0.999f -> drawContent()
                            else -> clipRect(right = size.width * frac) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    }
                },
            ),
    )
}

/** Synced lyrics. Compact = three-line window only (no source caption).
 *  Expanded = full lyric wall (+ optional source row as list item 0).
 *  Compact: tap → fill-block offset HUD (− / +).
 *  Expanded: scroll freely; single-tap line → top HUD; double-tap line → seek
 *  and center that line. Near start/end the compact window pins so the
 *  first/last three lines stay filled (no blank row above the opening lyric).
 */
@Composable
private fun lyricSourceDisplayName(source: LyricSource): String {
    val nameRes = when (source) {
        LyricSource.AMLL -> R.string.lyrics_source_name_amll
        LyricSource.MASS_API -> R.string.lyrics_source_name_mass_api
        LyricSource.QQ -> R.string.lyrics_source_name_qq
        LyricSource.LRCLIB -> R.string.lyrics_source_name_lrclib
    }
    return stringResource(R.string.lyrics_source_line, stringResource(nameRes))
}

@Composable
private fun LyricSourceAttributionLine(
    source: LyricSource,
    metrics: DetailOverlayMetrics,
    textAlign: TextAlign,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val baseSize = if (expanded) {
        metrics.lyricsExpandedAdjacentSp
    } else {
        metrics.lyricsAdjacentSp
    }
    // Quiet caption, clearly subordinate to the lyrics: smaller, lighter,
    // gently letter-spaced. Line height keeps the base slot rhythm.
    val fontSize = baseSize * 0.85f
    Text(
        text = lyricSourceDisplayName(source),
        color = Color.White.copy(alpha = 0.34f),
        fontSize = fontSize,
        fontWeight = FontWeight.Light,
        letterSpacing = fontSize * 0.06f,
        lineHeight = baseSize * 1.2f,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun SyncedLyricsSection(
    lines: List<LrcLine>,
    /**
     * Lyric clock as state, read here rather than by the caller: the 50ms ticker must
     * only invalidate this section, not the whole player. See [rememberLyricSyncPositionMs].
     */
    positionMsState: State<Long>,
    metrics: DetailOverlayMetrics,
    textAlign: TextAlign = TextAlign.Start,
    trackKey: String = "",
    lyricSource: LyricSource? = null,
    expanded: Boolean = false,
    /**
     * Compact band only: one active line (Mass rail open / side-push) vs three-line
     * window (rail closed). Same index / lead / latch path — UI shell picks mode;
     * do not mount a second lyric system.
     */
    singleLine: Boolean = false,
    karaokeLyrics: Boolean = false,
    onSeekClick: ((Long) -> Unit)? = null,
    /**
     * Expanded wall only. When the parent already applied the equal content frame
     * (portrait karaoke / mass-push), pass `0.dp` so side pads are not doubled.
     * `null` = wall owns its own edge pads.
     */
    expandedHorizontalEdgePad: Dp? = null,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return

    // The one read of the lyric clock; confines the 50ms ticker to this scope.
    val positionMs = positionMsState.value

    val context = LocalContext.current
    // Overlay-state lead: HUD and Mass rail share [LyricDisplayRuntime].
    LyricDisplayRuntime.ensureLoaded(context)
    val userLeadMs by LyricDisplayRuntime.leadMs.collectAsState(
        initial = LyricDisplayRuntime.currentLeadMs(),
    )
    var appliedLeadMs by remember {
        mutableLongStateOf(userLeadMs)
    }
    var showOffsetHud by remember(trackKey) { mutableStateOf(false) }
    var dragAccumPx by remember(trackKey) { mutableFloatStateOf(0f) }
    var hudEpoch by remember(trackKey) { mutableLongStateOf(0L) }
    // Bumped when a nudge actually changes the active line → skip fade.
    var offsetSnapEpoch by remember(trackKey) { mutableIntStateOf(0) }
    // True only on the composition that applies a user offset (bypass hold-snap).
    var bypassHoldForOffset by remember(trackKey) { mutableStateOf(false) }

    val effectiveLeadMs = userLeadMs.coerceIn(LYRIC_OFFSET_MIN_MS, LYRIC_OFFSET_MAX_MS)
    val followStep by LyricDisplayRuntime.followStep.collectAsState(
        initial = LyricDisplayRuntime.currentFollowStep(),
    )
    val followPreset = remember(followStep) { LyricDisplayRuntime.presetAt(followStep) }
    // Latch only for near-start progress glitch — do NOT add index hysteresis ms;
    // highlight must follow the lyric clock / progress 1:1.
    var latchedIndex by remember(trackKey) { mutableIntStateOf(-1) }
    // Ordinary API/LRC stamps (no TTML words): follow-preset bias → compare clock
    // to stamp+bias by subtracting bias from the playhead. Word-sync keeps raw stamps.
    val hasWordSyncLines = remember(lines) { lines.any { it.isWordSynced } }
    val stampBiasMs = if (hasWordSyncLines) 0L else followPreset.stampBiasMs
    // Stack user/HUD ms onto the playhead explicitly (leadMs=0 inside parser).
    val leadPositionMs = positionMs + effectiveLeadMs - stampBiasMs
    val rawIndex = LrcParser.indexForPosition(lines, leadPositionMs, leadMs = 0L)
    val holdSnapToStart =
        !bypassHoldForOffset &&
            rawIndex == 0 &&
            latchedIndex > 1 &&
            // Only a near-zero flash — real seek into the intro must show verse 0.
            positionMs < LYRIC_SNAP_GLITCH_MAX_MS
    // -1 = before first timed line (show upcoming only; do not light verse 0 early).
    val currentIndex = if (holdSnapToStart) latchedIndex else rawIndex
    // Hidden interludes (outro / sub-3s gaps / blank-run tails): park on the
    // previous row that paints — sung line OR the run-head ♫♫ row.
    val highlightIndex = when {
        currentIndex in lines.indices &&
            lines[currentIndex].isInterlude &&
            !LrcParser.isDisplayableInterlude(lines, currentIndex) -> {
            (currentIndex - 1 downTo 0)
                .firstOrNull {
                    !lines[it].isInterlude ||
                        LrcParser.isDisplayableInterlude(lines, it)
                }
                ?: -1
        }
        else -> currentIndex
    }
    val windowAnchorIndex = when {
        currentIndex < 0 -> currentIndex
        highlightIndex >= 0 -> highlightIndex
        else -> currentIndex
    }
    LaunchedEffect(rawIndex, holdSnapToStart) {
        if (!holdSnapToStart) {
            latchedIndex = rawIndex
        }
    }
    LaunchedEffect(bypassHoldForOffset) {
        if (bypassHoldForOffset) {
            bypassHoldForOffset = false
        }
    }

    fun bumpHud() {
        showOffsetHud = true
        hudEpoch++
    }

    fun applyUserLead(nextLeadMs: Long) {
        val coerced = nextLeadMs.coerceIn(LYRIC_OFFSET_MIN_MS, LYRIC_OFFSET_MAX_MS)
        if (coerced == userLeadMs) {
            bumpHud()
            return
        }
        appliedLeadMs = coerced
        LyricDisplayRuntime.setLeadMs(context, coerced)
        // Apply immediately — do not wait for LaunchedEffect / hold-snap.
        val newIndex = LrcParser.indexForPosition(
            lines,
            positionMs + coerced - stampBiasMs,
            leadMs = 0L,
        )
        latchedIndex = newIndex
        bypassHoldForOffset = true
        // Always refresh slots so active-row styling updates even when the
        // three visible lines stay the same (common near the pinned start).
        offsetSnapEpoch++
        bumpHud()
    }

    // Rail overlay-state: same latch / bypass / snap-epoch as [applyUserLead].
    // SideEffect (not LaunchedEffect) so karaoke settle sees the epoch bump on
    // the next apply — HUD already sets [appliedLeadMs] first and no-ops here.
    SideEffect {
        if (userLeadMs == appliedLeadMs) return@SideEffect
        appliedLeadMs = userLeadMs
        latchedIndex = LrcParser.indexForPosition(
            lines,
            positionMs + userLeadMs - stampBiasMs,
            leadMs = 0L,
        )
        bypassHoldForOffset = true
        offsetSnapEpoch++
    }

    fun nudgeOffset(deltaMs: Long) {
        applyUserLead(userLeadMs + deltaMs)
    }

    LaunchedEffect(hudEpoch) {
        if (!showOffsetHud) return@LaunchedEffect
        kotlinx.coroutines.delay(2_400L)
        showOffsetHud = false
    }

    // Compact: whole block tap opens fill HUD. Expanded: line gestures own tap/double-tap.
    // Single-line Mass band: no HUD (cramped); still shares lead/index with 3-line.
    val gestureMod = if (singleLine && !expanded) {
        Modifier
    } else {
        Modifier
            .pointerInput(expanded) {
                if (expanded) return@pointerInput
                detectTapGestures(
                    onTap = { bumpHud() },
                    onLongPress = { bumpHud() },
                )
            }
            .pointerInput(showOffsetHud) {
                if (!showOffsetHud) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragAccumPx = 0f
                        bumpHud()
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragAccumPx += dragAmount
                        while (abs(dragAccumPx) >= 24f) {
                            val step = if (dragAccumPx > 0f) 50L else -50L
                            val next = (userLeadMs + step)
                                .coerceIn(LYRIC_OFFSET_MIN_MS, LYRIC_OFFSET_MAX_MS)
                            if (next == userLeadMs) {
                                dragAccumPx = 0f
                                break
                            }
                            applyUserLead(next)
                            dragAccumPx -= if (step > 0L) 24f else -24f
                        }
                    },
                    onDragEnd = {
                        bumpHud()
                        dragAccumPx = 0f
                    },
                    onDragCancel = { dragAccumPx = 0f },
                )
            }
    }

    if (expanded) {
        ExpandedLyricsWall(
            lines = lines,
            lyricSource = lyricSource,
            currentIndex = currentIndex,
            metrics = metrics,
            textAlign = textAlign,
            trackKey = trackKey,
            karaokeLyrics = karaokeLyrics,
            lineClockMs = leadPositionMs,
            showOffsetHud = showOffsetHud,
            offsetSnapEpoch = offsetSnapEpoch,
            karaokeStepSettleMs = followPreset.karaokeStepSettleMs,
            karaokeJumpSettleMs = followPreset.karaokeJumpSettleMs,
            effectiveLeadMs = effectiveLeadMs,
            onNudge = { nudgeOffset(it) },
            onShowHud = { bumpHud() },
            onKeepHudOpen = { bumpHud() },
            onSeekClick = onSeekClick,
            horizontalEdgePad = expandedHorizontalEdgePad,
            modifier = modifier.then(gestureMod),
        )
        return
    }

    // One active line — same highlightIndex / lead as the three-line band.
    // Displayable interludes paint ♫♫ here too (not only in the 3-line window).
    if (singleLine) {
        val displayIndex = when {
            currentIndex in lines.indices &&
                LrcParser.isDisplayableInterlude(lines, currentIndex) -> currentIndex
            highlightIndex in lines.indices &&
                lyricDisplayText(lines, highlightIndex).isNotBlank() -> highlightIndex
            else -> compactNearestDisplayableIndex(lines, highlightIndex, currentIndex)
        } ?: return
        val text = lyricDisplayText(lines, displayIndex)
        if (text.isBlank()) return
        Box(modifier = modifier.fillMaxWidth()) {
            FadeLyricLineSlot(
                text = text,
                metrics = metrics,
                active = true,
                textAlign = textAlign,
                snapEpoch = offsetSnapEpoch,
                panFraction = compactOverflowPanFraction(
                    lines = lines,
                    index = displayIndex,
                    active = true,
                    clockMs = leadPositionMs,
                ),
            )
        }
        return
    }

    // Three-line window over *displayable* rows only (sung + ♫♫). Invisible blank
    // stamps stay on the timeline for karaoke math but must not take a fixed-height slot.
    val displayable = lines.indices.filter { lyricDisplayText(lines, it).isNotBlank() }
    if (displayable.isEmpty()) return
    val lastDisp = displayable.lastIndex
    val anchorInDisplayable = when {
        windowAnchorIndex < 0 -> -1
        else -> {
            val exact = displayable.indexOf(windowAnchorIndex)
            if (exact >= 0) {
                exact
            } else {
                displayable.indexOfLast { it <= windowAnchorIndex }.takeIf { it >= 0 }
                    ?: displayable.indexOfFirst { it >= windowAnchorIndex }.takeIf { it >= 0 }
                    ?: 0
            }
        }
    }
    var latchedWindowStart by remember(trackKey) { mutableIntStateOf(0) }
    val windowStart = stableLyricWindowStart(
        currentIndex = anchorInDisplayable,
        lastIdx = lastDisp,
        previousStart = latchedWindowStart,
    )
    SideEffect {
        if (latchedWindowStart != windowStart) {
            latchedWindowStart = windowStart
        }
    }
    val slotIndices = listOfNotNull(
        displayable.getOrNull(windowStart),
        displayable.getOrNull(windowStart + 1),
        displayable.getOrNull(windowStart + 2),
    )

    // Compact owns up to three lyric slots — never the source caption.
    // Source attribution lives only on the expanded wall (LazyList item + offset).
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.lyricsBlockHeight)
            .then(gestureMod),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(
                metrics.lyricsLineGap,
                Alignment.CenterVertically,
            ),
            horizontalAlignment = when (textAlign) {
                TextAlign.Center, TextAlign.Justify -> Alignment.CenterHorizontally
                TextAlign.End, TextAlign.Right -> Alignment.End
                else -> Alignment.Start
            },
        ) {
            slotIndices.forEach { idx ->
                val active = highlightIndex == idx
                FadeLyricLineSlot(
                    text = lyricDisplayText(lines, idx),
                    metrics = metrics,
                    active = active,
                    textAlign = textAlign,
                    snapEpoch = offsetSnapEpoch,
                    // Three-line only: light on the stamp — snap alpha open, no soft lag.
                    snapActiveAlpha = true,
                    panFraction = compactOverflowPanFraction(
                        lines = lines,
                        index = idx,
                        active = active,
                        clockMs = leadPositionMs,
                    ),
                )
            }
        }

        // Compact: fill the three-line block (legacy). Expanded uses top strip.
        AnimatedVisibility(
            visible = showOffsetHud,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(280)),
            modifier = Modifier.fillMaxSize(),
        ) {
            LyricOffsetHud(
                effectiveLeadMs = effectiveLeadMs,
                onNudge = { nudgeOffset(it) },
                onKeepOpen = { bumpHud() },
                metrics = metrics,
                fillBlock = true,
            )
        }
    }
}

/**
 * How the lyric wall catches the playhead.
 * - [Smooth]: adjacent line step — animate the whole way.
 * - [FastCatchUp]: ≤20s seek/catch-up — snap near target, then short center anim (no hard cut).
 * - [Jump]: >20s — jump straight to the line.
 */
private enum class LyricFollowScroll {
    Smooth,
    FastCatchUp,
    Jump,
}

/** Playback-time gap above this → hard jump; at/under → fast catch-up. */
private const val LYRIC_FOLLOW_FAST_CATCHUP_MAX_MS = 20_000L

/**
 * Align [index]'s vertical mid-line to the viewport mid-line using measured
 * [LazyListLayoutInfo] — never assume contentPadding + scrollToItem(0) centers.
 * Two passes; if the list is already at a scroll edge, stop (best-effort).
 */
private suspend fun LazyListState.scrollLyricLineToCenter(
    index: Int,
    mode: LyricFollowScroll,
) {
    if (index < 0) return

    fun centerDeltaPx(): Float? {
        val info = layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val itemCenter = item.offset + item.size / 2f
        return itemCenter - viewportCenter
    }

    val smooth = mode == LyricFollowScroll.Smooth
    val fastCatchUp = mode == LyricFollowScroll.FastCatchUp

    repeat(2) { pass ->
        var delta = centerDeltaPx()
        if (delta == null) {
            // Smooth may animateScrollToItem; catch-up/jump always land near first
            // so a long animateScrollToItem does not crawl across the wall.
            if (smooth && pass == 0) {
                animateScrollToItem(index)
            } else {
                scrollToItem(index)
            }
            yield()
            delta = centerDeltaPx()
        }
        if (delta == null || abs(delta) < 0.5f) return
        val beforeIndex = firstVisibleItemIndex
        val beforeOffset = firstVisibleItemScrollOffset
        if (smooth || fastCatchUp) {
            animateScrollBy(delta)
        } else {
            scrollBy(delta)
        }
        yield()
        // Hit min/max scroll (common on first/last line) — do not fight the edge.
        if (
            beforeIndex == firstVisibleItemIndex &&
            beforeOffset == firstVisibleItemScrollOffset &&
            abs(delta) > 1f
        ) {
            return
        }
    }
}

/** Pending double-tap seek: clear if playhead never reaches the line. */
private const val LYRIC_PENDING_SEEK_TIMEOUT_MS = 2_500L

private fun lyricDisplayText(lines: List<LrcLine>, index: Int): String {
    val line = lines.getOrNull(index) ?: return ""
    if (!line.isInterlude) return line.text
    // Brief / outro blanks: keep for span math, never show a one-♫ 卡间奏.
    if (!LrcParser.isDisplayableInterlude(lines, index)) return ""
    return LrcParser.interludeDisplayText(LrcParser.interludeSpanMs(lines, index))
}

/**
 * Compact 1-line / 3-line band: pick a row that actually paints text (sung or ♫♫).
 * Invisible blank stamps stay in [lines] for timing but are skipped here.
 */
private fun compactNearestDisplayableIndex(
    lines: List<LrcLine>,
    highlightIndex: Int,
    currentIndex: Int,
): Int? {
    fun displayable(i: Int): Boolean =
        i in lines.indices && lyricDisplayText(lines, i).isNotBlank()

    if (displayable(highlightIndex)) return highlightIndex
    val from = when {
        highlightIndex in lines.indices -> highlightIndex
        currentIndex in lines.indices -> currentIndex
        else -> 0
    }
    (from downTo 0).firstOrNull { displayable(it) }?.let { return it }
    (from..lines.lastIndex).firstOrNull { displayable(it) }?.let { return it }
    return lines.indices.firstOrNull { displayable(it) }
}

/** Karaoke: last timed line fills over this window (no next-line boundary). */
private const val KARAOKE_LAST_LINE_HOLD_MS = 6_000L

/**
 * Floor only when inventing a span (last-line hold / paced blend).
 * Do **not** use this as the karaoke sweep divisor — short LRC gaps are often
 * 300–600ms; coercing those to 900 left the fill stuck near the head.
 */
private const val KARAOKE_MIN_LINE_SPAN_MS = 900L

/** Sweep divisor floor — avoid /0 only; real short lines keep their stamp span. */
private const val KARAOKE_SWEEP_SPAN_EPS_MS = 1L

/**
 * Soft blend between ~50ms lyric-clock ticks (karaoke sweep + overflow pan).
 * A 140ms chase restarted every tick and stuttered; keep this in the 10–50ms
 * band so steps look smooth without lagging the stamp. Line open / rewind snap
 * (see [KaraokeTrackingLyricLine]) so each verse still lands on the timestamp.
 */
private const val KARAOKE_SWEEP_SOFT_MS = 40

/** Karaoke: unsung part of the active line (sung part is fully lit). */
private const val KARAOKE_UNSUNG_ALPHA = 0.58f

/** Karaoke: DstIn sweep feather as a fraction of line width (Apple-style soft edge). */
private const val KARAOKE_SWEEP_FEATHER_FRAC = 0.11f
/**
 * Line-only karaoke (lyrics-scrolling 1:1): last-line / missing-next fallback
 * duration when there is no later stamp. Word-sync never uses this.
 */
private const val KARAOKE_LINE_FALLBACK_DURATION_MS = 2_000L

/**
 * Line-only: long lyric text ⇒ slight sweep speed-up inside the stamp span.
 * Never applied to word-sync or interlude ♫ rows.
 */
private const val KARAOKE_LONG_LINE_SWEEP_BOOST = 1.22f

/** CJK-dominant line feels long around this many syllables/chars. */
private const val KARAOKE_LONG_LINE_CJK_UNITS = 16f

/** Latin-dominant line feels long around this many speech units (≈ letter/2). */
private const val KARAOKE_LONG_LINE_LATIN_UNITS = 21f

private fun isCjkLyricChar(ch: Char): Boolean {
    val code = ch.code
    return code in 0x4E00..0x9FFF || // CJK Unified
        code in 0x3400..0x4DBF || // CJK Ext-A
        code in 0x3040..0x30FF || // Hiragana / Katakana
        code in 0xAC00..0xD7AF // Hangul
}

/**
 * Human singing load for **one** lyric line. CJK syllable ≈ 1; Latin ≈ 2 letters / unit.
 * Not a whole-track metric — call per line only.
 */
private fun lyricLineSpeechUnits(text: String): Pair<Float, Boolean> {
    var cjk = 0
    var latinLetters = 0
    for (ch in text) {
        when {
            isCjkLyricChar(ch) -> cjk++
            ch.isLetter() -> latinLetters++
        }
    }
    val units = cjk + latinLetters / 2f
    val cjkDominant = cjk >= latinLetters
    return units to cjkDominant
}

/** True when this single line's text is long for its script (CN/JP/KR vs Latin). */
private fun isLongLyricLine(text: String): Boolean {
    val (units, cjkDominant) = lyricLineSpeechUnits(text)
    return if (cjkDominant) {
        units >= KARAOKE_LONG_LINE_CJK_UNITS
    } else {
        units >= KARAOKE_LONG_LINE_LATIN_UNITS
    }
}

/**
 * Slight visual boost for one long line. Uses that line's timeline span only as
 * the window to accelerate inside — never a whole-song judgment.
 */
private fun karaokeLongLineProgressBoost(text: String): Float =
    if (isLongLyricLine(text)) KARAOKE_LONG_LINE_SWEEP_BOOST else 1f

/**
 * Advance [clockMs] inside [startMs]..[endMs] by [boost] (long-line only).
 * Outside / boost≤1 → unchanged. Caps at [endMs] so the next line is not pulled.
 */
private fun accelerateClockInLineSpan(
    clockMs: Long,
    startMs: Long,
    endMs: Long,
    boost: Float,
): Long {
    if (boost <= 1.001f || endMs <= startMs) return clockMs
    if (clockMs <= startMs) return clockMs
    val t = clockMs - startMs
    val accelerated = startMs + (t * boost).toLong()
    return accelerated.coerceAtMost(endMs)
}

private fun wordLitFraction(word: LrcWord, clockMs: Long): Float {
    if (clockMs <= word.beginMs) return 0f
    if (clockMs >= word.endMs) return 1f
    val span = (word.endMs - word.beginMs).coerceAtLeast(1L)
    return ((clockMs - word.beginMs).toFloat() / span).coerceIn(0f, 1f)
}

/**
 * 0..1 sweep head from word timestamps.
 * Inside a word: length-weighted fill. Between words: hold at the boundary
 * (previous word fully lit) — no synthetic lead into the next glyph.
 */
private fun wordSyncSweepFraction(words: List<LrcWord>, clockMs: Long): Float {
    if (words.isEmpty()) return 0f
    if (clockMs < words.first().beginMs) return 0f
    if (clockMs >= words.last().endMs) return 1f
    var totalWeight = 0f
    for (word in words) {
        totalWeight += word.text.length.coerceAtLeast(1).toFloat()
    }
    if (totalWeight <= 0f) return 0f
    var completedWeight = 0f
    for (word in words) {
        val weight = word.text.length.coerceAtLeast(1).toFloat()
        if (clockMs < word.beginMs) {
            // Gap after previous end — keep head on the boundary.
            return (completedWeight / totalWeight).coerceIn(0f, 1f)
        }
        if (clockMs < word.endMs) {
            val lit = wordLitFraction(word, clockMs)
            return ((completedWeight + weight * lit) / totalWeight).coerceIn(0f, 1f)
        }
        completedWeight += weight
    }
    return 1f
}

private fun wordSyncSweepFraction(line: LrcLine, clockMs: Long): Float =
    wordSyncSweepFraction(line.words.orEmpty(), clockMs)

/**
 * Full lyric wall for cover-tap focus mode.
 * - Active line on optical mid-line (layoutInfo center), incl. first/last.
 * - Intro (index &lt; 0): park line 0 centered so verse 0 does not jump.
 * - User drag pauses follow; programmatic scroll does not.
 * - Single-tap → top HUD; double-tap → seek + center (pending times out).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpandedLyricsWall(
    lines: List<LrcLine>,
    lyricSource: LyricSource?,
    currentIndex: Int,
    metrics: DetailOverlayMetrics,
    textAlign: TextAlign,
    trackKey: String,
    karaokeLyrics: Boolean,
    /** Offset-adjusted lyric clock (same clock that picks [currentIndex]). */
    lineClockMs: Long,
    showOffsetHud: Boolean,
    /** Bumped by overlay offset (HUD / Mass rail) — skip karaoke settle. */
    offsetSnapEpoch: Int = 0,
    karaokeStepSettleMs: Long = 200L,
    karaokeJumpSettleMs: Long = 80L,
    effectiveLeadMs: Long,
    onNudge: (Long) -> Unit,
    onShowHud: () -> Unit,
    onKeepHudOpen: () -> Unit,
    onSeekClick: ((Long) -> Unit)? = null,
    /**
     * Override wall side pads. `null` = wall owns them:
     * square panes use the full equal content frame; phones keep the asymmetric
     * 0.5 / 0.65 column inset for landscape karaoke.
     */
    horizontalEdgePad: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val listIndexOffset = if (lyricSource != null) 1 else 0
    val hasWordSync = lines.any { it.isWordSynced }
    var previousFollowTarget by remember(trackKey) { mutableIntStateOf(Int.MIN_VALUE) }
    var followSuspended by remember(trackKey) { mutableStateOf(false) }
    var userScrollEpoch by remember(trackKey) { mutableIntStateOf(0) }
    // After double-tap seek: pin follow until playhead catches up (or timeout).
    var pendingSeekIndex by remember(trackKey) { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val itemHeightPx = with(density) { metrics.lyricsExpandedLineHeightDp.roundToPx() }
    val (textPadStart, textPadEnd) = when {
        horizontalEdgePad != null -> horizontalEdgePad to horizontalEdgePad
        // Square: keep the same flat inset as the unexpanded player.
        metrics.compactLyricBandSingleLine ->
            metrics.contentPaddingH to metrics.contentPaddingH
        else ->
            (metrics.contentPaddingH * 0.5f) to (metrics.contentPaddingH * 0.65f)
    }
    val lastIdx = lines.lastIndex

    // Line-only karaoke settle: LRC stamps can flicker across boundaries —
    // commit an adjacent step only after it holds briefly. Word-sync skips
    // this; TTML begin/end already define the handoff.
    // Overlay offset (HUD or Mass rail) is not playback chatter — skip settle.
    var karaokeIndex by remember(trackKey) { mutableIntStateOf(currentIndex) }
    var lastHudSnapEpoch by remember(trackKey) { mutableIntStateOf(0) }
    var lastLeadMsForSettle by remember(trackKey) { mutableLongStateOf(effectiveLeadMs) }
    LaunchedEffect(
        trackKey,
        karaokeLyrics,
        hasWordSync,
        currentIndex,
        offsetSnapEpoch,
        effectiveLeadMs,
        karaokeStepSettleMs,
        karaokeJumpSettleMs,
    ) {
        val hudNudge = offsetSnapEpoch != lastHudSnapEpoch
        lastHudSnapEpoch = offsetSnapEpoch
        val leadNudge = effectiveLeadMs != lastLeadMsForSettle
        lastLeadMsForSettle = effectiveLeadMs
        if (!karaokeLyrics || hasWordSync || hudNudge || leadNudge) {
            karaokeIndex = currentIndex
            return@LaunchedEffect
        }
        if (currentIndex == karaokeIndex) return@LaunchedEffect
        val step = abs(currentIndex - karaokeIndex)
        val settle = if (step == 1) karaokeStepSettleMs else karaokeJumpSettleMs
        if (settle > 0L) delay(settle)
        karaokeIndex = currentIndex
    }
    val rawEmphasizedIndex = if (karaokeLyrics) karaokeIndex else currentIndex
    // Hidden interludes (outro / brief gap / blank-run tails): no own ♫ row —
    // follow the previous row that paints (sung line or run-head ♫♫).
    val emphasizedIndex = when {
        rawEmphasizedIndex in 0..lastIdx &&
            lines[rawEmphasizedIndex].isInterlude &&
            !LrcParser.isDisplayableInterlude(lines, rawEmphasizedIndex) -> {
            (rawEmphasizedIndex - 1 downTo 0)
                .firstOrNull {
                    !lines[it].isInterlude ||
                        LrcParser.isDisplayableInterlude(lines, it)
                }
                ?: -1
        }
        else -> rawEmphasizedIndex
    }

    // Stable follow target: intro parks on 0; displayable ♫ is followable; brief/outro is not.
    val followTarget = when {
        pendingSeekIndex in 0..lastIdx -> pendingSeekIndex
        emphasizedIndex < 0 -> 0
        else -> emphasizedIndex
    }

    // Only user drag/fling suspends follow — never programmatic animateScrollBy.
    val userScrollConnection = remember(trackKey) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val fromUser =
                    source == NestedScrollSource.Drag || source == NestedScrollSource.Fling
                if (fromUser && available.y != 0f) {
                    followSuspended = true
                    userScrollEpoch++
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(userScrollEpoch, trackKey) {
        if (userScrollEpoch <= 0 || !followSuspended) return@LaunchedEffect
        delay(2_800L)
        followSuspended = false
    }

    LaunchedEffect(currentIndex, pendingSeekIndex) {
        if (pendingSeekIndex >= 0 && currentIndex == pendingSeekIndex) {
            pendingSeekIndex = -1
        }
    }

    LaunchedEffect(pendingSeekIndex, trackKey) {
        if (pendingSeekIndex < 0) return@LaunchedEffect
        delay(LYRIC_PENDING_SEEK_TIMEOUT_MS)
        if (pendingSeekIndex >= 0) {
            pendingSeekIndex = -1
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportPx = constraints.maxHeight.coerceAtLeast(0)
        // Edge pads so first/last lines can physically reach the mid-line.
        val centerPadPx = ((viewportPx - itemHeightPx) / 2).coerceAtLeast(0)
        val centerPadDp = with(density) { centerPadPx.toDp() }
        // Extra-soft square top dissolve: Android 8 below only. Android 8+ keep
        // the normal mask for their path (DstIn on 10+, stock legacy vignette on 8–9).
        val softSquareWall =
            SoftSquareLyricWall && metrics.compactLyricBandSingleLine
        val legacyEdgeVignetteStops = remember(viewportPx, itemHeightPx, softSquareWall) {
            legacyWallEdgeVignetteStops(
                viewportPx = viewportPx,
                itemHeightPx = itemHeightPx,
                softSquare = softSquareWall,
            )
        }

        LaunchedEffect(
            followTarget,
            trackKey,
            viewportPx,
            followSuspended,
            lastIdx,
        ) {
            if (viewportPx <= 0 || lastIdx < 0) return@LaunchedEffect
            // User browsing: pause follow unless a double-tap seek is in flight.
            if (followSuspended && pendingSeekIndex < 0) return@LaunchedEffect
            if (followTarget !in 0..lastIdx) return@LaunchedEffect
            try {
                val prev = previousFollowTarget
                previousFollowTarget = followTarget
                // Adjacent step: smooth. Larger catch-up: by playback-time gap —
                // ≤20s snap-near + short center anim; >20s hard jump.
                val mode = when {
                    prev != Int.MIN_VALUE && abs(followTarget - prev) <= 1 ->
                        LyricFollowScroll.Smooth
                    else -> {
                        val fromIdx = when {
                            prev in 0..lastIdx -> prev
                            else -> (listState.firstVisibleItemIndex - listIndexOffset)
                                .coerceIn(0, lastIdx)
                        }
                        val gapMs = abs(
                            lines[followTarget].timeMs - lines[fromIdx].timeMs,
                        )
                        if (gapMs <= LYRIC_FOLLOW_FAST_CATCHUP_MAX_MS) {
                            LyricFollowScroll.FastCatchUp
                        } else {
                            LyricFollowScroll.Jump
                        }
                    }
                }
                // Keyed on [followTarget] (not raw currentIndex) so -1↔0 does not
                // cancel in-flight center. Resume-after-drag still re-runs via
                // followSuspended in the key set.
                listState.scrollLyricLineToCenter(
                    followTarget + listIndexOffset,
                    mode = mode,
                )
            } catch (_: IllegalArgumentException) {
                // First frame after morph may not have laid out the target yet.
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                userScrollEnabled = true,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(userScrollConnection)
                    .then(
                        if (LegacySoftRenderLyrics) {
                            Modifier.drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colorStops = legacyEdgeVignetteStops,
                                    ),
                                )
                            }
                        } else {
                            Modifier
                                .graphicsLayer {
                                    compositingStrategy = CompositingStrategy.Offscreen
                                }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            colorStops = LyricListEdgeMaskStops,
                                        ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                        },
                    ),
                contentPadding = PaddingValues(
                    start = textPadStart,
                    end = textPadEnd,
                    top = centerPadDp,
                    bottom = centerPadDp,
                ),
                verticalArrangement = Arrangement.spacedBy(metrics.lyricsExpandedLineGap),
                horizontalAlignment = when (textAlign) {
                    TextAlign.Center, TextAlign.Justify -> Alignment.CenterHorizontally
                    TextAlign.End, TextAlign.Right -> Alignment.End
                    else -> Alignment.Start
                },
            ) {
                if (lyricSource != null) {
                    item(key = "$trackKey|source") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(metrics.lyricsExpandedLineHeightDp),
                            contentAlignment = when (textAlign) {
                                TextAlign.Center, TextAlign.Justify -> Alignment.Center
                                TextAlign.End, TextAlign.Right -> Alignment.CenterEnd
                                else -> Alignment.CenterStart
                            },
                        ) {
                            LyricSourceAttributionLine(
                                source = lyricSource,
                                metrics = metrics,
                                textAlign = textAlign,
                                expanded = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                itemsIndexed(
                    items = lines,
                    key = { index, line -> "$trackKey|$index|${line.timeMs}" },
                ) { index, line ->
                    // Brief / outro blank stamps: keep timeline, never show ♫ / take a row.
                    if (line.isInterlude && !LrcParser.isDisplayableInterlude(lines, index)) {
                        return@itemsIndexed
                    }
                    val active = index == emphasizedIndex || index == pendingSeekIndex
                    val alpha = when {
                        emphasizedIndex < 0 && pendingSeekIndex < 0 -> 0.36f
                        active -> 1f
                        abs(index - emphasizedIndex) == 1 -> 0.50f
                        abs(index - emphasizedIndex) == 2 -> 0.34f
                        else -> 0.22f
                    }
                    // Karaoke: no size emphasis — the handoff itself is the effect.
                    // Leaving / arriving lines cross-dissolve on one long soft curve.
                    val lineAlpha = if (karaokeLyrics) {
                        animateFloatAsState(
                            targetValue = alpha,
                            animationSpec = tween(520, easing = OverlaySoftEasing),
                            label = "karaokeLineAlpha",
                        ).value
                    } else {
                        alpha
                    }
                    val displayPlain = lyricDisplayText(lines, index)
                    // Mid-song interlude sweeps ♫; sung lines keep word/line rules.
                    val wordSweepKaraoke =
                        karaokeLyrics && active && !line.isInterlude && line.isWordSynced
                    val lineSweepKaraoke = karaokeLyrics && active && (
                        line.isInterlude || (!line.isWordSynced && !hasWordSync)
                    )
                    val sweepKaraoke = wordSweepKaraoke || lineSweepKaraoke
                    val lineStartMs = when {
                        wordSweepKaraoke ->
                            line.words?.firstOrNull()?.beginMs ?: line.timeMs
                        else -> line.timeMs
                    }
                    // Line end for sweep progress:
                    // - Word-sync: TTML / singing bounds.
                    // - Blank interlude ♫: real stamp→next (or hold) — never soft-capped.
                    // - Line-only sung: next stamp when spacing is normal; soft-cap via
                    //   singingEndMs only for unmarked large gaps (no ♫ invented).
                    // - Last sung line (no next): lyrics-scrolling fallback 2000.
                    // Never reintroduce pacedProgressSpanMs here (that raced short lines).
                    val lineEndMs = when {
                        wordSweepKaraoke ->
                            LrcParser.singingEndMs(lines, index)
                                .coerceAtLeast(lineStartMs + 1L)
                        line.isInterlude ->
                            LrcParser.singingEndMs(lines, index)
                                .coerceAtLeast(lineStartMs + 1L)
                        lines.getOrNull(index + 1) == null ->
                            lineStartMs + KARAOKE_LINE_FALLBACK_DURATION_MS
                        else ->
                            LrcParser.singingEndMs(lines, index)
                                .coerceAtLeast(lineStartMs + 1L)
                    }
                    val lineSpanMs = (lineEndMs - lineStartMs)
                        .coerceAtLeast(KARAOKE_SWEEP_SPAN_EPS_MS)
                    // Long-line ×1.22 only on line-only sung text — not TTML, not ♫.
                    val longLineBoost =
                        if (lineSweepKaraoke && !line.isInterlude) {
                            karaokeLongLineProgressBoost(line.text)
                        } else {
                            1f
                        }
                    val sweepClockMs = if (wordSweepKaraoke) {
                        lineClockMs
                    } else {
                        accelerateClockInLineSpan(
                            clockMs = lineClockMs,
                            startMs = lineStartMs,
                            endMs = lineEndMs,
                            boost = longLineBoost,
                        )
                    }
                    val sweepTarget = when {
                        wordSweepKaraoke -> wordSyncSweepFraction(line, sweepClockMs)
                        lineSweepKaraoke -> {
                            val linear = ((sweepClockMs - lineStartMs).toFloat() / lineSpanMs)
                                .coerceIn(0f, 1f)
                            // Inside the stamp window: dwell on spaces / commas
                            // (width-fill base + punct weights; not paced compression).
                            if (line.isInterlude) {
                                linear
                            } else {
                                LrcParser.lineOnlySweepFraction(line.text, linear)
                            }
                        }
                        else -> 0f
                    }
                    // Both paths snap to target — open-source width-fill has no chase tween.
                    val sweepFraction = when {
                        wordSweepKaraoke || lineSweepKaraoke -> sweepTarget
                        else -> 0f
                    }
                    val displayText = AnnotatedString(displayPlain)
                    var sweepLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    val lineModifier = Modifier
                        .combinedClickable(
                            interactionSource = remember(index) {
                                MutableInteractionSource()
                            },
                            indication = null,
                            onClick = onShowHud,
                            onDoubleClick = {
                                followSuspended = false
                                pendingSeekIndex = index
                                previousFollowTarget = Int.MIN_VALUE
                                onSeekClick?.invoke(line.timeMs.coerceAtLeast(0L))
                            },
                        )
                    if (karaokeLyrics) {
                        KaraokeTrackingLyricLine(
                            text = displayText,
                            words = line.words,
                            // Word-sync pan uses the same TTML clock as the sweep head.
                            lineClockMs = if (active) sweepClockMs else 0L,
                            metrics = metrics,
                            textAlign = textAlign,
                            active = active,
                            lineColor = Color.White.copy(alpha = lineAlpha),
                            trackByWord = wordSweepKaraoke,
                            sweepKaraoke = sweepKaraoke,
                            sweepFraction = sweepFraction,
                            modifier = lineModifier,
                        )
                    } else {
                        Text(
                            text = displayText,
                            color = Color.White.copy(alpha = lineAlpha),
                            fontSize = metrics.lyricsExpandedCurrentSp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            lineHeight = metrics.lyricsExpandedCurrentLineHeight,
                            textAlign = textAlign,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { layout ->
                                if (!layout.sameLayoutMetrics(sweepLayout)) sweepLayout = layout
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(metrics.lyricsExpandedLineHeightDp)
                                .then(
                                    // Legacy soft render: DstIn sweep cannot draw —
                                    // active line simply stays fully lit.
                                    if (lineSweepKaraoke && !LegacySoftRenderLyrics) {
                                        Modifier
                                            .graphicsLayer {
                                                compositingStrategy = CompositingStrategy.Offscreen
                                            }
                                            .drawWithContent {
                                                drawContent()
                                                val layout = sweepLayout
                                                val left: Float
                                                val right: Float
                                                if (layout != null && layout.lineCount > 0) {
                                                    left = (0 until layout.lineCount)
                                                        .minOf { layout.getLineLeft(it) }
                                                    right = (0 until layout.lineCount)
                                                        .maxOf { layout.getLineRight(it) }
                                                } else {
                                                    left = 0f
                                                    right = size.width
                                                }
                                                val width = right - left
                                                if (width > 1f) {
                                                    val head = left + width * sweepFraction
                                                val feather =
                                                    (width * KARAOKE_SWEEP_FEATHER_FRAC)
                                                        .coerceIn(6f, (width * 0.35f).coerceAtLeast(6f))
                                                    drawRect(
                                                        brush = Brush.horizontalGradient(
                                                            colorStops = arrayOf(
                                                                0f to Color.White,
                                                                1f to Color.White.copy(
                                                                    alpha = KARAOKE_UNSUNG_ALPHA,
                                                                ),
                                                            ),
                                                            startX = head - feather,
                                                            endX = head + feather,
                                                        ),
                                                        blendMode = BlendMode.DstIn,
                                                    )
                                                }
                                            }
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(lineModifier),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showOffsetHud,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(280)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 10.dp, end = 10.dp),
            ) {
                LyricOffsetHud(
                    effectiveLeadMs = effectiveLeadMs,
                    onNudge = onNudge,
                    onKeepOpen = onKeepHudOpen,
                    metrics = metrics,
                    fillBlock = false,
                )
            }
        }
    }
}

@Composable
private fun LyricOffsetHud(
    effectiveLeadMs: Long,
    onNudge: (Long) -> Unit,
    onKeepOpen: () -> Unit,
    metrics: DetailOverlayMetrics,
    fillBlock: Boolean,
) {
    val keepOpenInteraction = remember { MutableInteractionSource() }
    if (fillBlock) {
        // Compact three-line block: fill the lyrics area (legacy).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.52f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = keepOpenInteraction,
                        indication = null,
                        onClick = onKeepOpen,
                    ),
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LyricOffsetStepButton(onClick = { onNudge(-LYRIC_OFFSET_STEP_MS) }) {
                    Icon(
                        imageVector = Icons.Filled.Remove,
                        contentDescription = "Decrease lyric offset",
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "%+d ms".format(effectiveLeadMs),
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = (metrics.lyricsCurrentSp.value).sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                LyricOffsetStepButton(onClick = { onNudge(LYRIC_OFFSET_STEP_MS) }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Increase lyric offset",
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        return
    }

    // Expanded: compact top bar — height wraps so lyrics below stay interactive.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.52f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LyricOffsetStepButton(onClick = { onNudge(-LYRIC_OFFSET_STEP_MS) }) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = "Decrease lyric offset",
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(18.dp),
            )
        }
        // Middle label only — do not wrap ± buttons in a parent clickable.
        Text(
            text = "%+d ms".format(effectiveLeadMs),
            color = Color.White.copy(alpha = 0.94f),
            fontSize = (metrics.lyricsCurrentSp.value * 0.88f).coerceAtLeast(13f).sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = keepOpenInteraction,
                    indication = null,
                    onClick = onKeepOpen,
                ),
        )
        LyricOffsetStepButton(onClick = { onNudge(LYRIC_OFFSET_STEP_MS) }) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Increase lyric offset",
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun LyricOffsetStepButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "lyricOffsetStepScale",
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    pressed = true
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(140)
            pressed = false
        }
    }
}

private const val LYRIC_OFFSET_MIN_MS = LyricDisplayRuntime.OFFSET_MIN_MS
private const val LYRIC_OFFSET_MAX_MS = LyricDisplayRuntime.OFFSET_MAX_MS
private const val LYRIC_OFFSET_STEP_MS = LyricDisplayRuntime.OFFSET_STEP_MS
/** Match SendspinManager overlay lyric lag (pipeline + bar fill-in undo). */
private const val LYRIC_AUDIBLE_LAG_UI_MAX_MS = 660L

/** Soft overlay motion — short, small amplitude. */
private const val OVERLAY_MOTION_MS = 240
private const val OVERLAY_MOTION_FAST_MS = 180
/** Lyric line crossfade: keep soft scroll, but short enough not to feel late. */
private const val OVERLAY_LYRIC_IN_MS = 110
private const val OVERLAY_LYRIC_OUT_MS = 80
/** Landscape Mass side-push: slightly slower + softer than the first cut. */
private const val MASS_PUSH_FRACTION_MS = 480
private const val MASS_PUSH_MORPH_IN_MS = 460
private const val MASS_PUSH_MORPH_OUT_MS = 340
/** Portrait Mass overlay: NP lyric band 3→1. Down only — close snaps. */
private const val PORTRAIT_RAIL_NP_DOWN_MS = 480
/** Player ↔ lyrics-focus: fast fade + slight leftward dissolve (cover sits left). */
private const val LYRICS_FOCUS_MORPH_IN_MS = 200
private const val LYRICS_FOCUS_MORPH_OUT_MS = 150
private const val LYRICS_FOCUS_MORPH_SLIDE_FRAC = 0.035f
/** Middle → left chrome: same dissolve, +120ms so the slide reads. */
private const val LYRICS_FOCUS_WALL_IN_MS = LYRICS_FOCUS_MORPH_IN_MS + 120
private const val LYRICS_FOCUS_WALL_OUT_MS = LYRICS_FOCUS_MORPH_OUT_MS + 120
private const val LYRICS_FOCUS_WALL_SLIDE_FRAC = 0.06f
/** Ignore cover taps while a morph is still settling. */
private const val LYRICS_FOCUS_TOGGLE_GUARD_MS = 220L
/**
 * Cover / ambient crossfades. The previous curve was ease-out-quint
 * (0.22, 1, 0.36, 1), which covers ~90% of its travel in the first third of the
 * duration — paired with 700ms that reads as a snap plus a long dead tail rather
 * than a soft dissolve. This spreads the travel evenly, so the durations below can
 * come down to roughly the old *perceived* length.
 */
private val OverlaySoftEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)
private const val OVERLAY_COVER_IN_MS = 400
private const val OVERLAY_COVER_OUT_MS = 320
private const val OVERLAY_BACKDROP_IN_MS = 420
private const val OVERLAY_BACKDROP_OUT_MS = 360
private const val OVERLAY_COVER_SCALE_FROM = 0.985f
/** Tiny vertical nudge for lyric line changes — not a page flip. */
private const val OVERLAY_LYRIC_SLIDE_PX = 8

/**
 * Cold start paints the title first and fills in artwork, credits and lyrics over the
 * next few hundred ms. Letting each arrival run its own crossfade stacks several
 * uncoordinated fades on top of the service's birth fade, which is what makes the open
 * feel yanked. Inside this window content changes land instantly, so birth stays a
 * single fade owned by the shell.
 */
private const val OVERLAY_BIRTH_SETTLE_MS = 320L

/**
 * False for [OVERLAY_BIRTH_SETTLE_MS] after the caller first enters composition — i.e.
 * while the overlay is still being born and its metadata is still arriving.
 */
@Composable
private fun rememberBirthSettled(): Boolean {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(OVERLAY_BIRTH_SETTLE_MS)
        settled = true
    }
    return settled
}

/**
 * Shared by compact pan + karaoke line-sweep/pan.
 * Timeline gap may outlast speaking — compress slack so progress does not crawl.
 * Interlude ♫ keeps the real gap (marks already sized from it).
 */
private fun pacedProgressSpanMs(line: LrcLine, timelineSpanMs: Long): Long {
    val timelineSpan = timelineSpanMs.coerceAtLeast(1L)
    if (line.isInterlude) return timelineSpan
    val paced = LrcParser.estimatedSingSpanMs(line.text)
    if (timelineSpan <= paced) return timelineSpan
    val blended = paced + ((timelineSpan - paced) * LYRIC_PAN_SLACK_KEEP).toLong()
    return blended.coerceIn(KARAOKE_MIN_LINE_SPAN_MS, timelineSpan)
}

/** Keep this fraction of (timeline − paced) so progress is not only the estimate. */
private const val LYRIC_PAN_SLACK_KEEP = 0.22f

/**
 * Compact three-line pan: keep more of the LRC gap than karaoke sweep (0.22),
 * so scroll tracks estimated singing without racing to the end of the line.
 */
private const val COMPACT_PAN_SLACK_KEEP = 0.50f

/**
 * Compact three-line overflow pan 0→1.
 * Maps scroll to an estimated singing window (not the full stamp→stamp gap),
 * so long lines move with the vocal instead of crawling. Still milder than
 * karaoke's 0.22 slack — avoids the old "finished before the voice" feel.
 * Motion still [snap]s to the lyric clock (no short tween stutter).
 */
private fun compactOverflowPanFraction(
    lines: List<LrcLine>,
    index: Int,
    active: Boolean,
    clockMs: Long,
): Float {
    if (!active || index !in lines.indices) return 0f
    val line = lines[index]
    // Word-sync: pan with the real sung head — timestamps already dwell at breaths.
    if (line.isWordSynced) return wordSyncSweepFraction(line, clockMs)
    val startMs = line.timeMs
    val endMs = LrcParser.singingEndMs(lines, index).coerceAtLeast(startMs + 1L)
    val timelineSpan = (endMs - startMs).coerceAtLeast(1L)
    val span = compactPanSpanMs(line, timelineSpan)
    val linear = ((clockMs - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    // Dwell on spaces / commas like the wall sweep — a linear pan races through
    // breath pauses and reaches the line end before the vocal does.
    return if (line.isInterlude) {
        linear
    } else {
        LrcParser.lineOnlySweepFraction(line.text, linear)
    }
}

/** Singing-weighted pan window for three-line overflow (highlight still uses full gap). */
private fun compactPanSpanMs(line: LrcLine, timelineSpanMs: Long): Long {
    val timelineSpan = timelineSpanMs.coerceAtLeast(1L)
    if (line.isInterlude) return timelineSpan
    val paced = LrcParser.estimatedSingSpanMs(line.text)
    if (timelineSpan <= paced) return timelineSpan
    val blended = paced + ((timelineSpan - paced) * COMPACT_PAN_SLACK_KEEP).toLong()
    return blended.coerceIn(1L, timelineSpan)
}

/** One lyric row: soft fade + slight vertical scroll on text change. */
@Composable
private fun FadeLyricLineSlot(
    text: String?,
    metrics: DetailOverlayMetrics,
    active: Boolean,
    textAlign: TextAlign,
    snapEpoch: Int = 0,
    /**
     * Three-line compact only: snap opacity when a row becomes active so the
     * highlight opens on the stamp (no soft lag). Single-line keeps default fade.
     */
    snapActiveAlpha: Boolean = false,
    /** 0..1 progress pan when the line overflows; same right-dissolve as karaoke. */
    panFraction: Float = 0f,
) {
    // Invisible interlude stamps must not reserve a fixed-height empty row.
    if (text.isNullOrBlank()) return
    // Keep [active] OUT of AnimatedContent targetState — highlight-only changes
    // must not rebuild the row (that flashed bold/dim on fast tracks). Weight /
    // alpha update in-place via [LyricLineText]; snapEpoch still forces a refresh
    // after HUD nudges when the three visible lines stay the same.
    AnimatedContent(
        targetState = text to snapEpoch,
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.lyricsLineHeightDp)
            .clipToBounds(),
        transitionSpec = {
            val textChanged = initialState.first != targetState.first
            val fromOffsetNudge = initialState.second != targetState.second
            if (!textChanged || fromOffsetNudge) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                (
                    fadeIn(tween(OVERLAY_LYRIC_IN_MS, easing = OverlaySoftEasing)) +
                        slideInVertically(
                            animationSpec = tween(OVERLAY_LYRIC_IN_MS, easing = OverlaySoftEasing),
                            initialOffsetY = { OVERLAY_LYRIC_SLIDE_PX },
                        )
                    ) togetherWith (
                    fadeOut(tween(OVERLAY_LYRIC_OUT_MS, easing = OverlaySoftEasing)) +
                        slideOutVertically(
                            animationSpec = tween(OVERLAY_LYRIC_OUT_MS, easing = OverlaySoftEasing),
                            targetOffsetY = { -OVERLAY_LYRIC_SLIDE_PX },
                        )
                    )
            }
        },
        label = "lyricLineFade",
    ) { (line, _) ->
        LyricLineText(
            text = line.takeIf { it.isNotEmpty() },
            metrics = metrics,
            active = active,
            textAlign = textAlign,
            snapActiveAlpha = snapActiveAlpha,
            panFraction = panFraction,
        )
    }
}

@Composable
private fun LyricLineText(
    text: String?,
    metrics: DetailOverlayMetrics,
    active: Boolean,
    textAlign: TextAlign = TextAlign.Start,
    /** See [FadeLyricLineSlot.snapActiveAlpha] — three-line stamp-accurate light-up. */
    snapActiveAlpha: Boolean = false,
    /** Active-line overflow pan 0→1; dissolve edges follow portrait branch. */
    panFraction: Float = 0f,
) {
    val isPortrait =
        LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    val visible = !text.isNullOrBlank()
    val targetAlpha = when {
        !visible -> 0f
        active -> 1f
        else -> 0.3f
    }
    // Three-line: snap open when becoming active (stamp → lit). Soft fade only when dimming.
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (snapActiveAlpha && active) {
            snap()
        } else {
            tween(
                durationMillis = OVERLAY_LYRIC_OUT_MS,
                easing = OverlaySoftEasing,
            )
        },
        label = "lyricAlpha",
    )
    var containerWidthPx by remember(text) { mutableIntStateOf(0) }
    var textWidthPx by remember(text) { mutableIntStateOf(0) }
    val rawOverflowPx = (textWidthPx - containerWidthPx).toFloat()
    val overflowing = rawOverflowPx > 0.5f
    val dissolveEndPadPx =
        if (overflowing) with(density) { LyricOverflowDissolveEndPad.toPx() } else 0f
    val maxScrollPx =
        if (overflowing) (rawOverflowPx + dissolveEndPadPx).coerceAtLeast(0f) else 0f
    val longLinePan =
        overflowing && !text.isNullOrBlank() && isLongLyricLine(text)
    val targetScrollPx =
        if (active && overflowing) maxScrollPx * panFraction.coerceIn(0f, 1f) else 0f
    // Active long-line: snap to clock (75–90ms tween restarts were the stutter).
    // Inactive / short overflow: soft chase back to 0 or mild pan.
    val followClockPan = active && longLinePan
    val scrollPx by animateFloatAsState(
        targetValue = targetScrollPx,
        animationSpec = if (followClockPan) {
            snap()
        } else {
            tween(KARAOKE_SWEEP_SOFT_MS, easing = LinearEasing)
        },
        label = "compactLyricPan",
    )
    val effectiveTextAlign = if (overflowing) TextAlign.Start else textAlign
    val boxAlignment = when {
        overflowing -> Alignment.CenterStart
        textAlign == TextAlign.Center || textAlign == TextAlign.Justify -> Alignment.Center
        textAlign == TextAlign.End || textAlign == TextAlign.Right -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.lyricsLineHeightDp)
            .clipToBounds()
            .onSizeChanged { containerWidthPx = it.width }
            .then(
                if (LegacySoftRenderLyrics) {
                    Modifier
                } else {
                    Modifier.graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                },
            )
            .drawWithContent {
                drawContent()
                // Portrait three-line: soft dual edges. Landscape three-line: soft right.
                if (overflowing) {
                    if (LegacySoftRenderLyrics) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = legacyRowEdgeVignetteStops(leftFade = isPortrait),
                            ),
                        )
                    } else {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = lyricOverflowDissolveStops(
                                    leftFade = false,
                                    softRight = !isPortrait,
                                    softBoth = isPortrait,
                                ),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
            },
        contentAlignment = boxAlignment,
    ) {
        Text(
            text = text.orEmpty(),
            color = Color.White.copy(alpha = alpha),
            fontSize = metrics.lyricsCurrentSp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            lineHeight = metrics.lyricsCurrentLineHeight,
            textAlign = effectiveTextAlign,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            onTextLayout = { layout ->
                val w = layout.measuredLineWidthPx()
                if (textWidthPx != w) textWidthPx = w
            },
            modifier = Modifier
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .then(
                    if (LegacySoftRenderLyrics) {
                        // Layout-time placement — graphicsLayer never draws here.
                        Modifier.offset { IntOffset(-scrollPx.roundToInt(), 0) }
                    } else {
                        Modifier.graphicsLayer { translationX = -scrollPx }
                    },
                ),
        )
    }
}

/**
 * Progress bar + times. With no duration (e.g. live streams / HA entities that
 * don't report media_duration), shows an idle bar without misleading numbers.
 * Tap (when [onSeekClick] is set) seeks to the absolute position under the finger.
 */
@Composable
private fun ProgressSection(
    positionMs: Long,
    durationMs: Long,
    metrics: DetailOverlayMetrics,
    onSeekClick: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hasDuration = durationMs > 0L
    val fraction = if (hasDuration) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.progressBarHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.18f))
                .then(
                    if (hasDuration && onSeekClick != null) {
                        Modifier.pointerInput(durationMs) {
                            detectTapGestures { offset ->
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val ratio = (offset.x / width).coerceIn(0f, 1f)
                                val targetMs = kotlin.math.round(durationMs.toDouble() * ratio.toDouble())
                                    .toLong()
                                    .coerceIn(0L, durationMs)
                                onSeekClick(targetMs)
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (hasDuration) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.9f)),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = metrics.progressTimeTopGap),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = when {
                    hasDuration -> formatPlaybackTime(positionMs)
                    positionMs > 0L -> formatPlaybackTime(positionMs)
                    else -> "--:--"
                },
                color = Color.White.copy(alpha = 0.45f),
                fontSize = metrics.timeSp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start,
            )
            Text(
                text = if (hasDuration) formatPlaybackTime(durationMs) else "--:--",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = metrics.timeSp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * Weighted five-slot row: shuffle/repeat pin to the same left/right edges as the
 * progress bar; center cluster stays balanced across DPIs and aspect ratios.
 */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    repeatMode: String,
    shuffleEnabled: Boolean,
    metrics: DetailOverlayMetrics,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onShuffleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            DetailControlButton(
                onClick = onShuffleClick,
                tapPadding = metrics.controlTapPadding,
                edgeAligned = true,
            ) {
                val shuffleAlpha by animateFloatAsState(
                    targetValue = if (shuffleEnabled) 1f else 0.35f,
                    animationSpec = tween(OVERLAY_MOTION_FAST_MS, easing = FastOutSlowInEasing),
                    label = "shuffleTint",
                )
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = "Shuffle",
                    tint = Color.White.copy(alpha = shuffleAlpha),
                    modifier = Modifier.size(metrics.auxIconSize),
                )
            }
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            DetailControlButton(
                onClick = onPreviousClick,
                tapPadding = metrics.controlTapPadding,
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(metrics.skipIconSize),
                )
            }
        }
        Box(
            modifier = Modifier.weight(1.15f),
            contentAlignment = Alignment.Center,
        ) {
            SolidPlayButton(
                isPlaying = isPlaying,
                sizeDp = metrics.playButtonSize,
                iconSizeDp = metrics.playButtonSize * 0.46f,
                onClick = onPlayPauseClick,
            )
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            DetailControlButton(
                onClick = onNextClick,
                tapPadding = metrics.controlTapPadding,
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(metrics.skipIconSize),
                )
            }
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            DetailControlButton(
                onClick = onRepeatClick,
                tapPadding = metrics.controlTapPadding,
                edgeAligned = true,
            ) {
                AnimatedContent(
                    targetState = repeatMode,
                    transitionSpec = {
                        (
                            fadeIn(tween(OVERLAY_MOTION_FAST_MS, easing = FastOutSlowInEasing)) +
                                scaleIn(
                                    initialScale = 0.9f,
                                    animationSpec = tween(OVERLAY_MOTION_FAST_MS, easing = FastOutSlowInEasing),
                                )
                            ) togetherWith
                            fadeOut(tween(120, easing = FastOutSlowInEasing))
                    },
                    label = "repeatModeIcon",
                ) { mode ->
                    val active = mode == "one" || mode == "all"
                    val tintAlpha by animateFloatAsState(
                        targetValue = if (active) 1f else 0.35f,
                        animationSpec = tween(OVERLAY_MOTION_FAST_MS, easing = FastOutSlowInEasing),
                        label = "repeatTint",
                    )
                    when (mode) {
                        "one" -> Icon(
                            imageVector = Icons.Filled.RepeatOne,
                            contentDescription = "Repeat One",
                            tint = Color.White.copy(alpha = tintAlpha),
                            modifier = Modifier.size(metrics.auxIconSize),
                        )
                        "all" -> Icon(
                            imageVector = Icons.Filled.Repeat,
                            contentDescription = "Repeat All",
                            tint = Color.White.copy(alpha = tintAlpha),
                            modifier = Modifier.size(metrics.auxIconSize),
                        )
                        else -> Icon(
                            imageVector = Icons.Filled.Repeat,
                            contentDescription = "Repeat Off",
                            tint = Color.White.copy(alpha = tintAlpha),
                            modifier = Modifier.size(metrics.auxIconSize),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailControlButton(
    onClick: () -> Unit,
    tapPadding: Dp,
    edgeAligned: Boolean = false,
    content: @Composable () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "detailControlScale",
    )
    val horizontalPad = if (edgeAligned) 0.dp else tapPadding * 0.35f
    val verticalPad = tapPadding * 0.65f
    Box(
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    pressed = true
                    onClick()
                },
            )
            .padding(horizontal = horizontalPad, vertical = verticalPad),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(140)
            pressed = false
        }
    }
}

@Composable
private fun SolidPlayButton(
    isPlaying: Boolean,
    sizeDp: Dp,
    iconSizeDp: Dp,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "solidPlayScale",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (pressed) Color.White.copy(alpha = 0.85f) else Color.White,
        animationSpec = tween(OVERLAY_MOTION_FAST_MS, easing = FastOutSlowInEasing),
        label = "solidPlayBg",
    )
    Box(
        modifier = Modifier
            .size(sizeDp)
            .scale(scale)
            .shadow(8.dp, CircleShape)
            .background(backgroundColor, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    pressed = true
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (
                    fadeIn(tween(OVERLAY_MOTION_FAST_MS, easing = FastOutSlowInEasing)) +
                        scaleIn(
                            initialScale = 0.85f,
                            animationSpec = tween(OVERLAY_MOTION_FAST_MS, easing = FastOutSlowInEasing),
                        )
                    ) togetherWith
                    fadeOut(tween(120, easing = FastOutSlowInEasing))
            },
            label = "playPauseIcon",
        ) { playing ->
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = Color(0xFF111111),
                modifier = Modifier.size(iconSizeDp),
            )
        }
    }
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(100)
            pressed = false
        }
    }
}

/**
 * Lyrics use a local 50 ms clock between overlay progress pushes.
 * Progress bar keeps the raw [positionMs]; only lyric index uses this value.
 *
 * Overlay progress arrives via Intent (~every 200 ms) and is already slightly
 * stale on receipt. Blindly resetting the local clock to that value every tick
 * created a sawtooth (jump back → catch up). Stale/behind updates are ignored.
 *
 * Same-track seeks (scrub / skip in song) are large jumps and must align the
 * lyric clock immediately — do not treat them as pause "snap to start" glitches.
 *
 * Pause/resume upstream often reports a near-start progress then corrects.
 * We freeze the last good lyric time across that glitch so the active line
 * does not flash the first verse and jump back.
 *
 * State is keyed by [trackKey]. New tracks start at 0 and keep snap-to-start
 * disarmed briefly so a stale mid-song progress from the previous title cannot
 * lock the lyric clock away from the real intro. Open-gate mid-seed reject is
 * time-boxed; a clear behind gap (follow preset) always reseats so cold-start /
 * skip residuals cannot pin lyrics a beat late forever.
 *
 * HUD offset and follow tightness are overlay state stacked on this clock
 * (not a replacement for the gates). Follow step 2 is the shipped constants.
 *
 * Returns the clock as [State] rather than a `Long` on purpose. The fill-in ticker
 * writes every 50ms, so a plain `Long` return would put that read in the *caller's*
 * recompose scope and rebuild the whole player 20 times a second. Callers must pass
 * the state down untouched and read `.value` in the leaf that paints lyrics.
 */
@Composable
private fun rememberLyricSyncPositionMs(
    positionMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
    trackKey: String = "",
    /** Bumped only by progress-bar / lyric-line finger seek (not play/pause). */
    userSeekToken: Long = 0L,
    /** Absolute seat under the finger (1s grid), null when not holding a scrub. */
    userSeekSeatMs: Long? = null,
): State<Long> {
    val context = LocalContext.current
    LyricDisplayRuntime.ensureLoaded(context)
    val followStep by LyricDisplayRuntime.followStep.collectAsState(
        initial = LyricDisplayRuntime.currentFollowStep(),
    )
    val followPreset = remember(followStep) { LyricDisplayRuntime.presetAt(followStep) }

    // New title: seed from the bar only when it still looks like a track start
    // (MA/Sendspin open-lead is 1–4s). A leftover mid-track second is the
    // previous song — stay at 0 and let the gate drop it.
    // The state object is returned rather than its value: see the KDoc on the return type.
    val openLeadSeedMs = positionMs.coerceAtLeast(0L).let { incoming ->
        if (incoming <= LYRIC_OPEN_LEAD_MAX_MS) incoming else 0L
    }
    val syncedState = remember(trackKey) { mutableLongStateOf(openLeadSeedMs) }
    var syncedMsLong by syncedState
    var anchorMs by remember(trackKey) { mutableLongStateOf(openLeadSeedMs) }
    var anchorClock by remember(trackKey) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // Last accepted upstream playhead — extrapolator must not run far past this.
    var lastUpstreamMs by remember(trackKey) { mutableLongStateOf(openLeadSeedMs) }
    // After pause, keep holding until upstream progress is near the frozen time.
    var holdUntilPlausible by remember(trackKey) { mutableStateOf(false) }
    // snapToStart is for pause glitches on the *same* track, not title handoff.
    var snapArmed by remember(trackKey) { mutableStateOf(false) }
    // Next/prev open-lead gate: reject mid-track metadata seeds until the seam settles.
    var trackOpenGate by remember(trackKey) { mutableStateOf(true) }

    fun acceptUpstream(value: Long, nowElapsed: Long = SystemClock.elapsedRealtime()) {
        holdUntilPlausible = false
        lastUpstreamMs = value
        syncedMsLong = value
        anchorMs = value
        anchorClock = nowElapsed
    }

    // Finger scrub only: hard-seat even while paused; bypass 2.5s/4s scrub thresholds.
    LaunchedEffect(trackKey, userSeekToken, userSeekSeatMs) {
        if (userSeekToken == 0L) return@LaunchedEffect
        val seat = userSeekSeatMs ?: return@LaunchedEffect
        acceptUpstream(seat.coerceAtLeast(0L))
        trackOpenGate = false
    }

    LaunchedEffect(trackKey) {
        val gateMs = followPreset.openGateMs
        snapArmed = false
        trackOpenGate = true
        kotlinx.coroutines.delay(gateMs)
        trackOpenGate = false
        kotlinx.coroutines.delay((LYRIC_SNAP_ARM_DELAY_MS - gateMs).coerceAtLeast(0L))
        snapArmed = true
    }

    LaunchedEffect(trackKey, isPlaying) {
        if (!isPlaying) {
            // Pause→play on auto-next is a seam, not a user pause: the bar
            // often already sits 1–3s in (MA send clock). Freezing the lyric
            // clock at the 0 reset is what left highlight a few seconds late.
            if (trackOpenGate && syncedMsLong <= LYRIC_OPEN_LEAD_MAX_MS) {
                return@LaunchedEffect
            }
            holdUntilPlausible = true
            return@LaunchedEffect
        }
        // Resume / new track: keep holdUntilPlausible true until position looks sane.
        // Fresh trackKey already reset hold to false via remember(trackKey).
        anchorMs = syncedMsLong
        anchorClock = SystemClock.elapsedRealtime()
    }

    LaunchedEffect(trackKey, positionMs, isPlaying, userSeekToken, followStep) {
        // While holding a finger seat, positionMs is already the seat — still accept
        // small moves, but do not let lag chatter undo the scrub.
        if (userSeekSeatMs != null) {
            acceptUpstream(userSeekSeatMs.coerceAtLeast(0L))
            trackOpenGate = false
            return@LaunchedEffect
        }

        val clamped = positionMs.coerceAtLeast(0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        val openLead = clamped <= LYRIC_OPEN_LEAD_MAX_MS
        // Track-change pause→play: still follow the bar through the open-lead
        // band so lyrics are not pinned at 0 while the needle is already moving.
        if (!isPlaying && !(trackOpenGate && openLead)) {
            return@LaunchedEffect
        }

        val localNow = (anchorMs + (nowElapsed - anchorClock)).coerceAtLeast(0L)
        val held = syncedMsLong
        val nearHeld = abs(clamped - held) <= LYRIC_RESUME_CATCHUP_MS
        val deltaFromLocal = clamped - localNow
        val rollbackFromHeld = held - clamped
        val seekForwardMs = followPreset.seekForwardAlignMs
        val seekBackMs = followPreset.seekBackAlignMs
        val behindForceMs = followPreset.behindForceAlignMs
        val comfortableMs = followPreset.comfortableAheadMs
        val catchdownMinMs = followPreset.catchdownMinAheadMs
        val catchdownPerMs = followPreset.catchdownPerSampleMs

        // Directional seek: forward scrub snaps sooner; backward needs a larger
        // jump so 1–2s metadata/audible jitter does not thrash the lyric line.
        // During the open gate a leftover 1:50 must not count as a seek.
        when {
            deltaFromLocal >= seekForwardMs && (!trackOpenGate || openLead) -> {
                acceptUpstream(clamped, nowElapsed)
                trackOpenGate = false
                return@LaunchedEffect
            }
            deltaFromLocal <= -seekBackMs && (!trackOpenGate || openLead) -> {
                acceptUpstream(clamped, nowElapsed)
                trackOpenGate = false
                return@LaunchedEffect
            }
        }

        val behindUpstream = clamped - held

        // Lyrics must follow when clearly late — but not by adopting the
        // previous song's mid-track second during the title handoff.
        if (behindUpstream >= behindForceMs && (!trackOpenGate || openLead)) {
            acceptUpstream(clamped, nowElapsed)
            trackOpenGate = false
            return@LaunchedEffect
        }

        // Open gate (follow preset duration, default ≈1.2s): the accept band is
        // the protocol open-lead (1–4s), not the 1.2s intro tick. Next-track
        // MA/seed already sits there; rejecting it pinned lyrics at 0.
        if (trackOpenGate) {
            if (openLead) {
                acceptUpstream(clamped, nowElapsed)
            } else {
                // Mid-track leftover — hold intro clock; do not clamp lastUpstream
                // to held (that starved the fill-in).
                anchorMs = held
                anchorClock = nowElapsed
            }
            return@LaunchedEffect
        }

        // Only treat a near-zero flash as pause glitch (not a seek into the intro).
        val snapToStart =
            snapArmed &&
                clamped < LYRIC_SNAP_GLITCH_MAX_MS &&
                held > LYRIC_SNAP_HOLD_MIN_MS &&
                (held - clamped) > LYRIC_SNAP_MIN_DELTA_MS

        if (holdUntilPlausible) {
            // Release when upstream is near the frozen time, past the start-glitch
            // window, or clearly ahead (real catch-up — must not stay frozen).
            val released =
                nearHeld ||
                    (clamped > held && clamped >= LYRIC_SNAP_START_MAX_MS) ||
                    behindUpstream >= behindForceMs
            if (released) {
                acceptUpstream(clamped, nowElapsed)
            } else {
                // Still a post-pause glitch (0 / early progress) — stay frozen.
                anchorMs = held
                anchorClock = nowElapsed
            }
            return@LaunchedEffect
        }

        if (snapToStart) {
            anchorMs = held
            anchorClock = nowElapsed
            return@LaunchedEffect
        }

        // New-track send-clock → presentation handoff: the bar drops 1–4s
        // onto the speaker. Catch-down would leave lyrics early for seconds.
        if (rollbackFromHeld in 1L..LYRIC_OPEN_LEAD_MAX_MS &&
            openLead &&
            held <= LYRIC_OPEN_LEAD_MAX_MS
        ) {
            acceptUpstream(clamped, nowElapsed)
            return@LaunchedEffect
        }

        // Rollback handling — never invent index hysteresis; stabilize the clock:
        // - Tiny ahead: keep held, re-pin ticker (do not free-run past upstream).
        // - Small ahead: freeze (no pullback) so fast-song line boundaries are not
        //   crossed by 100–150ms chatter / catch-down.
        // - Clearly early (open-lead): soft catch-down toward upstream.
        // - Large drop: hard reseat (real back-seek).
        when {
            rollbackFromHeld in 1L..comfortableMs -> {
                // Intent jitter: keep held, restart fill-in so the 50ms ticker
                // cannot invent more lead between samples.
                anchorMs = held
                anchorClock = nowElapsed
                return@LaunchedEffect
            }
            rollbackFromHeld in (comfortableMs + 1) until catchdownMinMs -> {
                // Noise band: pin ticker, keep held time (no backward jump).
                anchorMs = held
                anchorClock = nowElapsed
                return@LaunchedEffect
            }
            rollbackFromHeld in catchdownMinMs until seekBackMs -> {
                val next = maxOf(clamped, held - catchdownPerMs)
                syncedMsLong = next
                anchorMs = next
                anchorClock = nowElapsed
                lastUpstreamMs = maxOf(clamped, minOf(lastUpstreamMs, next))
                return@LaunchedEffect
            }
            rollbackFromHeld >= seekBackMs -> {
                acceptUpstream(clamped, nowElapsed)
                return@LaunchedEffect
            }
        }

        // Upstream slightly behind local fill-in (Intent / ticker skew).
        // Cap how far we may sit ahead; never free-run past the speaker clock.
        val behindLocal = localNow - clamped
        if (behindLocal in 1L..LYRIC_STALE_PROGRESS_TOLERANCE_MS) {
            lastUpstreamMs = maxOf(lastUpstreamMs, clamped)
            val maxAllowed = lastUpstreamMs + LYRIC_MAX_AHEAD_OF_UPSTREAM_MS
            if (syncedMsLong > maxAllowed) {
                syncedMsLong = maxAllowed
                anchorMs = maxAllowed
                anchorClock = nowElapsed
            }
            return@LaunchedEffect
        }

        // Forward or equal — accept (monotonic advance).
        if (clamped >= held) {
            acceptUpstream(clamped, nowElapsed)
        }
    }

    LaunchedEffect(trackKey, isPlaying, durationMs) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(50L)
            if (holdUntilPlausible) {
                // Stay pinned to frozen lyric time while waiting for sane progress.
                syncedMsLong = anchorMs
                continue
            }
            var next = anchorMs + (SystemClock.elapsedRealtime() - anchorClock)
            // Sendspin/HA already smooth progress — do not invent a second race.
            next = next.coerceAtMost(lastUpstreamMs + LYRIC_MAX_AHEAD_OF_UPSTREAM_MS)
            if (durationMs > 0L) {
                next = next.coerceAtMost(durationMs)
            }
            syncedMsLong = next.coerceAtLeast(0L)
        }
    }

    return syncedState
}

/** Post-pause: accept upstream only when within this window of the frozen time. */
private const val LYRIC_RESUME_CATCHUP_MS = 3_000L
/** Treat progress in this window as "start of track" for resume release. */
private const val LYRIC_SNAP_START_MAX_MS = 8_000L
/** Near-zero flash only — real seeks into the intro use back-align. */
private const val LYRIC_SNAP_GLITCH_MAX_MS = 800L
private const val LYRIC_SNAP_HOLD_MIN_MS = 8_000L
private const val LYRIC_SNAP_MIN_DELTA_MS = 3_000L
/** After a track change, ignore snap-to-start until metadata settles. */
private const val LYRIC_SNAP_ARM_DELAY_MS = 3_000L
/**
 * Same band as SendspinManager `PROGRESS_OPEN_LEAD_MAX_MS`: a new title at
 * 1–4s is still the track start (MA queue / buffer), not a mid-track seed.
 */
private const val LYRIC_OPEN_LEAD_MAX_MS = 4_000L
/**
 * Upstream may lag the local fill-in by this much (Intent / 200ms ticker skew)
 * without treating it as a seek. Within the window we still **cap** how far
 * lyrics may sit ahead — never ignore and free-run (that caused 抢拍).
 */
private const val LYRIC_STALE_PROGRESS_TOLERANCE_MS = 200L
/**
 * Max lyric clock ahead of the last accepted upstream playhead.
 * Smooths between ~200ms progress emits without racing the speaker.
 */
private const val LYRIC_MAX_AHEAD_OF_UPSTREAM_MS = 80L

/** Match Sendspin/MA absolute seek 1s hard seat (nearest second). */
private fun alignUserSeekSeatMs(positionMs: Long): Long {
    val clamped = positionMs.coerceAtLeast(0L)
    return ((clamped + 500L) / 1_000L) * 1_000L
}

/**
 * How long the finger scrub owns the lyric/progress seat before lag subtract
 * resumes. Mirrors Manager local-seek pin hold (~3s).
 */
private const val USER_SEEK_LYRIC_SEAT_HOLD_MS = 3_000L

/** Release finger seat once live protocol progress is within the 1s seek grid. */
private const val USER_SEEK_LYRIC_SEAT_CATCHUP_MS = 1_000L

/**
 * Three-line window follow. Active index still follows the clock 1:1; this only
 * chooses which three rows are mounted. Forward scroll is normal; a one-line
 * step back only nudges the window by one (active at top) instead of snapping
 * to a fresh mid-centered ideal (that looked like an unexpected jump).
 */
private fun stableLyricWindowStart(
    currentIndex: Int,
    lastIdx: Int,
    previousStart: Int,
): Int {
    if (lastIdx < 0) return 0
    val maxStart = (lastIdx - 2).coerceAtLeast(0)
    if (currentIndex < 0) return 0
    val prev = previousStart.coerceIn(0, maxStart)
    val visibleEnd = (prev + 2).coerceAtMost(lastIdx)
    if (currentIndex in prev..visibleEnd) {
        // Landed on the bottom row → nudge forward once so the next line has room.
        if (currentIndex == visibleEnd && prev < maxStart) {
            return (currentIndex - 1).coerceIn(0, maxStart)
        }
        return prev
    }
    if (currentIndex > visibleEnd) {
        return when {
            currentIndex >= lastIdx -> maxStart
            else -> (currentIndex - 1).coerceIn(0, maxStart)
        }
    }
    // currentIndex < prev: stepped before the window.
    // Multi-line rewind (seek) → reseat; single-step → shift by one only.
    return if (currentIndex < prev - 1) {
        when {
            currentIndex <= 1 -> 0
            currentIndex >= lastIdx -> maxStart
            else -> (currentIndex - 1).coerceIn(0, maxStart)
        }
    } else {
        currentIndex.coerceIn(0, maxStart)
    }
}

private fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * vmin-based overlay metrics (same philosophy as [OverlayLogoBadge]).
 * Caps runaway scaling on wall tablets; landscape gets a slight text boost so
 * the right column stays visually balanced with the cover column.
 *
 * Below 680 dp: do not let vmin/360 inflate a square pane (480×480) into a
 * phone-sized cover + lyric band that overflows the leftover height.
 */
private data class DetailOverlayMetrics(
    val contentPaddingH: Dp,
    val contentPaddingTop: Dp,
    val contentPaddingBottom: Dp,
    val sectionGap: Dp,
    val portraitCoverBottomGap: Dp,
    val metaProgressGap: Dp,
    val progressTransportGap: Dp,
    val titleSp: TextUnit,
    val titleLineHeight: TextUnit,
    val titleLetterSpacing: TextUnit,
    val artistSp: TextUnit,
    val artistLineHeight: TextUnit,
    /** No-lyrics middle state — slightly larger meta type. */
    val titleSpSolo: TextUnit,
    val titleLineHeightSolo: TextUnit,
    val artistSpSolo: TextUnit,
    val artistLineHeightSolo: TextUnit,
    val titleArtistGap: Dp,
    /**
     * Square panes (480×480): the portrait stack has no room for the three-line
     * window above the foot — it pushed the cover out of its seat and left the band
     * glued to the artwork. Collapse the compact band to the active line there.
     * Landscape keeps three: its right column is sized by the full pane height.
     */
    val compactLyricBandSingleLine: Boolean,
    val lyricsBlockHeight: Dp,
    val lyricsLineHeightDp: Dp,
    val lyricsLineGap: Dp,
    val lyricsCurrentSp: TextUnit,
    val lyricsCurrentLineHeight: TextUnit,
    val lyricsAdjacentSp: TextUnit,
    val lyricsAdjacentLineHeight: TextUnit,
    val lyricsFocusCoverSize: Dp,
    val lyricsFocusCoverLandscapeSize: Dp,
    val lyricsFocusBridgeHeight: Dp,
    /** Landscape focus: vertical gap cover → title (artist gap unchanged). */
    val lyricsFocusCoverTitleGap: Dp,
    /** Portrait focus: horizontal gap cover → meta column. */
    val lyricsFocusCoverBesideGap: Dp,
    val lyricsFocusTitleSp: TextUnit,
    val lyricsFocusTitleLineHeight: TextUnit,
    val lyricsFocusArtistSp: TextUnit,
    val lyricsFocusArtistLineHeight: TextUnit,
    val lyricsFocusLetterSpacing: TextUnit,
    val lyricsExpandedLineHeightDp: Dp,
    val lyricsExpandedLineGap: Dp,
    val lyricsExpandedEdgePad: Dp,
    val lyricsExpandedCurrentSp: TextUnit,
    val lyricsExpandedCurrentLineHeight: TextUnit,
    val lyricsExpandedAdjacentSp: TextUnit,
    val lyricsExpandedAdjacentLineHeight: TextUnit,
    val metaLyricsGap: Dp,
    val lyricsProgressGap: Dp,
    val timeSp: TextUnit,
    val progressBarHeight: Dp,
    val progressTimeTopGap: Dp,
    val playButtonSize: Dp,
    val skipIconSize: Dp,
    val auxIconSize: Dp,
    val controlTapPadding: Dp,
    val coverFillFraction: Float,
    /** No-lyrics middle state — slightly larger cover. */
    val coverFillFractionSolo: Float,
    val coverCornerRadius: Dp,
    val coverShadowElevation: Dp,
    val landscapeCoverWeight: Float,
    val landscapeContentWeight: Float,
    val landscapeGutter: Dp,
    val landscapeCoverFillFraction: Float,
    val landscapeCoverFillFractionSolo: Float,
    val landscapeVisualShiftLeft: Dp,
)

/**
 * Landscape Mass side-push left pane metrics.
 * Dedicated branch (see design-previews/square-pane-np-branch.html):
 * portrait-unexpanded stack, fixed [MassPushInset], larger foot controls.
 */
private val MassPushInset = 24.dp
/** Lyrics-focus (karaoke) chrome in the side-push left pane: nudge cover + meta right. */
private val MassPushKaraokeChromeNudge = 15.dp

private fun DetailOverlayMetrics.scaledForMassPushPane(
    paneW: Dp,
    paneH: Dp,
): DetailOverlayMetrics {
    val ref = 360f
    // [paneW]/[paneH] are the INNER content box (outside equal MassPushInset).
    val s = minOf(paneW.value / ref, paneH.value / (ref * 1.2f), 1f)
        .coerceAtLeast(0.55f)
    // Title · artist: slightly aggressive so left pane type matches cover scale.
    val metaS = (paneW.value / 260f).coerceIn(0.95f, 1.55f)
    fun d(v: Float, lo: Float, hi: Float): Dp = (v * s).coerceIn(lo, hi).dp
    fun t(v: Float, lo: Float, hi: Float): TextUnit = (v * s).coerceIn(lo, hi).sp
    fun metaT(v: Float, lo: Float, hi: Float): TextUnit =
        (v * metaS).coerceIn(lo, hi).sp
    return copy(
        // Equal wrap on all sides — layout also applies [MassPushInset] literally.
        contentPaddingH = MassPushInset,
        contentPaddingTop = MassPushInset,
        contentPaddingBottom = MassPushInset,
        sectionGap = d(10f, 8f, 14f),
        portraitCoverBottomGap = d(6f, 5f, 8f),
        metaProgressGap = d(10f, 8f, 12f),
        progressTransportGap = d(10f, 8f, 12f),
        // Title / artist — with-lyrics merge line.
        titleSp = metaT(24f, 21f, 29f),
        titleLineHeight = metaT(30f, 26f, 36f),
        titleLetterSpacing = (-0.26f * metaS).sp,
        artistSp = metaT(19f, 17f, 23.5f),
        artistLineHeight = metaT(24f, 20f, 28f),
        // No-lyrics stacked meta — larger presence (matches big cover).
        titleSpSolo = metaT(27.5f, 24f, 33f),
        titleLineHeightSolo = metaT(34f, 29f, 40f),
        artistSpSolo = metaT(21.5f, 19f, 26f),
        artistLineHeightSolo = metaT(27f, 23f, 32f),
        titleArtistGap = d(5f, 4f, 7f),
        lyricsBlockHeight = d(56f, 48f, 64f),
        // Slot height for FadeLyricLineSlot / dissolve pan (must clear current line).
        lyricsLineHeightDp = d(30f, 26f, 34f),
        lyricsLineGap = d(2f, 2f, 4f),
        // Single emphasized lyric — step up again (still below title).
        lyricsCurrentSp = t(19f, 17f, 22f),
        lyricsCurrentLineHeight = t(25f, 22f, 28f),
        lyricsAdjacentSp = t(12f, 11f, 14f),
        lyricsAdjacentLineHeight = t(15f, 13f, 17f),
        lyricsFocusCoverSize = d(72f, 64f, 88f),
        lyricsFocusCoverTitleGap = d(6f, 4f, 8f),
        lyricsFocusCoverBesideGap = d(12f, 10f, 14f),
        lyricsFocusTitleSp = metaT(19f, 17f, 24f),
        lyricsFocusTitleLineHeight = metaT(24f, 20f, 28f),
        lyricsFocusArtistSp = metaT(15f, 13f, 18f),
        lyricsFocusArtistLineHeight = metaT(19f, 16f, 22f),
        metaLyricsGap = 0.dp,
        lyricsProgressGap = d(6f, 4f, 8f),
        // Foot: slight equal trim — still tappable, not tiny.
        timeSp = t(11.5f, 11f, 12.5f),
        progressBarHeight = d(6f, 5.5f, 7f),
        progressTimeTopGap = d(5f, 4f, 7f),
        playButtonSize = d(50f, 48f, 58f),
        skipIconSize = d(30f, 28f, 36f),
        auxIconSize = d(22f, 20f, 26f),
        controlTapPadding = d(6f, 5f, 8f),
        coverFillFraction = 0.82f,
        coverFillFractionSolo = 0.82f,
        coverCornerRadius = d(16f, 14f, 18f),
        coverShadowElevation = d(16f, 12f, 20f),
    )
}

/**
 * Mass-push cover from the hero box (above the foot).
 *
 * Two seats (do not conflate):
 * - **Playing stack** ([PortraitDetailContent] massPushCompact): [maxHeight] is
 *   already the hero strip after the transport foot — cover grows with that
 *   height (clearance + width + hero fraction), then a soft large-pane ceiling
 *   so tablets do not balloon into a top-heavy slab. Extra air stays ABOVE the
 *   cover ([Spacer] weight) — seat can sit high; the square must not dominate.
 * - **Waiting shell** ([WaitingForMediaCenteredShell]): sizes from the full pane
 *   and applies its own short-side trim — do not reuse this for waiting.
 *
 * Phones: ~72–78% hero / ~88–92%W. Large panes: lower fractions + soft dp cap.
 * Title-only (no artist): do not reserve an empty artist row.
 */
private fun massPushCoverSide(
    maxWidth: Dp,
    maxHeight: Dp,
    metrics: DetailOverlayMetrics,
    reserveLyricLine: Boolean,
    stackMeta: Boolean = false,
    hasArtist: Boolean = true,
): Dp {
    val titleLine = if (stackMeta) {
        maxOf(metrics.titleLineHeightSolo.value, metrics.titleSpSolo.value)
    } else {
        maxOf(metrics.titleLineHeight.value, metrics.titleSp.value)
    }
    val artistLine = if (stackMeta) {
        maxOf(metrics.artistLineHeightSolo.value, metrics.artistSpSolo.value)
    } else {
        maxOf(metrics.artistLineHeight.value, metrics.artistSp.value)
    }
    // Match TrackMeta: blank artist draws no second row / no " · " — don't reserve it.
    val metaBlock = when {
        stackMeta && hasArtist ->
            titleLine * 1.15f + metrics.titleArtistGap.value + artistLine * 1.15f
        stackMeta && !hasArtist ->
            titleLine * 1.2f
        hasArtist ->
            titleLine * 1.25f
        else ->
            titleLine * 1.15f
    }
    val lyricsBlock = if (reserveLyricLine) {
        6f + metrics.lyricsLineHeightDp.value + 2f
    } else {
        0f
    }
    val reservedBelowCover =
        metrics.portraitCoverBottomGap.value + metaBlock + lyricsBlock
    // Title-only: slightly less top breath so clearance can feed the cover.
    val breathFloor = maxOf(
        maxHeight.value * if (stackMeta && !hasArtist) 0.028f else 0.035f,
        4f,
    )
    val shortSide = minOf(maxWidth.value, maxHeight.value)
    // Large left panes: shrink the square, not the top seat (air above is fine).
    val heroFrac = when {
        shortSide >= 480f -> if (stackMeta) 0.58f else 0.52f
        shortSide >= 400f -> if (stackMeta) 0.66f else 0.60f
        else -> if (stackMeta) 0.78f else 0.72f
    }
    val widthFrac = when {
        shortSide >= 480f -> if (stackMeta) 0.78f else 0.74f
        shortSide >= 400f -> if (stackMeta) 0.84f else 0.80f
        else -> if (stackMeta) 0.92f else 0.88f
    }
    // Soft absolute ceiling — phones unconstrained; tablets avoid head-heavy art.
    val softCeiling = when {
        shortSide >= 680f -> 236f
        shortSide >= 520f -> 220f
        shortSide >= 440f -> 236f
        shortSide >= 380f -> 252f
        else -> Float.MAX_VALUE
    }
    // Primary: fill remaining hero height after meta/lyric/breath — tracks screen.
    val maxByClearance =
        (maxHeight.value - reservedBelowCover - breathFloor).coerceAtLeast(72f)
    val maxByHero = maxHeight.value * heroFrac
    val maxByWidth = maxWidth.value * widthFrac
    val chosen = minOf(
        maxByWidth,
        maxByClearance,
        maxByHero,
        softCeiling,
    )
    // Never force a floor above clearance — 480×480 overflowed when 120/136
    // was larger than the leftover hero.
    val floor = when {
        shortSide < 680f -> if (stackMeta) 96f else 88f
        else -> if (stackMeta) 136f else 120f
    }
    return chosen.coerceAtLeast(minOf(floor, maxByClearance)).dp
}

/**
 * Cover square for the two **default** (unexpanded) seats: the portrait hero box above
 * the meta stack, and the landscape cover column.
 *
 * Both used to be `fillMaxSize(fraction).aspectRatio(1f)`, which sizes off **width
 * only**: `fillMaxSize` hands `aspectRatio` two fixed, non-square constraints, none of
 * its enforcing passes can satisfy them, and the fallback pass matches `maxWidth` while
 * ignoring the height. On a square pane (480×480 → sw426dp) the seat is far shorter than
 * `fraction × width`, so the square spilled out of it — clipped at the top edge and
 * painted under the title / lyric band.
 *
 * [maxHeight] here is already the seat *after* the foot took its share, so the height
 * term also absorbs the lyric band appearing or disappearing. Phones and tablets stay on
 * the width term, unchanged.
 */
private fun defaultCoverSide(
    maxWidth: Dp,
    maxHeight: Dp,
    fillFraction: Float,
): Dp {
    // Never butt against the seat edge — keep a little air toward the meta row.
    val breath = (maxHeight * 0.04f).coerceIn(4.dp, 12.dp)
    val byHeight = (maxHeight - breath).coerceAtLeast(0.dp)
    return minOf(maxWidth * fillFraction, byHeight)
}

@Composable
private fun rememberDetailOverlayMetrics(
    isLandscape: Boolean,
    paneWidthDp: Float? = null,
    paneHeightDp: Float? = null,
    splitHero: Boolean = false,
): DetailOverlayMetrics {
    val configuration = LocalConfiguration.current
    val screenW = paneWidthDp ?: configuration.screenWidthDp.toFloat()
    val screenH = paneHeightDp ?: configuration.screenHeightDp.toFloat()
    val vmin = min(screenW, screenH)
    val vmax = max(screenW, screenH)
    val aspect = if (screenH > 0f) vmax / vmin else 1f

    return remember(screenW, screenH, isLandscape, splitHero) {
        // vmin/360 treats 480×480 like a large phone — it is a square, so chrome
        // and the cover square ate the leftover height. Below 680: cap scale;
        // near-square: stay at ~1.0. Tall phones (360×800) keep the old 1.0.
        // A left/right pane is also near-square, but that 0.56 air branch is for
        // a full square device. The split hero must not take it.
        val compact = vmin < 680f
        val squareish = aspect < 1.28f && !splitHero
        val rawScale = (vmin / 360f).coerceIn(0.9f, 1.55f)
        val scale = when {
            compact && squareish -> (vmin / 480f).coerceIn(0.88f, 1.05f)
            compact -> rawScale.coerceAtMost(1.12f)
            else -> rawScale
        }
        val landscapeTextBoost = when {
            isLandscape && aspect >= 1.55f -> 1.08f
            isLandscape && !(compact && squareish) -> 1.04f
            else -> 1f
        }
        val textScale = scale * landscapeTextBoost

        // Square panes (480×480) have no bezel to hide behind. Frame them with one flat
        // inset on all four sides. Flat, not scaled: fixed 36dp margin; the square
        // ladder already sits at scale ≈ 1. Everything below is either weighted (cover)
        // or measured off the padded seat, so the stack absorbs the loss.
        val squarePaneInset = 36f
        val horizontalPad = when {
            splitHero -> 18f
            isLandscape -> 40f * scale
            compact && squareish -> squarePaneInset
            else -> 28f * scale
        }
        val topPad = when {
            splitHero -> 14f
            isLandscape -> 28f * scale
            compact && squareish -> squarePaneInset
            else -> 44f * scale
        }
        val bottomPad = when {
            splitHero -> 12f
            isLandscape -> 28f * scale
            compact && squareish -> squarePaneInset
            else -> 34f * scale
        }
        val lyricLineH = (24f * scale).coerceIn(if (compact && squareish) 18f else 20f, 30f)
        val lyricGap = (if (compact && squareish) 8f else 12f) * scale
        val lyricBlockH = lyricLineH * 3f + lyricGap * 2f + 6f * scale
        // Focus chrome + lyric wall share the same size ladder (+0…5dp by screen).
        val lyricBoostDp = ((scale - 1f) / 0.55f * 5f).coerceIn(0f, 5f)
        val unifiedLyricSp = (20f * textScale + lyricBoostDp).coerceIn(
            if (compact && squareish) 16f else 18f,
            if (compact && squareish) 22f else 33f,
        )
        val unifiedLyricLineH = (unifiedLyricSp * 1.3f).coerceIn(
            if (compact && squareish) 20f else 22f,
            if (compact && squareish) 28f else 40f,
        )
        // Expanded left chrome: keep cover↔title ratios (≈4.4 portrait / ≈8.6 landscape)
        // so enlarging type does not leave a top-heavy cover.
        val focusTitleSpValue = (20f * textScale + lyricBoostDp * 0.75f).coerceIn(
            if (compact && squareish) 15f else 16f,
            if (compact && squareish) 20f else 28f,
        )
        val focusTitleLhValue = (25f * textScale + lyricBoostDp * 0.75f).coerceIn(
            if (compact && squareish) 18f else 20f,
            if (compact && squareish) 26f else 34f,
        )
        val focusArtistSpValue = (15.5f * textScale + lyricBoostDp * 0.6f).coerceIn(
            if (compact && squareish) 12f else 13f,
            if (compact && squareish) 16f else 22f,
        )
        val focusArtistLhValue = (19.5f * textScale + lyricBoostDp * 0.6f).coerceIn(
            if (compact && squareish) 15f else 16f,
            if (compact && squareish) 20f else 26f,
        )

        DetailOverlayMetrics(
            contentPaddingH = horizontalPad.dp,
            contentPaddingTop = topPad.dp,
            contentPaddingBottom = bottomPad.dp,
            sectionGap = ((if (compact && squareish) 14f else 24f) * scale).dp,
            portraitCoverBottomGap = ((if (isLandscape) 24f else if (compact && squareish) 8f else 12f) * scale).dp,
            metaProgressGap = ((if (compact && squareish) 10f else 18f) * scale).dp,
            progressTransportGap = ((if (compact && squareish) 6f else 10f) * scale).dp,
            // With 3-line lyrics: keep room for the lyric band.
            titleSp = (29f * textScale).coerceIn(if (compact && squareish) 20f else 24f, 40f).sp,
            titleLineHeight = (35f * textScale).coerceIn(if (compact && squareish) 24f else 30f, 46f).sp,
            titleLetterSpacing = (-0.35f * textScale).sp,
            artistSp = (19.5f * textScale).coerceIn(if (compact && squareish) 14f else 16f, 28f).sp,
            artistLineHeight = (24f * textScale).coerceIn(if (compact && squareish) 17f else 20f, 32f).sp,
            // Middle wait state: only a light step above normal meta.
            titleSpSolo = (31.5f * textScale).coerceIn(if (compact && squareish) 22f else 26f, 42f).sp,
            titleLineHeightSolo = (37.5f * textScale).coerceIn(if (compact && squareish) 26f else 32f, 48f).sp,
            artistSpSolo = (21f * textScale).coerceIn(if (compact && squareish) 15f else 17f, 28f).sp,
            artistLineHeightSolo = (25.5f * textScale).coerceIn(if (compact && squareish) 18f else 21f, 34f).sp,
            titleArtistGap = (4f * scale).coerceIn(2f, 6f).dp,
            compactLyricBandSingleLine = splitHero || (compact && squareish),
            lyricsBlockHeight = lyricBlockH.coerceIn(
                if (compact && squareish) 64f else 88f,
                if (compact && squareish) 84f else 124f,
            ).dp,
            lyricsLineHeightDp = lyricLineH.dp,
            lyricsLineGap = lyricGap.coerceIn(if (compact && squareish) 6f else 8f, 16f).dp,
            lyricsCurrentSp = (15f * textScale).coerceIn(if (compact && squareish) 12f else 13f, 19f).sp,
            lyricsCurrentLineHeight = (19f * textScale).coerceIn(if (compact && squareish) 15f else 16f, 22f).sp,
            lyricsAdjacentSp = (12.5f * textScale).coerceIn(if (compact && squareish) 10f else 11f, 16f).sp,
            lyricsAdjacentLineHeight = (16f * textScale).coerceIn(if (compact && squareish) 13f else 14f, 19f).sp,
            lyricsFocusCoverSize = (focusTitleSpValue * 4.4f).coerceIn(
                if (compact && squareish) 64f else 76f,
                if (compact && squareish) 88f else 120f,
            ).dp,
            lyricsFocusCoverLandscapeSize = (focusTitleSpValue * 8.6f).coerceIn(
                if (compact && squareish) 110f else 148f,
                if (compact && squareish) 168f else 240f,
            ).dp,
            lyricsFocusBridgeHeight = 0.dp,
            // Restrained, scale-led: phone ~10/16dp → tablet gently larger.
            lyricsFocusCoverTitleGap = (10f * scale).coerceIn(if (compact && squareish) 6f else 8f, 16f).dp,
            lyricsFocusCoverBesideGap = (16f * scale).coerceIn(if (compact && squareish) 10f else 14f, 24f).dp,
            lyricsFocusTitleSp = focusTitleSpValue.sp,
            lyricsFocusTitleLineHeight = focusTitleLhValue.sp,
            lyricsFocusArtistSp = focusArtistSpValue.sp,
            lyricsFocusArtistLineHeight = focusArtistLhValue.sp,
            lyricsFocusLetterSpacing = (-0.22f * textScale).sp,
            lyricsExpandedLineHeightDp = (34f * scale + lyricBoostDp).coerceIn(
                if (compact && squareish) 26f else 30f,
                if (compact && squareish) 36f else 48f,
            ).dp,
            lyricsExpandedLineGap = (12f * scale).coerceIn(if (compact && squareish) 7f else 9f, 16f).dp,
            // Kept for metrics compatibility; wall uses dynamic center pads instead.
            lyricsExpandedEdgePad = (56f * scale).coerceIn(
                if (compact && squareish) 28f else 40f,
                if (compact && squareish) 52f else 88f,
            ).dp,
            lyricsExpandedCurrentSp = unifiedLyricSp.sp,
            lyricsExpandedCurrentLineHeight = unifiedLyricLineH.sp,
            lyricsExpandedAdjacentSp = unifiedLyricSp.sp,
            lyricsExpandedAdjacentLineHeight = unifiedLyricLineH.sp,
            metaLyricsGap = ((if (compact && squareish) 6f else 10f) * scale).dp,
            lyricsProgressGap = ((if (compact && squareish) 10f else 18f) * scale).dp,
            timeSp = (13.5f * scale).coerceIn(if (compact && squareish) 11f else 12f, 17f).sp,
            progressBarHeight = (5.5f * scale).coerceIn(4f, 7f).dp,
            progressTimeTopGap = ((if (compact && squareish) 5f else 7f) * scale).dp,
            playButtonSize = ((if (splitHero) 52f else if (isLandscape) 62f else 68f) * scale).coerceIn(
                if (splitHero) 44f else if (compact && squareish) 48f else 54f,
                if (splitHero) 58f else if (compact && squareish) 64f else 96f,
            ).dp,
            skipIconSize = (36f * scale).coerceIn(if (compact && squareish) 26f else 30f, 52f).dp,
            auxIconSize = (24f * scale).coerceIn(if (compact && squareish) 18f else 20f, 34f).dp,
            controlTapPadding = (8.5f * scale).coerceIn(if (compact && squareish) 5f else 6f, 12f).dp,
            coverFillFraction = when {
                // Left/right pane: the cover box is already the hero share.
                // The 0.56 branch is only for a full square device, where it
                // leaves air above a cover that would otherwise fill the screen.
                splitHero -> 0.96f
                // Square panes: the width fraction is not the binding term (see
                // [defaultCoverSide]) — this only sets how much air sits above the
                // cover once it fits, so keep it well under the phone fractions.
                compact && squareish -> 0.56f
                compact -> if (vmin >= 400f) 0.74f else 0.72f
                else -> if (vmin >= 400f) 0.80f else 0.78f
            },
            // Middle solo: slight bump only — never near full-bleed (weight box
            // grows when the lyric band is absent; size is capped in layout).
            coverFillFractionSolo = when {
                compact && squareish -> 0.68f
                compact -> if (vmin >= 400f) 0.80f else 0.78f
                else -> if (vmin >= 400f) 0.86f else 0.84f
            },
            coverCornerRadius = (18f * scale).coerceIn(14f, 24f).dp,
            coverShadowElevation = (22f * scale).coerceIn(if (compact && squareish) 12f else 16f, 30f).dp,
            // Landscape: near-equal columns; cover column slightly wider, but the
            // square itself is smaller so the right meta/lyrics column does not
            // look starved (right content already needs width for text/controls).
            landscapeCoverWeight = 1.05f,
            landscapeContentWeight = 1.0f,
            landscapeGutter = (28f * scale).coerceIn(if (compact && squareish) 14f else 20f, 40f).dp,
            landscapeCoverFillFraction = when {
                compact && squareish -> 0.62f
                compact -> if (vmin >= 400f) 0.70f else 0.68f
                else -> if (vmin >= 400f) 0.76f else 0.72f
            },
            landscapeCoverFillFractionSolo = when {
                compact && squareish -> 0.66f
                compact -> if (vmin >= 400f) 0.74f else 0.72f
                else -> if (vmin >= 400f) 0.80f else 0.76f
            },
            landscapeVisualShiftLeft = if (isLandscape && !(compact && squareish)) 20.dp else 0.dp,
        )
    }
}

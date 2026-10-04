package com.shuffleframe.app

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------- image requests ----------

/** Decode at screen size, not camera size: a 50MP photo would otherwise eat ~200MB of memory. */
private fun slideRequest(context: Context, uri: Uri, width: Int, height: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .size(width, height)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .build()

/** A tiny copy for the blurred backdrop. Blurring hides the low resolution. */
private fun backdropRequest(context: Context, uri: Uri): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .size(240, 240)
        .scale(Scale.FILL)
        .precision(Precision.INEXACT)
        .memoryCacheKey("backdrop:$uri")
        .build()

// ---------- Ken Burns ----------

private data class KenBurns(
    val startScale: Float, val endScale: Float,
    val startX: Float, val startY: Float,
    val endX: Float, val endY: Float,
) {
    companion object {
        fun random(speed: KenBurnsSpeed): KenBurns {
            // (minimum zoom, extra random zoom, drift range) per speed
            val (minZoom, extraZoom, driftRange) = when (speed) {
                KenBurnsSpeed.Subtle -> Triple(1.04f, 0.03f, 0.02f)
                KenBurnsSpeed.Normal -> Triple(1.10f, 0.08f, 0.06f)
                KenBurnsSpeed.Dramatic -> Triple(1.22f, 0.13f, 0.10f)
            }
            val big = minZoom + Random.nextFloat() * extraZoom
            val zoomIn = Random.nextBoolean()
            fun drift() = (Random.nextFloat() - 0.5f) * driftRange
            return KenBurns(
                startScale = if (zoomIn) 1.0f else big,
                endScale = if (zoomIn) big else 1.0f,
                startX = drift(), startY = drift(),
                endX = drift(), endY = drift(),
            )
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

// ---------- transitions ----------

private fun transitionFor(style: TransitionStyle, forward: Boolean): ContentTransform = when (style) {
    TransitionStyle.Crossfade ->
        fadeIn(tween(1200)) togetherWith fadeOut(tween(1200))

    TransitionStyle.Slide -> {
        val dir = if (forward) 1 else -1
        val spec = tween<androidx.compose.ui.unit.IntOffset>(750, easing = FastOutSlowInEasing)
        (slideInHorizontally(spec) { it * dir } + fadeIn(tween(750))) togetherWith
            (slideOutHorizontally(spec) { -it * dir } + fadeOut(tween(750)))
    }

    TransitionStyle.Zoom ->
        (fadeIn(tween(1000)) + scaleIn(tween(1000), initialScale = 1.08f)) togetherWith
            (fadeOut(tween(1000)) + scaleOut(tween(1000), targetScale = 0.96f))

    // Old photo fades fully to black, then the new one fades up.
    TransitionStyle.FadeToBlack ->
        fadeIn(tween(900, delayMillis = 900)) togetherWith fadeOut(tween(900))

    // The new photo waits underneath while the old one swings away like a page.
    // The swing itself is drawn in SlideView; this just keeps the old photo on top.
    TransitionStyle.PageTurn ->
        (EnterTransition.None togetherWith fadeOut(tween(200, delayMillis = PAGE_TURN_MS - 150)))
            .apply { targetContentZIndex = -1f }
}

private const val PAGE_TURN_MS = 1100

/** Pinch-zoom state for the photo on screen. Only used while paused. */
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    val zoomed get() = scale > 1.01f
    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

private fun clampOffset(o: Offset, scale: Float, size: IntSize): Offset {
    val maxX = (scale - 1f) * size.width / 2f
    val maxY = (scale - 1f) * size.height / 2f
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

// ---------- screen ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun SlideshowScreen(
    vm: SlideshowViewModel,
    folderName: String,
    count: Int,
    onChangeSource: () -> Unit,
) {
    val slide = vm.current ?: return
    val settings = vm.settings
    val context = LocalContext.current
    val view = LocalView.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val targetW = with(density) { config.screenWidthDp.dp.roundToPx() }
    val targetH = with(density) { config.screenHeightDp.dp.roundToPx() }

    var controlsVisible by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val poke = { interactionTick++ }

    val zoom = remember { ZoomState() }
    val direction = rememberUpdatedState(slide.forward)
    LaunchedEffect(slide.key) { zoom.reset() }
    LaunchedEffect(vm.playing) { if (vm.playing) zoom.reset() }

    // Keep the screen awake only while playing.
    DisposableEffect(vm.playing) {
        view.keepScreenOn = vm.playing
        onDispose { view.keepScreenOn = false }
    }

    // Auto-advance. Restarts whenever the photo, play state or interval changes.
    LaunchedEffect(slide.key, vm.playing, settings.intervalSeconds) {
        if (vm.playing) {
            delay(settings.intervalSeconds * 1000L)
            vm.next()
        }
    }

    // Load the next photo in the background so it's ready when its turn comes.
    LaunchedEffect(slide.key, targetW, targetH) {
        vm.upcomingUri()?.let { uri ->
            context.imageLoader.enqueue(slideRequest(context, uri, targetW, targetH))
            context.imageLoader.enqueue(backdropRequest(context, uri))
        }
    }

    // Controls fade away after a few seconds of no interaction.
    LaunchedEffect(controlsVisible, interactionTick, showSettings) {
        if (controlsVisible && !showSettings) {
            delay(3500)
            controlsVisible = false
        }
    }

    LaunchedEffect(vm.showHint) {
        if (vm.showHint) {
            delay(4500)
            vm.dismissHint()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .clipToBounds()
            // Pinch to zoom (and drag to pan once zoomed), only while paused.
            // Runs in the Initial pass so it gets first refusal before swipe-to-skip.
            .pointerInput(vm.playing) {
                if (vm.playing) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || zoom.zoomed) {
                            val newScale = (zoom.scale * event.calculateZoom()).coerceIn(1f, 5f)
                            zoom.offset = clampOffset(zoom.offset + event.calculatePan(), newScale, size)
                            zoom.scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(vm.playing) {
                val paused = !vm.playing
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        interactionTick++
                        vm.dismissHint()
                    },
                    // Double-tap while paused: zoom in on that spot, or back out.
                    onDoubleTap = if (paused) { pos ->
                        if (zoom.zoomed) {
                            zoom.reset()
                        } else {
                            val target = 2.5f
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            zoom.offset = clampOffset((pos - centre) * (1f - target), target, size)
                            zoom.scale = target
                        }
                    } else null,
                )
            }
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        val threshold = 72.dp.toPx()
                        if (total < -threshold) vm.next() else if (total > threshold) vm.previous()
                        interactionTick++
                        vm.dismissHint()
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        total += amount
                    },
                )
            },
    ) {
        AnimatedContent(
            targetState = slide,
            contentKey = { it.key },
            transitionSpec = { transitionFor(settings.transition, targetState.forward) },
            label = "slide",
        ) { s ->
            val pageTurn = settings.transition == TransitionStyle.PageTurn
            val turn by transition.animateFloat(
                transitionSpec = { if (pageTurn) tween(PAGE_TURN_MS, easing = FastOutSlowInEasing) else snap() },
                label = "turn",
            ) { state -> if (state == EnterExitState.PostExit) 1f else 0f }
            SlideView(
                slide = s,
                settings = settings,
                width = targetW,
                height = targetH,
                playing = vm.playing,
                zoom = zoom.takeIf { s.key == slide.key },
                turnProgress = { turn },
                turnForward = { direction.value },
            )
        }

        // Small paused marker when the controls are hidden.
        AnimatedVisibility(
            visible = !vm.playing && !controlsVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(20.dp),
        ) {
            Icon(AppIcons.Pause, contentDescription = "Paused", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }

        // First-run hint.
        AnimatedVisibility(
            visible = vm.showHint && !controlsVisible,
            enter = fadeIn(tween(600)), exit = fadeOut(tween(600)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
        ) {
            Text(
                "Tap for controls  ·  Swipe to skip",
                color = Color.White,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        // Controls overlay.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(250)), exit = fadeOut(tween(450)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
                )
                Box(
                    Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                )

                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(start = 24.dp, end = 12.dp, top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(folderName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Light, maxLines = 1)
                        Text(
                            "$count photos · shuffled",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, letterSpacing = 0.5.sp,
                        )
                    }
                    RoundButton(AppIcons.Photos, "Change album or folder", 48.dp, 22.dp) { poke(); onChangeSource() }
                    RoundButton(AppIcons.Tune, "Settings", 48.dp, 22.dp) { poke(); showSettings = true }
                }

                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(bottom = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(AppIcons.Previous, "Previous", 56.dp, 28.dp) { poke(); vm.previous() }
                    RoundButton(
                        if (vm.playing) AppIcons.Pause else AppIcons.Play,
                        if (vm.playing) "Pause" else "Play",
                        76.dp, 34.dp, emphasised = true,
                    ) { poke(); vm.togglePlaying() }
                    RoundButton(AppIcons.Next, "Next", 56.dp, 28.dp) { poke(); vm.next() }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor = Panel,
            contentColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.25f)) },
        ) {
            SettingsSheet(settings = settings, onChange = vm::updateSettings)
        }
    }
}

@Composable
private fun SlideView(
    slide: Slide,
    settings: Settings,
    width: Int,
    height: Int,
    playing: Boolean,
    zoom: ZoomState?,
    turnProgress: () -> Float,
    turnForward: () -> Boolean,
) {
    val context = LocalContext.current
    val motion = remember(slide.key, settings.kenBurnsSpeed) { KenBurns.random(settings.kenBurnsSpeed) }
    val progress = remember(slide.key) { Animatable(0f) }
    val durationMs = settings.intervalSeconds * 1000 + 2500

    // Ken Burns runs while playing and freezes while paused, picking up where it stopped.
    LaunchedEffect(slide.key, settings.kenBurns, playing) {
        if (!settings.kenBurns) {
            progress.snapTo(0f)
        } else if (playing) {
            val remaining = ((1f - progress.value) * durationMs).toInt()
            if (remaining > 0) progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        }
    }

    val backdrop = remember(slide.uri) { backdropRequest(context, slide.uri) }
    val main = remember(slide.uri, width, height) { slideRequest(context, slide.uri, width, height) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Page turn: swing away around the left edge (or right, going backwards).
                val t = turnProgress()
                if (t > 0f) {
                    val forward = turnForward()
                    transformOrigin = TransformOrigin(if (forward) 0f else 1f, 0.5f)
                    rotationY = (if (forward) -1f else 1f) * 90f * t
                    cameraDistance = 14f * density
                }
            }
            .clipToBounds()
    ) {
        AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }
                .blur(40.dp, BlurredEdgeTreatment.Rectangle),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        AsyncImage(
            model = main,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val t = if (settings.kenBurns) progress.value else 0f
                    val s = lerp(motion.startScale, motion.endScale, t)
                    val z = zoom?.scale ?: 1f
                    val pan = zoom?.offset ?: Offset.Zero
                    scaleX = s * z
                    scaleY = s * z
                    translationX = lerp(motion.startX, motion.endX, t) * size.width + pan.x
                    translationY = lerp(motion.startY, motion.endY, t) * size.height + pan.y
                },
        )
        // Shadow that deepens as the page turns away.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = turnProgress() * 0.6f }
                .background(Color.Black)
        )
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (emphasised) Modifier
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun SettingsSheet(settings: Settings, onChange: (Settings) -> Unit) {
    var interval by remember(settings.intervalSeconds) { mutableFloatStateOf(settings.intervalSeconds.toFloat()) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(bottom = 28.dp)
            .navigationBarsPadding()
    ) {
        Label("Interval")
        Text("${interval.roundToInt()} seconds per photo", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Light)
        Slider(
            value = interval,
            onValueChange = { interval = it },
            onValueChangeFinished = { onChange(settings.copy(intervalSeconds = interval.roundToInt())) },
            valueRange = 3f..60f,
            steps = 56,
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )

        Spacer(Modifier.height(20.dp))
        Label("Transition")
        ChoicePills(TransitionStyle.entries, settings.transition, { it.label }) {
            onChange(settings.copy(transition = it))
        }

        Spacer(Modifier.height(28.dp))
        ToggleRow("Ken Burns", "Slow pan and zoom on each photo", settings.kenBurns) {
            onChange(settings.copy(kenBurns = it))
        }
        AnimatedVisibility(visible = settings.kenBurns) {
            Column(Modifier.padding(top = 14.dp)) {
                ChoicePills(KenBurnsSpeed.entries, settings.kenBurnsSpeed, { it.label }) {
                    onChange(settings.copy(kenBurnsSpeed = it))
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Label("Shuffle")
        ToggleRow(
            "Remember where I got to",
            "Carry on the current round after closing the app, instead of starting afresh",
            settings.rememberShuffle,
        ) { onChange(settings.copy(rememberShuffle = it)) }
        Spacer(Modifier.height(18.dp))
        ToggleRow(
            "Fair shuffle",
            "Give each album equal screen time, so small albums aren’t drowned out. For On this day, each year takes a turn.",
            settings.fairShuffle,
        ) { onChange(settings.copy(fairShuffle = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoicePills(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                label(option),
                color = if (isSelected) Ink else Color.White,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) Amber else Color.White.copy(alpha = 0.08f))
                    .clickable { onPick(option) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Amber,
                checkedThumbColor = Ink,
                uncheckedTrackColor = Color.White.copy(alpha = 0.1f),
                uncheckedThumbColor = Muted,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        color = Muted,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

package com.shuffleframe.app

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
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
        fun random(): KenBurns {
            val big = 1.10f + Random.nextFloat() * 0.08f
            val zoomIn = Random.nextBoolean()
            fun drift() = (Random.nextFloat() - 0.5f) * 0.06f
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
}

// ---------- screen ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideshowScreen(
    vm: SlideshowViewModel,
    folderName: String,
    count: Int,
    onChangeFolder: () -> Unit,
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
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    controlsVisible = !controlsVisible
                    interactionTick++
                    vm.dismissHint()
                })
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
            SlideView(s, settings, targetW, targetH)
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
                    RoundButton(AppIcons.Folder, "Change folder", 48.dp, 22.dp) { poke(); onChangeFolder() }
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
private fun SlideView(slide: Slide, settings: Settings, width: Int, height: Int) {
    val context = LocalContext.current
    val motion = remember(slide.key) { KenBurns.random() }
    val progress = remember(slide.key) { Animatable(0f) }
    val durationMs = settings.intervalSeconds * 1000 + 2500

    LaunchedEffect(slide.key, settings.kenBurns) {
        if (settings.kenBurns) {
            progress.animateTo(1f, tween(durationMs, easing = LinearEasing))
        } else {
            progress.snapTo(0f)
        }
    }

    val backdrop = remember(slide.uri) { backdropRequest(context, slide.uri) }
    val main = remember(slide.uri, width, height) { slideRequest(context, slide.uri, width, height) }

    Box(Modifier.fillMaxSize().clipToBounds()) {
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
                    scaleX = s
                    scaleY = s
                    translationX = lerp(motion.startX, motion.endX, t) * size.width
                    translationY = lerp(motion.startY, motion.endY, t) * size.height
                },
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TransitionStyle.entries.forEach { style ->
                val selected = style == settings.transition
                Text(
                    style.label,
                    color = if (selected) Ink else Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) Amber else Color.White.copy(alpha = 0.08f))
                        .clickable { onChange(settings.copy(transition = style)) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ken Burns", color = Color.White, fontSize = 16.sp)
                Text("Slow pan and zoom on each photo", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = settings.kenBurns,
                onCheckedChange = { onChange(settings.copy(kenBurns = it)) },
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

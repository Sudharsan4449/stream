package com.streamcast.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.net.URLDecoder

// Reusable TvFocusableButton component
@Composable
fun TvFocusableButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow)
    )
    
    val elevation by animateDpAsState(
        targetValue = if (isFocused) 8.dp else 0.dp
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .shadow(elevation, RoundedCornerShape(12.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent {
                if ((it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) && it.type == KeyEventType.KeyDown) {
                    onClick()
                    true
                } else false
            },
        color = if (isFocused) Color.White else Color(0x33FFFFFF),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content(isFocused)
        }
    }
}

// Custom TV Timeline component
@Composable
fun TvTimeline(
    currentTime: Long,
    totalDuration: Long,
    bufferedPosition: Long,
    focusRequester: FocusRequester,
    onSeek: (Long) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    val trackHeight by animateDpAsState(if (isFocused) 12.dp else 4.dp)
    val thumbRadius by animateDpAsState(if (isFocused) 16.dp else 0.dp)
    
    val progress = if (totalDuration > 0) currentTime.toFloat() / totalDuration.toFloat() else 0f
    val bufferedProgress = if (totalDuration > 0) bufferedPosition.toFloat() / totalDuration.toFloat() else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val multiplier = if (event.nativeKeyEvent.repeatCount > 5) 5 else 1
                    val step = 10000L * multiplier
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onSeek((currentTime - step).coerceAtLeast(0L))
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSeek((currentTime + step).coerceAtMost(totalDuration))
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Background Track
        Box(modifier = Modifier.fillMaxWidth().height(trackHeight).background(Color.DarkGray, CircleShape))
        // Buffered Track
        Box(modifier = Modifier.fillMaxWidth(bufferedProgress).height(trackHeight).background(Color.Gray, CircleShape))
        // Active Progress Track
        Box(modifier = Modifier.fillMaxWidth(progress).height(trackHeight).background(Color(0xFFE50914), CircleShape))
        // Focus Thumb
        Box(
            modifier = Modifier
                .offset(x = (-thumbRadius))
                .fillMaxWidth(progress)
                .wrapContentWidth(Alignment.End)
                .size(thumbRadius * 2)
                .background(Color.White, CircleShape)
                .shadow(if (isFocused) 8.dp else 0.dp, CircleShape)
        )
    }
}

// Side Panel Selector Dialog
@Composable
fun TvSelectorDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        try {
            initialFocusRequester.requestFocus()
        } catch (e: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
            .onKeyEvent {
                if ((it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE) && it.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else false
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .background(Color(0xFF141414))
                .padding(32.dp)
        ) {
            Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            
            LazyColumn {
                itemsIndexed(options) { index, option ->
                    val fr = if (index == selectedIndex) initialFocusRequester else FocusRequester()
                    TvFocusableButton(
                        focusRequester = fr,
                        onClick = { onSelect(index) },
                        modifier = Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp)
                    ) { isFocused ->
                        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (index == selectedIndex) {
                                Icon(Icons.Default.Check, contentDescription = "Selected", tint = if (isFocused) Color.Black else Color.White)
                                Spacer(Modifier.width(16.dp))
                            }
                            Text(option, color = if (isFocused) Color.Black else Color.White, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
}

class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var isPlayingState by mutableStateOf(false)
    private var currentTimeState by mutableStateOf(0L)
    private var totalDurationState by mutableStateOf(0L)
    private var bufferedPositionState by mutableStateOf(0L)
    private var brightnessState by mutableStateOf(0.8f)
    private var playbackSpeedState by mutableStateOf(1.0f)
    private var resizeModeState by mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    private var lastBackPressTime = 0L
    private var videoUrl: String = ""
    
    private var subtitleSizeState by mutableStateOf(16f)
    private var subtitleStyleState by mutableStateOf("Normal")

    // Elevated UI state for global Key Event processing
    private var showControlsState by mutableStateOf(true)
    private var showAudioDialogState by mutableStateOf(false)
    private var showSubtitleDialogState by mutableStateOf(false)
    private var interactionCounter by mutableStateOf(0)

    companion object {
        const val EXTRA_VIDEO_URL = "EXTRA_VIDEO_URL"
        const val EXTRA_SUBTITLE_URL = "EXTRA_SUBTITLE_URL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setScreenBrightness(brightnessState)

        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL)
        val videoTitle = try {
            URLDecoder.decode(videoUrl, "UTF-8").substringAfterLast('/')
        } catch (e: Exception) {
            videoUrl.substringAfterLast('/')
        }

        if (videoUrl.isNotEmpty()) {
            setupPlayer(videoUrl, subtitleUrl)
        } else {
            Toast.makeText(this, "Invalid video URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            val isPlaying = isPlayingState
            val currentTime = currentTimeState
            val totalDuration = totalDurationState
            val bufferedPosition = bufferedPositionState
            val showControls = showControlsState
            val showAudioDialog = showAudioDialogState
            val showSubtitleDialog = showSubtitleDialogState
            val resizeMode = resizeModeState
            val subtitleSize = subtitleSizeState
            val subtitleStyle = subtitleStyleState
            
            // Focus Requesters
            val playPauseFocusRequester = remember { FocusRequester() }
            val rewindFocusRequester = remember { FocusRequester() }
            val forwardFocusRequester = remember { FocusRequester() }
            val timelineFocusRequester = remember { FocusRequester() }
            val backButtonFocusRequester = remember { FocusRequester() }
            val audioFocusRequester = remember { FocusRequester() }
            val subtitleFocusRequester = remember { FocusRequester() }
            val speedFocusRequester = remember { FocusRequester() }
            val modeFocusRequester = remember { FocusRequester() }
            val downloadFocusRequester = remember { FocusRequester() }

            // Auto-hide controls after 5 seconds of inactivity
            LaunchedEffect(interactionCounter) {
                if (showControls) {
                    kotlinx.coroutines.delay(5000)
                    showControlsState = false
                }
            }

            LaunchedEffect(Unit) {
                triggerInteraction()
                try {
                    playPauseFocusRequester.requestFocus()
                } catch (e: Exception) {}
            }

            LaunchedEffect(isPlaying) {
                if (isPlaying) {
                    while (true) {
                        currentTimeState = player?.currentPosition ?: 0L
                        totalDurationState = player?.duration ?: 0L
                        bufferedPositionState = player?.bufferedPosition ?: 0L
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // Base Video Layer wrapper to catch Taps
                Box(modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            if (!showControlsState) {
                                triggerInteraction()
                                try { playPauseFocusRequester.requestFocus() } catch (e: Exception) {}
                            } else {
                                triggerInteraction()
                            }
                        })
                    }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                this.player = this@PlayerActivity.player
                                this.resizeMode = resizeMode
                            }
                        },
                        update = { view ->
                            view.resizeMode = resizeMode
                            view.player = this@PlayerActivity.player
                            view.subtitleView?.let { subView ->
                                subView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subtitleSize)
                                when (subtitleStyle) {
                                    "Normal" -> subView.setStyle(CaptionStyleCompat.DEFAULT)
                                    "Yellow" -> subView.setStyle(CaptionStyleCompat(Color.Yellow.toArgb(), Color.Transparent.toArgb(), Color.Transparent.toArgb(), CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.Black.toArgb(), null))
                                    "BlackBG" -> subView.setStyle(CaptionStyleCompat(Color.White.toArgb(), Color.Black.copy(alpha = 0.5f).toArgb(), Color.Transparent.toArgb(), CaptionStyleCompat.EDGE_TYPE_NONE, Color.Black.toArgb(), null))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Overlay Controls Layer
                AnimatedVisibility(
                    visible = showControls && !showAudioDialog && !showSubtitleDialog,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.7f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.9f)
                                    )
                                )
                            )
                            .padding(horizontal = 48.dp, vertical = 32.dp)
                            .pointerInput(Unit) {
                                // Reset timer if user taps anywhere on the controls
                                detectTapGestures(onTap = { triggerInteraction() })
                            }
                    ) {
                        // Top Bar: Back & Title
                        Row(
                            modifier = Modifier.fillMaxWidth().align(Alignment.TopStart),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TvFocusableButton(
                                    focusRequester = backButtonFocusRequester,
                                    onClick = { 
                                        savePlaybackPosition()
                                        finish() 
                                    },
                                    modifier = Modifier.size(56.dp).focusProperties { down = playPauseFocusRequester; right = downloadFocusRequester }
                                ) { isFocused ->
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = if (isFocused) Color.Black else Color.White)
                                }
                                Spacer(Modifier.width(24.dp))
                                Text(text = videoTitle, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 400.dp))
                            }
                            
                            TvFocusableButton(
                                focusRequester = downloadFocusRequester,
                                onClick = { 
                                    triggerInteraction()
                                    val downloadIntent = Intent(this@PlayerActivity, TvDownloadService::class.java).apply {
                                        action = TvDownloadService.ACTION_DOWNLOAD
                                        putExtra(TvDownloadService.EXTRA_URL, videoUrl)
                                        putExtra(TvDownloadService.EXTRA_FILENAME, videoTitle.ifEmpty { "downloaded_video.mp4" })
                                    }
                                    startService(downloadIntent)
                                },
                                modifier = Modifier.size(56.dp).focusProperties { down = playPauseFocusRequester; left = backButtonFocusRequester }
                            ) { isFocused ->
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Download", tint = if (isFocused) Color.Black else Color.White)
                            }
                        }

                        // Center Controls: Rewind, Play, Forward
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvFocusableButton(
                                focusRequester = rewindFocusRequester,
                                onClick = { 
                                    seekBackward(10000L)
                                    triggerInteraction()
                                },
                                modifier = Modifier.size(64.dp).focusProperties { right = playPauseFocusRequester; up = backButtonFocusRequester; down = timelineFocusRequester }
                            ) { isFocused -> Icon(Icons.Default.FastRewind, contentDescription = "Rewind", tint = if (isFocused) Color.Black else Color.White, modifier = Modifier.size(32.dp)) }

                            TvFocusableButton(
                                focusRequester = playPauseFocusRequester,
                                onClick = { 
                                    togglePlayPause()
                                    triggerInteraction()
                                },
                                modifier = Modifier.size(80.dp).focusProperties { left = rewindFocusRequester; right = forwardFocusRequester; up = backButtonFocusRequester; down = timelineFocusRequester }
                            ) { isFocused -> Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = if (isFocused) Color.Black else Color.White, modifier = Modifier.size(48.dp)) }

                            TvFocusableButton(
                                focusRequester = forwardFocusRequester,
                                onClick = { 
                                    seekForward(10000L)
                                    triggerInteraction()
                                },
                                modifier = Modifier.size(64.dp).focusProperties { left = playPauseFocusRequester; up = backButtonFocusRequester; down = timelineFocusRequester }
                            ) { isFocused -> Icon(Icons.Default.FastForward, contentDescription = "Forward", tint = if (isFocused) Color.Black else Color.White, modifier = Modifier.size(32.dp)) }
                        }

                        // Bottom Section: Timeline & Tools
                        Column(
                            modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(currentTime), color = Color.White, fontWeight = FontWeight.Medium)
                                Text(formatTime(totalDuration), color = Color.White, fontWeight = FontWeight.Medium)
                            }
                            
                            Spacer(Modifier.height(8.dp))
                            
                            TvTimeline(
                                currentTime = currentTime,
                                totalDuration = totalDuration,
                                bufferedPosition = bufferedPosition,
                                focusRequester = timelineFocusRequester,
                                onSeek = { newTime ->
                                    player?.seekTo(newTime)
                                    currentTimeState = newTime
                                    triggerInteraction()
                                }
                            )

                            Spacer(Modifier.height(24.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                TvFocusableButton(
                                    focusRequester = audioFocusRequester,
                                    onClick = { 
                                        showAudioDialogState = true 
                                        triggerInteraction()
                                    },
                                    modifier = Modifier.height(48.dp).width(120.dp).focusProperties { up = timelineFocusRequester; right = subtitleFocusRequester }
                                ) { isFocused -> Text("Audio", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Medium) }

                                TvFocusableButton(
                                    focusRequester = subtitleFocusRequester,
                                    onClick = { 
                                        showSubtitleDialogState = true 
                                        triggerInteraction()
                                    },
                                    modifier = Modifier.height(48.dp).width(120.dp).focusProperties { up = timelineFocusRequester; left = audioFocusRequester; right = speedFocusRequester }
                                ) { isFocused -> Text("Subtitles", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Medium) }

                                TvFocusableButton(
                                    focusRequester = speedFocusRequester,
                                    onClick = { 
                                        playbackSpeedState = when (playbackSpeedState) {
                                            0.5f -> 1.0f
                                            1.0f -> 1.5f
                                            1.5f -> 2.0f
                                            2.0f -> 0.5f
                                            else -> 1.0f
                                        }
                                        player?.setPlaybackSpeed(playbackSpeedState)
                                        triggerInteraction()
                                    },
                                    modifier = Modifier.height(48.dp).width(120.dp).focusProperties { up = timelineFocusRequester; left = subtitleFocusRequester; right = modeFocusRequester }
                                ) { isFocused -> Text("${playbackSpeedState}x", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Medium) }
                                
                                TvFocusableButton(
                                    focusRequester = modeFocusRequester,
                                    onClick = { 
                                        resizeModeState = when (resizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                        triggerInteraction()
                                    },
                                    modifier = Modifier.height(48.dp).width(120.dp).focusProperties { up = timelineFocusRequester; left = speedFocusRequester }
                                ) { isFocused -> 
                                    val modeText = when (resizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
                                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                                        else -> "Fit"
                                    }
                                    Text(modeText, color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Medium) 
                                }
                            }
                        }
                    }
                }
                
                // Dialogs overlay
                if (showAudioDialog) {
                    val audioGroups = player?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO } ?: emptyList()
                    val options = mutableListOf<String>()
                    var selectedIndex = 0
                    if (audioGroups.isEmpty()) {
                        options.add("No audio tracks found")
                    } else {
                        var optIdx = 0
                        for (group in audioGroups) {
                            for (i in 0 until group.length) {
                                val format = group.getTrackFormat(i)
                                val isSelected = group.isTrackSelected(i)
                                val language = format.language ?: "Unknown"
                                val label = format.label ?: "Track ${i + 1}"
                                options.add("$language - $label")
                                if (isSelected) selectedIndex = optIdx
                                optIdx++
                            }
                        }
                    }
                    
                    TvSelectorDialog(
                        title = "Audio Tracks",
                        options = options,
                        selectedIndex = selectedIndex,
                        onSelect = { idx ->
                            if (audioGroups.isNotEmpty()) {
                                var currentIdx = 0
                                for (group in audioGroups) {
                                    for (i in 0 until group.length) {
                                        if (currentIdx == idx) {
                                            player?.let { p ->
                                                p.trackSelectionParameters = p.trackSelectionParameters
                                                    .buildUpon()
                                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                                    .build()
                                            }
                                        }
                                        currentIdx++
                                    }
                                }
                            }
                            showAudioDialogState = false
                            triggerInteraction()
                            try { audioFocusRequester.requestFocus() } catch(e:Exception){}
                        },
                        onDismiss = {
                            showAudioDialogState = false
                            triggerInteraction()
                            try { audioFocusRequester.requestFocus() } catch(e:Exception){}
                        }
                    )
                }

                if (showSubtitleDialog) {
                    val options = listOf("Size: Small", "Size: Normal", "Size: Large", "Style: Normal", "Style: Yellow", "Style: BlackBG")
                    val selectedIndex = -1 
                    
                    TvSelectorDialog(
                        title = "Subtitles",
                        options = options,
                        selectedIndex = selectedIndex,
                        onSelect = { idx ->
                            when(idx) {
                                0 -> subtitleSizeState = 12f
                                1 -> subtitleSizeState = 16f
                                2 -> subtitleSizeState = 24f
                                3 -> subtitleStyleState = "Normal"
                                4 -> subtitleStyleState = "Yellow"
                                5 -> subtitleStyleState = "BlackBG"
                            }
                            showSubtitleDialogState = false
                            triggerInteraction()
                            try { subtitleFocusRequester.requestFocus() } catch(e:Exception){}
                        },
                        onDismiss = {
                            showSubtitleDialogState = false
                            triggerInteraction()
                            try { subtitleFocusRequester.requestFocus() } catch(e:Exception){}
                        }
                    )
                }
            }
        }
    }

    private fun triggerInteraction() {
        showControlsState = true
        interactionCounter++
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Ignore volume keys
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyDown(keyCode, event)
        }

        // If controls are hidden, intercept ALL keys
        if (!showControlsState) {
            triggerInteraction()
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> seekBackward(10000L)
                KeyEvent.KEYCODE_DPAD_RIGHT -> seekForward(10000L)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlayPause()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < 2000) {
                        savePlaybackPosition()
                        finish()
                    } else {
                        lastBackPressTime = now
                        Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return true
        }

        triggerInteraction()

        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (showAudioDialogState) {
                showAudioDialogState = false
                return true
            }
            if (showSubtitleDialogState) {
                showSubtitleDialogState = false
                return true
            }
            if (showControlsState) {
                showControlsState = false
                return true
            }
            
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 2000) {
                savePlaybackPosition()
                finish()
            } else {
                lastBackPressTime = now
                Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun setupPlayer(videoUrl: String, subtitleUrl: String?) {
        val loadErrorHandlingPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int {
                return Integer.MAX_VALUE
            }
        }
        
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        val playerBuilder = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            
        player = playerBuilder.build()
        isPlayingState = player?.isPlaying ?: false

        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayingState = isPlaying
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                totalDurationState = player?.duration ?: 0L
            }
        })

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(videoUrl))

        if (subtitleUrl != null) {
            val extension = subtitleUrl.substringAfterLast('.', "").lowercase()
            val mimeType = when (extension) {
                "vtt" -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                .setMimeType(mimeType)
                .setLanguage("en")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
        }

        player?.setMediaItem(mediaItemBuilder.build())
        player?.prepare()
        val savedPos = getSavedPlaybackPosition(videoUrl)
        if (savedPos > 0) {
            player?.seekTo(savedPos)
            Toast.makeText(this, "Resuming from ${formatTime(savedPos)}", Toast.LENGTH_SHORT).show()
        }
        player?.playWhenReady = true
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) p.pause() else p.play()
        }
    }

    private fun seekBackward(amount: Long = 10000L) {
        player?.let { p ->
            p.seekTo(Math.max(0, p.currentPosition - amount))
            currentTimeState = p.currentPosition
        }
    }

    private fun seekForward(amount: Long = 10000L) {
        player?.let { p ->
            p.seekTo(Math.min(p.duration, p.currentPosition + amount))
            currentTimeState = p.currentPosition
        }
    }

    private fun setScreenBrightness(value: Float) {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = value
        window.attributes = layoutParams
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun savePlaybackPosition() {
        val p = player ?: return
        val currentPos = p.currentPosition
        if (videoUrl.isNotEmpty() && p.duration > 0 && currentPos < p.duration - 5000) {
            val prefs = getSharedPreferences("PlayerPlaybackPrefs", Context.MODE_PRIVATE)
            prefs.edit().putLong(videoUrl, currentPos).apply()
        } else if (videoUrl.isNotEmpty() && currentPos >= p.duration - 5000) {
            val prefs = getSharedPreferences("PlayerPlaybackPrefs", Context.MODE_PRIVATE)
            prefs.edit().remove(videoUrl).apply()
        }
    }

    private fun getSavedPlaybackPosition(url: String): Long {
        val prefs = getSharedPreferences("PlayerPlaybackPrefs", Context.MODE_PRIVATE)
        return prefs.getLong(url, 0L)
    }

    override fun onPause() {
        super.onPause()
        savePlaybackPosition()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}

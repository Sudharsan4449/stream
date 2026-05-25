package com.streamcast.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder

class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var isPlayingState by mutableStateOf(false)
    private var currentTimeState by mutableStateOf(0L)
    private var totalDurationState by mutableStateOf(0L)
    private var showControlsState by mutableStateOf(true)
    private var brightnessState by mutableStateOf(0.8f)
    private var resizeModeState by mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    private var lastBackPressTime = 0L
    private var videoUrl: String = ""

    companion object {
        const val EXTRA_VIDEO_URL = "EXTRA_VIDEO_URL"
        const val EXTRA_SUBTITLE_URL = "EXTRA_SUBTITLE_URL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screen dimming or locking during playback
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
            val showControls = showControlsState
            val brightness = brightnessState
            val resizeMode = resizeModeState

            val coroutineScope = rememberCoroutineScope()
            var controlsTimeoutJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

            fun resetControlsTimer() {
                showControlsState = true
                controlsTimeoutJob?.cancel()
                controlsTimeoutJob = coroutineScope.launch {
                    delay(5000)
                    showControlsState = false
                }
            }

            // Automatically hide controls after 5 seconds on load
            LaunchedEffect(Unit) {
                resetControlsTimer()
            }

            // Track progress position when playing
            LaunchedEffect(isPlaying) {
                if (isPlaying) {
                    while (true) {
                        currentTimeState = player?.currentPosition ?: 0L
                        totalDurationState = player?.duration ?: 0L
                        delay(1000)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // 1. ExoPlayer view
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
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 2. Playback Overlay HUD
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(32.dp)
                            .clickable { resetControlsTimer() }
                    ) {
                        // --- TOP BAR: Exit Button & Title ---
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopStart),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            var isExitFocused by remember { mutableStateOf(false) }
                            Button(
                                onClick = {
                                    savePlaybackPosition()
                                    finish()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isExitFocused) Color.Red else Color(0xFFDC2626)
                                ),
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .onFocusChanged { isExitFocused = it.isFocused }
                                    .focusable()
                            ) {
                                Text("Exit Video", color = Color.White)
                            }
                            Text(
                                text = videoTitle,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // --- CENTER CONTROLS: Seek / Play / Pause Buttons ---
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Seek Backward Button (-10s)
                            var isRewindFocused by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = {
                                    seekBackward()
                                    resetControlsTimer()
                                },
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(
                                        if (isRewindFocused) Color(0xFF0EA5E9) else Color(0xFF1E293B),
                                        shape = RoundedCornerShape(28.dp)
                                    )
                                    .onFocusChanged { isRewindFocused = it.isFocused }
                                    .focusable()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh, // Use refresh as a circular rewind-like symbol
                                    contentDescription = "Rewind 10 seconds",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Play/Pause Toggle Button
                            var isPlayFocused by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = {
                                    togglePlayPause()
                                    resetControlsTimer()
                                },
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(
                                        if (isPlayFocused) Color(0xFF10B981) else Color(0xFF1E293B),
                                        shape = RoundedCornerShape(36.dp)
                                    )
                                    .onFocusChanged { isPlayFocused = it.isFocused }
                                    .focusable()
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            // Seek Forward Button (+10s)
                            var isForwardFocused by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = {
                                    seekForward()
                                    resetControlsTimer()
                                },
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(
                                        if (isForwardFocused) Color(0xFF0EA5E9) else Color(0xFF1E293B),
                                        shape = RoundedCornerShape(28.dp)
                                    )
                                    .onFocusChanged { isForwardFocused = it.isFocused }
                                    .focusable()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Forward 10 seconds",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // --- BOTTOM PANEL: Progress Indicator, Brightness & Aspect Ratio ---
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                        ) {
                            // Progress bar indicator
                            val progress = if (totalDuration > 0) currentTime.toFloat() / totalDuration.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .background(Color.Gray.copy(alpha = 0.5f), shape = RoundedCornerShape(3.dp)),
                                color = Color(0xFF10B981)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${formatTime(currentTime)} / ${formatTime(totalDuration)}",
                                    color = Color.LightGray,
                                    fontSize = 13.sp
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Aspect Ratio Toggle Button
                                    var isModeFocused by remember { mutableStateOf(false) }
                                    Button(
                                        onClick = {
                                            resizeModeState = when (resizeMode) {
                                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                            }
                                            resetControlsTimer()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isModeFocused) Color(0xFF10B981) else Color(0xFF1E293B)
                                        ),
                                        modifier = Modifier
                                            .onFocusChanged { isModeFocused = it.isFocused }
                                            .focusable()
                                    ) {
                                        val modeText = when (resizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
                                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                                            else -> "Fit"
                                        }
                                        Text("Mode: $modeText", color = if (isModeFocused) Color.Black else Color.White)
                                    }

                                    // Brightness Control Button
                                    var isBrightnessFocused by remember { mutableStateOf(false) }
                                    Button(
                                        onClick = {
                                            brightnessState = when (brightness) {
                                                0.2f -> 0.4f
                                                0.4f -> 0.6f
                                                0.6f -> 0.8f
                                                0.8f -> 1.0f
                                                else -> 0.2f
                                            }
                                            setScreenBrightness(brightnessState)
                                            resetControlsTimer()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isBrightnessFocused) Color(0xFF10B981) else Color(0xFF1E293B)
                                        ),
                                        modifier = Modifier
                                            .onFocusChanged { isBrightnessFocused = it.isFocused }
                                            .focusable()
                                    ) {
                                        Text(
                                            text = "Brightness: ${(brightness * 100).toInt()}%",
                                            color = if (isBrightnessFocused) Color.Black else Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupPlayer(videoUrl: String, subtitleUrl: String?) {
        val playerBuilder = ExoPlayer.Builder(this)
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

    private fun seekBackward() {
        player?.let { p ->
            p.seekTo(Math.max(0, p.currentPosition - 10000))
            currentTimeState = p.currentPosition
        }
    }

    private fun seekForward() {
        player?.let { p ->
            p.seekTo(Math.min(p.duration, p.currentPosition + 10000))
            currentTimeState = p.currentPosition
        }
    }

    private fun setScreenBrightness(value: Float) {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = value
        window.attributes = layoutParams
    }

    private fun isNavigationOrActionKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> true
            else -> false
        }
    }

    // Capture TV Remote controls
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If controls are hidden, any navigation/action key wakes the controls first
        if (!showControlsState && isNavigationOrActionKey(keyCode)) {
            showControlsState = true
            return true
        }

        player?.let { p ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // Let focusable items handle their clicks if controls are visible.
                    // If we want a global play/pause toggle when no buttons are selected, we fallback here.
                    // But in standard Compose focus flow, returning false lets the focused component consume it.
                    return false
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // If controls are visible, we let focus navigate left unless we want to force seek
                    // For standard TV UX, left/right triggers seek if controls are hidden.
                    // If controls are visible, we can still handle seek, but let's seek directly if no focus matches.
                    return false
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    return false
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (!showControlsState) {
                        showControlsState = true
                        return true
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressTime < 2000) {
                            savePlaybackPosition()
                            finish()
                        } else {
                            lastBackPressTime = now
                            showControlsState = false
                            Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                }
                else -> {}
            }
        }
        return super.onKeyDown(keyCode, event)
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

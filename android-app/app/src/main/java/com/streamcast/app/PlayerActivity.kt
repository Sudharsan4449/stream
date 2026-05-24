package com.streamcast.app

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    companion object {
        const val EXTRA_VIDEO_URL = "EXTRA_VIDEO_URL"
        const val EXTRA_SUBTITLE_URL = "EXTRA_SUBTITLE_URL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Prevent screen dimming or locking during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            controllerHideOnTouch = true
        }
        setContentView(playerView)

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL)

        if (videoUrl != null) {
            setupPlayer(videoUrl, subtitleUrl)
        } else {
            finish()
        }
    }

    private fun setupPlayer(videoUrl: String, subtitleUrl: String?) {
        val playerBuilder = ExoPlayer.Builder(this)
        player = playerBuilder.build()
        playerView.player = player

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(videoUrl))

        if (subtitleUrl != null) {
            val extension = subtitleUrl.substringAfterLast('.', "").lowercase()
            val mimeType = when (extension) {
                "vtt" -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_SUBRIP // Fallback to SubRip (.srt)
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
        player?.playWhenReady = true
    }

    // Capture standard TV Remote control key inputs for comfortable control
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        player?.let { p ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (p.isPlaying) p.pause() else p.play()
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (p.isPlaying) p.pause() else p.play()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // Rewind 10 seconds
                    p.seekTo(Math.max(0, p.currentPosition - 10000))
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Fast Forward 10 seconds
                    p.seekTo(Math.min(p.duration, p.currentPosition + 10000))
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
                else -> {}
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}

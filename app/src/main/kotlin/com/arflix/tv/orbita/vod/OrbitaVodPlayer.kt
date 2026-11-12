package com.arflix.tv.orbita.vod

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Saves where each movie / episode was stopped, to resume and to draw progress bars. */
object OrbitaWatchProgress {
    private const val PREFS = "orbita_progress"

    fun save(context: Context, url: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val done = positionMs > durationMs * 0.93
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("p:$url", if (done) 0 else positionMs)
            .putLong("d:$url", durationMs)
            .putBoolean("w:$url", done || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("w:$url", false))
            .apply()
    }

    fun position(context: Context, url: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("p:$url", 0)

    fun watched(context: Context, url: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("w:$url", false)

    fun fraction(context: Context, url: String): Float {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = p.getLong("d:$url", 0)
        if (p.getBoolean("w:$url", false) && p.getLong("p:$url", 0) == 0L) return 1f
        return if (d > 0) (p.getLong("p:$url", 0).toFloat() / d).coerceIn(0f, 1f) else 0f
    }
}

data class OrbitaPlayItem(val title: String, val url: String, val subtitle: String? = null)

/** Full-screen player for movies and episodes from the user's list. */
@OptIn(UnstableApi::class)
class OrbitaVodPlayerActivity : ComponentActivity() {

    companion object {
        private var queue: List<OrbitaPlayItem> = emptyList()
        private var startIndex = 0

        fun start(context: Context, items: List<OrbitaPlayItem>, index: Int, resume: Boolean = true) {
            if (items.isEmpty()) return
            queue = items
            startIndex = index.coerceIn(0, items.lastIndex)
            context.startActivity(
                Intent(context, OrbitaVodPlayerActivity::class.java).putExtra("resume", resume)
            )
        }
    }

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var overlay: TextView
    private var index = 0
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { overlay.visibility = View.GONE }
    private val saver = object : Runnable {
        override fun run() { saveProgress(); handler.postDelayed(this, 10_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (queue.isEmpty()) { finish(); return }
        index = savedInstanceState?.getInt("index") ?: startIndex
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 4000
            setShowSubtitleButton(true)
            setShowNextButton(queue.size > 1)
            setShowPreviousButton(queue.size > 1)
        }
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.argb(180, 18, 14, 48))
            setPadding(36, 20, 36, 20)
            visibility = View.GONE
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(56, 44, 56, 0) })
        setContentView(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onStart() {
        super.onStart()
        if (player == null) init()
        handler.postDelayed(saver, 10_000)
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun init() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("OrbitaPlay/Android")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player = exo
        view.player = exo
        exo.setMediaItems(queue.map { item ->
            MediaItem.Builder().setUri(Uri.parse(item.url))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setSubtitle(item.subtitle).build())
                .build()
        }, index, if (intent.getBooleanExtra("resume", true)) OrbitaWatchProgress.position(this, queue[index].url) else 0L)
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveProgressFor(index)
                index = exo.currentMediaItemIndex
                show(queue[index].title + (queue[index].subtitle?.let { "\n$it" } ?: ""))
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { saveProgress(); finish() }
            }

            override fun onPlayerError(error: PlaybackException) {
                show("Não foi possível reproduzir (${error.errorCodeName})", 6000)
            }
        })
        exo.playWhenReady = true
        exo.prepare()
        show(queue[index].title + (queue[index].subtitle?.let { "\n$it" } ?: ""))
    }

    private fun saveProgress() = saveProgressFor(index)

    private fun saveProgressFor(i: Int) {
        val exo = player ?: return
        val item = queue.getOrNull(i) ?: return
        if (exo.currentMediaItemIndex != i) return
        OrbitaWatchProgress.save(this, item.url, exo.currentPosition, exo.duration)
    }

    private fun show(text: String, ms: Long = 3500) {
        overlay.text = text
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, ms)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Left / right seek 10 s when the controls are hidden, like most IPTV players.
        if (event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { player?.seekBack(); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { player?.seekForward(); view.showController(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

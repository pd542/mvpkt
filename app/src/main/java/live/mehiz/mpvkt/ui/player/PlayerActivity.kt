@file:Suppress("TooManyFunctions")

package live.mehiz.mpvkt.ui.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.github.k1rakishou.fsaf.FileManager
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.mehiz.mpvkt.database.entities.CustomButtonEntity
import live.mehiz.mpvkt.database.entities.PlaybackStateEntity
import live.mehiz.mpvkt.databinding.PlayerLayoutBinding
import live.mehiz.mpvkt.domain.playbackstate.repository.PlaybackStateRepository
import live.mehiz.mpvkt.network.PlaybackSessionLog
import live.mehiz.mpvkt.network.SegmentedHttpCache
import live.mehiz.mpvkt.network.SystemHttpProxy
import live.mehiz.mpvkt.preferences.AdvancedPreferences
import live.mehiz.mpvkt.preferences.AudioPreferences
import live.mehiz.mpvkt.preferences.GesturePreferences
import live.mehiz.mpvkt.preferences.NetworkPreferences
import live.mehiz.mpvkt.preferences.PlayerPreferences
import live.mehiz.mpvkt.preferences.SubtitlesPreferences
import live.mehiz.mpvkt.ui.player.controls.PlayerControls
import live.mehiz.mpvkt.ui.theme.MpvKtTheme
import org.koin.android.ext.android.inject
import java.io.File
import java.util.Locale

@Suppress("TooManyFunctions", "LargeClass")
class PlayerActivity : AppCompatActivity() {

  private val viewModel: PlayerViewModel by viewModels<PlayerViewModel> { PlayerViewModelProviderFactory(this) }
  private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }
  private val playerObserver by lazy { PlayerObserver(this) }
  private val playbackStateRepository: PlaybackStateRepository by inject()
  val player by lazy { binding.player }
  val windowInsetsController by lazy { WindowCompat.getInsetsController(window, window.decorView) }
  val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
  private var mediaSession: MediaSession? = null
  private val playerPreferences: PlayerPreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val gesturePreferences: GesturePreferences by inject()
  private val networkPreferences: NetworkPreferences by inject()
  private val fileManager: FileManager by inject()

  private var fileName = ""
  private var mediaPlaybackService: MediaPlaybackService? = null
  private var serviceBound = false

  /** Multi-connection Range downloader + local proxy (progressive HTTP only). */
  private var segmentedHttpCache: SegmentedHttpCache? = null
  private var segmentedSourceUrl: String? = null
  private var segmentedWatchdogJob: kotlinx.coroutines.Job? = null
  private var playbackLogHeartbeatJob: kotlinx.coroutines.Job? = null
  private var segmentedReconnectInProgress = false
  private var segmentedPendingSeek: Int? = null
  private var lastSegmentedReconnectAtMs = 0L
  private var lastPausedForCache: Boolean? = null
  private var lastEofReached: Boolean? = null

  /** Dedup adaptive decoder re-evaluation while video-params stream in. */
  private var adaptiveDecoderAppliedForFile = false
  private var lastAdaptiveDecoderSignature: String? = null

  private var audioFocusRequest: AudioFocusRequestCompat? = null
  private var restoreAudioFocus: () -> Unit = {}

  private data class PlaybackStateSnapshot(
    val pos: Int,
    val duration: Int,
    val playbackSpeed: Double,
    val sid: Int,
    val subDelay: Int,
    val subSpeed: Double,
    val secondarySid: Int,
    val secondarySubDelay: Int,
    val aid: Int,
    val audioDelay: Int,
  )

  private data class SegmentedWatchdogState(
    val cache: SegmentedHttpCache,
    val snapshot: SegmentedHttpCache.CacheSnapshot,
    val position: Int?,
    val duration: Int?,
    val now: Long,
    val lastPlaybackProgressAtMs: Long,
  )

  private var pipRect: android.graphics.Rect? = null
  val isPipSupported by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      false
    } else {
      packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
  }
  private var pipReceiver: BroadcastReceiver? = null

  private val noisyReceiver = object : BroadcastReceiver() {
    var initialized = false
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
        viewModel.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContentView(binding.root)

    setupMPV()
    setupAudio()
    setupMediaSession()
    PlaybackSessionLog.startSession(applicationContext, reason = "PlayerActivity.onCreate")
    startPlaybackLogHeartbeat()
    // Wipe any leftover segmented files from a previous process before opening a URL.
    clearSegmentedPlaybackCache()
    // Default path matches upstream: resolve URI and play immediately on main thread.
    // Multi-connection (optional) only runs on a background thread when explicitly enabled.
    startPlayback(intent, useLoadfileCommand = false)
    setOrientation()

    binding.controls.setContent {
      MpvKtTheme {
        PlayerControls(
          viewModel = viewModel,
          onBackPress = ::finish,
          modifier = Modifier.onGloballyPositioned {
            pipRect = it.boundsInWindow().toAndroidRect()
          },
        )
      }
    }
  }

  private fun startPlayback(intent: Intent, useLoadfileCommand: Boolean) {
    val source = resolvePlayableUri(intent) ?: return
    val multiPref = networkPreferences.multiConnectionDownload.get()
    PlaybackSessionLog.i(
      "PLAY",
      "start source=${PlaybackSessionLog.redactUrl(source)} segmentedPref=$multiPref",
    )
    // Multi-conn remains the default under system proxy (opt-in switch can disable it).
    // Origin Range downloads go through SystemHttpProxy; mpv only reads the local proxy.
    val wantSegmented = shouldUseSegmentedDownload(source)
    PlaybackSessionLog.i("PLAY", "wantSegmented=$wantSegmented")

    if (!wantSegmented) {
      segmentedSourceUrl = null
      stopSegmentedWatchdog()
      // Direct play — same as upstream mpvKt (libmpv may use system http-proxy).
      playUri(source, useLoadfileCommand)
      return
    }

    // Segmented path: probe + head download on IO, then hand URL to mpv.
    // On failure, open() already returns the original URL.
    lifecycleScope.launch {
      val playable = withContext(Dispatchers.IO) {
        runCatching { maybeAccelerateHttp(source) }.getOrElse {
          PlaybackSessionLog.e("SEG", "maybeAccelerateHttp failed", it)
          source
        }
      }
      PlaybackSessionLog.i(
        "PLAY",
        "playable=${PlaybackSessionLog.redactUrl(playable)} localProxy=${playable.isLocalSegmentedProxy()}",
      )
      playUri(playable, useLoadfileCommand)
      if (playable.isLocalSegmentedProxy()) startSegmentedWatchdog() else stopSegmentedWatchdog()
    }
  }

  private fun startPlaybackLogHeartbeat() {
    if (playbackLogHeartbeatJob?.isActive == true) return
    playbackLogHeartbeatJob = lifecycleScope.launch {
      while (!player.isExiting) {
        delay(PlaybackSessionLog.HEARTBEAT_MS)
        if (player.isExiting) return@launch
        PlaybackSessionLog.snapshotPlayback("HEARTBEAT")
        val snap = segmentedHttpCache?.snapshot()
        if (snap != null) {
          val writeAgeMs = System.currentTimeMillis() - snap.lastWriteAtMs
          PlaybackSessionLog.i(
            "SEG",
            "heartbeat running=${snap.running} fully=${snap.fullyCached} " +
              "downloaded=${snap.downloadedBytes}/${snap.totalSize} " +
              "lastWriteAgeMs=$writeAgeMs",
          )
        }
      }
    }
  }

  private fun stopPlaybackLogHeartbeat() {
    playbackLogHeartbeatJob?.cancel()
    playbackLogHeartbeatJob = null
  }

  /** Detect Android system / NekoBox HTTP proxy when the preference allows it. */
  private fun resolveSystemHttpProxy(): SystemHttpProxy.Info? {
    if (!networkPreferences.useSystemHttpProxy.get()) return null
    return SystemHttpProxy.current(applicationContext)
  }

  /**
   * Multi-conn when enabled and the URL is acceleratable.
   * Emby/Jellyfin direct streams use segmented by default (including under VPN/TUN).
   * Optional [NetworkPreferences.disableMultiConnUnderProxy] can skip multi-conn when a
   * pure system HTTP proxy is active (not VPN transparent mode).
   */
  private fun shouldUseSegmentedDownload(source: String): Boolean {
    val multiEnabled = networkPreferences.multiConnectionDownload.get()
    val acceleratable = SegmentedHttpCache.shouldTryAccelerate(source)
    val proxy = resolveSystemHttpProxy()
    val blockedByProxy = proxy != null && networkPreferences.disableMultiConnUnderProxy.get()
    proxy?.takeIf { blockedByProxy }?.let {
      Log.i(TAG, "skip multi-conn under system proxy ${it.mpvHttpProxyUrl}")
    }
    return multiEnabled && acceleratable && !blockedByProxy
  }

  /**
   * Local segmented proxy must not go through system http-proxy (would loop / hang).
   * Remote URLs restore the system proxy so direct play still works under NekoBox.
   */
  private fun applyMpvHttpProxyForUri(uri: String) {
    if (!networkPreferences.useSystemHttpProxy.get()) {
      runCatching { MPVLib.setPropertyString("http-proxy", "") }
      return
    }
    if (uri.isLocalSegmentedProxy()) {
      // Loopback multi-conn cache — never tunnel through NekoBox.
      runCatching { MPVLib.setPropertyString("http-proxy", "") }
      Log.d(TAG, "cleared mpv http-proxy for local segmented URL")
      return
    }
    val info = SystemHttpProxy.current(applicationContext)
    if (info != null) {
      runCatching { MPVLib.setPropertyString("http-proxy", info.mpvHttpProxyUrl) }
      Log.d(TAG, "mpv http-proxy=${info.mpvHttpProxyUrl} for remote URL")
    } else {
      runCatching { MPVLib.setPropertyString("http-proxy", "") }
    }
  }

  private fun playUri(uri: String, useLoadfileCommand: Boolean) {
    val isLocalProxy = uri.isLocalSegmentedProxy()
    val isRemoteHttp = uri.startsWith("http://", ignoreCase = true) ||
      uri.startsWith("https://", ignoreCase = true)
    applyMpvHttpProxyForUri(uri)
    // Headers must be applied BEFORE the first network request.
    // setIntentExtras() only runs on FILE_LOADED — too late for auth-gated Emby.
    if (isRemoteHttp && !isLocalProxy) {
      applyPlaybackHttpHeaders(uri, intent.extras)
    }
    PlaybackSessionLog.i(
      "PLAY",
      "load uri=${PlaybackSessionLog.redactUrl(uri)} localProxy=$isLocalProxy " +
        "useLoadfile=$useLoadfileCommand remoteHttp=$isRemoteHttp",
    )
    // Local multi-conn proxy / remote HTTP: always use loadfile so headers stick.
    if (useLoadfileCommand || isLocalProxy || isRemoteHttp) {
      if (isLocalProxy) {
        // Help lavf treat progressive proxy as seekable http.
        runCatching { MPVLib.setOptionString("force-seekable", "yes") }
      }
      MPVLib.command("loadfile", uri)
    } else {
      player.playFile(uri)
    }
  }

  /** Resolve content/file/http URI without multi-connection. */
  private fun resolvePlayableUri(intent: Intent): String? {
    val uri = parsePathFromIntent(intent)
    return if (uri?.startsWith("content://") == true) {
      uri.toUri().openContentFd(this)
    } else {
      uri
    }
  }

  /**
   * Optional multi-connection Range download.
   * Returns a localhost proxy URL when segmented mode is active,
   * otherwise the original remote URL.
   */
  private fun maybeAccelerateHttp(uri: String): String {
    if (!shouldUseSegmentedDownload(uri)) return uri

    // Drop previous media's on-disk segments before starting a new download.
    clearSegmentedPlaybackCache()

    val connections = networkPreferences.multiConnectionCount.get().coerceIn(2, 16)
    val chunkKb = networkPreferences.multiConnectionChunkKb.get().coerceIn(256, 4096)
    val cacheRoot = File(cacheDir, "segmented-http").also { it.mkdirs() }
    val requestHeaders = playbackHttpHeaders(uri, intent.extras)
    val systemProxy = resolveSystemHttpProxy()
    val accelerator = SegmentedHttpCache(
      cacheDir = cacheRoot,
      connections = connections,
      chunkBytes = chunkKb * 1024,
      userAgent = requestHeaders.userAgentOrDefault(),
      requestHeaders = requestHeaders,
      systemProxy = systemProxy,
      limitConnectionsUnderProxy = true,
    )
    val result = accelerator.open(uri)
    return if (result.usedSegmented) {
      segmentedHttpCache = accelerator
      segmentedSourceUrl = uri
      PlaybackSessionLog.i(
        "SEG",
        "opened multi-conn playPath=${PlaybackSessionLog.redactUrl(result.playPath)} " +
          "src=${PlaybackSessionLog.redactUrl(uri)} connections=$connections chunkKb=$chunkKb " +
          "proxy=${systemProxy?.mpvHttpProxyUrl ?: "none"}",
      )
      result.playPath
    } else {
      accelerator.deleteCache()
      PlaybackSessionLog.i(
        "SEG",
        "fallback direct (not segmented) src=${PlaybackSessionLog.redactUrl(uri)}",
      )
      uri
    }
  }

  /**
   * Stop multi-conn proxy and delete all segmented media files under app cache.
   * Called on player exit, media switch, and end-of-file so app data does not grow.
   */
  private fun clearSegmentedPlaybackCache() {
    stopSegmentedWatchdog()
    segmentedHttpCache?.deleteCache()
    segmentedHttpCache = null
    segmentedSourceUrl = null
    purgeSegmentedHttpCacheDir()
  }

  private fun purgeSegmentedHttpCacheDir() {
    val root = File(cacheDir, "segmented-http")
    if (!root.exists()) return
    root.listFiles()?.forEach { child ->
      runCatching {
        if (child.isDirectory) child.deleteRecursively() else child.delete()
      }
    }
  }

  private fun startSegmentedWatchdog() {
    if (segmentedWatchdogJob?.isActive == true) return
    segmentedWatchdogJob = lifecycleScope.launch { runSegmentedWatchdog() }
  }

  private suspend fun runSegmentedWatchdog() {
    var lastPos = viewModel.pos ?: 0
    var lastPlaybackProgressAtMs = System.currentTimeMillis()
    while (!player.isExiting) {
      delay(SEGMENTED_WATCHDOG_INTERVAL_MS)
      val state = currentSegmentedWatchdogState(lastPlaybackProgressAtMs) ?: return
      val position = state.position
      if (position != null && position > lastPos) {
        lastPos = position
        lastPlaybackProgressAtMs = state.now
      }
      if (!shouldReconnectSegmentedCache(state)) continue
      PlaybackSessionLog.w(
        "SEG",
        "stall detected pos=$position duration=${state.duration} " +
          "downloaded=${state.snapshot.downloadedBytes}/${state.snapshot.totalSize} " +
          "lastWriteAgeMs=${state.now - state.snapshot.lastWriteAtMs} " +
          "progressAgeMs=${state.now - state.lastPlaybackProgressAtMs}",
      )
      PlaybackSessionLog.snapshotPlayback("SEG-STALL")
      reconnectSegmentedCache(position ?: 0)
      lastSegmentedReconnectAtMs = state.now
      return
    }
  }

  private fun currentSegmentedWatchdogState(lastPlaybackProgressAtMs: Long): SegmentedWatchdogState? {
    val cache = segmentedHttpCache
    val snapshot = cache?.snapshot()
    return if (cache != null && snapshot != null) {
      SegmentedWatchdogState(
        cache = cache,
        snapshot = snapshot,
        position = viewModel.pos,
        duration = viewModel.duration,
        now = System.currentTimeMillis(),
        lastPlaybackProgressAtMs = lastPlaybackProgressAtMs,
      )
    } else {
      null
    }
  }

  private fun shouldSkipSegmentedWatchdog(state: SegmentedWatchdogState): Boolean {
    val inactive = viewModel.paused == true ||
      state.duration == null ||
      state.duration <= 0 ||
      state.position == null ||
      state.snapshot.fullyCached ||
      !state.snapshot.running
    val freshWrite = state.now - state.snapshot.lastWriteAtMs < SEGMENTED_STALL_TIMEOUT_MS
    val progressing = state.now - state.lastPlaybackProgressAtMs < SEGMENTED_STALL_TIMEOUT_MS
    return inactive || freshWrite || progressing
  }

  private fun hasSegmentedCacheAhead(state: SegmentedWatchdogState): Boolean {
    val duration = state.duration
    val position = state.position
    val byteOffset = if (duration != null && position != null) {
      val ratio = state.snapshot.totalSize.toDouble() * position.toDouble()
      (ratio / duration.toDouble()).toLong().coerceIn(0L, state.snapshot.totalSize)
    } else {
      0L
    }
    return duration == null ||
      position == null ||
      state.cache.cachedAheadFrom(byteOffset) >= SEGMENTED_MIN_CACHED_AHEAD_BYTES
  }

  private fun shouldReconnectSegmentedCache(state: SegmentedWatchdogState): Boolean {
    val cooledDown = state.now - lastSegmentedReconnectAtMs >= SEGMENTED_RECONNECT_COOLDOWN_MS
    return !shouldSkipSegmentedWatchdog(state) &&
      cooledDown &&
      !segmentedReconnectInProgress &&
      !hasSegmentedCacheAhead(state)
  }

  private fun stopSegmentedWatchdog() {
    segmentedWatchdogJob?.cancel()
    segmentedWatchdogJob = null
    segmentedReconnectInProgress = false
    segmentedPendingSeek = null
  }

  private fun reconnectSegmentedCache(position: Int) {
    val source = segmentedSourceUrl ?: return
    segmentedReconnectInProgress = true
    segmentedWatchdogJob?.cancel()
    segmentedWatchdogJob = null
    PlaybackSessionLog.w(
      "SEG",
      "reconnect begin pos=$position src=${PlaybackSessionLog.redactUrl(source)}",
    )
    Toast.makeText(this, "网络卡住，正在重新连接", Toast.LENGTH_SHORT).show()
    lifecycleScope.launch {
      val oldCache = segmentedHttpCache
      segmentedHttpCache = null
      val reopened = withContext(Dispatchers.IO) {
        oldCache?.deleteCache()
        purgeSegmentedHttpCacheDir()
        val connections = networkPreferences.multiConnectionCount.get().coerceIn(2, 16)
        val chunkKb = networkPreferences.multiConnectionChunkKb.get().coerceIn(256, 4096)
        val cacheRoot = File(cacheDir, "segmented-http").also { it.mkdirs() }
        val systemProxy = resolveSystemHttpProxy()
        val requestHeaders = playbackHttpHeaders(source, intent.extras)
        val accelerator = SegmentedHttpCache(
          cacheDir = cacheRoot,
          connections = connections,
          chunkBytes = chunkKb * 1024,
          userAgent = requestHeaders.userAgentOrDefault(),
          requestHeaders = requestHeaders,
          systemProxy = systemProxy,
          limitConnectionsUnderProxy = true,
        )
        val result = accelerator.open(source)
        if (result.usedSegmented) {
          accelerator to result.playPath
        } else {
          accelerator.deleteCache()
          null to null
        }
      }
      val cache = reopened.first
      val playPath = reopened.second
      if (cache == null || playPath == null) {
        PlaybackSessionLog.e(
          "SEG",
          "reconnect failed — multi-conn reopen unsuccessful src=${PlaybackSessionLog.redactUrl(source)}",
        )
        segmentedSourceUrl = source
        segmentedReconnectInProgress = false
        startSegmentedWatchdog()
        return@launch
      }
      PlaybackSessionLog.i(
        "SEG",
        "reconnect ok playPath=${PlaybackSessionLog.redactUrl(playPath)} seekTo=$position",
      )
      segmentedHttpCache = cache
      segmentedSourceUrl = source
      segmentedPendingSeek = position
      playUri(playPath, useLoadfileCommand = true)
      // The watchdog restarts after the reloaded file is actually loaded.
      startSegmentedWatchdog()
    }
  }

  private fun onSegmentedReloaded() {
    val seekPosition = segmentedPendingSeek ?: run {
      segmentedReconnectInProgress = false
      if (segmentedHttpCache != null) startSegmentedWatchdog()
      return
    }
    segmentedPendingSeek = null
    segmentedReconnectInProgress = false
    PlaybackSessionLog.i("SEG", "post-reconnect seek absolute=$seekPosition")
    MPVLib.command("seek", seekPosition.toString(), "absolute+keyframes")
    startSegmentedWatchdog()
  }

  private fun String.isLocalSegmentedProxy(): Boolean = startsWith("http://127.0.0.1:") ||
    startsWith("http://localhost:")

  override fun onDestroy() {
    Log.d(TAG, "Exiting")
    PlaybackSessionLog.snapshotPlayback("EXIT")
    stopPlaybackLogHeartbeat()
    audioFocusRequest?.let {
      AudioManagerCompat.abandonAudioFocusRequest(audioManager, it)
    }
    audioFocusRequest = null
    mediaSession?.release()
    if (noisyReceiver.initialized) {
      unregisterReceiver(noisyReceiver)
      noisyReceiver.initialized = false
    }
    endBackgroundPlayback()

    player.isExiting = true
    if (isFinishing) {
      MPVLib.command("stop")
    }
    MPVLib.removeObserver(playerObserver)
    MPVLib.destroy()
    // Always wipe segmented multi-conn data when leaving the player.
    clearSegmentedPlaybackCache()
    PlaybackSessionLog.endSession(reason = "PlayerActivity.onDestroy finishing=$isFinishing")

    super.onDestroy()
  }

  override fun onPause() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
      !isInPictureInPictureMode &&
      !playerPreferences.automaticBackgroundPlayback.get()
    ) {
      viewModel.pause()
    }
    saveVideoPlaybackState(fileName)
    super.onPause()
  }

  override fun finish() {
    setReturnIntent()
    super.finish()
  }

  override fun onStop() {
    saveVideoPlaybackState(fileName)
    if (!isFinishing && !serviceBound && playerPreferences.automaticBackgroundPlayback.get()) {
      startBackgroundPlayback()
    } else {
      viewModel.pause()
      if (serviceBound) {
        unbindService(serviceConnection)
        serviceBound = false
      }
    }
    window.attributes.screenBrightness.let {
      if (playerPreferences.rememberBrightness.get() && it != -1f) {
        playerPreferences.defaultBrightness.set(it)
      }
    }
    super.onStop()
  }

  @SuppressLint("NewApi")
  override fun onUserLeaveHint() {
    if (isPipSupported && viewModel.paused == false && playerPreferences.automaticallyEnterPip.get()) {
      enterPictureInPictureMode()
    }
    super.onUserLeaveHint()
  }

  @SuppressLint("NewApi")
  override fun onBackPressed() {
    if (isPipSupported && viewModel.paused == false && playerPreferences.automaticallyEnterPip.get()) {
      if (viewModel.sheetShown.value == Sheets.None && viewModel.panelShown.value == Panels.None) {
        enterPictureInPictureMode()
      }
    } else {
      super.onBackPressed()
    }
  }

  override fun onStart() {
    super.onStart()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPipSupported) {
      setPictureInPictureParams(createPipParams())
    }
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    binding.root.systemUiVisibility =
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
      View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
      View.SYSTEM_UI_FLAG_LOW_PROFILE
    windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
    windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes.layoutInDisplayCutoutMode = if (playerPreferences.drawOverDisplayCutout.get()) {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      } else {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
      }
    }

    if (playerPreferences.rememberBrightness.get()) {
      playerPreferences.defaultBrightness.get().let {
        if (it != -1f) viewModel.changeBrightnessTo(it)
      }
    }

    if (serviceBound) {
      endBackgroundPlayback()
    }
    if (segmentedHttpCache != null) {
      startSegmentedWatchdog()
    }
  }

  private fun copyMPVAssets() {
    Utils.copyAssets(this@PlayerActivity)
    copyMPVScripts()
    copyMPVConfigFiles()
    // fonts can be lazily loaded
    lifecycleScope.launch(Dispatchers.IO) {
      copyMPVFonts()
    }
  }

  private fun setupMPV() {
    copyMPVAssets()
    player.initialize(filesDir.path, cacheDir.path)
    MPVLib.addObserver(playerObserver)
  }

  private fun setupAudio() {
    applyAudioChannels(audioPreferences.audioChannels.get())

    val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN).also {
      it.setAudioAttributes(
        AudioAttributesCompat.Builder().setUsage(AudioAttributesCompat.USAGE_MEDIA)
          .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC).build(),
      )
      it.setOnAudioFocusChangeListener(audioFocusChangeListener)
    }.build()
    AudioManagerCompat.requestAudioFocus(audioManager, request).let {
      if (it == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return@let
      audioFocusRequest = request
    }
  }

  private fun copyMPVConfigFiles() {
    val applicationPath = filesDir.path
    try {
      val mpvConf = fileManager.fromUri(advancedPreferences.mpvConfStorageUri.get().toUri())
        ?: error("User hasn't set any mpvConfig directory")
      if (!fileManager.exists(mpvConf)) error("Couldn't access mpv configuration directory")
      fileManager.copyDirectoryWithContent(mpvConf, fileManager.fromPath(applicationPath), true)
    } catch (e: Exception) {
      File("$applicationPath/mpv.conf")
        .also { if (!it.exists()) it.createNewFile() }
        .writeText(advancedPreferences.mpvConf.get())
      File("$applicationPath/input.conf")
        .also { if (!it.exists()) it.createNewFile() }
        .writeText(advancedPreferences.inputConf.get())
      Log.e("PlayerActivity", "Couldn't copy mpv configuration files: ${e.message}")
    }
  }

  private fun copyMPVScripts() {
    val mpvktLua = assets.open("mpvkt.lua")
    val applicationPath = filesDir.path

    val scriptsDir = fileManager.createDir(fileManager.fromPath(applicationPath), "scripts")!!

    fileManager.deleteContent(scriptsDir)

    File("$scriptsDir/mpvkt.lua")
      .also { if (!it.exists()) it.createNewFile() }
      .writeText(mpvktLua.bufferedReader().readText())
  }

  fun setupCustomButtons(buttons: List<CustomButtonEntity>) {
    val applicationPath = filesDir.path

    val scriptsDir = fileManager.createDir(fileManager.fromPath(applicationPath), "scripts")!!

    val customButtonsContent = buildString {
      appendLine("local lua_modules = mp.find_config_file('scripts')")
      appendLine("if lua_modules then")
      appendLine("package.path = package.path .. ';' .. lua_modules .. '/?.lua;' .. lua_modules .. '/?/init.lua'")
      appendLine("end")
      appendLine("local mpvkt = require 'mpvkt'")
      buttons.forEach { button ->
        appendLine("function button${button.id}()")
        appendLine(button.content)
        appendLine("end")
        appendLine("mp.register_script_message('call_button_${button.id}', button${button.id})")
        appendLine("function button${button.id}long()")
        appendLine(button.longPressContent)
        appendLine("end")
        appendLine("mp.register_script_message('call_button_${button.id}_long', button${button.id}long)")
      }
    }

    val file = File("$scriptsDir/custombuttons.lua")
      .also { if (!it.exists()) it.createNewFile() }

    file.writeText(customButtonsContent)

    MPVLib.command("load-script", file.absolutePath)
  }

  private fun copyMPVFonts() {
    try {
      val cachePath = cacheDir.path
      val fontsDir = fileManager.fromUri(subtitlesPreferences.fontsFolder.get().toUri())
        ?: error("User hasn't set any fonts directory")
      if (!fileManager.exists(fontsDir)) error("Couldn't access fonts directory")

      val destDir = fileManager.fromPath("$cachePath/fonts")
      if (!fileManager.exists(destDir)) fileManager.createDir(fileManager.fromPath(cachePath), "fonts")

      if (fileManager.findFile(destDir, "subfont.ttf") == null) {
        resources.assets.open("subfont.ttf")
          .copyTo(File("$cachePath/fonts/subfont.ttf").outputStream())
      }

      fileManager.copyDirectoryWithContent(fontsDir, destDir, false)
    } catch (e: Exception) {
      Log.e("PlayerActivity", "Couldn't copy fonts to application directory: ${e.message}")
    }
  }

  private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener {
    when (it) {
      AudioManager.AUDIOFOCUS_LOSS,
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
      -> {
        val oldRestore = restoreAudioFocus
        val wasPlayerPaused = viewModel.paused ?: false
        viewModel.pause()
        restoreAudioFocus = {
          oldRestore()
          if (!wasPlayerPaused) viewModel.unpause()
        }
      }

      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        MPVLib.command("multiply", "volume", "0.5")
        restoreAudioFocus = {
          MPVLib.command("multiply", "volume", "2")
        }
      }

      AudioManager.AUDIOFOCUS_GAIN -> {
        restoreAudioFocus()
        restoreAudioFocus = {}
      }

      AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
        Log.d("PlayerActivity", "didn't get audio focus")
      }
    }
  }

  override fun onResume() {
    super.onResume()

    viewModel.currentVolume.update {
      audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).also {
        if (it < viewModel.maxVolume) viewModel.changeMPVVolumeTo(100)
      }
    }
  }

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val binder = service as MediaPlaybackService.MediaPlaybackBinder
      mediaPlaybackService = binder.getService()
      serviceBound = true

      fileName.let { title ->
        val artist = MPVLib.getPropertyString("metadata/artist") ?: ""
        mediaPlaybackService?.setMediaInfo(title = title, artist = artist, thumbnail = MPVLib.grabThumbnail(1080))
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      mediaPlaybackService = null
      serviceBound = false
    }
  }

  private fun startBackgroundPlayback() {
    val intent = Intent(this, MediaPlaybackService::class.java)
    startService(intent)
    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
  }

  private fun endBackgroundPlayback() {
    if (serviceBound) {
      unbindService(serviceConnection)
      serviceBound = false
    }
    stopService(Intent(this, MediaPlaybackService::class.java))
    mediaPlaybackService = null
  }

  private fun setIntentExtras(extras: Bundle?) {
    if (extras == null) return

    extras.getString("title")?.let { MPVLib.setPropertyString("force-media-title", it) }
    MPVLib.setPropertyInt("time-pos", extras.getInt("position", 0) / 1000)

    // subtitles
    if (extras.containsKey("subs")) {
      val subList = Utils.getParcelableArray<Uri>(extras, "subs")
      val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

      for (suburi in subList) {
        val subfile = suburi.resolveUri(this) ?: continue
        val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

        Log.v(TAG, "Adding subtitles from intent extras: $subfile")
        MPVLib.command("sub-add", subfile, flag)
      }
    }

    // Keep headers in sync after FILE_LOADED (path may change via redirects).
    val path = MPVLib.getPropertyString("path").orEmpty()
    applyPlaybackHttpHeaders(path, extras)
  }

  /**
   * Apply HTTP headers for Emby/Jellyfin and external clients **before** network open.
   * Sources:
   * 1. Intent extras `headers` (name/value pairs)
   * 2. Query `api_key` / `X-Emby-Token` → `X-Emby-Token` header (common Emby stream URLs)
   */
  private fun applyPlaybackHttpHeaders(url: String, extras: Bundle?) {
    val headers = playbackHttpHeaders(url, extras)
    if (headers.isEmpty()) return
    headers.entries
      .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
      ?.let { MPVLib.setPropertyString("user-agent", it.value) }
    val headersString = headers
      .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
      .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
      .joinToString(",")
    if (headersString.isNotBlank()) {
      MPVLib.setPropertyString("http-header-fields", headersString)
      PlaybackSessionLog.i(
        "HTTP",
        "headers applied keys=${headers.keys.joinToString()} " +
          "url=${PlaybackSessionLog.redactUrl(url)}",
      )
    }
  }

  private fun playbackHttpHeaders(url: String, extras: Bundle?): Map<String, String> {
    val headers = intentHeaders(extras).toMutableMap()
    injectMediaServerAuthHeaders(url, headers)
    return headers
  }

  private fun intentHeaders(extras: Bundle?): Map<String, String> {
    val raw = extras?.getStringArray("headers") ?: return emptyMap()
    return raw.asSequence()
      .chunked(2)
      .filter { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
      .associate { it[0] to it[1] }
  }

  /**
   * Emby stream URLs usually put the token in `api_key=` query only.
   * Some servers still expect `X-Emby-Token` on the request; without it open fails
   * immediately with END_FILE and no duration.
   */
  private fun injectMediaServerAuthHeaders(url: String, headers: MutableMap<String, String>) {
    val lower = url.lowercase(Locale.US)
    val looksLikeEmby = lower.contains("/emby/") ||
      lower.contains("/jellyfin/") ||
      lower.contains("mediasourceid=") ||
      lower.contains("/videos/")
    val uri = if (looksLikeEmby) runCatching { url.toUri() }.getOrNull() else null
    val token = uri?.let { parsed ->
      sequenceOf("api_key", "ApiKey", "X-Emby-Token", "apiKey")
        .mapNotNull { key -> parsed.getQueryParameter(key)?.takeIf { it.isNotBlank() } }
        .firstOrNull()
    }
    val hasTokenHeader = headers.keys.any {
      it.equals("X-Emby-Token", ignoreCase = true) ||
        it.equals("X-MediaBrowser-Token", ignoreCase = true)
    }
    if (looksLikeEmby && token != null && !hasTokenHeader) {
      headers["X-Emby-Token"] = token
    }
  }

  private fun Map<String, String>.userAgentOrDefault(): String =
    entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value ?: DEFAULT_SEGMENTED_UA

  @Suppress("NestedBlockDepth")
  private fun parsePathFromIntent(intent: Intent): String? {
    return when (intent.action) {
      Intent.ACTION_VIEW -> intent.data?.resolveUri(this)
      Intent.ACTION_SEND -> {
        if (intent.hasExtra(Intent.EXTRA_STREAM)) {
          intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!.resolveUri(this)
        } else {
          intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            val uri = it.trim().toUri()
            if (uri.isHierarchical && !uri.isRelative) uri.resolveUri(this) else null
          }
        }
      }

      else -> intent.getStringExtra("uri")
    }
  }

  private fun getFileName(intent: Intent): String {
    val uri = if (intent.type == "text/plain") {
      intent.getStringExtra(Intent.EXTRA_TEXT)!!.toUri()
    } else {
      (intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && uri != null) {
      val cursor = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null)
      if (cursor?.moveToFirst() == true) return cursor.getString(0).also { cursor.close() }
    }
    return uri?.lastPathSegment?.substringAfterLast("/") ?: uri?.path ?: ""
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      if (!isInPictureInPictureMode) {
        viewModel.changeVideoAspect(playerPreferences.videoAspect.get())
      } else {
        viewModel.hideControls()
      }
    }
    super.onConfigurationChanged(newConfig)
  }

  // a bunch of observers
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(property: String, value: Long) {
    if (player.isExiting) return
  }

  @Suppress("UnusedParameter")
  internal fun onObserverEvent(property: String) {
    if (player.isExiting) return
  }

  internal fun onObserverEvent(property: String, value: Boolean) {
    if (player.isExiting) return
    when (property) {
      "pause" if value -> {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PlaybackSessionLog.i("MPV", "pause=true")
        PlaybackSessionLog.snapshotPlayback("PAUSE")
      }
      "pause" -> {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PlaybackSessionLog.i("MPV", "pause=false")
      }
      "paused-for-cache" -> {
        if (lastPausedForCache != value) {
          lastPausedForCache = value
          if (value) {
            PlaybackSessionLog.w("MPV", "paused-for-cache=true (buffering / may stall)")
            PlaybackSessionLog.snapshotPlayback("BUFFER")
          } else {
            PlaybackSessionLog.i("MPV", "paused-for-cache=false (buffer recovered)")
          }
        }
      }
      "eof-reached" if value -> {
        if (lastEofReached != true) {
          lastEofReached = true
          PlaybackSessionLog.w("MPV", "eof-reached=true")
          PlaybackSessionLog.snapshotPlayback("EOF")
        }
        // Sparse multi-conn proxy can short-close a body → lavf reports EOF mid-movie.
        // Recover instead of wiping cache when we are clearly not at the real end.
        if (tryRecoverSegmentedFalseEof()) {
          lastEofReached = false
          return
        }
        clearSegmentedPlaybackCache()
        if (playerPreferences.closeAfterReachingEndOfVideo.get()) finishAndRemoveTask()
      }
      "eof-reached" -> lastEofReached = value
    }
  }

  /**
   * Mid-file `eof-reached` under segmented multi-conn is almost always a short proxy
   * body (hole at playhead), not the real end of the movie.
   */
  private fun tryRecoverSegmentedFalseEof(): Boolean {
    val position = viewModel.pos
    val duration = viewModel.duration
    val now = System.currentTimeMillis()
    val midFile = position != null && duration != null && duration > 5 && position < duration - 3
    val coolingDown = now - lastSegmentedReconnectAtMs < SEGMENTED_RECONNECT_COOLDOWN_MS
    val ready = segmentedHttpCache != null &&
      segmentedSourceUrl != null &&
      !segmentedReconnectInProgress &&
      midFile &&
      !coolingDown
    if (!ready) {
      if (segmentedHttpCache != null && midFile && coolingDown) {
        PlaybackSessionLog.w(
          "SEG",
          "false EOF at pos=$position/$duration but reconnect cooling down",
        )
      }
      return false
    }
    PlaybackSessionLog.w(
      "SEG",
      "false EOF at pos=$position/$duration — reconnect segmented multi-conn",
    )
    // midFile guarantees non-null position.
    reconnectSegmentedCache(requireNotNull(position))
    lastSegmentedReconnectAtMs = now
    return true
  }

  internal fun onObserverEvent(property: String, value: String) {
    if (player.isExiting) return
    when (property) {
      "video-params/gamma", "video-params/pixelformat", "video-codec" ->
        maybeApplyAdaptiveDecoder("prop:$property")
    }
    when (property.substringBeforeLast("/")) {
      "user-data/mpvkt" -> viewModel.handleLuaInvocation(property, value)
    }
  }

  @Suppress("UnusedParameter")
  internal fun onObserverEvent(property: String, value: MPVNode) {
    if (player.isExiting) return
  }

  @SuppressLint("NewApi")
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(property: String, value: Double) {
    if (player.isExiting) return
    when (property) {
      "video-params/aspect" -> if (isPipSupported) createPipParams()
    }
  }

  /**
   * Evaluate adaptive decoder once metadata is available.
   * Re-runs if the stream signature changes (e.g. late gamma/DoVi props).
   */
  private fun maybeApplyAdaptiveDecoder(source: String): Boolean {
    var ready = false
    val info = if (player.isExiting) {
      null
    } else {
      runCatching { AdaptiveDecoderSelector.probeStreamInfo() }.getOrNull()
    }
    if (info?.hasEnoughMetadata() == true) {
      val signature = adaptiveDecoderSignature(info)
      ready = adaptiveDecoderAppliedForFile && signature == lastAdaptiveDecoderSignature
      if (!ready) {
        ready = runCatching { player.applyAdaptiveDecoderIfNeeded() }.getOrDefault(false)
        if (ready) {
          adaptiveDecoderAppliedForFile = true
          lastAdaptiveDecoderSignature = signature
          Log.i(TAG, "Adaptive decoder via $source: $signature")
        }
      }
    }
    return ready
  }

  private fun adaptiveDecoderSignature(info: AdaptiveDecoderSelector.StreamInfo): String = listOf(
    info.doviProfile?.toString().orEmpty(),
    info.gamma,
    info.pixfmt,
    info.codec,
    info.width?.toString().orEmpty(),
    info.height?.toString().orEmpty(),
    info.isProfile5.toString(),
    info.isHdr.toString(),
    info.isHighBitDepth.toString(),
  ).joinToString("|")

  internal fun event(eventId: Int) {
    if (player.isExiting) return
    when (eventId) {
      MPVLib.mpvEventId.MPV_EVENT_START_FILE -> {
        PlaybackSessionLog.i("MPV", "event=START_FILE")
        lastEofReached = false
      }
      MPVLib.mpvEventId.MPV_EVENT_FILE_LOADED -> onFileLoadedEvent()
      MPVLib.mpvEventId.MPV_EVENT_END_FILE -> logMpvLifecycle("END_FILE", warn = true)
      MPVLib.mpvEventId.MPV_EVENT_IDLE -> logMpvLifecycle("IDLE", warn = true)
      MPVLib.mpvEventId.MPV_EVENT_SEEK -> PlaybackSessionLog.d("MPV", "event=SEEK")
      MPVLib.mpvEventId.MPV_EVENT_PLAYBACK_RESTART -> {
        player.isExiting = false
        logMpvLifecycle("PLAYBACK_RESTART", snapshotTag = "RESTART")
      }
      MPVLib.mpvEventId.MPV_EVENT_SHUTDOWN -> PlaybackSessionLog.w("MPV", "event=SHUTDOWN")
      MPVLib.mpvEventId.MPV_EVENT_QUEUE_OVERFLOW -> {
        PlaybackSessionLog.e("MPV", "event=QUEUE_OVERFLOW")
      }
    }
  }

  private fun logMpvLifecycle(
    eventName: String,
    warn: Boolean = false,
    snapshotTag: String = eventName,
  ) {
    if (warn) {
      PlaybackSessionLog.w("MPV", "event=$eventName")
    } else {
      PlaybackSessionLog.i("MPV", "event=$eventName")
    }
    PlaybackSessionLog.snapshotPlayback(snapshotTag)
  }

  private fun onFileLoadedEvent() {
    fileName = getFileName(intent)
    setIntentExtras(intent.extras)
    val mediaTitle = MPVLib.getPropertyString("media-title")
    if (mediaTitle.isNullOrBlank() || mediaTitle.isDigitsOnly()) {
      MPVLib.setPropertyString("media-title", fileName)
    }
    PlaybackSessionLog.i("MPV", "event=FILE_LOADED title=$fileName mediaTitle=$mediaTitle")
    PlaybackSessionLog.snapshotPlayback("FILE_LOADED")
    lifecycleScope.launch(Dispatchers.IO) { loadVideoPlaybackState(fileName) }
    onSegmentedReloaded()
    setOrientation()
    viewModel.changeVideoAspect(playerPreferences.videoAspect.get())
    adaptiveDecoderAppliedForFile = false
    lastAdaptiveDecoderSignature = null
    // video-params may populate slightly after FILE_LOADED; retry a few times.
    lifecycleScope.launch {
      repeat(8) { attempt ->
        if (player.isExiting) return@launch
        if (maybeApplyAdaptiveDecoder("file-loaded#$attempt")) return@launch
        delay(200L * (attempt + 1))
      }
      if (!adaptiveDecoderAppliedForFile) {
        Log.w(TAG, "Adaptive decoder: stream metadata not ready after retries")
      }
    }
  }

  private fun saveVideoPlaybackState(mediaTitle: String) {
    if (mediaTitle.isBlank() || player.isExiting) return
    val snapshot = runCatching {
      PlaybackStateSnapshot(
        pos = viewModel.pos ?: 0,
        duration = viewModel.duration ?: 0,
        playbackSpeed = MPVLib.getPropertyDouble("speed") ?: 1.0,
        sid = player.sid,
        subDelay = ((MPVLib.getPropertyDouble("sub-delay") ?: 0.0) * 1000).toInt(),
        subSpeed = MPVLib.getPropertyDouble("sub-speed") ?: subtitlesPreferences.defaultSubSpeed.get().toDouble(),
        secondarySid = player.secondarySid,
        secondarySubDelay = ((MPVLib.getPropertyDouble("secondary-sub-delay") ?: 0.0) * 1000).toInt(),
        aid = player.aid,
        audioDelay = ((MPVLib.getPropertyDouble("audio-delay") ?: 0.0) * 1000).toInt(),
      )
    }.getOrElse { error ->
      Log.e(TAG, "Couldn't read playback state: ${error.message}")
      return
    }

    lifecycleScope.launch(Dispatchers.IO) {
      val oldState = playbackStateRepository.getVideoDataByTitle(fileName)
      Log.d(TAG, "Saving playback state")
      playbackStateRepository.upsert(
        PlaybackStateEntity(
          mediaTitle = mediaTitle,
          lastPosition = if (playerPreferences.savePositionOnQuit.get()) {
            if (snapshot.pos < snapshot.duration - 1) snapshot.pos else 0
          } else {
            oldState?.lastPosition ?: 0
          },
          playbackSpeed = snapshot.playbackSpeed,
          sid = snapshot.sid,
          subDelay = snapshot.subDelay,
          subSpeed = snapshot.subSpeed,
          secondarySid = snapshot.secondarySid,
          secondarySubDelay = snapshot.secondarySubDelay,
          aid = snapshot.aid,
          audioDelay = snapshot.audioDelay,
        ),
      )
    }
  }

  private suspend fun loadVideoPlaybackState(mediaTitle: String) {
    if (mediaTitle.isBlank()) return
    val state = playbackStateRepository.getVideoDataByTitle(mediaTitle)
    val getDelay: (Int, Int?) -> Double = { preferenceDelay, stateDelay ->
      (stateDelay ?: preferenceDelay) / 1000.0
    }
    val subDelay = getDelay(subtitlesPreferences.defaultSubDelay.get(), state?.subDelay)
    val secondarySubDelay = getDelay(subtitlesPreferences.defaultSecondarySubDelay.get(), state?.secondarySubDelay)
    val audioDelay = getDelay(audioPreferences.defaultAudioDelay.get(), state?.audioDelay)
    state?.let {
      player.sid = it.sid
      player.secondarySid = it.secondarySid
      player.aid = it.aid
      MPVLib.setPropertyDouble("sub-delay", subDelay)
      MPVLib.setPropertyDouble("secondary-sub-delay", secondarySubDelay)
      MPVLib.setPropertyDouble("speed", it.playbackSpeed)
      MPVLib.setPropertyDouble("audio-delay", audioDelay)
    }
    if (playerPreferences.savePositionOnQuit.get()) {
      state?.lastPosition?.let { if (it != 0) MPVLib.setPropertyInt("time-pos", it) }
    }
    MPVLib.setPropertyDouble("sub-speed", state?.subSpeed ?: subtitlesPreferences.defaultSubSpeed.get().toDouble())
  }

  private fun setReturnIntent() {
    Log.d(TAG, "setting return intent")
    setResult(
      RESULT_OK,
      Intent(RESULT_INTENT).apply {
        viewModel.pos?.let { putExtra("position", it * 1000) }
        viewModel.duration?.let { putExtra("duration", it * 1000) }
      },
    )
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    // Switching media: stop previous multi-conn session and delete its cache files.
    clearSegmentedPlaybackCache()
    startPlayback(intent, useLoadfileCommand = true)
  }

  @RequiresApi(Build.VERSION_CODES.O)
  fun createPipParams(): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      builder.setTitle(MPVLib.getPropertyString("media-title") ?: fileName)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val autoEnter = playerPreferences.automaticallyEnterPip.get()
      builder.setAutoEnterEnabled(viewModel.paused == false && autoEnter)
      builder.setSeamlessResizeEnabled(viewModel.paused == false && autoEnter)
    }
    builder.setActions(createPipActions(this, viewModel.paused == true))
    pipRect?.let { builder.setSourceRectHint(it) }
    val aspect = player.getVideoOutAspect()
    MPVLib.getPropertyInt("video-params/h")?.let {
      val height = it
      if (aspect != null && aspect > 0.0 && height > 0) {
        val width = height * aspect
        val rational = Rational(height, width.toInt()).toFloat()
        if (rational in 0.42..2.38) builder.setAspectRatio(Rational(width.toInt(), height))
      }
    }
    return builder.build()
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    if (!isInPictureInPictureMode) {
      pipReceiver?.let {
        unregisterReceiver(pipReceiver)
        pipReceiver = null
      }
      super.onPictureInPictureModeChanged(false, newConfig)
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      setPictureInPictureParams(createPipParams())
    }
    viewModel.hideControls()
    viewModel.hideSeekBar()
    viewModel.isBrightnessSliderShown.update { false }
    viewModel.isVolumeSliderShown.update { false }
    viewModel.sheetShown.update { Sheets.None }
    pipReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || intent.action != PIP_INTENTS_FILTER) return
        when (intent.getIntExtra(PIP_INTENT_ACTION, 0)) {
          PIP_PAUSE -> viewModel.pause()
          PIP_PLAY -> viewModel.unpause()
          PIP_FF -> viewModel.handleRightDoubleTap()
          PIP_FR -> viewModel.handleLeftDoubleTap()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          setPictureInPictureParams(createPipParams())
        }
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER), RECEIVER_NOT_EXPORTED)
    } else {
      registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER))
    }
    super.onPictureInPictureModeChanged(true, newConfig)
  }

  private fun setOrientation() {
    requestedOrientation = when (playerPreferences.orientation.get()) {
      PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
      PlayerOrientation.Video -> if ((player.getVideoOutAspect() ?: 0.0) > 1.0) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      }

      PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
      PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
      PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    when (keyCode) {
      KeyEvent.KEYCODE_VOLUME_UP -> {
        viewModel.changeVolumeBy(1)
        viewModel.displayVolumeSlider()
      }

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        viewModel.changeVolumeBy(-1)
        viewModel.displayVolumeSlider()
      }

      KeyEvent.KEYCODE_DPAD_RIGHT -> viewModel.handleLeftDoubleTap()
      KeyEvent.KEYCODE_DPAD_LEFT -> viewModel.handleRightDoubleTap()
      KeyEvent.KEYCODE_SPACE -> viewModel.pauseUnpause()
      KeyEvent.KEYCODE_MEDIA_STOP -> finishAndRemoveTask()

      KeyEvent.KEYCODE_MEDIA_REWIND -> viewModel.handleLeftDoubleTap()
      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> viewModel.handleRightDoubleTap()

      // other keys should be bound by the user in input.conf ig
      else -> {
        event?.let { player.onKey(it) }
        super.onKeyDown(keyCode, event)
      }
    }
    return true
  }

  override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
    if (player.onKey(event!!)) return true
    return super.onKeyUp(keyCode, event)
  }

  private fun setupMediaSession() {
    val previousAction = gesturePreferences.mediaPreviousGesture.get()
    val playAction = gesturePreferences.mediaPlayGesture.get()
    val nextAction = gesturePreferences.mediaNextGesture.get()

    mediaSession = MediaSession(this, "PlayerActivity").apply {
      setCallback(
        object : MediaSession.Callback() {
          override fun onPlay() {
            when (playAction) {
              SingleActionGesture.None -> {}
              SingleActionGesture.Seek -> {}
              SingleActionGesture.PlayPause -> {
                super.onPlay()
                viewModel.unpause()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
              }

              SingleActionGesture.Custom -> {
                MPVLib.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
              }
            }
          }

          override fun onPause() {
            when (playAction) {
              SingleActionGesture.None -> {}
              SingleActionGesture.Seek -> {}
              SingleActionGesture.PlayPause -> {
                super.onPause()
                viewModel.pause()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
              }

              SingleActionGesture.Custom -> {
                MPVLib.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
              }
            }
          }

          override fun onSkipToPrevious() {
            when (previousAction) {
              SingleActionGesture.None -> {}
              SingleActionGesture.Seek -> {
                viewModel.leftSeek()
              }

              SingleActionGesture.PlayPause -> {
                viewModel.pauseUnpause()
              }

              SingleActionGesture.Custom -> {
                MPVLib.command("keypress", CustomKeyCodes.MediaPrevious.keyCode)
              }
            }
          }

          override fun onSkipToNext() {
            when (nextAction) {
              SingleActionGesture.None -> {}
              SingleActionGesture.Seek -> {
                viewModel.rightSeek()
              }

              SingleActionGesture.PlayPause -> {
                viewModel.pauseUnpause()
              }

              SingleActionGesture.Custom -> {
                MPVLib.command("keypress", CustomKeyCodes.MediaNext.keyCode)
              }
            }
          }

          override fun onStop() {
            super.onStop()
            isActive = false
            this@PlayerActivity.onStop()
          }
        },
      )
      setPlaybackState(
        PlaybackState.Builder()
          .setActions(
            PlaybackState.ACTION_PLAY or
              PlaybackState.ACTION_PAUSE or
              PlaybackState.ACTION_STOP or
              PlaybackState.ACTION_SKIP_TO_PREVIOUS or
              PlaybackState.ACTION_SKIP_TO_NEXT,
          )
          .build(),
      )
      isActive = true
    }

    val filter = IntentFilter().apply { addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY) }
    registerReceiver(noisyReceiver, filter)
    noisyReceiver.initialized = true
  }

  companion object {
    // action of result intent
    private const val RESULT_INTENT = "live.mehiz.mpvkt.ui.player.PlayerActivity.result"
    private const val SEGMENTED_WATCHDOG_INTERVAL_MS = 1_000L
    private const val SEGMENTED_STALL_TIMEOUT_MS = 10_000L
    private const val SEGMENTED_MIN_CACHED_AHEAD_BYTES = 256L * 1024L
    private const val SEGMENTED_RECONNECT_COOLDOWN_MS = 20_000L
    private const val DEFAULT_SEGMENTED_UA =
      "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
  }
}

const val TAG = "mpvKt"

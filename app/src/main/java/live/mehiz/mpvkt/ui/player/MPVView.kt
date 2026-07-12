package live.mehiz.mpvkt.ui.player

import android.content.Context
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPVLib
import live.mehiz.mpvkt.preferences.AdvancedPreferences
import live.mehiz.mpvkt.preferences.AudioPreferences
import live.mehiz.mpvkt.preferences.DecoderPreferences
import live.mehiz.mpvkt.preferences.NetworkPreferences
import live.mehiz.mpvkt.preferences.PlayerPreferences
import live.mehiz.mpvkt.preferences.SubtitlesPreferences
import live.mehiz.mpvkt.ui.player.controls.components.panels.toColorHexString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.reflect.KProperty

@Suppress("TooManyFunctions")
class MPVView(context: Context, attributes: AttributeSet) : BaseMPVView(context, attributes), KoinComponent {

  private val audioPreferences: AudioPreferences by inject()
  private val playerPreferences: PlayerPreferences by inject()
  private val decoderPreferences: DecoderPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val networkPreferences: NetworkPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  var isExiting = false

  /**
   * Returns the video aspect ratio. Rotation is taken into account.
   */
  fun getVideoOutAspect(): Double? {
    return MPVLib.getPropertyDouble("video-params/aspect")?.let {
      if (it < 0.001) return 0.0
      if ((MPVLib.getPropertyInt("video-params/rotate") ?: 0) % 180 == 90) 1.0 / it else it
    }
  }

  class TrackDelegate(private val name: String) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Int {
      val v = MPVLib.getPropertyString(name)
      // we can get null here for "no" or other invalid value
      return v?.toIntOrNull() ?: -1
    }
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
      if (value == -1) MPVLib.setPropertyString(name, "no") else MPVLib.setPropertyInt(name, value)
    }
  }

  var sid: Int by TrackDelegate("sid")
  var secondarySid: Int by TrackDelegate("secondary-sid")
  var aid: Int by TrackDelegate("aid")

  override fun initOptions() {
    setVo(if (decoderPreferences.gpuNext.get()) "gpu-next" else "gpu")
    MPVLib.setOptionString("profile", "fast")
    MPVLib.setOptionString("hwdec", if (decoderPreferences.tryHWDecoding.get()) "auto" else "no")

    if (decoderPreferences.useYUV420P.get()) {
      MPVLib.setOptionString("vf", "format=yuv420p")
    }
    MPVLib.setOptionString("msg-level", "all=" + if (advancedPreferences.verboseLogging.get()) "v" else "warn")

    MPVLib.setPropertyBoolean("keep-open", true)
    MPVLib.setPropertyBoolean("input-default-bindings", true)

    // Keep TLS defaults identical to upstream mpvKt (safe for HTTPS streams).
    MPVLib.setOptionString("tls-verify", "yes")
    MPVLib.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")

    // Demuxer cache + conservative stream reconnect for long-pause resume.
    setupNetworkAndCacheOptions()

    val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    screenshotDir.mkdirs()
    MPVLib.setOptionString("screenshot-directory", screenshotDir.path)

    VideoFilters.entries.forEach {
      MPVLib.setOptionString(it.mpvProperty, it.preference(decoderPreferences).get().toString())
    }

    MPVLib.setOptionString("speed", playerPreferences.defaultSpeed.get().toString())
    // workaround for <https://github.com/mpv-player/mpv/issues/14651>
    MPVLib.setOptionString("vd-lavc-film-grain", "cpu")

    setupSubtitlesOptions()
    setupAudioOptions()
  }

  /**
   * User-tunable demuxer cache + decoder threads + conservative stream reconnect.
   *
   * Long-pause resume depends on FFmpeg reconnect options: after idle, CDN/HTTP
   * keep-alive sockets die and plain unpause cannot continue without reconnect.
   * Avoid CDN-hostile flags like reconnect_on_http_error=4xx,5xx.
   */
  private fun setupNetworkAndCacheOptions() {
    val forwardCacheBytes =
      networkPreferences.demuxerMaxCacheMb.get().coerceIn(8, 512) * 1024L * 1024L
    val backCacheBytes =
      networkPreferences.demuxerMaxBackCacheMb.get().coerceIn(8, 512) * 1024L * 1024L
    // Same options upstream always set (with fixed 32/64 MiB).
    MPVLib.setOptionString("demuxer-max-bytes", forwardCacheBytes.toString())
    MPVLib.setOptionString("demuxer-max-back-bytes", backCacheBytes.toString())

    val readahead = networkPreferences.demuxerReadaheadSecs.get().coerceIn(0, 120)
    if (readahead > 0) {
      MPVLib.setOptionString("demuxer-readahead-secs", readahead.toString())
    }

    val cacheSecs = networkPreferences.cacheSecs.get().coerceIn(1, 300)
    MPVLib.setOptionString("cache-secs", cacheSecs.toString())
    MPVLib.setOptionString("cache", "yes")
    MPVLib.setOptionString("cache-pause", "yes")
    MPVLib.setOptionString(
      "cache-pause-initial",
      if (networkPreferences.cachePauseInitial.get()) "yes" else "no",
    )
    MPVLib.setOptionString(
      "cache-pause-wait",
      networkPreferences.cachePauseWaitSecs.get().coerceIn(0, 30).toString(),
    )
    MPVLib.setOptionString(
      "demuxer-seekable-cache",
      if (networkPreferences.demuxerSeekableCache.get()) "yes" else "no",
    )

    val vdThreads = networkPreferences.videoDecoderThreads.get().coerceIn(0, 16)
    if (vdThreads > 0) {
      MPVLib.setOptionString("vd-lavc-threads", vdThreads.toString())
    }
    val adThreads = networkPreferences.audioDecoderThreads.get().coerceIn(0, 8)
    if (adThreads > 0) {
      MPVLib.setOptionString("ad-lavc-threads", adThreads.toString())
    }

    if (networkPreferences.demuxerThread.get()) {
      MPVLib.setOptionString("demuxer-thread", "yes")
    }
    if (networkPreferences.prefetchPlaylist.get()) {
      MPVLib.setOptionString("prefetch-playlist", "yes")
    }

    val timeout = networkPreferences.networkTimeoutSecs.get().coerceIn(5, 300)
    MPVLib.setOptionString("network-timeout", timeout.toString())

    val streamBufferKb = networkPreferences.streamBufferSizeKb.get().coerceIn(16, 2048)
    MPVLib.setOptionString("stream-buffer-size", (streamBufferKb * 1024).toString())

    // Always enable conservative reconnect so long-paused HTTP/HLS can resume.
    MPVLib.setOptionString(
      "stream-lavf-o",
      listOf(
        "reconnect=1",
        "reconnect_streamed=1",
        "reconnect_on_network_error=1",
        "reconnect_delay_max=5",
        "rw_timeout=${timeout * 1_000_000L}",
      ).joinToString(","),
    )

    if (networkPreferences.optimizeForNetwork.get()) {
      MPVLib.setOptionString("force-seekable", "yes")
      MPVLib.setOptionString("hr-seek", "yes")
    }
    if (networkPreferences.preferHighestBandwidth.get()) {
      MPVLib.setOptionString("hls-bitrate", "max")
    }
  }

  override fun observeProperties() {
    for ((name, format) in observedProps) MPVLib.observeProperty(name, format)
  }

  override fun postInitOptions() {
    when (decoderPreferences.debanding.get()) {
      Debanding.None -> {}
      Debanding.CPU -> MPVLib.command("vf", "add", "@deband:gradfun=radius=12")
      Debanding.GPU -> MPVLib.setOptionString("deband", "yes")
    }

    advancedPreferences.enabledStatisticsPage.get().let {
      if (it != 0) {
        MPVLib.command("script-binding", "stats/display-stats-toggle")
        MPVLib.command("script-binding", "stats/display-page-$it")
      }
    }
  }

  @Suppress("ReturnCount")
  fun onKey(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_MULTIPLE || KeyEvent.isModifierKey(event.keyCode)) {
      return false
    }

    var mapped = KeyMapping[event.keyCode]
    if (mapped == null) {
      // Fallback to produced glyph
      if (!event.isPrintingKey) {
        if (event.repeatCount == 0) {
          Log.d(TAG, "Unmapped non-printable key ${event.keyCode}")
        }
        return false
      }

      val ch = event.unicodeChar
      if (ch.and(KeyCharacterMap.COMBINING_ACCENT) != 0) {
        return false // dead key
      }
      mapped = ch.toChar().toString()
    }

    if (event.repeatCount > 0) {
      return true // eat event but ignore it, mpv has its own key repeat
    }

    val mod: MutableList<String> = mutableListOf()
    event.isShiftPressed && mod.add("shift")
    event.isCtrlPressed && mod.add("ctrl")
    event.isAltPressed && mod.add("alt")
    event.isMetaPressed && mod.add("meta")

    val action = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
    mod.add(mapped)
    MPVLib.command(action, mod.joinToString("+"))

    return true
  }

  private val observedProps = mapOf(
    "pause" to MPVLib.mpvFormat.MPV_FORMAT_FLAG,
    "video-params/aspect" to MPVLib.mpvFormat.MPV_FORMAT_DOUBLE,
    "eof-reached" to MPVLib.mpvFormat.MPV_FORMAT_FLAG,

    "user-data/mpvkt/show_text" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    "user-data/mpvkt/toggle_ui" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    "user-data/mpvkt/show_panel" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    "user-data/mpvkt/set_button_title" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    "user-data/mpvkt/reset_button_title" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    "user-data/mpvkt/toggle_button" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    "user-data/mpvkt/seek_by" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    "user-data/mpvkt/seek_to" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    "user-data/mpvkt/seek_by_with_text" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    "user-data/mpvkt/seek_to_with_text" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    "user-data/mpvkt/software_keyboard" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
  )

  private fun setupAudioOptions() {
    MPVLib.setOptionString("alang", audioPreferences.preferredLanguages.get())
    MPVLib.setOptionString("audio-delay", (audioPreferences.defaultAudioDelay.get() / 1000.0).toString())
    MPVLib.setOptionString("audio-pitch-correction", audioPreferences.audioPitchCorrection.get().toString())
    MPVLib.setOptionString("volume-max", (audioPreferences.volumeBoostCap.get() + 100).toString())
  }

  // Setup
  private fun setupSubtitlesOptions() {
    MPVLib.setOptionString("slang", subtitlesPreferences.preferredLanguages.get())

    MPVLib.setOptionString("sub-fonts-dir", context.cacheDir.path + "/fonts/")
    MPVLib.setOptionString("sub-delay", (subtitlesPreferences.defaultSubDelay.get() / 1000.0).toString())
    MPVLib.setOptionString("sub-speed", subtitlesPreferences.defaultSubSpeed.get().toString())
    MPVLib.setOptionString(
      "secondary-sub-delay",
      (subtitlesPreferences.defaultSecondarySubDelay.get() / 1000.0).toString()
    )

    MPVLib.setOptionString("sub-font", subtitlesPreferences.font.get())
    if (subtitlesPreferences.overrideAssSubs.get()) {
      MPVLib.setOptionString("sub-ass-override", "force")
      MPVLib.setOptionString("sub-ass-justify", "yes")
    }
    MPVLib.setOptionString("sub-font-size", subtitlesPreferences.fontSize.get().toString())
    MPVLib.setOptionString("sub-bold", if (subtitlesPreferences.bold.get()) "yes" else "no")
    MPVLib.setOptionString("sub-italic", if (subtitlesPreferences.italic.get()) "yes" else "no")
    MPVLib.setOptionString("sub-justify", subtitlesPreferences.justification.get().value)
    MPVLib.setOptionString("sub-color", subtitlesPreferences.textColor.get().toColorHexString())
    MPVLib.setOptionString("sub-back-color", subtitlesPreferences.backgroundColor.get().toColorHexString())
    MPVLib.setOptionString("sub-border-color", subtitlesPreferences.borderColor.get().toColorHexString())
    MPVLib.setOptionString("sub-border-size", subtitlesPreferences.borderSize.get().toString())
    MPVLib.setOptionString("sub-border-style", subtitlesPreferences.borderStyle.get().value)
    MPVLib.setOptionString("sub-shadow-offset", subtitlesPreferences.shadowOffset.get().toString())
    MPVLib.setOptionString("sub-pos", subtitlesPreferences.subPos.get().toString())
    MPVLib.setOptionString("sub-scale", subtitlesPreferences.subScale.get().toString())
  }
}

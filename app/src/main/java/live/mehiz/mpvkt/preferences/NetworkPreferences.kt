package live.mehiz.mpvkt.preferences

import live.mehiz.mpvkt.preferences.preference.PreferenceStore

/**
 * User-tunable network streaming, demuxer cache and decoder thread settings.
 * Applied in [live.mehiz.mpvkt.ui.player.MPVView.initOptions].
 */
class NetworkPreferences(preferenceStore: PreferenceStore) {
  /** Forward demuxer cache size in MiB (maps to demuxer-max-bytes). */
  val demuxerMaxCacheMb = preferenceStore.getInt("network_demuxer_max_cache_mb", 64)

  /** Backward demuxer cache size in MiB (maps to demuxer-max-back-bytes). */
  val demuxerMaxBackCacheMb = preferenceStore.getInt("network_demuxer_max_back_cache_mb", 64)

  /**
   * How many seconds of media the demuxer should try to buffer ahead
   * (maps to demuxer-readahead-secs). This is the main "cache time" control.
   */
  val demuxerReadaheadSecs = preferenceStore.getInt("network_demuxer_readahead_secs", 10)

  /**
   * Soft limit for the amount of data kept in the demuxer cache in seconds
   * (maps to cache-secs). Useful for live / long streams.
   */
  val cacheSecs = preferenceStore.getInt("network_cache_secs", 50)

  /** Pause playback until a small amount of cache is filled (cache-pause-initial). */
  val cachePauseInitial = preferenceStore.getBoolean("network_cache_pause_initial", true)

  /** Seconds of cache required before unpausing after underrun (cache-pause-wait). */
  val cachePauseWaitSecs = preferenceStore.getInt("network_cache_pause_wait_secs", 1)

  /** Video decoder thread count; 0 = auto (vd-lavc-threads). */
  val videoDecoderThreads = preferenceStore.getInt("network_vd_lavc_threads", 0)

  /** Audio decoder thread count; 0 = auto (ad-lavc-threads). */
  val audioDecoderThreads = preferenceStore.getInt("network_ad_lavc_threads", 0)

  /** Run demuxer on a separate thread (demuxer-thread). */
  val demuxerThread = preferenceStore.getBoolean("network_demuxer_thread", true)

  /** Prefetch next playlist entry (prefetch-playlist). */
  val prefetchPlaylist = preferenceStore.getBoolean("network_prefetch_playlist", false)

  /** Network I/O timeout in seconds (network-timeout). 0 = mpv default. */
  val networkTimeoutSecs = preferenceStore.getInt("network_timeout_secs", 60)

  /** Stream buffer size in KiB (stream-buffer-size). 0 = mpv default. */
  val streamBufferSizeKb = preferenceStore.getInt("network_stream_buffer_size_kb", 128)

  /** Enable TLS verification for https streams. */
  val tlsVerify = preferenceStore.getBoolean("network_tls_verify", true)

  /** Apply higher defaults tuned for network streams when URL is remote. */
  val optimizeForNetwork = preferenceStore.getBoolean("network_optimize_for_streaming", true)
}

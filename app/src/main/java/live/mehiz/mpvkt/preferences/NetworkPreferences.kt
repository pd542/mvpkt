package live.mehiz.mpvkt.preferences

import live.mehiz.mpvkt.preferences.preference.PreferenceStore

/**
 * User-tunable network streaming, demuxer cache and decoder thread settings.
 * Applied in [live.mehiz.mpvkt.ui.player.MPVView.initOptions].
 *
 * Important: decoder thread count does **not** speed up network download / cache fill rate.
 * Cache fill is limited by bandwidth and demuxer readahead / cache size options below.
 */
class NetworkPreferences(preferenceStore: PreferenceStore) {
  /** Forward demuxer cache size in MiB (maps to demuxer-max-bytes). Main buffer capacity. */
  val demuxerMaxCacheMb = preferenceStore.getInt("network_demuxer_max_cache_mb", 128)

  /** Backward demuxer cache size in MiB (maps to demuxer-max-back-bytes). */
  val demuxerMaxBackCacheMb = preferenceStore.getInt("network_demuxer_max_back_cache_mb", 64)

  /**
   * How many seconds of media the demuxer should try to buffer ahead
   * (maps to demuxer-readahead-secs). Primary "how far ahead to cache" control.
   */
  val demuxerReadaheadSecs = preferenceStore.getInt("network_demuxer_readahead_secs", 30)

  /**
   * Soft limit for the amount of data kept in the demuxer cache in seconds
   * (maps to cache-secs / demuxer-max-bytes interaction).
   */
  val cacheSecs = preferenceStore.getInt("network_cache_secs", 120)

  /** Pause playback until a small amount of cache is filled (cache-pause-initial). */
  val cachePauseInitial = preferenceStore.getBoolean("network_cache_pause_initial", true)

  /** Seconds of cache required before unpausing after underrun (cache-pause-wait). */
  val cachePauseWaitSecs = preferenceStore.getInt("network_cache_pause_wait_secs", 3)

  /**
   * Video decoder thread count; 0 = auto (vd-lavc-threads).
   * Only helps software decode CPU work — does not download faster.
   */
  val videoDecoderThreads = preferenceStore.getInt("network_vd_lavc_threads", 0)

  /** Audio decoder thread count; 0 = auto (ad-lavc-threads). */
  val audioDecoderThreads = preferenceStore.getInt("network_ad_lavc_threads", 0)

  /** Run demuxer on a separate thread (demuxer-thread). */
  val demuxerThread = preferenceStore.getBoolean("network_demuxer_thread", true)

  /** Prefetch next playlist entry (prefetch-playlist). */
  val prefetchPlaylist = preferenceStore.getBoolean("network_prefetch_playlist", false)

  /** Network I/O timeout in seconds (network-timeout). */
  val networkTimeoutSecs = preferenceStore.getInt("network_timeout_secs", 60)

  /** Stream buffer size in KiB (stream-buffer-size). Larger helps high-bitrate streams. */
  val streamBufferSizeKb = preferenceStore.getInt("network_stream_buffer_size_kb", 512)

  /** Enable TLS verification for https streams. */
  val tlsVerify = preferenceStore.getBoolean("network_tls_verify", true)

  /** Apply higher defaults tuned for network streams. */
  val optimizeForNetwork = preferenceStore.getBoolean("network_optimize_for_streaming", true)

  /**
   * Prefer highest HLS/DASH bandwidth ladder entry when available
   * (hls-bitrate=max / similar lavf behaviour via stream options).
   */
  val preferHighestBandwidth = preferenceStore.getBoolean("network_prefer_highest_bandwidth", true)

  /** Keep demuxer cache seekable for smoother seeking on network streams. */
  val demuxerSeekableCache = preferenceStore.getBoolean("network_demuxer_seekable_cache", true)

  /**
   * Multi-connection Range download (browser/IDM style) for progressive HTTP(S) files.
   * Serves a local proxy URL to mpv while N connections fill a sparse cache.
   * No effect on HLS/DASH (m3u8) or servers without Accept-Ranges.
   */
  val multiConnectionDownload = preferenceStore.getBoolean("network_multi_connection_download", true)

  /** Parallel connections for multi-connection download (2–16). */
  val multiConnectionCount = preferenceStore.getInt("network_multi_connection_count", 8)

  /** Chunk size per Range request in KiB (256–8192). */
  val multiConnectionChunkKb = preferenceStore.getInt("network_multi_connection_chunk_kb", 1024)
}

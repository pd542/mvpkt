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
  /**
   * Forward demuxer cache size in MiB (maps to demuxer-max-bytes).
   *
   * **This is the real "how far ahead can we cache" ceiling.** With `cache=yes`
   * `demuxer-readahead-secs` is ignored, so the forward buffer is bounded by
   * `min(demuxer-max-bytes, cache-secs × bitrate)`. The old 64 MiB default only
   * held ~6–25 s of a 20–80 Mbps Emby stream, which stalled the multi-conn
   * downloader (local socket backpressure) and throttled throughput.
   * 256 MiB is safe on 8 GB devices and holds ~100 s at 20 Mbps.
   */
  val demuxerMaxCacheMb = preferenceStore.getInt("network_demuxer_max_cache_mb_v2", 256)

  /** Backward demuxer cache size in MiB (maps to demuxer-max-back-bytes). */
  val demuxerMaxBackCacheMb = preferenceStore.getInt("network_demuxer_max_back_cache_mb_v2", 128)

  /**
   * Seconds of media the demuxer should try to buffer ahead
   * (maps to demuxer-readahead-secs).
   *
   * NOTE: only effective when `cache=no`. With the default `cache=yes`
   * (which this app always sets) mpv ignores it and [demuxerMaxCacheMb] /
   * [cacheSecs] govern the forward buffer instead.
   */
  val demuxerReadaheadSecs = preferenceStore.getInt("network_demuxer_readahead_secs", 10)

  /**
   * Soft limit for the amount of data kept in the demuxer cache in seconds
   * (maps to cache-secs). Secondary bound on the forward buffer; the effective
   * limit is `min(demuxer-max-bytes, cache-secs × bitrate)`.
   */
  val cacheSecs = preferenceStore.getInt("network_cache_secs_v2", 60)

  /** Pause playback until a small amount of cache is filled (cache-pause-initial). */
  val cachePauseInitial = preferenceStore.getBoolean("network_cache_pause_initial", false)

  /** Seconds of cache required before unpausing after underrun (cache-pause-wait). */
  val cachePauseWaitSecs = preferenceStore.getInt("network_cache_pause_wait_secs", 1)

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

  /**
   * Forward Android system HTTP(S) proxy (NekoBox / Clash **system-proxy** mode)
   * into libmpv `http-proxy` and Java multi-conn downloads.
   * Global VPN/TUN is transparent: never double-proxied even if a local mixed port exists.
   * With no proxy configured, traffic stays direct.
   */
  val useSystemHttpProxy = preferenceStore.getBoolean("network_use_system_http_proxy", true)

  /**
   * When a system HTTP proxy is active, auto-disable multi-connection download.
   * Off by default: multi-conn still runs under proxy (connections are capped).
   * Enable only if NekoBox / HTTP proxy stalls concurrent Range streams.
   */
  val disableMultiConnUnderProxy =
    preferenceStore.getBoolean("network_disable_multi_conn_under_proxy", false)

  /** Apply higher defaults tuned for network streams. Default off — safer playback. */
  val optimizeForNetwork = preferenceStore.getBoolean("network_optimize_for_streaming", false)

  /**
   * Prefer highest HLS/DASH bandwidth ladder entry when available.
   * Default off — can break some adaptive streams.
   */
  val preferHighestBandwidth = preferenceStore.getBoolean("network_prefer_highest_bandwidth", false)

  /** Keep demuxer cache seekable for smoother seeking on network streams. */
  val demuxerSeekableCache = preferenceStore.getBoolean("network_demuxer_seekable_cache", true)

  /**
   * Multi-connection Range download (browser/IDM style) for progressive HTTP(S) files.
   * Head is downloaded first, then N parallel workers fill the rest via a local proxy.
   * No effect on HLS/DASH (m3u8) or servers without Accept-Ranges.
   * Auto-falls back to direct URL if Range/head fails.
   */
  // New key so previous broken defaults are not reused.
  val multiConnectionDownload = preferenceStore.getBoolean("network_multi_connection_download_v3", true)

  /** Parallel connections for multi-connection download (2–16). */
  val multiConnectionCount = preferenceStore.getInt("network_multi_connection_count", 8)

  /** Chunk size per Range request in KiB (256–4096). */
  val multiConnectionChunkKb = preferenceStore.getInt("network_multi_connection_chunk_kb", 1024)

  /**
   * Use multi-connection Range download for **transcoded** media-server streams
   * (Emby/Jellyfin `/Videos/.../stream` with bitrate/transcoding params).
   *
   * Transcode output is generated sequentially, so far-ahead byte ranges may not
   * exist yet (the server either stalls or answers 200). This app therefore uses
   * a *watermark window* scheduler for these sessions: workers only fetch chunks
   * close to the transcoder's produced watermark, so N connections pipeline
   * instead of deadlocking on bytes that do not exist.
   *
   * Throughput is still capped by the server's transcode speed, not by bandwidth.
   * Default off — enable when the server transcodes faster than the network.
   */
  val multiConnTranscode = preferenceStore.getBoolean("network_multi_conn_transcode", false)

  /**
   * Cap parallel Range connections when a **system HTTP proxy** is active
   * (NekoBox 系统代理 / corporate proxy). Proxy nodes often multiplex or rate
   * limit per-host concurrent streams, so 8–16 sockets can be slower than 4.
   * Transparent VPN/TUN is not affected (it is not a system HTTP proxy).
   */
  val proxyConnectionCap = preferenceStore.getBoolean("network_proxy_connection_cap", true)

  /** Connection count used while [proxyConnectionCap] is enabled (2–8). */
  val proxyConnectionCapCount = preferenceStore.getInt("network_proxy_connection_cap_count", 4)
}

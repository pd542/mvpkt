@file:Suppress(
  "ReturnCount",
  "LongParameterList",
  "CyclomaticComplexMethod",
  "NestedBlockDepth",
  "LoopWithTooManyJumpStatements",
  "LongMethod",
  "TooManyFunctions",
  "ComplexCondition",
  "LargeClass",
  "MagicNumber",
)

package live.mehiz.mpvkt.network

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Multi-connection progressive loader (browser / IDM style).
 *
 * Playback-safe strategy:
 * 1. Probe Range support
 * 2. Synchronously download a contiguous **head** (and a small **tail** for moov-at-end mp4)
 * 3. Serve via localhost HTTP proxy that only returns real bytes ([ensureRange] fills holes)
 * 4. Background workers extend the contiguous tip with short parallel stripes (not scatter-fill)
 *
 * Never hand a sparse file path to mpv — unwritten regions read as zeros and break demux.
 *
 * ## Two scheduling modes
 *
 * - **Static file** (CDN / direct): chunks are addressable at random. Workers may
 *   stripe ahead freely; every offset returns real bytes immediately.
 * - **Live transcode** (`config.transcodeMode`, Emby/Jellyfin `/Videos/.../stream`):
 *   the server *produces* the byte stream sequentially. A request for a chunk that
 *   has not been produced yet either blocks until it is (burning a connection and
 *   making multi-conn slower than single-stream) or is answered `200 OK` from byte 0
 *   (which would corrupt the cache). Here the scheduler clamps every request to
 *   `[producedWatermark, producedWatermark + TRANSCODE_LOOKAHEAD_BYTES)`, so all N
 *   connections pipeline over already-produced bytes. Throughput remains bounded by
 *   the server's transcode rate, not by bandwidth.
 */
class SegmentedHttpCache(
  private val cacheDir: File,
  private val connections: Int,
  private val chunkBytes: Int,
  private val userAgent: String = DEFAULT_UA,
  private val requestHeaders: Map<String, String> = emptyMap(),
  /**
   * Optional Android system / NekoBox HTTP proxy. When set, all origin Range
   * requests go through it; localhost playback proxy is never proxied.
   */
  private val systemProxy: SystemHttpProxy.Info? = null,
  /**
   * When true (or when [systemProxy] is set), cap parallel Range workers so
   * SOCKS/HTTP proxies that limit concurrent streams do not stall head download.
   */
  private val limitConnectionsUnderProxy: Boolean = true,
  /**
   * Allow multi-connection download for **transcoded** media-server streams.
   *
   * Transcode output is produced sequentially, so byte ranges far past the
   * transcoder's watermark do not exist yet. In this mode the scheduler switches
   * to a *watermark window*: only chunks near the produced prefix are requested
   * (with bounded look-ahead), so N connections pipeline instead of deadlocking.
   */
  private val allowTranscode: Boolean = false,
  /** Hard cap applied when a real system HTTP proxy is in use (0 = no extra cap). */
  private val proxyConnCap: Int = 0,
  /**
   * How many bytes ahead of the playhead the scheduler should keep prioritised,
   * in bytes. `0` keeps the conservative built-in default (see
   * [DEFAULT_READ_AHEAD_BYTES]).
   *
   * This is what actually fills mpv's demux cache: work inside
   * `[playhead, playhead + readAheadBytes)` is pinned and will not be abandoned,
   * while anything past it is opportunistic. Size it from (and at or below) the
   * demuxer byte budget so the downloader and the player agree on the target.
   */
  private val readAheadBytes: Long = 0L,
) {
  private var session: Session? = null

  data class OpenResult(
    /** URL/path for mpv loadfile (localhost proxy when segmented). */
    val playPath: String,
    val usedSegmented: Boolean,
  )

  data class ProbeResult(
    val supportsRange: Boolean,
    val contentLength: Long,
    val contentType: String?,
    val finalUrl: String,
    /**
     * Transport-level failure that prevented the probe from being answered, if any
     * (e.g. `Cleartext HTTP traffic to <host> not permitted`). Used to explain why
     * multi-connection acceleration was skipped instead of degrading silently.
     */
    val lastError: String? = null,
  )

  data class CacheSnapshot(
    val totalSize: Long,
    val downloadedBytes: Long,
    val fullyCached: Boolean,
    val lastWriteAtMs: Long,
    val running: Boolean,
  )

  /**
   * Open segmented session. On failure [OpenResult.playPath] is [originalUrl]
   * and [OpenResult.usedSegmented] is false.
   */
  fun open(originalUrl: String): OpenResult {
    return runCatching {
      val direct = resolveDirectMediaUrl(originalUrl, userAgent, requestHeaders, systemProxy)
      if (!isAcceleratableUrl(direct, allowTranscode)) {
        // Prefer resolved direct URL for mpv even when not multi-conn.
        PlaybackSessionLog.i(
          "SEG",
          "not acceleratable url=${PlaybackSessionLog.redactUrl(direct)} transcode=$allowTranscode",
        )
        return@runCatching OpenResult(direct, false)
      }
      startSession(direct)
    }.getOrElse {
      PlaybackSessionLog.e(
        "SEG",
        "open failed url=${PlaybackSessionLog.redactUrl(originalUrl)}",
        it,
      )
      shutdownQuietly(deleteCache = true)
      OpenResult(originalUrl, false)
    }
  }

  private fun startSession(mediaUrl: String): OpenResult {
    // Transcode endpoints often omit Accept-Ranges on HEAD even though per-request
    // Range works. When allowTranscode is on we accept a 200 probe as "maybe range".
    val probe = probe(mediaUrl, userAgent, requestHeaders, systemProxy)
    val transcode = isMediaServerTranscodeUrl(mediaUrl.lowercase(Locale.US))
    val rangeUsable = probe.supportsRange || (allowTranscode && probe.contentLength >= MIN_FILE_FOR_ACCEL)
    if (!rangeUsable || probe.contentLength < MIN_FILE_FOR_ACCEL) {
      PlaybackSessionLog.i(
        "SEG",
        "probe skip range=${probe.supportsRange} len=${probe.contentLength} " +
          "type=${probe.contentType} transcode=$transcode " +
          "url=${PlaybackSessionLog.redactUrl(mediaUrl)}",
      )
      // The most common reason the probe fails on an otherwise reachable server is
      // Android blocking cleartext HTTP for this app (network_security_config).
      // Say so loudly: otherwise this degrades to single-connection playback
      // silently, and neither the byte budget nor the connection count can help.
      probe.lastError?.let { err ->
        val cleartext = err.contains("Cleartext", ignoreCase = true)
        PlaybackSessionLog.w(
          "SEG",
          if (cleartext) {
            "MULTI-CONN DISABLED: cleartext HTTP blocked by the app's network " +
              "security policy. This URL is http:// and the probe was refused, so " +
              "multi-connection acceleration will NOT run (playback falls back to a " +
              "single connection). Fix: permit cleartext for this host in " +
              "res/xml/network_security_config.xml. err=$err"
          } else {
            "probe failed, multi-conn disabled err=$err"
          },
        )
      }
      return OpenResult(mediaUrl, false)
    }

    // Proxies (NekoBox HTTP/SOCKS, corporate MITM) often throttle many concurrent
    // Range streams; keep a small worker count so head download still finishes.
    val connCount = run {
      var n = connections.coerceIn(2, 16)
      if (systemProxy != null && limitConnectionsUnderProxy) n = n.coerceAtMost(4)
      if (systemProxy != null && proxyConnCap > 0) n = n.coerceAtMost(proxyConnCap.coerceIn(2, 16))
      n.coerceAtLeast(2)
    }
    val chunk = chunkBytes.coerceIn(MIN_CHUNK, MAX_CHUNK)
    val headBytes = min(probe.contentLength, HEAD_BYTES)
    val tailBytes = min(probe.contentLength / 4, TAIL_BYTES).coerceAtLeast(0L)

    val format = guessMediaFormat(probe.finalUrl, mediaUrl)
    val sess = Session(
      config = SessionConfig(
        originUrl = probe.finalUrl,
        totalSize = probe.contentLength,
        contentType = sanitizeContentType(probe.contentType, format),
        fileExtension = format.extension,
        connections = connCount,
        chunkBytes = chunk,
        userAgent = userAgent,
        requestHeaders = requestHeaders,
        systemProxy = systemProxy,
        transcodeMode = transcode,
        readAheadBytes = readAheadBytes,
      ),
      cacheDir = cacheDir,
      // Route the segment cache's internal diagnostics to the playback session log.
      //
      // This used to be `log = {}` -- an empty lambda. The class has ~24 log()
      // call sites, so every one of them was silently discarded: no session-start
      // line, no re-anchor proof, no spin diagnostics. That made field logs
      // impossible to act on, because a starved playhead and a slow link look
      // identical when neither reports anything.
      log = { PlaybackSessionLog.d("SEGX", it) },
    )

    PlaybackSessionLog.i(
      "SEG",
      "session start len=${probe.contentLength} conn=$connCount chunk=$chunk " +
        "transcode=$transcode rangeProbe=${probe.supportsRange} " +
        "proxy=${systemProxy?.mpvHttpProxyUrl ?: "none"} " +
        "url=${PlaybackSessionLog.redactUrl(probe.finalUrl)}",
    )
    // Contiguous head before play — required for demux.
    val headOk = sess.downloadRangeBlocking(0L, headBytes - 1)
    if (!headOk) {
      PlaybackSessionLog.w(
        "SEG",
        "head download failed bytes=0-${headBytes - 1} " +
          "url=${PlaybackSessionLog.redactUrl(probe.finalUrl)}",
      )
    }
    val have = sess.store.contiguousFrom(0L)
    if (!headOk || have < min(headBytes, MIN_HEAD_TO_START)) {
      sess.close(deleteCache = true)
      return OpenResult(mediaUrl, false)
    }

    // Tail for moov-at-end progressive mp4/mkv (common on CDN progressive files).
    if (tailBytes > 0 && probe.contentLength > headBytes + tailBytes) {
      val tailStart = probe.contentLength - tailBytes
      sess.downloadRangeBlocking(tailStart, probe.contentLength - 1)
    }

    sess.startBackground(afterOffset = have)
    session = sess

    return OpenResult(sess.localUrl, true)
  }

  /** Stop proxy/workers. Always deletes on-disk segments (no cross-session reuse). */
  fun close() = shutdownQuietly(deleteCache = true)

  fun deleteCache() = shutdownQuietly(deleteCache = true)

  fun snapshot(): CacheSnapshot? = session?.snapshot()

  fun cachedAheadFrom(byteOffset: Long): Long = session?.cachedAheadFrom(byteOffset) ?: 0L

  private fun shutdownQuietly(deleteCache: Boolean) {
    session?.close(deleteCache)
    session = null
  }

  companion object {
    private const val DEFAULT_UA =
      "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val MIN_CHUNK = 256 * 1024
    private const val MAX_CHUNK = 4 * 1024 * 1024

    /** Contiguous head downloaded before playback starts. Keep small so URL load/seek starts quickly. */
    private const val HEAD_BYTES = 1L * 1024L * 1024L

    /** Tail for container index (moov / cues) at end of file. */
    private const val TAIL_BYTES = 1L * 1024L * 1024L
    private const val MIN_HEAD_TO_START = 512L * 1024L
    private const val MIN_FILE_FOR_ACCEL = 3L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Stale seek priority is dropped so a dead target cannot freeze sequential fill forever. */
    private const val PRIORITY_TTL_MS = 20_000L

    /** Cap a single ensureRange wait (proxy must not freeze mpv for a full minute). */
    private const val MAX_ENSURE_TIMEOUT_MS = 12_000L

    /**
     * Default playhead read-ahead window (bytes) when the caller does not size one.
     *
     * 8 MiB is deliberately small: it matches the historical behaviour so callers
     * that have not opted in (and low-memory devices) are unaffected. Callers that
     * set `demuxer-max-bytes` should pass a comparable value — see
     * [SessionConfig.readAheadBytes].
     */
    private const val DEFAULT_READ_AHEAD_BYTES = 8L * 1024L * 1024L

    /** Floor for a caller-supplied read-ahead window (bytes). */
    private const val MIN_READ_AHEAD_BYTES = 4L * 1024L * 1024L

    /**
     * Ceiling for a caller-supplied read-ahead window (bytes).
     *
     * Bounded well below the demuxer budget: the priority window pins work the
     * scheduler will not abandon, so a huge window on a slow link just means the
     * downloader commits to bytes far past the playhead while the player starves.
     * 512 MiB is the largest window we consider useful on a 8 GB device.
     */
    private const val MAX_READ_AHEAD_BYTES = 512L * 1024L * 1024L

    /** Prefer asking for at least this many bytes ahead of the read cursor. */
    private const val MIN_STREAM_BYTES = 512L * 1024L

    /** First bytes needed to answer a seek quickly; more data is prefetched in background. */
    private const val SEEK_START_BYTES = 256L * 1024L

    /**
     * Soft cap on empty-read retries before aborting one response body.
     * Each idle sleep is ~50ms → 300 ≈ 15s when the CDN is silent.
     * While background Range workers still write, use the higher cap.
     */
    private const val BODY_IDLE_ROUNDS_MAX = 300
    private const val BODY_IDLE_ROUNDS_WHILE_DOWNLOADING = 1_200

    /** Move this far (or jump outside window) before treating as a new seek generation. */
    private const val PRIORITY_SEEK_DELTA = 512L * 1024L

    /**
     * How far the playhead must be ahead of the sequential fill cursor before the
     * filler abandons its current position and re-anchors to the playhead.
     *
     * A whole chunk worth: below this the cursor will reach the playhead on its own
     * almost immediately, so jumping just adds churn.
     */
    private const val FILL_ANCHOR_TOLERANCE = 4L * 1024L * 1024L

    /**
     * How far past the produced watermark (see [Session.producedWatermark]) the
     * transcode scheduler may plan.
     *
     * This is the whole point of transcode multi-conn: instead of N workers each
     * jumping to a far-away chunk (which does not exist yet → hang / HTTP 200),
     * all N workers stay inside `[watermark, watermark + lookahead)`. Because the
     * previous lookahead chunk has already been produced while the current one
     * downloads, the requests hit real bytes immediately and pipeline. With 8
     * connections the effective lookahead is `8 × chunkBytes`; 32 MiB keeps that
     * bounded on an 8 GB device while still leaving room to pipeline.
     */
    private const val TRANSCODE_LOOKAHEAD_BYTES = 32L * 1024L * 1024L

    fun isAcceleratableUrl(url: String, allowTranscode: Boolean = false): Boolean {
      val lower = url.lowercase(Locale.US)
      if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
      // Adaptive streaming (HLS/DASH) — mpv handles these natively, never accelerate.
      if (isAdaptiveStreamingUrl(lower)) return false
      // Transcoded media-server sessions are generated sequentially; only
      // accelerate them when the caller opted into watermark-window scheduling.
      if (isMediaServerTranscodeUrl(lower) && !allowTranscode) return false
      // OpenList/Alist intermediate /d/?sign= links are NOT final media — resolve first.
      // Real CDN signed URLs (X-Amz-*) ARE acceleratable after resolve.
      if (isOpenListIntermediate(lower)) {
        return false
      }
      return true
    }

    /**
     * Whether the multi-conn path should run at all (may only resolve OpenList then
     * fall back to direct, or start segmented on the final CDN URL).
     */
    fun shouldTryAccelerate(url: String, allowTranscode: Boolean = false): Boolean {
      val lower = url.lowercase(Locale.US)
      if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
      if (isAdaptiveStreamingUrl(lower)) return false
      if (isMediaServerTranscodeUrl(lower) && !allowTranscode) return false
      return true
    }

    /**
     * Emby / Jellyfin / media-server stream URLs.
     * Under VPN or fragile tunnels, multi-conn Range often fails while native single-stream works.
     */
    fun isMediaServerStreamUrl(url: String): Boolean {
      val lower = url.lowercase(Locale.US)
      return isMediaServerStreamUrlLower(lower)
    }

    private fun isMediaServerStreamUrlLower(urlLower: String): Boolean =
      urlLower.contains("/emby/") ||
        urlLower.contains("/jellyfin/") ||
        (urlLower.contains("/videos/") && urlLower.contains("/stream")) ||
        urlLower.contains("mediasourceid=") ||
        isMediaServerTranscodeUrl(urlLower)

    private fun isAdaptiveStreamingUrl(urlLower: String): Boolean =
      urlLower.contains(".m3u8") ||
        urlLower.contains(".mpd") ||
        urlLower.contains("format=m3u") ||
        urlLower.contains("type=m3u8") ||
        urlLower.contains("/hls/") ||
        urlLower.contains("playlist")

    /**
     * Transcoded / quality-capped media-server sessions are not byte-stable files.
     * Direct static Emby/Jellyfin streams can still use Range once auth headers are preserved.
     */
    private fun isMediaServerTranscodeUrl(urlLower: String): Boolean =
      urlLower.contains("/transcode") ||
        urlLower.contains("transcoding") ||
        urlLower.contains("transcodingid=") ||
        urlLower.contains("videobitrate=") ||
        urlLower.contains("audiobitrate=") ||
        urlLower.contains("maxstreamingbitrate=")

    /** Alist/OpenList proxy download path that is not the real CDN object. */
    fun isOpenListIntermediate(urlLower: String): Boolean {
      val hasSign = urlLower.contains("sign=")
      val hasDPath = urlLower.contains("/d/")
      val local = urlLower.contains("localhost") ||
        urlLower.contains("127.0.0.1") ||
        urlLower.contains("0.0.0.0")
      // Classic: http://host:5244/d/path/file.mp4?sign=...
      if (hasDPath && hasSign) return true
      if (local && hasDPath) return true
      return false
    }

    /**
     * Follow redirects / OpenList gate to the real media CDN URL.
     * Does not use Range on the first hop so one-shot OpenList signs stay valid.
     */
    fun resolveDirectMediaUrl(
      url: String,
      userAgent: String = DEFAULT_UA,
      requestHeaders: Map<String, String> = emptyMap(),
      systemProxy: SystemHttpProxy.Info? = null,
    ): String {
      val lower = url.lowercase(Locale.US)
      if (!lower.startsWith("http://") && !lower.startsWith("https://")) return url
      // Real object-store signed URLs are already final.
      if (lower.contains("x-amz-signature=") || lower.contains("x-oss-signature=")) {
        return url
      }
      if (!isOpenListIntermediate(lower) && !lower.contains("sign=")) {
        return url
      }
      return try {
        val cleanHeaders = requestHeaders.sanitizedForOrigin()
        var current = url
        var hops = 0
        while (hops < 8) {
          hops++
          // HEAD first — no body, safe for one-shot OpenList signs.
          val head = openConnection(current, userAgent, cleanHeaders, systemProxy).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
          }
          try {
            head.connect()
            val code = head.responseCode
            val loc = head.getHeaderField("Location")
            val type = head.contentType
            val len = head.getHeaderFieldLong("Content-Length", -1L)
            if (code in 300..399 && !loc.isNullOrBlank()) {
              head.disconnect()
              current = URL(URL(current), loc).toString()
              continue
            }
            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
              if (!isHtmlType(type) && (len < 0 || len >= MIN_FILE_FOR_ACCEL)) {
                val finalUrl = head.url.toString()
                head.disconnect()
                return finalUrl
              }
            }
            head.disconnect()
          } catch (e: Exception) {
            runCatching { head.disconnect() }
          }

          // Some OpenList builds do not support HEAD — one full GET without Range,
          // but disconnect immediately after headers if redirected / large.
          val get = openConnection(current, userAgent, cleanHeaders, systemProxy).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
          }
          try {
            get.connect()
            val code = get.responseCode
            val loc = get.getHeaderField("Location")
            val type = get.contentType
            val len = get.getHeaderFieldLong("Content-Length", -1L)
            if (code in 300..399 && !loc.isNullOrBlank()) {
              runCatching { get.inputStream.close() }
              get.disconnect()
              current = URL(URL(current), loc).toString()
              continue
            }
            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
              if (!isHtmlType(type) && (len < 0 || len >= MIN_FILE_FOR_ACCEL)) {
                // Do NOT read the body — just take the URL as media origin.
                runCatching { get.inputStream.close() }
                val finalUrl = get.url.toString()
                get.disconnect()
                return finalUrl
              }
            }
            runCatching { get.inputStream.close() }
            get.disconnect()
          } catch (e: Exception) {
            runCatching { get.disconnect() }
          }
          break
        }
        current
      } catch (e: Exception) {
        url
      }
    }

    fun probe(
      url: String,
      userAgent: String = DEFAULT_UA,
      requestHeaders: Map<String, String> = emptyMap(),
      systemProxy: SystemHttpProxy.Info? = null,
    ): ProbeResult {
      // Prefer HEAD first — does not download body and is safer for signed URLs
      // that somehow still reached probe.
      val head = probeOnce(url, userAgent, requestHeaders, useHead = true, systemProxy = systemProxy)
      if (head.supportsRange && head.contentLength >= MIN_FILE_FOR_ACCEL && !isHtmlType(head.contentType)) {
        return head
      }
      // Fallback tiny Range GET only when HEAD did not prove Range support.
      val get = probeOnce(url, userAgent, requestHeaders, useHead = false, systemProxy = systemProxy)
      if (isHtmlType(get.contentType) || get.contentLength in 1 until 8 * 1024) {
        // HTML error page or tiny payload — not a progressive video.
        return ProbeResult(false, get.contentLength, get.contentType, get.finalUrl, get.lastError)
      }
      // Surface whichever attempt actually failed so the caller can explain the skip.
      return get.copy(lastError = get.lastError ?: head.lastError)
    }

    private fun isHtmlType(type: String?): Boolean {
      val t = type?.lowercase(Locale.US) ?: return false
      return t.contains("text/html") || t.contains("application/xhtml")
    }

    private fun probeOnce(
      url: String,
      userAgent: String,
      requestHeaders: Map<String, String>,
      useHead: Boolean,
      systemProxy: SystemHttpProxy.Info? = null,
    ): ProbeResult {
      val method = if (useHead) "HEAD" else "GET"
      val conn = openConnection(url, userAgent, requestHeaders.sanitizedForOrigin(), systemProxy).apply {
        requestMethod = method
        if (!useHead) {
          setRequestProperty("Range", "bytes=0-0")
        }
        instanceFollowRedirects = true
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
      }
      return try {
        conn.connect()
        val code = conn.responseCode
        val finalUrl = conn.url.toString()
        val type = conn.contentType
        val acceptRanges = conn.getHeaderField("Accept-Ranges")
          ?.lowercase(Locale.US)
          ?.contains("bytes") == true
        when {
          code == HttpURLConnection.HTTP_PARTIAL -> {
            val total = parseTotalFromContentRange(conn.getHeaderField("Content-Range")) ?: -1L
            runCatching { if (!useHead) conn.inputStream.close() }
            conn.disconnect()
            ProbeResult(total > 0 && !isHtmlType(type), total, type, finalUrl)
          }
          code == HttpURLConnection.HTTP_OK -> {
            val len = conn.getHeaderFieldLong("Content-Length", -1L)
            runCatching { if (!useHead) conn.inputStream.close() }
            conn.disconnect()
            // HEAD/GET 200: only enable multi-conn when server advertises ranges
            // and payload looks large enough to be a real media file.
            val ok = acceptRanges && len >= MIN_FILE_FOR_ACCEL && !isHtmlType(type)
            ProbeResult(ok, len, type, finalUrl)
          }
          else -> {
            PlaybackSessionLog.w(
              "SEG",
              "probe $method http=$code type=$type url=${PlaybackSessionLog.redactUrl(finalUrl)}",
            )
            conn.disconnect()
            ProbeResult(false, -1L, type, finalUrl)
          }
        }
      } catch (e: Exception) {
        val msg = "${e.javaClass.simpleName}:${e.message}"
        PlaybackSessionLog.w(
          "SEG",
          "probe $method error=$msg url=${PlaybackSessionLog.redactUrl(url)}",
        )
        runCatching { conn.disconnect() }
        ProbeResult(false, -1L, null, url, lastError = msg)
      }
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
      if (header.isNullOrBlank()) return null
      val slash = header.lastIndexOf('/')
      if (slash < 0 || slash == header.lastIndex) return null
      val total = header.substring(slash + 1).trim()
      return total.takeUnless { it == "*" }?.toLongOrNull()
    }

    data class MediaFormat(
      val extension: String,
      val mime: String,
    )

    /**
     * Infer container from URL path / Content-Disposition-like filename in query.
     * Supports progressive files: mp4, mkv, webm, mov, avi, ts, m4v, flv, …
     */
    fun guessMediaFormat(vararg urls: String): MediaFormat {
      val path = urls
        .asSequence()
        .map { it.substringBefore('#').substringBefore('?') }
        .map { it.substringAfterLast('/').lowercase(Locale.US) }
        .firstOrNull { it.contains('.') }
        ?: ""
      // Also scan full URL for .ext before query (OpenList path encodes name).
      val blob = urls.joinToString(" ").lowercase(Locale.US)
      fun has(ext: String): Boolean =
        path.endsWith(".$ext") ||
          blob.contains(".$ext?") ||
          blob.contains(".$ext&") ||
          blob.contains(".$ext%") ||
          blob.contains("filename%3d") && blob.contains(".$ext") ||
          blob.contains("filename=") && blob.contains(".$ext")

      return when {
        has("mkv") || has("mk3d") || has("mka") ->
          MediaFormat("mkv", "video/x-matroska")
        has("webm") -> MediaFormat("webm", "video/webm")
        has("mov") || has("qt") -> MediaFormat("mov", "video/quicktime")
        has("m4v") -> MediaFormat("m4v", "video/x-m4v")
        has("m4a") -> MediaFormat("m4a", "audio/mp4")
        has("mp3") -> MediaFormat("mp3", "audio/mpeg")
        has("flac") -> MediaFormat("flac", "audio/flac")
        has("aac") -> MediaFormat("aac", "audio/aac")
        has("ogg") || has("ogv") || has("opus") ->
          MediaFormat("ogg", "application/ogg")
        has("avi") -> MediaFormat("avi", "video/x-msvideo")
        has("flv") -> MediaFormat("flv", "video/x-flv")
        has("wmv") || has("asf") -> MediaFormat("wmv", "video/x-ms-wmv")
        has("ts") || has("m2ts") || has("mts") ->
          MediaFormat("ts", "video/mp2t")
        has("mpg") || has("mpeg") -> MediaFormat("mpg", "video/mpeg")
        has("3gp") || has("3g2") -> MediaFormat("3gp", "video/3gpp")
        has("wav") -> MediaFormat("wav", "audio/wav")
        has("mp4") || has("f4v") -> MediaFormat("mp4", "video/mp4")
        else -> MediaFormat("mp4", "video/mp4") // safe progressive default
      }
    }

    private fun sanitizeContentType(raw: String?, format: MediaFormat): String {
      val fallback = format.mime
      if (raw.isNullOrBlank()) return fallback
      val clean = raw.substringBefore(';').trim().lowercase(Locale.US)
      if (clean.isBlank()) return fallback
      // CDN often serves progressive media as application/octet-stream.
      if (clean == "application/octet-stream" ||
        clean == "binary/octet-stream" ||
        clean == "application/force-download" ||
        clean == "application/download"
      ) {
        return fallback
      }
      if (clean.startsWith("video/") || clean.startsWith("audio/")) return clean
      // text/html already rejected at probe; other text → use format guess.
      if (clean.startsWith("text/")) return fallback
      return clean
    }

    fun openConnection(
      url: String,
      userAgent: String,
      requestHeaders: Map<String, String> = emptyMap(),
      systemProxy: SystemHttpProxy.Info? = null,
    ): HttpURLConnection {
      val useProxy = systemProxy != null && !systemProxy.shouldBypass(url)
      val conn = if (useProxy) {
        URL(url).openConnection(systemProxy!!.javaProxy) as HttpURLConnection
      } else {
        URL(url).openConnection() as HttpURLConnection
      }
      conn.setRequestProperty("User-Agent", userAgent)
      conn.setRequestProperty("Accept", "*/*")
      conn.setRequestProperty("Connection", "keep-alive")
      requestHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }
      return conn
    }

    private fun Map<String, String>.sanitizedForOrigin(): Map<String, String> = filterKeys { name ->
      val lower = name.lowercase(Locale.US)
      lower != "range" && lower != "connection" && lower != "host" && lower != "accept-encoding"
    }
  }

  private data class SessionConfig(
    val originUrl: String,
    val totalSize: Long,
    val contentType: String,
    /** File extension without dot, e.g. mp4 / mkv / webm. */
    val fileExtension: String,
    val connections: Int,
    val chunkBytes: Int,
    val userAgent: String,
    val requestHeaders: Map<String, String>,
    val systemProxy: SystemHttpProxy.Info? = null,
    /**
     * Media-server transcode session: output is produced sequentially, so the
     * scheduler must never request far past the highest byte the server has
     * actually produced. [Session.producedWatermark] tracks that boundary.
     */
    val transcodeMode: Boolean = false,
    /**
     * How many bytes ahead of the playhead the priority window should cover.
     *
     * This is what actually fills mpv's demux cache. With the old fixed 8 MiB the
     * window only ever covered "what mpv needs right now", so `demuxer-max-bytes`
     * stayed empty (`demuxCacheSec=0.0`) and any network dip caused an instant
     * rebuffer. Sized from the user's demuxer byte budget so the front-end
     * downloader and the back-end cache describe the same target.
     */
    val readAheadBytes: Long = DEFAULT_READ_AHEAD_BYTES,
  )

  private class Session(
    private val config: SessionConfig,
    cacheDir: File,
    private val log: (String) -> Unit,
  ) {
    val store = ContiguousStore()
    val cacheFile: File =
      File(cacheDir, "seg_${config.originUrl.hashCode().toUInt()}_${config.totalSize}.bin")
    private val raf: RandomAccessFile
    private val executor: ThreadPoolExecutor
    private val serverExecutor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(true)
    private val downloaded = AtomicLong(0)
    private val lastWriteAtMs = AtomicLong(System.currentTimeMillis())
    private val inFlightRanges = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    /**
     * Rate limit for the per-iteration / per-request diagnostics.
     *
     * The playback log is written synchronously to a file with a flush on every
     * line, and `ensureRange` can spin thousands of times a second while the
     * playhead is starved. Logging every spin would both flood the log and slow
     * down the very loop we are trying to measure. One line per key per second is
     * plenty to reconstruct what happened.
     */
    private val lastLogAtMs = ConcurrentHashMap<String, Long>()

    private fun logThrottled(key: String, message: () -> String) {
      val now = System.currentTimeMillis()
      val prev = lastLogAtMs.put(key, now)
      if (prev == null || now - prev >= 1_000L) log(message())
    }

    /**
     * Playback/seek head — background filler yields to this region first.
     * [priorityGen] invalidates stale windows after rapid seeks so old targets
     * cannot monopolize all download workers forever.
     */
    private val priorityStart = AtomicLong(-1L)
    private val priorityEnd = AtomicLong(-1L)
    private val priorityGen = AtomicLong(0L)
    private val priorityDeadlineMs = AtomicLong(0L)

    /**
     * Highest byte offset (exclusive) the origin has actually produced.
     *
     * Only meaningful in [SessionConfig.transcodeMode]. For a live transcode the
     * server cannot serve bytes it has not generated yet, so any Range request
     * beyond this boundary either hangs or is silently answered with `200 OK`
     * from byte 0. The scheduler therefore clamps its window to
     * `watermark + TRANSCODE_LOOKAHEAD_BYTES` so N workers pipeline over already
     * produced bytes instead of deadlocking on not-yet-existing ones.
     *
     * Advances only on successful contiguous progress from the *tip*, never from
     * a seek, so a far-away seek cannot inflate it.
     */
    private val producedWatermark = AtomicLong(0L)

    /** True once any transcode Range request was answered with 200 (bytes not ready). */
    private val transcodeRangeDenied = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    val localUrl: String

    init {
      cacheDir.mkdirs()
      // Never reuse leftover segments from a previous play — always start clean so
      // app storage does not accumulate after exit / media switch.
      runCatching { cacheFile.delete() }
      // Remove legacy range-index files from older builds that reused segments.
      runCatching { File(cacheFile.absolutePath + ".ranges").delete() }
      store.clear()
      raf = RandomAccessFile(cacheFile, "rw")
      raf.setLength(config.totalSize)
      // Slightly more workers than configured connections so proxy ensureRange
      // jobs are not starved by the sequential stripe filler.
      val poolSize = (config.connections + 2).coerceIn(2, 18)
      executor = ThreadPoolExecutor(
        poolSize,
        poolSize,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
      )
      // Bind IPv4 loopback only — mpv gets http://127.0.0.1:port/...
      val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
      serverSocket = ss
      // Extension helps libmpv pick demuxer (mp4/mkv/webm/…, not only mp4).
      val ext = config.fileExtension.ifBlank { "mp4" }
      localUrl = "http://127.0.0.1:${ss.localPort}/media.$ext"
      log("proxy listen $localUrl type=${config.contentType} file=${cacheFile.name}")
      // Accept clients as soon as the socket is up (before head finishes).
      serverExecutor.execute { acceptLoop() }
    }

    fun downloadRangeBlocking(start: Long, endInclusive: Long): Boolean {
      return tryDownloadOnce(start, endInclusive, retries = 3)
    }

    fun snapshot(): CacheSnapshot = CacheSnapshot(
      totalSize = config.totalSize,
      downloadedBytes = downloaded.get(),
      fullyCached = store.isFullyCovered(0L, config.totalSize),
      lastWriteAtMs = lastWriteAtMs.get(),
      running = running.get(),
    )

    fun cachedAheadFrom(byteOffset: Long): Long = store.contiguousFrom(byteOffset.coerceIn(0L, config.totalSize))

    /**
     * Mark [start, endExclusive) as the live playback window.
     * Background sequential fill yields so seek can grab connections.
     *
     * Important: progressive BodyReader advances [start] every buffer — that must
     * **not** bump [priorityGen] or in-flight Range jobs get cancelled/ignored and
     * multi-conn appears "broken". Only real seeks bump the generation.
     */

    /**
     * The caller's requested window, or the default when they passed nothing /
     * a non-positive value (0 = "keep the built-in behaviour").
     */
    private val requestedReadAheadBytes: Long =
      if (config.readAheadBytes > 0) config.readAheadBytes else DEFAULT_READ_AHEAD_BYTES

    /** Clamped, enforcible playhead read-ahead target in bytes. */
    private fun readAheadBytes(): Long =
      requestedReadAheadBytes.coerceIn(MIN_READ_AHEAD_BYTES, MAX_READ_AHEAD_BYTES)

    /**
     * End of the playhead priority window starting at [from].
     *
     * Never shorter than a few chunks (so a tiny read-ahead cannot starve the
     * scheduler) and never past EOF.
     */
    private fun readAheadEnd(from: Long): Long {
      val target = maxOf(readAheadBytes(), config.chunkBytes.toLong() * 4L)
      return min(config.totalSize, from + target)
    }

    fun setPlayheadPriority(start: Long, endExclusive: Long) {
      val s = start.coerceAtLeast(0L)
      val e = min(endExclusive, config.totalSize).coerceAtLeast(s)
      val prevStart = priorityStart.get()
      val prevEnd = priorityEnd.get()
      // Progressive BodyReader only moves start forward inside the current window —
      // that must NOT count as a seek. Real seeks jump backward or far past prevEnd.
      val isNewSeek = prevStart < 0L ||
        s + PRIORITY_SEEK_DELTA < prevStart ||
        s > prevEnd + PRIORITY_SEEK_DELTA

      priorityStart.set(s)
      // Grow the window forward while playing; on a real seek, replace the window.
      priorityEnd.set(
        if (isNewSeek) e else maxOf(e, prevEnd),
      )
      // Non-expiring memory of the playhead, for [fillAnchor]. Only moves forward
      // on sequential reads; a real seek may legitimately move it backwards.
      if (isNewSeek) {
        lastPlayheadOffset.set(s)
      } else {
        lastPlayheadOffset.set(maxOf(lastPlayheadOffset.get(), s))
      }
      if (isNewSeek) {
        priorityGen.incrementAndGet()
        log("playhead priority SEEK $s-$e")
      }
      priorityDeadlineMs.set(System.currentTimeMillis() + PRIORITY_TTL_MS)
    }

    /**
     * Where the sequential filler should actually be filling, given [current].
     *
     * Normally this is just [current] (plain sequential tip fill). But when the
     * playhead is somewhere this loop has not reached yet, the playhead is what
     * matters and the cursor is dragged forward to it.
     *
     * Uses [lastPlayheadOffset] rather than only [activePriorityWindow] because the
     * window expires after [PRIORITY_TTL_MS] of no reads -- and the playhead goes
     * quiet precisely when it is starved, i.e. exactly when re-anchoring matters
     * most. Without this, a seek to the middle of a 27 GiB file leaves the filler
     * marching up from byte 0, tens of GiB behind, while the player rebuffers.
     *
     * Only ever moves *forward*: re-anchoring backwards would reverse the cursor we
     * already advanced and thrash on every seek. A tolerance keeps it from twitching
     * for sub-chunk playhead wobble.
     */
    private fun fillAnchor(current: Long): Long {
      val playhead = maxOf(
        activePriorityWindow()?.first ?: -1L,
        lastPlayheadOffset.get(),
      )
      if (playhead < 0L) return current
      if (playhead <= current + FILL_ANCHOR_TOLERANCE) return current
      // Never jump past EOF.
      return min(playhead, config.totalSize)
    }

    /**
     * The most recent playhead byte offset, whether or not its priority window is
     * still refreshing.
     *
     * [activePriorityWindow] expires after [PRIORITY_TTL_MS] of no reads, which is
     * correct for *prioritising* work — but it is exactly the wrong signal for the
     * sequential filler, because the playhead stops issuing reads precisely when it
     * has been starved. Falling back to "start from the file head" in that moment is
     * how a seek to the middle of a 27 GiB file ends up racing a filler that is 13 GiB
     * behind. So keep a separate, non-expiring record of where the player last was.
     */
    private val lastPlayheadOffset = AtomicLong(-1L)

    private fun activePriorityWindow(): Pair<Long, Long>? {
      val s = priorityStart.get()
      val e = priorityEnd.get()
      if (s < 0L || e <= s) return null
      if (System.currentTimeMillis() > priorityDeadlineMs.get()) {
        // Expire only when nothing has refreshed the deadline (no reads / seeks).
        priorityStart.compareAndSet(s, -1L)
        return null
      }
      return s to min(e, config.totalSize)
    }

    /**
     * Ensure [start, endExclusive) is cached. Uses **parallel** workers for holes
     * so seek does not wait on single-thread sequential fill.
     *
     * Timeouts are moderate: return as soon as the requested span is contiguous,
     * but never cancel in-flight Range downloads just because the wait elapsed —
     * those bytes are still needed for the next BodyReader iteration.
     */
    fun ensureRange(start: Long, endExclusive: Long, timeoutMs: Long): Boolean {
      val end = min(endExclusive, config.totalSize)
      if (start >= end) return true
      setPlayheadPriority(start, readAheadEnd(start))
      val cappedTimeout = timeoutMs.coerceIn(500L, MAX_ENSURE_TIMEOUT_MS)
      val deadline = System.currentTimeMillis() + cappedTimeout
      var spins = 0
      var lastHave = -1L
      while (running.get() && System.currentTimeMillis() < deadline) {
        val have = store.contiguousFrom(start)
        if (start + have >= end) return true
        val holeStart = start + have
        // In transcode mode never plan past produced bytes + bounded look-ahead.
        val ceiling = schedulerCeiling()
        if (holeStart >= ceiling) {
          // Nothing to fetch yet — the transcoder has not produced these bytes.
          // Return "partial" so the caller streams what exists and waits.
          log("ensureRange waiting for transcode watermark hole=$holeStart ceil=$ceiling")
          return false
        }
        // Size the parallel fill window from the *configured* connection count.
        //
        // This used to be `connections.coerceAtMost(6)`. On a 16-connection setup
        // that silently capped the window at 6 chunks (~8.8 MiB with a 1.5 MiB
        // chunk), so even though 16 connections were open only 6 slices were ever
        // in flight and the bytes the playhead needed stayed sparse. A sparse
        // window is useless to the player: `contiguousFrom(pos)` stays tiny and mpv
        // reports demuxCacheSec ~= 0 and rebuffers every few seconds.
        //
        // Keep a sane upper bound (12, matching fillWindowParallel / the stripe
        // filler) so one ensureRange call cannot flood the origin, but honour the
        // real connection count below that.
        val windowChunks = config.connections.coerceIn(2, 12)
        val windowEnd = min(
          min(end, ceiling),
          holeStart + config.chunkBytes.toLong() * windowChunks,
        )
        val filled = fillWindowParallel(holeStart, windowEnd, deadline)
        val nowHave = store.contiguousFrom(start)
        if (nowHave > lastHave) {
          lastHave = nowHave
          spins = 0
        } else {
          spins++
          if (!filled || spins >= 2) {
            runCatching { Thread.sleep(40L * spins.coerceAtMost(6)) }
          }
        }
        // Diagnostic for the "playhead starved while downloads run" case: shows the
        // exact contiguous runway at the playhead and how wide the parallel window
        // actually is. Without this, a stalled playhead is indistinguishable from a
        // slow link in the log.
        if (spins in 1..2 && nowHave < end - start) {
          logThrottled("ensureRange") {
            "ensureRange spin=$spins start=$start end=$end have=$nowHave " +
              "hole=$holeStart windowEnd=$windowEnd chunks=$windowChunks filled=$filled"
          }
        }
        if (start + nowHave >= end) return true
      }
      val finalHave = store.contiguousFrom(start)
      val success = start + finalHave >= end
      if (!success) {
        log("ensureRange partial start=$start end=$end have=$finalHave")
      }
      // Partial is OK — caller streams available bytes; downloads keep running.
      return success
    }

    /**
     * Download [from, endExclusive) with up to N parallel Range requests.
     *
     * Sliding-window ("pipelined") scheduler: whenever a slot frees up we start the
     * next slice immediately, instead of dispatching a fixed batch and blocking
     * until every member of that batch finishes.
     *
     * The old batch form was the main cause of the sawtooth throughput users see as
     * "loads fast, then crawls": `jobs.forEach { it.get() }` waited for the *slowest*
     * slice, so one stalled Range GET left the other N-1 connections idle. With a
     * 3x-slow outlier that averages ~1/3 of ideal, and it gets worse as the worker
     * count grows — which is why raising connections from 8 to 16 made things
     * slower rather than faster. Keeping every connection busy hides per-request
     * jitter (proxy RTT, Emby-side scheduling) instead of amplifying it.
     *
     * @return true if any progress was made or region already covered
     */
    private fun fillWindowParallel(from: Long, endExclusive: Long, deadline: Long): Boolean {
      val end = min(min(endExclusive, schedulerCeiling()), config.totalSize)
      if (from >= end) return false
      if (store.isFullyCovered(from, end)) return true
      val chunk = config.chunkBytes.toLong()
      val workers = config.connections.coerceIn(2, 12)
      val cursor = WindowCursor(from)
      val genAtStart = priorityGen.get()
      // In-flight slices, kept short (a handful of entries) so the poll below stays cheap.
      val inflight = ArrayList<CompletableFuture<Boolean>>(workers)

      /** True while this fill still owns the area around [offset]. */
      fun stillWanted(offset: Long): Boolean {
        if (!running.get()) return false
        if (priorityGen.get() == genAtStart) return true
        // A real seek superseded this window: keep going only if the new window
        // still covers the slice we are about to start.
        val ps = priorityStart.get()
        val pe = priorityEnd.get()
        return ps >= 0L && offset < pe && (offset + chunk) > ps
      }

      while (true) {
        if (System.currentTimeMillis() >= deadline) break
        if (!stillWanted(cursor.pos)) break
        // Top up every free slot rather than refilling only after the whole batch ends.
        topUp(inflight, workers, end, chunk, cursor, ::stillWanted)
        if (inflight.isEmpty()) break
        // Wait for *any* completion (not all), then reclaim finished slots.
        // Never cancel in-flight Range GETs — cancelling them was the main reason
        // multi-conn "stopped working" after the anti-stall patch.
        val waitMs = (deadline - System.currentTimeMillis()).coerceIn(20L, 1_000L)
        awaitAny(inflight, waitMs)
        inflight.removeAll { it.isDone }
      }

      if (cursor.scheduled == 0) {
        return store.contiguousFrom(from) > 0 || store.isFullyCovered(from, end)
      }
      // Let already-running slices finish rather than abandoning them; they are
      // bounded by the per-connection read timeout.
      val drainMs = (deadline - System.currentTimeMillis()).coerceIn(0L, 2_000L)
      inflight.forEach { f ->
        runCatching { f.get(drainMs, TimeUnit.MILLISECONDS) }
      }
      return store.contiguousFrom(from) > 0 || store.isFullyCovered(from, end)
    }

    /** Mutable scheduling cursor shared by the window loops. */
    private class WindowCursor(var pos: Long) {
      var scheduled = 0
    }

    /**
     * Block until any future in [futures] completes, or [waitMs] elapses.
     *
     * Deliberately avoids `CompletableFuture.anyOf(*futures.toTypedArray())`:
     *   * the spread operator force-copies the whole array on every call, which
     *     detekt flags (SpreadOperator) and which this hot path calls once per
     *     scheduling round;
     *   * `anyOf` has no Collection overload, only varargs, so the spread is not
     *     avoidable if we go through it.
     * A latch is the cheaper and clearer primitive here: whoever finishes first
     * trips it, and the caller polls the same way it did before.
     */
    private fun awaitAny(futures: List<CompletableFuture<Boolean>>, waitMs: Long) {
      if (futures.isEmpty()) return
      if (futures.any { it.isDone }) return
      val anyDone = CountDownLatch(1)
      futures.forEach { f ->
        f.whenComplete { _, _ -> anyDone.countDown() }
      }
      runCatching { anyDone.await(waitMs, TimeUnit.MILLISECONDS) }
    }

    /**
     * Start slices until [inflight] holds [workers] entries or [end] is reached.
     *
     * Kept separate from [fillWindowParallel] so both the priority window and the
     * stripe filler can drive the same pipelined scheduling without duplicating the
     * contiguous-skip and seek-guard logic.
     */
    private fun topUp(
      inflight: MutableList<CompletableFuture<Boolean>>,
      workers: Int,
      end: Long,
      chunk: Long,
      cursor: WindowCursor,
      stillWanted: (Long) -> Boolean,
    ) {
      while (inflight.size < workers && cursor.pos < end) {
        if (!stillWanted(cursor.pos)) break
        val already = store.contiguousFrom(cursor.pos)
        if (already > 0) {
          cursor.pos += already
          continue
        }
        val rangeEnd = min(end, cursor.pos + chunk) - 1
        if (rangeEnd < cursor.pos) break
        val start = cursor.pos
        cursor.pos = rangeEnd + 1
        cursor.scheduled++
        inflight += scheduleRangeDownload(start, rangeEnd)
      }
    }

    fun startBackground(afterOffset: Long) {
      // acceptLoop already started in init.
      if (afterOffset >= config.totalSize) return
      // Stripe filler: sequential tip fill, but always yields to seek priority.
      serverExecutor.execute { stripeFillLoop(afterOffset) }
      log(
        "background stripe filler from $afterOffset workers=${config.connections} " +
          "chunkKb=${config.chunkBytes / 1024} readAheadMb=${config.readAheadBytes / (1024 * 1024)}",
      )
    }

    /**
     * Fill remaining file with parallel stripes, but **priority playhead first**.
     * Stale priority windows expire so a failed seek cannot freeze all progress.
     * In-flight Range downloads are never cancelled — only new scheduling yields.
     */
    private fun stripeFillLoop(from: Long) {
      var pos = from
      val stripe = config.connections.coerceIn(2, 12)
      val chunk = config.chunkBytes.toLong()
      while (running.get() && pos < config.totalSize) {
        // 0) Re-anchor the sequential fill to the playhead FIRST.
        //
        // This must run *before* the priority-window block below. That block can
        // `continue` the loop (when a fill attempt makes no progress), so any
        // anchor logic placed after it is unreachable in exactly the situation it
        // exists for: a seek far ahead of the sequential cursor. In the field this
        // showed up as the re-anchor log line never firing at all while the player
        // rebuffered -- the cursor kept marching up from the file head, tens of GiB
        // behind the playhead.
        //
        // The player seeks: on a 27 GiB file it can jump to a byte offset that is
        // *tens of GiB* past where this loop started. Filling sequentially from the
        // original `from` (the file head) then walks toward the playhead at link
        // speed -- minutes or hours behind, during which the playhead has literally
        // zero buffered bytes and rebuffers continuously.
        //
        // So: whenever a live priority window points at a region this loop is still
        // far behind, move the sequential cursor to the playhead's own offset. The
        // bytes before it are not worthless (they are the file), but they are
        // useless *now*, and "now" is the only thing that decides whether playback
        // stalls. The head/tail ranges mpv needs for demux are already fetched
        // eagerly at open time.
        val anchor = fillAnchor(pos)
        if (anchor != pos) {
          log("stripe fill re-anchored $pos -> $anchor (playhead moved)")
          pos = anchor
        }

        // 1) Serve seek/playhead holes first (if still active).
        // Sparse multi-conn can leave holes at the playhead while total downloaded is large;
        // always drain the live window before sequential tip fill.
        val priority = activePriorityWindow()
        if (priority != null) {
          val (pStart, pEnd) = priority
          val holeStart = pStart + store.contiguousFrom(pStart)
          if (holeStart < pEnd) {
            val deadline = System.currentTimeMillis() + 10_000L
            val filled = fillWindowParallel(holeStart, pEnd, deadline)
            // Do NOT spin here until the whole window is contiguous.
            //
            // The window can be large (readAheadBytes, up to hundreds of MiB) while
            // the link may only deliver single-digit Mbps, so "wait until fully
            // filled" means "never prefetch anything beyond the playhead". That is
            // exactly the stall we are trying to fix: the player empties its cache
            // while the filler keeps re-attacking the same oversized window.
            //
            // Waiting is only useful when the hole is *inside the bytes the player
            // is about to read next* — i.e. a short leading gap. Once we have made
            // progress, fall through to sequential tip fill, which is what actually
            // builds a runway ahead of the playhead.
            val madeProgress = store.contiguousFrom(pStart) + pStart > holeStart
            if (!filled && !madeProgress) {
              // Nothing at all came back: back off briefly so we do not hot-spin on
              // a dead origin, then retry from the top of the loop.
              runCatching { Thread.sleep(20) }
              continue
            }
          }
        }

        // 2) Sequential tip fill (after priority is satisfied or expired).
        val already = store.contiguousFrom(pos)
        if (already > 0) {
          pos += already
          continue
        }
        // Transcode mode: the stripe filler must stay inside produced bytes.
        // Requesting past the watermark would hang or return 200-from-zero and
        // would also race the priority reader for the same scarce transcode
        // throughput. Wait for the watermark to advance instead.
        if (config.transcodeMode) {
          val ceiling = schedulerCeiling()
          if (pos >= ceiling) {
            runCatching { Thread.sleep(120) }
            continue
          }
        }
        // Priority window was handled above. Only yield to it here if the playhead
        // itself still has an *unread* leading gap; a merely "sparse but progressing"
        // window must not starve sequential tip fill or we never build a runway.
        val livePriority = activePriorityWindow()
        if (livePriority != null) {
          val (ps2, _) = livePriority
          val leadHave = store.contiguousFrom(ps2)
          if (leadHave <= 0L) continue
        }

        // Sliding-window ("pipelined") stripe fill.
        //
        // Previously this dispatched `stripe` slices and then blocked in
        // `jobs.forEach { it.get(30s) }` until *every* one of them finished, so a
        // single slow slice idled the whole stripe. That is the same sawtooth the
        // priority path above used to have: throughput collapses to one connection's
        // worth while the rest wait, then recovers in a burst. Refill each free slot
        // as soon as it frees up so every connection stays busy and per-request
        // jitter is absorbed instead of amplified.
        val inflight = ArrayList<CompletableFuture<Boolean>>(stripe)
        var stripePos = pos
        val genAtSchedule = priorityGen.get()
        val stripeCeiling = schedulerCeiling()
        val stripeDeadline = System.currentTimeMillis() + 30_000L
        while (true) {
          while (inflight.size < stripe && stripePos < config.totalSize && stripePos < stripeCeiling) {
            val skip = store.contiguousFrom(stripePos)
            if (skip > 0) {
              stripePos += skip
              continue
            }
            val start = stripePos
            val end = min(min(config.totalSize, stripeCeiling), start + chunk) - 1
            if (end < start) break
            stripePos = end + 1
            inflight += scheduleRangeDownload(start, end) {
              // Skip only if a real seek happened after schedule and this slice is
              // far from the new playhead — do not drop work for progressive reads.
              if (priorityGen.get() != genAtSchedule) {
                val p = activePriorityWindow()
                if (p != null && (end < p.first || start > p.second)) return@scheduleRangeDownload false
              }
              true
            }
          }
          if (inflight.isEmpty()) break
          val waitMs = (stripeDeadline - System.currentTimeMillis()).coerceIn(20L, 1_000L)
          awaitAny(inflight, waitMs)
          inflight.removeAll { it.isDone }
          if (System.currentTimeMillis() >= stripeDeadline) break
        }
        if (stripePos <= pos && store.contiguousFrom(pos) <= 0) {
          var scan = pos
          while (scan < config.totalSize && store.contiguousFrom(scan) > 0) {
            scan += store.contiguousFrom(scan)
          }
          if (scan <= pos) {
            if (store.contiguousFrom(pos) + pos >= config.totalSize) break
            runCatching { Thread.sleep(100) }
          } else {
            pos = scan
          }
          continue
        }
        val progressed = store.contiguousFrom(pos)
        if (progressed <= 0) {
          val end = min(config.totalSize, pos + chunk) - 1
          downloadRangeBlocking(pos, end)
          val again = store.contiguousFrom(pos)
          if (again <= 0) {
            log("stripe stuck at $pos — sleep and retry")
            runCatching { Thread.sleep(150) }
          } else {
            pos += again
          }
        } else {
          pos += progressed
        }
      }
      log("stripe fill done downloaded=${downloaded.get()}/${config.totalSize}")
    }

    private fun scheduleRangeDownload(
      start: Long,
      endInclusive: Long,
      shouldRun: () -> Boolean = { true },
    ): CompletableFuture<Boolean> {
      if (store.isFullyCovered(start, endInclusive + 1)) {
        return CompletableFuture.completedFuture(true)
      }
      val key = "$start-$endInclusive"
      return inFlightRanges.computeIfAbsent(key) {
        val future = CompletableFuture<Boolean>()
        executor.execute {
          try {
            future.complete(shouldRun() && downloadRangeBlocking(start, endInclusive))
          } catch (e: Exception) {
            future.complete(false)
          } finally {
            inFlightRanges.remove(key, future)
          }
        }
        future
      }
    }

    private fun tryDownloadOnce(start: Long, endInclusive: Long, retries: Int): Boolean {
      if (!running.get()) return false
      if (start < 0L || endInclusive < start) return false
      if (start >= config.totalSize) return true
      val end = min(endInclusive, config.totalSize - 1)
      if (store.isFullyCovered(start, end + 1)) return true

      repeat(retries) { attempt ->
        if (!running.get()) return false
        val err = downloadRangeAttempt(start, end)
        if (err == null) {
          if (store.isFullyCovered(start, end + 1)) return true
          // Partial body is OK if we extended coverage; caller may re-request rest.
          if (store.contiguousFrom(start) > 0) {
            log("partial range $start-$end have=${store.contiguousFrom(start)}")
          }
        } else {
          log("range $start-$end try ${attempt + 1}: $err")
          runCatching { Thread.sleep(250L * (attempt + 1)) }
        }
      }
      return store.isFullyCovered(start, end + 1)
    }

    /** One Range GET attempt. Returns null on success, error message on failure. */
    private fun downloadRangeAttempt(start: Long, end: Long): String? {
      var conn: HttpURLConnection? = null
      // Tracks whether the exchange ended in a usable state. Only a fully consumed
      // body leaves the socket safe to return to the keep-alive pool.
      var reusable = false
      return try {
        conn = openConnection(
          config.originUrl,
          config.userAgent,
          config.requestHeaders.sanitizedForOrigin(),
          config.systemProxy,
        ).apply {
          requestMethod = "GET"
          setRequestProperty("Range", "bytes=$start-$end")
          instanceFollowRedirects = true
          connectTimeout = CONNECT_TIMEOUT_MS
          readTimeout = READ_TIMEOUT_MS
        }
        conn.connect()
        val code = conn.responseCode
        when {
          code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK ->
            "HTTP $code for $start-$end"
          code == HttpURLConnection.HTTP_OK && start != 0L ->
            handleRangeIgnored(start)
          else -> consumeRangeBody(conn, start, end) { reusable = true }
        }
      } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
      } finally {
        // Keep the socket alive on the happy path so the JVM's KeepAliveCache can
        // hand it to the next Range GET. Calling disconnect() unconditionally made
        // the "Connection: keep-alive" header a no-op: every slice paid a fresh
        // TCP + proxy handshake, which on a 60-150 ms RTT tunnel is 20-35% of the
        // slice's wall time spent transferring nothing. That is the mechanical
        // cause of throughput oscillating between "fine" and "stalled".
        //
        // Tear the connection down whenever the exchange did not finish cleanly:
        // on error/timeout the socket is unusable, and returning it to the pool
        // would hand a poisoned connection to the next caller.
        if (!reusable) {
          runCatching { conn?.disconnect() }
        }
      }
    }

    /**
     * Stream the accepted response body into the cache and report whether the
     * watermark may advance. Returns null on success, else a diagnostic message.
     *
     * @param onBodyConsumed signals that the socket may be pooled (see
     *   [writeStreamToFile]).
     */
    private fun consumeRangeBody(
      conn: HttpURLConnection,
      start: Long,
      end: Long,
      onBodyConsumed: () -> Unit,
    ): String? {
      val wrote = writeStreamToFile(conn.inputStream, start, end, onBodyConsumed)
      if (wrote <= 0 && !store.isFullyCovered(start, end + 1)) {
        return "no bytes written for $start-$end"
      }
      // Contiguous tip progress proves the server produced up to here, so the
      // watermark may advance. (Only ever moves forward.)
      if (config.transcodeMode) {
        advanceWatermarkTo(start + wrote + store.contiguousFrom(start + wrote))
      }
      return null
    }

    /**
     * The server ignored our Range and is re-sending from byte 0. For a transcode
     * session this means "bytes at [start] are not produced yet". Never write a body
     * that starts at 0 into offset [start] — that would corrupt the cache — so just
     * report it and let the scheduler back off the window.
     */
    private fun handleRangeIgnored(start: Long): String {
      if (config.transcodeMode) transcodeRangeDenied.set(true)
      return "Range ignored at $start (HTTP 200)"
    }

    /**
     * Raise the produced-bytes watermark to [candidate] if it is higher.
     *
     * Callers must only pass offsets derived from **contiguous progress starting at
     * the current watermark**, never from an isolated seek fill — a sparse seek far
     * ahead does not mean the transcoder skipped ahead.
     */
    private fun advanceWatermarkTo(candidate: Long) {
      val clamped = candidate.coerceIn(0L, config.totalSize)
      while (true) {
        val cur = producedWatermark.get()
        if (clamped <= cur) return
        if (producedWatermark.compareAndSet(cur, clamped)) {
          log("transcode watermark -> $clamped")
          return
        }
      }
    }

    /** Upper bound (exclusive) the scheduler may request in transcode mode. */
    private fun schedulerCeiling(): Long {
      if (!config.transcodeMode) return config.totalSize
      val wm = producedWatermark.get()
      return min(config.totalSize, wm + TRANSCODE_LOOKAHEAD_BYTES)
    }

    /** @return number of bytes written */
    /**
     * Streams a Range body into the cache file.
     *
     * @param onBodyConsumed invoked when the response body reached EOF, i.e. the
     *   socket is drained and `HttpURLConnection` may legally return it to the
     *   keep-alive pool. It is *not* invoked when we stop early at [endInclusive]
     *   (one buffer of trailing bytes is still unread) or when playback is torn
     *   down mid-read, because in both cases a pooled connection would hand
     *   leftover body bytes to the next request.
     */
    private fun writeStreamToFile(
      stream: InputStream,
      start: Long,
      endInclusive: Long,
      onBodyConsumed: () -> Unit = {},
    ): Long {
      val input = BufferedInputStream(stream, 64 * 1024)
      val buf = ByteArray(64 * 1024)
      var writePos = start
      var written = 0L
      try {
        while (running.get() && writePos <= endInclusive) {
          val n = input.read(buf)
          if (n < 0) {
            onBodyConsumed()
            break
          }
          val maxWrite = (endInclusive + 1 - writePos).toInt()
          if (maxWrite <= 0) break
          val w = min(n, maxWrite)
          if (!running.get()) break
          synchronized(raf) {
            if (!running.get()) break
            raf.seek(writePos)
            raf.write(buf, 0, w)
          }
          val from = writePos
          writePos += w
          written += w
          downloaded.addAndGet(w.toLong())
          lastWriteAtMs.set(System.currentTimeMillis())
          store.mark(from, writePos)
        }
        // We asked for exactly `endInclusive + 1 - start` bytes. If the response
        // delivered every one of them, there is nothing left in flight worth
        // draining: the connection is safe to pool. (Servers that send a slightly
        // longer body fall through to the buffered case and get disconnected.)
        if (written >= endInclusive + 1 - start) onBodyConsumed()
      } finally {
        runCatching { input.close() }
      }
      return written
    }

    private fun acceptLoop() {
      val ss = serverSocket ?: return
      log("acceptLoop start")
      while (running.get()) {
        try {
          val socket = ss.accept()
          serverExecutor.execute { handleClient(socket) }
        } catch (e: Exception) {
          if (!running.get()) break
          log("accept error: ${e.message}")
        }
      }
      log("acceptLoop end")
    }

    private fun handleClient(socket: Socket) {
      try {
        socket.tcpNoDelay = true
        // Idle timeout for abandoned client sockets after scrubbing.
        socket.soTimeout = 90_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val request = readHttpRequest(input) ?: run {
          log("proxy: bad request")
          socket.close()
          return
        }
        log(
          "proxy ${request.method} ${request.path} " +
            "range=${request.headers["range"]} ua=${request.headers["user-agent"]}",
        )
        if (request.method != "GET" && request.method != "HEAD") {
          writeResponse(output, 405, "Method Not Allowed", "text/plain", 0, emptyMap(), null)
          output.flush()
          return
        }
        val range = parseRangeHeader(request.headers["range"], config.totalSize)
        val isHead = request.method == "HEAD"
        if (range != null) {
          val (from, to) = range
          val length = to - from + 1
          // Block briefly for the first slice, then stream via BodyReader.
          // Keep enough headroom for demux without multi-second freezes.
          val firstSliceBytes = if (from == 0L) {
            maxOf(config.chunkBytes.toLong(), MIN_STREAM_BYTES)
          } else {
            SEEK_START_BYTES
          }
          val firstSliceEnd = min(to + 1, from + firstSliceBytes)
          setPlayheadPriority(from, readAheadEnd(from))
          val pre = ensureRange(from, firstSliceEnd, 8_000L)
          // Per-request line, but mpv issues these in bursts: throttle to one per
          // second so a seek storm does not bury the more useful spin diagnostics.
          logThrottled("proxy206") {
            "proxy 206 $from-$to firstSlice=$pre need=${firstSliceEnd - from}"
          }
          // Only fail hard when we truly have nothing at the request start.
          // Returning 503 too eagerly made multi-conn look completely broken.
          if (store.contiguousFrom(from) <= 0L && !isHead) {
            // One more longer attempt before giving up.
            ensureRange(from, min(to + 1, from + MIN_STREAM_BYTES), 10_000L)
          }
          if (store.contiguousFrom(from) <= 0L && !isHead) {
            log("proxy 503 no data at $from")
            writeResponse(output, 503, "Service Unavailable", "text/plain", 0, emptyMap(), null)
            output.flush()
            return
          }
          val extra = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes $from-$to/${config.totalSize}",
          )
          writeResponse(
            output,
            206,
            "Partial Content",
            config.contentType,
            length,
            extra,
            if (isHead) null else BodyReader(from, length),
          )
        } else {
          val pre = ensureRange(0L, min(config.totalSize, 512 * 1024L), 8_000L)
          log("proxy 200 full firstSlice=$pre")
          writeResponse(
            output,
            200,
            "OK",
            config.contentType,
            config.totalSize,
            mapOf("Accept-Ranges" to "bytes"),
            if (isHead) null else BodyReader(0L, config.totalSize),
          )
        }
        output.flush()
      } catch (e: Exception) {
        log("proxy client error: ${e.message}")
      } finally {
        runCatching { socket.close() }
      }
    }

    private inner class BodyReader(private val start: Long, private val length: Long) {
      fun writeTo(out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        var pos = start
        var idleRounds = 0
        // No hard total-body deadline — long progressive files must stream for minutes.
        // Only abort after sustained no-progress (idleRounds). Closing early with a
        // declared Content-Length causes lavf to treat the short body as real EOF
        // mid-movie (false eof-reached while pos << duration).
        val ahead = readAheadBytes()
        while (remaining > 0 && running.get()) {
          val want = min(buf.size.toLong(), remaining).toInt()
          val needEnd = min(
            pos + maxOf(want.toLong(), MIN_STREAM_BYTES),
            pos + remaining,
          )
          setPlayheadPriority(pos, min(config.totalSize, pos + ahead))
          val ok = ensureRange(pos, needEnd, 10_000L)
          val avail = store.contiguousFrom(pos).toInt()
          if (avail <= 0) {
            idleRounds++
            val writing = System.currentTimeMillis() - lastWriteAtMs.get() < 20_000L
            val maxIdle = if (writing) {
              BODY_IDLE_ROUNDS_WHILE_DOWNLOADING
            } else {
              BODY_IDLE_ROUNDS_MAX
            }
            if (idleRounds > maxIdle) {
              log(
                "BodyReader abort at $pos remain=$remaining idle=$idleRounds " +
                  "ok=$ok writing=$writing downloaded=${downloaded.get()}",
              )
              PlaybackSessionLog.w(
                "SEG",
                "BodyReader short-close at byte=$pos remain=$remaining " +
                  "(likely false EOF for mpv)",
              )
              // Abort without padding — client sees connection close mid-body.
              break
            }
            // Re-hit the hole with a longer ensure while waiting.
            if (idleRounds % 10 == 0) {
              ensureRange(pos, min(config.totalSize, pos + MIN_STREAM_BYTES * 2), 12_000L)
            }
            runCatching { Thread.sleep(50) }
            continue
          }
          idleRounds = 0
          val toRead = min(want, avail)
          synchronized(raf) {
            raf.seek(pos)
            raf.readFully(buf, 0, toRead)
          }
          out.write(buf, 0, toRead)
          out.flush()
          pos += toRead
          remaining -= toRead
        }
        if (remaining > 0) {
          log("BodyReader short start=$start sent=${length - remaining}/$length")
        }
      }
    }

    fun close(deleteCache: Boolean = true) {
      running.set(false)
      executor.shutdownNow()
      serverExecutor.shutdownNow()
      runCatching { serverSocket?.close() }
      runCatching { raf.close() }
      if (deleteCache) {
        runCatching { cacheFile.delete() }
        runCatching { File(cacheFile.absolutePath + ".ranges").delete() }
      }
      store.clear()
    }

    private data class HttpRequest(
      val method: String,
      val path: String,
      val headers: Map<String, String>,
    )

    private fun readHttpRequest(input: InputStream): HttpRequest? {
      val first = readLine(input) ?: return null
      val parts = first.split(' ')
      if (parts.size < 2) return null
      val headers = HashMap<String, String>()
      while (true) {
        val line = readLine(input) ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx > 0) {
          headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
            line.substring(idx + 1).trim()
        }
      }
      return HttpRequest(parts[0].uppercase(Locale.US), parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
      val sb = StringBuilder()
      while (true) {
        val c = input.read()
        if (c < 0) return sb.toString().ifEmpty { null }
        if (c == '\n'.code) break
        if (c != '\r'.code) sb.append(c.toChar())
      }
      return sb.toString()
    }

    private fun writeResponse(
      out: OutputStream,
      code: Int,
      reason: String,
      type: String,
      contentLength: Long,
      extra: Map<String, String>,
      body: BodyReader?,
    ) {
      val sb = StringBuilder()
      sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
      sb.append("Content-Type: ").append(type).append("\r\n")
      sb.append("Content-Length: ").append(contentLength).append("\r\n")
      sb.append("Connection: close\r\n")
      // Help lavf treat the resource as seekable.
      sb.append("Accept-Ranges: bytes\r\n")
      extra.forEach { (k, v) ->
        if (!k.equals("Accept-Ranges", ignoreCase = true)) {
          sb.append(k).append(": ").append(v).append("\r\n")
        }
      }
      sb.append("\r\n")
      out.write(sb.toString().toByteArray(Charsets.US_ASCII))
      body?.writeTo(out)
    }

    private fun parseRangeHeader(header: String?, total: Long): Pair<Long, Long>? {
      if (header.isNullOrBlank() || !header.lowercase(Locale.US).startsWith("bytes=")) {
        return null
      }
      // Only first range (mpv/ffmpeg send a single range).
      val spec = header.substring(6).trim().substringBefore(',').trim()
      if (spec.startsWith("-")) {
        // suffix: bytes=-N
        val n = spec.substring(1).toLongOrNull() ?: return null
        if (n <= 0) return null
        val start = (total - n).coerceAtLeast(0L)
        return start to (total - 1)
      }
      val dash = spec.indexOf('-')
      if (dash < 0) return null
      val start = spec.substring(0, dash).toLongOrNull() ?: return null
      val endStr = spec.substring(dash + 1)
      val end = if (endStr.isEmpty()) {
        total - 1
      } else {
        endStr.toLongOrNull() ?: return null
      }
      if (start < 0L || start >= total) return null
      val clampedEnd = end.coerceIn(start, total - 1)
      return start to clampedEnd
    }
  }

  class ContiguousStore {
    private val lock = Object()
    private val map = TreeMap<Long, Long>()

    fun mark(start: Long, end: Long) {
      if (end <= start) return
      synchronized(lock) {
        var s = start
        var e = end
        val prev = map.floorEntry(s)
        if (prev != null && prev.value >= s) {
          s = prev.key
          e = maxOf(e, prev.value)
          map.remove(prev.key)
        }
        while (true) {
          val next = map.ceilingEntry(s) ?: break
          if (next.key > e) break
          e = maxOf(e, next.value)
          map.remove(next.key)
        }
        map[s] = e
        lock.notifyAll()
      }
    }

    fun contiguousFrom(pos: Long): Long = synchronized(lock) {
      val entry = map.floorEntry(pos)
      if (entry == null || entry.value <= pos) 0L else entry.value - pos
    }

    fun isFullyCovered(start: Long, endExclusive: Long): Boolean = synchronized(lock) {
      val entry = map.floorEntry(start)
      entry != null && entry.value >= endExclusive
    }

    fun clear() = synchronized(lock) {
      map.clear()
    }
  }
}

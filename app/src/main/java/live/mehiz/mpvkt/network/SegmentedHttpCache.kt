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
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 */
class SegmentedHttpCache(
  private val cacheDir: File,
  private val connections: Int,
  private val chunkBytes: Int,
  private val userAgent: String = DEFAULT_UA,
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
      val direct = resolveDirectMediaUrl(originalUrl, userAgent)
      if (!isAcceleratableUrl(direct)) {
        // Prefer resolved direct URL for mpv even when not multi-conn.
        return@runCatching OpenResult(direct, false)
      }
      startSession(direct)
    }.getOrElse {
      shutdownQuietly(deleteCache = true)
      OpenResult(originalUrl, false)
    }
  }

  private fun startSession(mediaUrl: String): OpenResult {
    val probe = probe(mediaUrl, userAgent)
    if (!probe.supportsRange || probe.contentLength < MIN_FILE_FOR_ACCEL) {
      return OpenResult(mediaUrl, false)
    }

    val connCount = connections.coerceIn(2, 16)
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
      ),
      cacheDir = cacheDir,
      log = {},
    )

    // Contiguous head before play — required for demux.
    val headOk = sess.downloadRangeBlocking(0L, headBytes - 1)
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

    /** Max playhead priority window size (bytes). */
    private const val MAX_PRIORITY_AHEAD = 4L * 1024L * 1024L

    /** Prefer asking for at least this many bytes ahead of the read cursor. */
    private const val MIN_STREAM_BYTES = 256L * 1024L

    /** First bytes needed to answer a seek quickly; more data is prefetched in background. */
    private const val SEEK_START_BYTES = 128L * 1024L

    /** Soft cap on empty-read retries before aborting one response body. */
    private const val BODY_IDLE_ROUNDS_MAX = 80

    /** Move this far (or jump outside window) before treating as a new seek generation. */
    private const val PRIORITY_SEEK_DELTA = 512L * 1024L

    fun isAcceleratableUrl(url: String): Boolean {
      val lower = url.lowercase(Locale.US)
      if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
      // Adaptive streaming / media-server endpoints — mpv handles these natively.
      if (isAdaptiveStreamingUrl(lower) || isEmbyLikeUrl(lower)) return false
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
    fun shouldTryAccelerate(url: String): Boolean {
      val lower = url.lowercase(Locale.US)
      if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
      return !isAdaptiveStreamingUrl(lower) && !isEmbyLikeUrl(lower)
    }

    private fun isAdaptiveStreamingUrl(urlLower: String): Boolean =
      urlLower.contains(".m3u8") ||
        urlLower.contains(".mpd") ||
        urlLower.contains("format=m3u") ||
        urlLower.contains("type=m3u8") ||
        urlLower.contains("/hls/") ||
        urlLower.contains("playlist")

    /**
     * Emby/Jellyfin endpoints often require auth headers and can represent transcoding
     * sessions or quality-selected streams. The local segmented proxy cannot preserve
     * that full protocol, so leave these URLs to mpv's native HTTP/HLS handling.
     */
    private fun isEmbyLikeUrl(urlLower: String): Boolean =
      urlLower.contains("/emby/") ||
        urlLower.contains("/jellyfin/") ||
        urlLower.contains("/videos/") && urlLower.contains("/stream") ||
        urlLower.contains("mediasourceid=") ||
        urlLower.contains("videobitrate=") ||
        urlLower.contains("audiobitrate=") ||
        urlLower.contains("transcoding")

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
    fun resolveDirectMediaUrl(url: String, userAgent: String = DEFAULT_UA): String {
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
        var current = url
        var hops = 0
        while (hops < 8) {
          hops++
          // HEAD first — no body, safe for one-shot OpenList signs.
          val head = openConnection(current, userAgent).apply {
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
          val get = openConnection(current, userAgent).apply {
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

    fun probe(url: String, userAgent: String = DEFAULT_UA): ProbeResult {
      // Prefer HEAD first — does not download body and is safer for signed URLs
      // that somehow still reached probe.
      val head = probeOnce(url, userAgent, useHead = true)
      if (head.supportsRange && head.contentLength >= MIN_FILE_FOR_ACCEL && !isHtmlType(head.contentType)) {
        return head
      }
      // Fallback tiny Range GET only when HEAD did not prove Range support.
      val get = probeOnce(url, userAgent, useHead = false)
      if (isHtmlType(get.contentType) || get.contentLength in 1 until 8 * 1024) {
        // HTML error page or tiny payload — not a progressive video.
        return ProbeResult(false, get.contentLength, get.contentType, get.finalUrl)
      }
      return get
    }

    private fun isHtmlType(type: String?): Boolean {
      val t = type?.lowercase(Locale.US) ?: return false
      return t.contains("text/html") || t.contains("application/xhtml")
    }

    private fun probeOnce(url: String, userAgent: String, useHead: Boolean): ProbeResult {
      val conn = openConnection(url, userAgent).apply {
        requestMethod = if (useHead) "HEAD" else "GET"
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
            conn.disconnect()
            ProbeResult(false, -1L, type, finalUrl)
          }
        }
      } catch (e: Exception) {
        runCatching { conn.disconnect() }
        ProbeResult(false, -1L, null, url)
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

    fun openConnection(url: String, userAgent: String): HttpURLConnection {
      val conn = URL(url).openConnection() as HttpURLConnection
      conn.setRequestProperty("User-Agent", userAgent)
      conn.setRequestProperty("Accept", "*/*")
      conn.setRequestProperty("Connection", "keep-alive")
      return conn
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
     * Playback/seek head — background filler yields to this region first.
     * [priorityGen] invalidates stale windows after rapid seeks so old targets
     * cannot monopolize all download workers forever.
     */
    private val priorityStart = AtomicLong(-1L)
    private val priorityEnd = AtomicLong(-1L)
    private val priorityGen = AtomicLong(0L)
    private val priorityDeadlineMs = AtomicLong(0L)
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
      if (isNewSeek) {
        priorityGen.incrementAndGet()
        log("playhead priority SEEK $s-$e")
      }
      priorityDeadlineMs.set(System.currentTimeMillis() + PRIORITY_TTL_MS)
    }

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
      setPlayheadPriority(start, min(end + config.chunkBytes.toLong(), start + MAX_PRIORITY_AHEAD))
      val cappedTimeout = timeoutMs.coerceIn(500L, MAX_ENSURE_TIMEOUT_MS)
      val deadline = System.currentTimeMillis() + cappedTimeout
      var spins = 0
      var lastHave = -1L
      while (running.get() && System.currentTimeMillis() < deadline) {
        val have = store.contiguousFrom(start)
        if (start + have >= end) return true
        val holeStart = start + have
        val windowEnd = min(
          end,
          holeStart + config.chunkBytes.toLong() * config.connections.coerceAtMost(6),
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
     * @return true if any progress was made or region already covered
     */
    private fun fillWindowParallel(from: Long, endExclusive: Long, deadline: Long): Boolean {
      val end = min(endExclusive, config.totalSize)
      if (from >= end) return true
      if (store.isFullyCovered(from, end)) return true
      val chunk = config.chunkBytes.toLong()
      val workers = config.connections.coerceIn(2, 12)
      val jobs = ArrayList<Future<*>>(workers)
      var pos = from
      var scheduled = 0
      val genAtStart = priorityGen.get()
      while (pos < end && scheduled < workers) {
        if (!running.get()) break
        // Stop scheduling more slices if a real seek superseded this window.
        if (priorityGen.get() != genAtStart) {
          val ps = priorityStart.get()
          val pe = priorityEnd.get()
          val overlaps = ps >= 0L && pos < pe && (pos + chunk) > ps
          if (!overlaps) break
        }
        val already = store.contiguousFrom(pos)
        if (already > 0) {
          pos += already
          continue
        }
        val rangeEnd = min(end, pos + chunk) - 1
        if (rangeEnd < pos) break
        val start = pos
        val stop = rangeEnd
        pos = rangeEnd + 1
        scheduled++
        jobs += scheduleRangeDownload(start, stop)
      }
      if (jobs.isEmpty()) {
        return store.contiguousFrom(from) > 0 || store.isFullyCovered(from, end)
      }
      // Wait for progress, but NEVER cancel in-flight Range GETs — cancelling them
      // was the main reason multi-conn "stopped working" after the anti-stall patch.
      val waitMs = (deadline - System.currentTimeMillis()).coerceIn(100L, 6_000L)
      jobs.forEach { f ->
        runCatching { f.get(waitMs, TimeUnit.MILLISECONDS) }
      }
      return store.contiguousFrom(from) > 0 || store.isFullyCovered(from, end)
    }

    fun startBackground(afterOffset: Long) {
      // acceptLoop already started in init.
      if (afterOffset >= config.totalSize) return
      // Stripe filler: sequential tip fill, but always yields to seek priority.
      serverExecutor.execute { stripeFillLoop(afterOffset) }
      log("background stripe filler from $afterOffset workers=${config.connections}")
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
        // 1) Serve seek/playhead holes first (if still active).
        val priority = activePriorityWindow()
        if (priority != null) {
          val (pStart, pEnd) = priority
          val need = !store.isFullyCovered(pStart, pEnd)
          if (need) {
            val deadline = System.currentTimeMillis() + 8_000L
            fillWindowParallel(pStart, pEnd, deadline)
            if (!store.isFullyCovered(pStart, min(pEnd, config.totalSize))) {
              runCatching { Thread.sleep(30) }
              continue
            }
            log("priority window filled $pStart-$pEnd")
          }
        }

        // 2) Sequential tip fill (after priority is satisfied or expired).
        val already = store.contiguousFrom(pos)
        if (already > 0) {
          pos += already
          continue
        }
        val livePriority = activePriorityWindow()
        if (livePriority != null) {
          val (ps2, pe2) = livePriority
          if (!store.isFullyCovered(ps2, pe2)) continue
        }

        val jobs = ArrayList<Future<*>>(stripe)
        var stripePos = pos
        val genAtSchedule = priorityGen.get()
        repeat(stripe) {
          if (stripePos >= config.totalSize) return@repeat
          val skip = store.contiguousFrom(stripePos)
          if (skip > 0) {
            stripePos += skip
            return@repeat
          }
          val start = stripePos
          val end = min(config.totalSize, start + chunk) - 1
          stripePos = end + 1
          jobs += scheduleRangeDownload(start, end) {
            // Skip only if a real seek happened after schedule and this slice is
            // far from the new playhead — do not drop work for progressive reads.
            if (priorityGen.get() != genAtSchedule) {
              val p = activePriorityWindow()
              if (p != null && (end < p.first || start > p.second)) return@scheduleRangeDownload false
            }
            true
          }
        }
        if (jobs.isEmpty()) {
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
        jobs.forEach { f ->
          runCatching { f.get(30, TimeUnit.SECONDS) }
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
    ): Future<Boolean> {
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
      return try {
        conn = openConnection(config.originUrl, config.userAgent).apply {
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
            "Range ignored at $start (HTTP 200)"
          else -> {
            val wrote = writeStreamToFile(conn.inputStream, start, end)
            if (wrote > 0 || store.isFullyCovered(start, end + 1)) {
              null
            } else {
              "no bytes written for $start-$end"
            }
          }
        }
      } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
      } finally {
        runCatching { conn?.disconnect() }
      }
    }

    /** @return number of bytes written */
    private fun writeStreamToFile(stream: InputStream, start: Long, endInclusive: Long): Long {
      val input = BufferedInputStream(stream, 64 * 1024)
      val buf = ByteArray(64 * 1024)
      var writePos = start
      var written = 0L
      try {
        while (running.get() && writePos <= endInclusive) {
          val n = input.read(buf)
          if (n < 0) break
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
          setPlayheadPriority(from, min(config.totalSize, from + MAX_PRIORITY_AHEAD))
          val pre = ensureRange(from, firstSliceEnd, 8_000L)
          log("proxy 206 $from-$to firstSlice=$pre need=${firstSliceEnd - from}")
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
        // Only abort after sustained no-progress (idleRounds), which still unsticks seeks.
        val ahead = maxOf(config.chunkBytes.toLong() * 3L, MAX_PRIORITY_AHEAD)
        while (remaining > 0 && running.get()) {
          val want = min(buf.size.toLong(), remaining).toInt()
          val needEnd = min(
            pos + maxOf(want.toLong(), MIN_STREAM_BYTES),
            pos + remaining,
          )
          setPlayheadPriority(pos, min(config.totalSize, pos + ahead))
          val ok = ensureRange(pos, needEnd, 6_000L)
          val avail = store.contiguousFrom(pos).toInt()
          if (avail <= 0) {
            idleRounds++
            if (idleRounds > BODY_IDLE_ROUNDS_MAX) {
              log("BodyReader abort at $pos remain=$remaining idle=$idleRounds ok=$ok")
              // Abort without padding — client sees connection close mid-body and retries.
              break
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

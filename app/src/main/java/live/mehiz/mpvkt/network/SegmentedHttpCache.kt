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

  /**
   * Open segmented session. On failure [OpenResult.playPath] is [originalUrl]
   * and [OpenResult.usedSegmented] is false.
   */
  fun open(originalUrl: String): OpenResult {
    // Do not reset DiagLog sinks here — PlayerActivity already points them at
    // external Android/data/.../files/logs + internal cache.
    logE("open() url=$originalUrl conn=$connections chunk=$chunkBytes")
    return runCatching {
      val direct = resolveDirectMediaUrl(originalUrl, userAgent)
      if (direct != originalUrl) {
        logE("resolved openlist/redirect → $direct")
      }
      if (!isAcceleratableUrl(direct)) {
        logE("skip: not acceleratable after resolve")
        // Prefer resolved direct URL for mpv even when not multi-conn.
        return@runCatching OpenResult(direct, false)
      }
      startSession(direct)
    }.getOrElse { e ->
      logE("segmented open failed → direct: ${e.message}", e)
      shutdownQuietly()
      OpenResult(originalUrl, false)
    }
  }

  private fun startSession(mediaUrl: String): OpenResult {
    val probe = probe(mediaUrl, userAgent)
    logE(
      "probe range=${probe.supportsRange} len=${probe.contentLength} " +
        "type=${probe.contentType} final=${probe.finalUrl}",
    )
    if (!probe.supportsRange || probe.contentLength < MIN_FILE_FOR_ACCEL) {
      logE("skip segmented range=${probe.supportsRange} len=${probe.contentLength}")
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
      log = { msg -> logE(msg) },
    )

    // Contiguous head before play — required for demux.
    val headOk = sess.downloadRangeBlocking(0L, headBytes - 1)
    val have = sess.store.contiguousFrom(0L)
    logE("head download ok=$headOk have=$have need=$headBytes")
    if (!headOk || have < min(headBytes, MIN_HEAD_TO_START)) {
      logE("head incomplete have=$have need=$headBytes → direct")
      sess.close()
      return OpenResult(mediaUrl, false)
    }

    // Tail for moov-at-end progressive mp4/mkv (common on CDN progressive files).
    if (tailBytes > 0 && probe.contentLength > headBytes + tailBytes) {
      val tailStart = probe.contentLength - tailBytes
      val tailOk = sess.downloadRangeBlocking(tailStart, probe.contentLength - 1)
      logE("tail download ok=$tailOk start=$tailStart size=$tailBytes")
    }

    sess.startBackground(afterOffset = have)
    session = sess

    logE(
      "segmented OK head=$have total=${probe.contentLength} " +
        "workers=$connCount → ${sess.localUrl}",
    )
    return OpenResult(sess.localUrl, true)
  }

  fun close() = shutdownQuietly()

  private fun shutdownQuietly() {
    session?.close()
    session = null
    logE("shutdown")
  }

  /** Release-safe logger ([DiagLog] uses Log.println so R8 won't strip it). */
  private fun logE(msg: String, err: Throwable? = null) {
    DiagLog.e(TAG, msg, err)
  }

  companion object {
    private const val TAG = "SegmentedHttpCache"
    private const val DEFAULT_UA =
      "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val MIN_CHUNK = 256 * 1024
    private const val MAX_CHUNK = 4 * 1024 * 1024

    /** Contiguous head downloaded before playback starts. */
    private const val HEAD_BYTES = 4L * 1024L * 1024L

    /** Tail for container index (moov / cues) at end of file. */
    private const val TAIL_BYTES = 1L * 1024L * 1024L
    private const val MIN_HEAD_TO_START = 512L * 1024L
    private const val MIN_FILE_FOR_ACCEL = 3L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun isAcceleratableUrl(url: String): Boolean {
      val lower = url.lowercase(Locale.US)
      if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
      // Adaptive streaming — mpv handles natively.
      val adaptive = lower.contains(".m3u8") ||
        lower.contains(".mpd") ||
        lower.contains("format=m3u") ||
        lower.contains("type=m3u8") ||
        lower.contains("/hls/") ||
        lower.contains("playlist")
      if (adaptive) return false
      // OpenList/Alist intermediate /d/?sign= links are NOT final media — resolve first.
      // Real CDN signed URLs (X-Amz-*) ARE acceleratable after resolve.
      if (isOpenListIntermediate(lower)) {
        DiagLog.e(TAG, "skip acceleratable: openlist intermediate (resolve first)")
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
      val adaptive = lower.contains(".m3u8") ||
        lower.contains(".mpd") ||
        lower.contains("format=m3u") ||
        lower.contains("type=m3u8") ||
        lower.contains("/hls/") ||
        lower.contains("playlist")
      return !adaptive
    }

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
            DiagLog.e(
              TAG,
              "resolve HEAD hop=$hops code=$code type=$type len=$len loc=${loc ?: "-"}",
            )
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
            DiagLog.e(TAG, "resolve HEAD hop=$hops fail: ${e.message}")
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
            DiagLog.e(
              TAG,
              "resolve GET hop=$hops code=$code type=$type len=$len loc=${loc ?: "-"}",
            )
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
            DiagLog.e(TAG, "resolve GET hop=$hops fail: ${e.message}")
          }
          break
        }
        current
      } catch (e: Exception) {
        DiagLog.e(TAG, "resolveDirect failed: ${e.message}", e)
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
        DiagLog.e(
          TAG,
          "probe rejects non-media type=${get.contentType} len=${get.contentLength}",
        )
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
        DiagLog.e(TAG, "probe error head=$useHead: ${e.message}", e)
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
    private var serverSocket: ServerSocket? = null
    val localUrl: String

    init {
      cacheDir.mkdirs()
      if (cacheFile.exists()) {
        runCatching { cacheFile.delete() }
      }
      raf = RandomAccessFile(cacheFile, "rw")
      raf.setLength(config.totalSize)
      executor = ThreadPoolExecutor(
        config.connections,
        config.connections,
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

    /**
     * Ensure [start, endExclusive) is in the cache, downloading missing holes.
     */
    fun ensureRange(start: Long, endExclusive: Long, timeoutMs: Long): Boolean {
      val end = min(endExclusive, config.totalSize)
      if (start >= end) return true
      val deadline = System.currentTimeMillis() + timeoutMs
      var spins = 0
      while (running.get() && System.currentTimeMillis() < deadline) {
        val have = store.contiguousFrom(start)
        if (start + have >= end) return true
        val holeStart = start + have
        val holeEnd = min(end, holeStart + config.chunkBytes) - 1
        val before = store.contiguousFrom(holeStart)
        val ok = downloadRangeBlocking(holeStart, holeEnd)
        val after = store.contiguousFrom(holeStart)
        if (!ok && after <= before) {
          spins++
          if (spins >= 3) {
            log("ensureRange no progress at $holeStart after $spins tries")
            // Brief backoff then continue until deadline.
            runCatching { Thread.sleep(150L * spins) }
          }
        } else {
          spins = 0
        }
      }
      val finalHave = store.contiguousFrom(start)
      val success = start + finalHave >= end
      if (!success) {
        log("ensureRange FAIL start=$start end=$end have=$finalHave")
      }
      return success
    }

    fun startBackground(afterOffset: Long) {
      // acceptLoop already started in init.
      if (afterOffset >= config.totalSize) return
      // Stripe filler: keep multi-conn benefit without scattering holes across the file.
      serverExecutor.execute { stripeFillLoop(afterOffset) }
      log("background stripe filler from $afterOffset workers=${config.connections}")
    }

    /**
     * Fill the file from [from] using parallel stripes of [connections] chunks,
     * waiting for each stripe before advancing — keeps the contiguous tip growing.
     */
    private fun stripeFillLoop(from: Long) {
      var pos = from
      val stripe = config.connections.coerceIn(2, 16)
      val chunk = config.chunkBytes.toLong()
      while (running.get() && pos < config.totalSize) {
        // Skip already-contiguous region (tail may already be present).
        val already = store.contiguousFrom(pos)
        if (already > 0) {
          pos += already
          continue
        }
        val jobs = ArrayList<Future<*>>(stripe)
        var stripePos = pos
        repeat(stripe) {
          if (stripePos >= config.totalSize) return@repeat
          val start = stripePos
          val end = min(config.totalSize, start + chunk) - 1
          stripePos = end + 1
          jobs += executor.submit {
            downloadRangeBlocking(start, end)
          }
        }
        // Wait for this stripe (interruptible).
        jobs.forEach { f ->
          runCatching { f.get(120, TimeUnit.SECONDS) }
        }
        val progressed = store.contiguousFrom(pos)
        if (progressed <= 0) {
          // Hole failed — force single-thread download of next chunk.
          val end = min(config.totalSize, pos + chunk) - 1
          downloadRangeBlocking(pos, end)
          val again = store.contiguousFrom(pos)
          if (again <= 0) {
            log("stripe stuck at $pos — sleep and retry")
            runCatching { Thread.sleep(400) }
          } else {
            pos += again
          }
        } else {
          pos += progressed
        }
      }
      log("stripe fill done downloaded=${downloaded.get()}/${config.totalSize}")
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
        socket.soTimeout = 120_000
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
          // Prefill a small window before headers so demux can start immediately.
          val prefillEnd = min(to + 1, from + 512 * 1024L)
          val pre = ensureRange(from, prefillEnd, 45_000L)
          log("proxy 206 $from-$to prefill=$pre")
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
          val pre = ensureRange(0L, min(config.totalSize, 512 * 1024L), 20_000L)
          log("proxy 200 full prefill=$pre")
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
        while (remaining > 0 && running.get()) {
          val want = min(buf.size.toLong(), remaining).toInt()
          val needEnd = pos + want
          val ok = ensureRange(pos, needEnd, 90_000L)
          val avail = store.contiguousFrom(pos).toInt()
          if (avail <= 0) {
            idleRounds++
            if (!ok || idleRounds > 40) {
              log("BodyReader abort at $pos remain=$remaining idle=$idleRounds")
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

    fun close() {
      running.set(false)
      executor.shutdownNow()
      serverExecutor.shutdownNow()
      runCatching { serverSocket?.close() }
      runCatching { raf.close() }
      log("closed downloaded=${downloaded.get()}/${config.totalSize}")
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
  }
}

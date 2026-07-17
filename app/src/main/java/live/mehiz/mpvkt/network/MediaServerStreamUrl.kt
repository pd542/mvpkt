@file:Suppress("ReturnCount")

package live.mehiz.mpvkt.network

import android.net.Uri
import java.util.Locale

/**
 * Emby / Jellyfin stream URL helpers.
 *
 * External clients often hand mpvKt a **transcode** URL with very low caps, e.g.
 * `VideoBitrate=64000` + `TranscodeReasons=ContainerBitrateExceedsLimit`, which
 * looks like "bad clarity" rather than a player bug. When the user prefers max
 * quality we rewrite those query params toward **direct / original** playback.
 */
object MediaServerStreamUrl {
  private val mediaServerHostHints = listOf(
    "/emby/",
    "/jellyfin/",
    "mediasourceid=",
    "/videos/",
  )

  /**
   * Query keys that force or describe a transcode ladder.
   * Dropped when rewriting for original quality (Static=true).
   */
  private val qualityCapParams = setOf(
    "videobitrate",
    "audiobitrate",
    "maxstreamingbitrate",
    "maxbitrate",
    "maxwidth",
    "maxheight",
    "width",
    "height",
    "videocodec",
    "audiocodec",
    "transcodereasons",
    "transcodingmaxaudiochannels",
    "transcodingcontainercodec",
    "transcodingprotocol",
    "requireavc",
    "requirehevc",
    "subtitlemethod",
    "segmentcontainer",
    "minsegments",
    "breakonnonkeyframes",
    "h264profile",
    "h264level",
    "hevcprofile",
    "hevclevel",
    "videoprofile",
    "videolevel",
    "videorange",
    "videorangelevel",
    "framerate",
    "maxframerate",
    "maxrefframes",
    "maxvideobitratedeviation",
    "maxaudiobitrate",
    "maxaudiobitratedeviation",
    "transcodingframerate",
    "enableautofrateconversion",
    "allowvideostreamcopy",
    "allowaudiostreamcopy",
    "copytimestamps",
  )

  /** Always keep authentication / session identity. */
  private val keepAlways = setOf(
    "api_key",
    "apikey",
    "deviceid",
    "mediasourceid",
    "playsessionid",
    "tag",
    "mediaclientid",
    "userid",
    "access_token",
    "x-emby-token",
  )

  fun isLikelyMediaServerStream(url: String): Boolean {
    val lower = url.lowercase(Locale.US)
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    return mediaServerHostHints.any { lower.contains(it) }
  }

  fun isLowQualityTranscode(url: String): Boolean {
    if (!isLikelyMediaServerStream(url)) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val names = uri.queryParameterNames.map { it.lowercase(Locale.US) }.toSet()
    if ("transcodereasons" in names) return true
    val videoBitrate = uri.getQueryParameterIgnoreCase("VideoBitrate")?.toLongOrNull()
    val maxStreaming = uri.getQueryParameterIgnoreCase("MaxStreamingBitrate")?.toLongOrNull()
    // < 2 Mbps is almost always a mobile/preview ladder, not original film quality.
    if (videoBitrate != null && videoBitrate in 1 until 2_000_000L) return true
    if (maxStreaming != null && maxStreaming in 1 until 2_000_000L) return true
    return false
  }

  /**
   * Prefer original/direct file for Emby/Jellyfin when [preferOriginal] is true.
   * Non-media-server URLs are returned unchanged.
   */
  fun preferOriginalQuality(url: String, preferOriginal: Boolean): RewriteResult {
    if (!preferOriginal || !isLikelyMediaServerStream(url)) {
      return RewriteResult(url, changed = false, reason = "unchanged")
    }
    val uri = runCatching { Uri.parse(url) }.getOrNull()
      ?: return RewriteResult(url, changed = false, reason = "parse-failed")

    val builder = uri.buildUpon().clearQuery()
    val kept = linkedMapOf<String, String>()
    for (name in uri.queryParameterNames) {
      val value = uri.getQueryParameter(name) ?: continue
      val lower = name.lowercase(Locale.US)
      when {
        lower == "static" -> Unit // re-set below
        lower in qualityCapParams -> Unit
        lower in keepAlways || shouldKeepParam(lower) -> kept[name] = value
        else -> kept[name] = value
      }
    }
    // Force direct/static stream so Emby serves the original container when possible.
    kept["Static"] = "true"
    // Drop residual Static=false if present under another casing — already cleared.

    for ((k, v) in kept) {
      builder.appendQueryParameter(k, v)
    }
    val rewritten = builder.build().toString()
    val changed = rewritten != url
    val reason = when {
      !changed -> "already-direct"
      isLowQualityTranscode(url) -> "drop-low-bitrate-transcode"
      else -> "prefer-static-original"
    }
    return RewriteResult(rewritten, changed = changed, reason = reason)
  }

  private fun shouldKeepParam(lower: String): Boolean {
    // Keep stream selection indices and subtitle picks; drop size/codec caps only.
    return lower == "audiostreamindex" ||
      lower == "subtitlestreamindex" ||
      lower == "starttimeticks" ||
      lower == "videostreamindex" ||
      lower.startsWith("x-")
  }

  private fun Uri.getQueryParameterIgnoreCase(name: String): String? {
    val target = name.lowercase(Locale.US)
    for (n in queryParameterNames) {
      if (n.lowercase(Locale.US) == target) return getQueryParameter(n)
    }
    return null
  }

  data class RewriteResult(
    val url: String,
    val changed: Boolean,
    val reason: String,
  )
}

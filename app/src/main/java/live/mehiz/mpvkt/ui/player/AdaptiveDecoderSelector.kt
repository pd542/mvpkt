package live.mehiz.mpvkt.ui.player

import android.util.Log
import `is`.xyz.mpv.MPVLib
import live.mehiz.mpvkt.preferences.DecoderPreferences

/**
 * Inspects the currently loaded video stream and picks the best practical
 * decoder / renderer path for Android libmpv.
 *
 * Priority is **correct color** over raw throughput when the two conflict
 * (notably Dolby Vision Profile 5 IPT, where mediacodec + forced yuv420p
 * produces a green cast).
 *
 * Note: detection helpers avoid complex raw-string Regex inside property
 * getters — detekt 1.23.x can crash on those PSI ranges in CI.
 */
object AdaptiveDecoderSelector {

  data class StreamInfo(
    val codec: String,
    val format: String,
    val pixfmt: String,
    val gamma: String,
    val primaries: String,
    val colormatrix: String,
    val sigPeak: Double?,
    val width: Int?,
    val height: Int?,
    val doviProfile: Long?,
    val bitDepthHint: Int?,
  ) {
    val blob: String =
      listOf(codec, format, pixfmt, gamma, primaries, colormatrix)
        .joinToString(" ")
        .lowercase()

    val isDolbyVision: Boolean = computeIsDolbyVision()

    val isProfile5: Boolean = computeIsProfile5()

    val isHdr: Boolean = computeIsHdr()

    val isHighBitDepth: Boolean = computeIsHighBitDepth()

    val isUhd: Boolean = computeIsUhd()

    fun hasEnoughMetadata(): Boolean {
      // Wait until mpv has populated something useful about the video track.
      return listOf(
        codec.isNotBlank(),
        format.isNotBlank(),
        gamma.isNotBlank(),
        pixfmt.isNotBlank(),
        doviProfile != null,
        (width ?: 0) > 0,
      ).any { it }
    }

    private fun computeIsDolbyVision(): Boolean = doviProfile != null || blob.hasAny(dolbyMarkers)

    private fun computeIsProfile5(): Boolean {
      val parsedProfile = if (isDolbyVision) parseProfileNumber(blob) else null
      return when {
        doviProfile != null -> doviProfile == PROFILE_5
        blob.hasAny(profile5Markers) -> true
        else -> parsedProfile == PROFILE_5
      }
    }

    private fun computeIsHdr(): Boolean {
      val peak = sigPeak ?: 0.0
      return listOf(
        isDolbyVision,
        peak > SDR_PEAK,
        gamma.lowercase().hasAny(hdrGammaMarkers),
        blob.hasAny(hdrMarkers),
      ).any { it }
    }

    private fun computeIsHighBitDepth(): Boolean {
      val hint = bitDepthHint ?: 0
      return listOf(
        hint >= HIGH_BIT_DEPTH,
        pixfmt.lowercase().hasAny(highBitDepthMarkers),
        blob.hasAny(highBitDepthTextMarkers),
      ).any { it }
    }

    private fun computeIsUhd(): Boolean {
      val w = width ?: 0
      val h = height ?: 0
      return listOf(
        w >= UHD_WIDTH,
        h >= UHD_HEIGHT,
        w * h >= UHD_PIXELS,
      ).any { it }
    }
  }

  data class DecoderPlan(
    val kind: StreamKind,
    val vo: String,
    val hwdec: String,
    val forceYuv420p: Boolean,
    val toneMapping: String?,
    val hdrComputePeak: String?,
    val reason: String,
  )

  enum class StreamKind {
    DOLBY_VISION_P5,
    DOLBY_VISION_OTHER,
    HDR,
    HIGH_BIT_DEPTH_SDR,
    SDR,
  }

  fun probeStreamInfo(): StreamInfo = StreamInfo(
    codec = joinProps(
      "video-codec",
      "current-tracks/video/codec",
      "current-tracks/video/decoder",
      "current-tracks/video/codec-profile",
    ),
    format = firstProp("video-format", "current-tracks/video/format-name"),
    pixfmt = firstProp(
      "video-params/pixelformat",
      "video-params/hw-pixelformat",
      "current-tracks/video/format-name",
    ),
    gamma = firstProp(
      "video-params/gamma",
      "video-params/transfer",
      "video-params/coltransfer",
    ),
    primaries = firstProp("video-params/primaries", "video-params/colorprimaries"),
    colormatrix = firstProp("video-params/colormatrix", "video-params/colorspace"),
    sigPeak = MPVLib.getPropertyDouble("video-params/sig-peak")
      ?: MPVLib.getPropertyDouble("video-params/max-luma"),
    width = firstInt("video-params/w", "dwidth")
      ?: readLongish("current-tracks/video/demux-w")?.toInt(),
    height = firstInt("video-params/h", "dheight")
      ?: readLongish("current-tracks/video/demux-h")?.toInt(),
    doviProfile = readLongish(
      "video-params/dolby-vision-profile",
      "video-params/dovi-profile",
      "current-tracks/video/dolby-vision-profile",
      "current-tracks/video/dovi-profile",
      "track-list/0/dolby-vision-profile",
      "metadata/dolby-vision-profile",
    ),
    bitDepthHint = readLongish(
      "video-params/component-bits",
      "video-params/bits-per-component",
      "current-tracks/video/component-bits",
    )?.toInt(),
  )

  /**
   * Build a decoder plan from stream info + user preferences.
   *
   * User prefs are treated as **capabilities / preferences**, not hard locks:
   * - [DecoderPreferences.tryHWDecoding] false → never enable hwdec
   * - [DecoderPreferences.gpuNext] false → only force gpu-next when color requires it (DV/HDR)
   * - [DecoderPreferences.useYUV420P] true → only applied for 8-bit SDR
   */
  fun planFor(info: StreamInfo, prefs: DecoderPreferences): DecoderPlan = when {
    info.isProfile5 -> dolbyVisionProfile5Plan()
    info.isDolbyVision -> dolbyVisionPlan()
    info.isHdr -> hdrPlan(prefs)
    info.isHighBitDepth -> highBitDepthSdrPlan(prefs)
    else -> sdrPlan(prefs)
  }

  /**
   * Apply [plan] to the live mpv instance. Returns true if any property changed.
   */
  fun applyPlan(plan: DecoderPlan): Boolean {
    val changed = listOf(
      applyVo(plan.vo),
      applyHwdec(plan.hwdec),
      applyYuvFilter(plan.forceYuv420p),
      applyOptionalProperty("tone-mapping", plan.toneMapping),
      applyOptionalProperty("hdr-compute-peak", plan.hdrComputePeak),
    ).any { it }

    logAndRefresh(plan, changed)
    return changed
  }

  private fun dolbyVisionProfile5Plan(): DecoderPlan = DecoderPlan(
    kind = StreamKind.DOLBY_VISION_P5,
    vo = "gpu-next",
    // Android mediacodec does not reliably expose DoVi IPT side data.
    hwdec = "no",
    forceYuv420p = false,
    toneMapping = "auto",
    hdrComputePeak = "yes",
    reason = "Dolby Vision Profile 5 (IPT) → gpu-next + software decode, no yuv420p",
  )

  private fun dolbyVisionPlan(): DecoderPlan = DecoderPlan(
    kind = StreamKind.DOLBY_VISION_OTHER,
    vo = "gpu-next",
    // Prefer SW for correct RPU/metadata path; still honor explicit "never HW" only.
    hwdec = "no",
    forceYuv420p = false,
    toneMapping = "auto",
    hdrComputePeak = "yes",
    reason = "Dolby Vision (non-P5) → gpu-next + software decode for metadata path",
  )

  private fun hdrPlan(prefs: DecoderPreferences): DecoderPlan = DecoderPlan(
    kind = StreamKind.HDR,
    vo = "gpu-next",
    // HDR10/HLG can often use hwdec; keep user's HW preference.
    hwdec = if (prefs.tryHWDecoding.get()) "auto" else "no",
    forceYuv420p = false,
    toneMapping = "auto",
    hdrComputePeak = "yes",
    reason = "HDR (PQ/HLG/HDR10) → gpu-next, keep high bit depth, tone-map to display",
  )

  private fun highBitDepthSdrPlan(prefs: DecoderPreferences): DecoderPlan = DecoderPlan(
    kind = StreamKind.HIGH_BIT_DEPTH_SDR,
    vo = if (prefs.gpuNext.get()) "gpu-next" else "gpu",
    hwdec = if (prefs.tryHWDecoding.get()) "auto" else "no",
    forceYuv420p = false,
    toneMapping = null,
    hdrComputePeak = null,
    reason = "10/12-bit SDR → preserve bit depth (no forced yuv420p)",
  )

  private fun sdrPlan(prefs: DecoderPreferences): DecoderPlan {
    val userHw = prefs.tryHWDecoding.get()
    val userYuv = prefs.useYUV420P.get()
    return DecoderPlan(
      kind = StreamKind.SDR,
      vo = if (prefs.gpuNext.get()) "gpu-next" else "gpu",
      hwdec = if (userHw) "auto" else "no",
      // Forced yuv420p is only safe-ish for 8-bit SDR compatibility cases.
      forceYuv420p = userYuv,
      toneMapping = null,
      hdrComputePeak = null,
      reason = sdrReason(userYuv, userHw),
    )
  }

  private fun sdrReason(userYuv: Boolean, userHw: Boolean): String = if (userYuv) {
    "8-bit SDR → user yuv420p compatibility filter enabled"
  } else {
    val hwLabel = if (userHw) "auto" else "no"
    "8-bit SDR → default path (hwdec=$hwLabel)"
  }

  private fun applyVo(targetVo: String): Boolean {
    val currentVo = prop("current-vo") ?: prop("vo") ?: ""
    val shouldChange = !currentVo.contains(targetVo)
    if (shouldChange) runCatching { MPVLib.setPropertyString("vo", targetVo) }
    return shouldChange
  }

  private fun applyHwdec(targetHwdec: String): Boolean {
    val hwdec = prop("hwdec") ?: ""
    val hwdecCurrent = prop("hwdec-current") ?: ""
    val shouldForceNo = targetHwdec == "no" && hwdecCurrent.isNotBlank() && hwdecCurrent != "no"
    val shouldChange = hwdec != targetHwdec || shouldForceNo
    if (shouldChange) runCatching { MPVLib.setPropertyString("hwdec", targetHwdec) }
    return shouldChange
  }

  private fun applyYuvFilter(forceYuv420p: Boolean): Boolean {
    val hasYuv = (prop("vf") ?: "").contains("yuv420p", ignoreCase = true)
    val shouldAdd = forceYuv420p && !hasYuv
    val shouldClear = !forceYuv420p && hasYuv
    when {
      shouldAdd -> runCatching { MPVLib.command("vf", "set", "format=yuv420p") }
      shouldClear -> clearVideoFilters()
    }
    return shouldAdd || shouldClear
  }

  private fun clearVideoFilters() {
    runCatching { MPVLib.command("vf", "clr") }
    runCatching { MPVLib.setPropertyString("vf", "") }
  }

  private fun applyOptionalProperty(name: String, target: String?): Boolean {
    val shouldChange = target != null && prop(name) != target
    if (shouldChange) runCatching { MPVLib.setPropertyString(name, target.orEmpty()) }
    return shouldChange
  }

  private fun logAndRefresh(plan: DecoderPlan, changed: Boolean) {
    if (changed) {
      val pos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
      runCatching { MPVLib.command("seek", pos.toString(), "absolute+exact") }
      Log.i(TAG, "Adaptive decoder applied: ${plan.kind} — ${plan.reason}")
    } else {
      Log.d(TAG, "Adaptive decoder already matched: ${plan.kind} — ${plan.reason}")
    }
  }

  /**
   * Parse a profile number from codec/metadata text without Regex.
   * Accepts forms like "profile 5", "profile=8", "p5", "P:5".
   */
  private fun parseProfileNumber(text: String): Long? {
    val lower = text.lowercase()
    return profileMarkers.firstNotNullOfOrNull { marker ->
      generateSequence(lower.indexOf(marker).takeIf { it >= 0 }) { previous ->
        lower.indexOf(marker, previous + marker.length).takeIf { it >= 0 }
      }.firstNotNullOfOrNull { index -> profileAt(lower, marker, index) }
    }
  }

  private fun profileAt(text: String, marker: String, index: Int): Long? {
    val start = skipSeparators(text, index + marker.length)
    val end = start.digitEnd(text)
    val hasDigit = start < text.length && text[start].isDigit()
    val validMarker = index == 0 || !text[index - 1].isLetterOrDigit()
    return text.substring(start, end).toLongOrNull().takeIf { hasDigit && validMarker }
  }

  private fun skipSeparators(text: String, start: Int): Int {
    var index = start
    while (index < text.length && text[index] in profileSeparators) index++
    return index
  }

  private fun Int.digitEnd(text: String): Int {
    var index = this
    while (index < text.length && text[index].isDigit()) index++
    return index
  }

  private fun prop(name: String): String? = MPVLib.getPropertyString(name)?.takeIf { it.isNotBlank() }

  private fun firstProp(vararg keys: String): String = keys.firstNotNullOfOrNull { prop(it) }.orEmpty()

  private fun firstInt(vararg keys: String): Int? = keys.firstNotNullOfOrNull { MPVLib.getPropertyInt(it) }

  private fun joinProps(vararg keys: String): String = keys.mapNotNull { prop(it) }.joinToString(" ")

  private fun readLongish(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
    MPVLib.getPropertyLong(key)
      ?: MPVLib.getPropertyInt(key)?.toLong()
      ?: MPVLib.getPropertyString(key)?.toLongOrNull()
  }

  private fun String.hasAny(markers: List<String>): Boolean = markers.any { contains(it) }

  private const val PROFILE_5 = 5L
  private const val SDR_PEAK = 1.0
  private const val HIGH_BIT_DEPTH = 10
  private const val UHD_WIDTH = 3800
  private const val UHD_HEIGHT = 2100
  private const val UHD_PIXELS = 3800 * 2000

  private val dolbyMarkers = listOf("dolby", "dovi", "dvhe", "dvh1", "dvav", "ipt", "ipt-pq")
  private val profile5Markers = listOf(
    "ipt",
    "ictcp",
    "ipt-pq",
    "dvhe.05",
    "dvhe.5",
    "dvh1.05",
    "dvh1.5",
    "profile 5",
    "profile=5",
    "profile:5",
    "profile_5",
    "profile-5",
    "profile.5",
  )
  private val hdrGammaMarkers = listOf("pq", "hlg", "st2084", "bt.2100", "smpte2084")
  private val hdrMarkers = listOf("hdr10", "hdr")
  private val highBitDepthMarkers = listOf(
    "p010",
    "p012",
    "p016",
    "yuv420p10",
    "yuv422p10",
    "yuv444p10",
    "10le",
    "10be",
    "12le",
    "16le",
  )
  private val highBitDepthTextMarkers = listOf("10bit", "12bit", "16bit")
  private val profileMarkers = listOf("profile", "p")
  private val profileSeparators = setOf(' ', '=', ':', '_', '-', '.')
}

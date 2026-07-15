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
      return codec.isNotBlank() ||
        format.isNotBlank() ||
        gamma.isNotBlank() ||
        pixfmt.isNotBlank() ||
        doviProfile != null ||
        (width != null && width > 0)
    }

    private fun computeIsDolbyVision(): Boolean {
      if (doviProfile != null) return true
      return blob.contains("dolby") ||
        blob.contains("dovi") ||
        blob.contains("dvhe") ||
        blob.contains("dvh1") ||
        blob.contains("dvav") ||
        blob.contains("ipt") ||
        blob.contains("ipt-pq")
    }

    private fun computeIsProfile5(): Boolean {
      if (doviProfile == 5L) return true
      if (doviProfile != null) return false

      // Profile 5 exclusive IPT color space markers.
      if (blob.contains("ipt") || blob.contains("ictcp") || blob.contains("ipt-pq")) {
        return true
      }

      // Common single-layer P5 codec ids: dvhe.05 / dvh1.05 / profile 5 / p5.
      if (blob.contains("dvhe.05") || blob.contains("dvhe.5") ||
        blob.contains("dvh1.05") || blob.contains("dvh1.5")
      ) {
        return true
      }
      if (blob.contains("profile 5") || blob.contains("profile=5") ||
        blob.contains("profile:5") || blob.contains("profile_5") ||
        blob.contains("profile-5") || blob.contains("profile.5")
      ) {
        return true
      }

      if (!isDolbyVision) return false
      val parsed = parseProfileNumber(blob)
      return parsed == 5L
    }

    private fun computeIsHdr(): Boolean {
      if (isDolbyVision) return true
      if ((sigPeak ?: 0.0) > 1.0) return true
      val g = gamma.lowercase()
      return g.contains("pq") ||
        g.contains("hlg") ||
        g.contains("st2084") ||
        g.contains("bt.2100") ||
        g.contains("smpte2084") ||
        blob.contains("hdr10") ||
        blob.contains("hdr")
    }

    private fun computeIsHighBitDepth(): Boolean {
      val hint = bitDepthHint
      if (hint != null && hint >= 10) return true
      val p = pixfmt.lowercase()
      return p.contains("p010") ||
        p.contains("p012") ||
        p.contains("p016") ||
        p.contains("yuv420p10") ||
        p.contains("yuv422p10") ||
        p.contains("yuv444p10") ||
        p.contains("10le") ||
        p.contains("10be") ||
        p.contains("12le") ||
        p.contains("16le") ||
        blob.contains("10bit") ||
        blob.contains("12bit") ||
        blob.contains("16bit")
    }

    private fun computeIsUhd(): Boolean {
      val w = width ?: 0
      val h = height ?: 0
      return w >= 3800 || h >= 2100 || w * h >= 3800 * 2000
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

  fun probeStreamInfo(): StreamInfo {
    val dovi = readLongish(
      "video-params/dolby-vision-profile",
      "video-params/dovi-profile",
      "current-tracks/video/dolby-vision-profile",
      "current-tracks/video/dovi-profile",
      "track-list/0/dolby-vision-profile",
      "metadata/dolby-vision-profile",
    )
    val bitDepth = readLongish(
      "video-params/component-bits",
      "video-params/bits-per-component",
      "current-tracks/video/component-bits",
    )?.toInt()

    return StreamInfo(
      codec = joinProps(
        "video-codec",
        "current-tracks/video/codec",
        "current-tracks/video/decoder",
        "current-tracks/video/codec-profile",
      ),
      format = prop("video-format") ?: prop("current-tracks/video/format-name") ?: "",
      pixfmt = prop("video-params/pixelformat")
        ?: prop("video-params/hw-pixelformat")
        ?: prop("current-tracks/video/format-name")
        ?: "",
      gamma = prop("video-params/gamma")
        ?: prop("video-params/transfer")
        ?: prop("video-params/coltransfer")
        ?: "",
      primaries = prop("video-params/primaries")
        ?: prop("video-params/colorprimaries")
        ?: "",
      colormatrix = prop("video-params/colormatrix")
        ?: prop("video-params/colorspace")
        ?: "",
      sigPeak = MPVLib.getPropertyDouble("video-params/sig-peak")
        ?: MPVLib.getPropertyDouble("video-params/max-luma"),
      width = MPVLib.getPropertyInt("video-params/w")
        ?: MPVLib.getPropertyInt("dwidth")
        ?: readLongish("current-tracks/video/demux-w")?.toInt(),
      height = MPVLib.getPropertyInt("video-params/h")
        ?: MPVLib.getPropertyInt("dheight")
        ?: readLongish("current-tracks/video/demux-h")?.toInt(),
      doviProfile = dovi,
      bitDepthHint = bitDepth,
    )
  }

  /**
   * Build a decoder plan from stream info + user preferences.
   *
   * User prefs are treated as **capabilities / preferences**, not hard locks:
   * - [DecoderPreferences.tryHWDecoding] false → never enable hwdec
   * - [DecoderPreferences.gpuNext] false → only force gpu-next when color requires it (DV/HDR)
   * - [DecoderPreferences.useYUV420P] true → only applied for 8-bit SDR
   */
  fun planFor(info: StreamInfo, prefs: DecoderPreferences): DecoderPlan {
    val userHw = prefs.tryHWDecoding.get()
    val userGpuNext = prefs.gpuNext.get()
    val userYuv = prefs.useYUV420P.get()

    return when {
      info.isProfile5 -> DecoderPlan(
        kind = StreamKind.DOLBY_VISION_P5,
        vo = "gpu-next",
        // Android mediacodec does not reliably expose DoVi IPT side data.
        hwdec = "no",
        forceYuv420p = false,
        toneMapping = "auto",
        hdrComputePeak = "yes",
        reason = "Dolby Vision Profile 5 (IPT) → gpu-next + software decode, no yuv420p",
      )

      info.isDolbyVision -> DecoderPlan(
        kind = StreamKind.DOLBY_VISION_OTHER,
        vo = "gpu-next",
        // Prefer SW for correct RPU/metadata path; still honor explicit "never HW" only.
        hwdec = "no",
        forceYuv420p = false,
        toneMapping = "auto",
        hdrComputePeak = "yes",
        reason = "Dolby Vision (non-P5) → gpu-next + software decode for metadata path",
      )

      info.isHdr -> DecoderPlan(
        kind = StreamKind.HDR,
        vo = "gpu-next",
        // HDR10/HLG can often use hwdec; keep user's HW preference.
        hwdec = if (userHw) "auto" else "no",
        forceYuv420p = false,
        toneMapping = "auto",
        hdrComputePeak = "yes",
        reason = "HDR (PQ/HLG/HDR10) → gpu-next, keep high bit depth, tone-map to display",
      )

      info.isHighBitDepth -> DecoderPlan(
        kind = StreamKind.HIGH_BIT_DEPTH_SDR,
        vo = if (userGpuNext) "gpu-next" else "gpu",
        hwdec = if (userHw) "auto" else "no",
        forceYuv420p = false,
        toneMapping = null,
        hdrComputePeak = null,
        reason = "10/12-bit SDR → preserve bit depth (no forced yuv420p)",
      )

      else -> {
        val hwLabel = if (userHw) "auto" else "no"
        val sdrReason = if (userYuv) {
          "8-bit SDR → user yuv420p compatibility filter enabled"
        } else {
          "8-bit SDR → default path (hwdec=$hwLabel)"
        }
        DecoderPlan(
          kind = StreamKind.SDR,
          vo = if (userGpuNext) "gpu-next" else "gpu",
          hwdec = hwLabel,
          // Forced yuv420p is only safe-ish for 8-bit SDR compatibility cases.
          forceYuv420p = userYuv,
          toneMapping = null,
          hdrComputePeak = null,
          reason = sdrReason,
        )
      }
    }
  }

  /**
   * Apply [plan] to the live mpv instance. Returns true if any property changed.
   */
  fun applyPlan(plan: DecoderPlan): Boolean {
    var changed = false

    val currentVo = prop("current-vo") ?: prop("vo") ?: ""
    if (!currentVo.contains(plan.vo)) {
      runCatching { MPVLib.setPropertyString("vo", plan.vo) }
      changed = true
    }

    val hwdec = prop("hwdec") ?: ""
    val hwdecCurrent = prop("hwdec-current") ?: ""
    if (hwdec != plan.hwdec) {
      runCatching { MPVLib.setPropertyString("hwdec", plan.hwdec) }
      changed = true
    } else if (plan.hwdec == "no" && hwdecCurrent.isNotBlank() && hwdecCurrent != "no") {
      // Ensure active decoder also drops HW path.
      runCatching { MPVLib.setPropertyString("hwdec", "no") }
      changed = true
    }

    val vf = prop("vf") ?: ""
    val hasYuv = vf.contains("yuv420p", ignoreCase = true)
    when {
      plan.forceYuv420p && !hasYuv -> {
        runCatching { MPVLib.command("vf", "set", "format=yuv420p") }
        changed = true
      }
      !plan.forceYuv420p && hasYuv -> {
        runCatching { MPVLib.command("vf", "clr") }
        runCatching { MPVLib.setPropertyString("vf", "") }
        changed = true
      }
    }

    val toneMapping = plan.toneMapping
    if (toneMapping != null) {
      val cur = prop("tone-mapping")
      if (cur != toneMapping) {
        runCatching { MPVLib.setPropertyString("tone-mapping", toneMapping) }
        changed = true
      }
    }
    val hdrPeak = plan.hdrComputePeak
    if (hdrPeak != null) {
      val cur = prop("hdr-compute-peak")
      if (cur != hdrPeak) {
        runCatching { MPVLib.setPropertyString("hdr-compute-peak", hdrPeak) }
        changed = true
      }
    }

    if (changed) {
      val pos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
      runCatching { MPVLib.command("seek", pos.toString(), "absolute+exact") }
      Log.i(TAG, "Adaptive decoder applied: ${plan.kind} — ${plan.reason}")
    } else {
      Log.d(TAG, "Adaptive decoder already matched: ${plan.kind} — ${plan.reason}")
    }
    return changed
  }

  /**
   * Parse a profile number from codec/metadata text without Regex.
   * Accepts forms like "profile 5", "profile=8", "p5", "P:5".
   */
  private fun parseProfileNumber(text: String): Long? {
    val lower = text.lowercase()
    val markers = listOf("profile", "p")
    for (marker in markers) {
      var start = 0
      while (start < lower.length) {
        val idx = lower.indexOf(marker, start)
        if (idx < 0) break
        var i = idx + marker.length
        while (i < lower.length && (lower[i] == ' ' || lower[i] == '=' || lower[i] == ':' ||
            lower[i] == '_' || lower[i] == '-' || lower[i] == '.')
        ) {
          i++
        }
        if (i < lower.length && lower[i].isDigit()) {
          // Require marker boundary so bare "p" in "ipt" is not matched.
          val beforeOk = idx == 0 || !lower[idx - 1].isLetterOrDigit()
          if (beforeOk) {
            var j = i
            while (j < lower.length && lower[j].isDigit()) j++
            val num = lower.substring(i, j).toLongOrNull()
            if (num != null) return num
          }
        }
        start = idx + marker.length
      }
    }
    return null
  }

  private fun prop(name: String): String? = MPVLib.getPropertyString(name)?.takeIf { it.isNotBlank() }

  private fun joinProps(vararg keys: String): String =
    keys.mapNotNull { prop(it) }.joinToString(" ")

  private fun readLongish(vararg keys: String): Long? {
    for (key in keys) {
      MPVLib.getPropertyLong(key)?.let { return it }
      MPVLib.getPropertyInt(key)?.toLong()?.let { return it }
      MPVLib.getPropertyString(key)?.toLongOrNull()?.let { return it }
    }
    return null
  }
}

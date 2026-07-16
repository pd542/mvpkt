package live.mehiz.mpvkt.preferences

import live.mehiz.mpvkt.preferences.preference.PreferenceStore
import live.mehiz.mpvkt.preferences.preference.getEnum
import live.mehiz.mpvkt.ui.player.Debanding

class DecoderPreferences(preferenceStore: PreferenceStore) {
  val tryHWDecoding = preferenceStore.getBoolean("try_hw_dec", true)

  // gpu-next is required for correct Dolby Vision (esp. Profile 5 IPT) tonemapping.
  val gpuNext = preferenceStore.getBoolean("gpu_next", true)

  // Forced yuv420p strips high-bit-depth / non-YCbCr frames and can turn DV P5 green.
  // Keep available as an opt-in compatibility switch, but default off.
  val useYUV420P = preferenceStore.getBoolean("use_yuv420p", false)

  // Auto-fix DV Profile 5: force gpu-next, drop yuv420p, and fall back to software decode
  // so libplacebo can apply the IPT→display transform (mediacodec path often cannot).
  // Kept for backward compatibility; adaptive decoder supersedes this when enabled.
  val autoFixDolbyVision = preferenceStore.getBoolean("auto_fix_dolby_vision", true)

  // Inspect each loaded stream and pick vo/hwdec/vf/tone-mapping automatically
  // (DV P5, other DV, HDR10/HLG, 10-bit SDR, 8-bit SDR).
  val adaptiveDecoder = preferenceStore.getBoolean("adaptive_decoder", true)

  val debanding = preferenceStore.getEnum("debanding", Debanding.None)
  val debandIterations = preferenceStore.getInt("deband_iterations", 1)
  val debandThreshold = preferenceStore.getInt("deband_threshold", 48)
  val debandRange = preferenceStore.getInt("deband_range", 16)
  val debandGrain = preferenceStore.getInt("deband_grain", 32)

  val brightnessFilter = preferenceStore.getInt("filter_brightness")
  val saturationFilter = preferenceStore.getInt("filter_saturation")
  val gammaFilter = preferenceStore.getInt("filter_gamma")
  val contrastFilter = preferenceStore.getInt("filter_contrast")
  val hueFilter = preferenceStore.getInt("filter_hue")
}

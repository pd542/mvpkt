package live.mehiz.mpvkt.ui.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.Serializable
import live.mehiz.mpvkt.R
import live.mehiz.mpvkt.preferences.NetworkPreferences
import live.mehiz.mpvkt.preferences.preference.collectAsState
import live.mehiz.mpvkt.presentation.Screen
import live.mehiz.mpvkt.ui.utils.LocalBackStack
import me.zhanghai.compose.preference.PreferenceCategory
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Serializable
object NetworkPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val preferences = koinInject<NetworkPreferences>()
    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(text = stringResource(id = R.string.pref_network)) },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding),
        ) {
          PreferenceCategory(
            title = { Text(stringResource(R.string.pref_network_category_cache)) },
          )

          val demuxerMaxCacheMb by preferences.demuxerMaxCacheMb.collectAsState()
          val demuxerMaxCacheMbFloat = demuxerMaxCacheMb.toFloat()
          SliderPreference(
            value = demuxerMaxCacheMbFloat,
            onValueChange = { value ->
              preferences.demuxerMaxCacheMb.set(value.roundToInt().coerceIn(8, 512))
            },
            title = { Text(stringResource(R.string.pref_network_demuxer_max_cache)) },
            valueRange = 8f..512f,
            summary = {
              Text(stringResource(R.string.pref_network_value_mb, demuxerMaxCacheMb))
            },
            onSliderValueChange = { value ->
              preferences.demuxerMaxCacheMb.set(value.roundToInt().coerceIn(8, 512))
            },
            sliderValue = demuxerMaxCacheMbFloat,
          )

          val demuxerMaxBackCacheMb by preferences.demuxerMaxBackCacheMb.collectAsState()
          val demuxerMaxBackCacheMbFloat = demuxerMaxBackCacheMb.toFloat()
          SliderPreference(
            value = demuxerMaxBackCacheMbFloat,
            onValueChange = { value ->
              preferences.demuxerMaxBackCacheMb.set(value.roundToInt().coerceIn(8, 512))
            },
            title = { Text(stringResource(R.string.pref_network_demuxer_max_back_cache)) },
            valueRange = 8f..512f,
            summary = {
              Text(stringResource(R.string.pref_network_value_mb, demuxerMaxBackCacheMb))
            },
            onSliderValueChange = { value ->
              preferences.demuxerMaxBackCacheMb.set(value.roundToInt().coerceIn(8, 512))
            },
            sliderValue = demuxerMaxBackCacheMbFloat,
          )

          val demuxerReadaheadSecs by preferences.demuxerReadaheadSecs.collectAsState()
          val demuxerReadaheadSecsFloat = demuxerReadaheadSecs.toFloat()
          SliderPreference(
            value = demuxerReadaheadSecsFloat,
            onValueChange = { value ->
              preferences.demuxerReadaheadSecs.set(value.roundToInt().coerceIn(0, 120))
            },
            title = { Text(stringResource(R.string.pref_network_demuxer_readahead_secs)) },
            valueRange = 0f..120f,
            summary = {
              Text(
                if (demuxerReadaheadSecs == 0) {
                  stringResource(R.string.generic_disabled)
                } else {
                  stringResource(R.string.pref_network_value_secs, demuxerReadaheadSecs)
                },
              )
            },
            onSliderValueChange = { value ->
              preferences.demuxerReadaheadSecs.set(value.roundToInt().coerceIn(0, 120))
            },
            sliderValue = demuxerReadaheadSecsFloat,
          )

          val cacheSecs by preferences.cacheSecs.collectAsState()
          val cacheSecsFloat = cacheSecs.toFloat()
          SliderPreference(
            value = cacheSecsFloat,
            onValueChange = { value ->
              preferences.cacheSecs.set(value.roundToInt().coerceIn(1, 300))
            },
            title = { Text(stringResource(R.string.pref_network_cache_secs)) },
            valueRange = 1f..300f,
            summary = {
              Text(stringResource(R.string.pref_network_value_secs, cacheSecs))
            },
            onSliderValueChange = { value ->
              preferences.cacheSecs.set(value.roundToInt().coerceIn(1, 300))
            },
            sliderValue = cacheSecsFloat,
          )

          val cachePauseInitial by preferences.cachePauseInitial.collectAsState()
          SwitchPreference(
            value = cachePauseInitial,
            onValueChange = preferences.cachePauseInitial::set,
            title = { Text(stringResource(R.string.pref_network_cache_pause_initial)) },
            summary = { Text(stringResource(R.string.pref_network_cache_pause_initial_summary)) },
          )

          val cachePauseWaitSecs by preferences.cachePauseWaitSecs.collectAsState()
          val cachePauseWaitSecsFloat = cachePauseWaitSecs.toFloat()
          SliderPreference(
            value = cachePauseWaitSecsFloat,
            onValueChange = { value ->
              preferences.cachePauseWaitSecs.set(value.roundToInt().coerceIn(0, 30))
            },
            title = { Text(stringResource(R.string.pref_network_cache_pause_wait)) },
            valueRange = 0f..30f,
            summary = {
              Text(stringResource(R.string.pref_network_value_secs, cachePauseWaitSecs))
            },
            onSliderValueChange = { value ->
              preferences.cachePauseWaitSecs.set(value.roundToInt().coerceIn(0, 30))
            },
            sliderValue = cachePauseWaitSecsFloat,
          )

          PreferenceCategory(
            title = { Text(stringResource(R.string.pref_network_category_threads)) },
          )

          val videoDecoderThreads by preferences.videoDecoderThreads.collectAsState()
          val videoDecoderThreadsFloat = videoDecoderThreads.toFloat()
          SliderPreference(
            value = videoDecoderThreadsFloat,
            onValueChange = { value ->
              preferences.videoDecoderThreads.set(value.roundToInt().coerceIn(0, 16))
            },
            title = { Text(stringResource(R.string.pref_network_video_decoder_threads)) },
            valueRange = 0f..16f,
            summary = {
              Text(
                if (videoDecoderThreads == 0) {
                  stringResource(R.string.pref_network_threads_auto)
                } else {
                  stringResource(R.string.pref_network_value_threads, videoDecoderThreads)
                },
              )
            },
            onSliderValueChange = { value ->
              preferences.videoDecoderThreads.set(value.roundToInt().coerceIn(0, 16))
            },
            sliderValue = videoDecoderThreadsFloat,
          )

          val audioDecoderThreads by preferences.audioDecoderThreads.collectAsState()
          val audioDecoderThreadsFloat = audioDecoderThreads.toFloat()
          SliderPreference(
            value = audioDecoderThreadsFloat,
            onValueChange = { value ->
              preferences.audioDecoderThreads.set(value.roundToInt().coerceIn(0, 8))
            },
            title = { Text(stringResource(R.string.pref_network_audio_decoder_threads)) },
            valueRange = 0f..8f,
            summary = {
              Text(
                if (audioDecoderThreads == 0) {
                  stringResource(R.string.pref_network_threads_auto)
                } else {
                  stringResource(R.string.pref_network_value_threads, audioDecoderThreads)
                },
              )
            },
            onSliderValueChange = { value ->
              preferences.audioDecoderThreads.set(value.roundToInt().coerceIn(0, 8))
            },
            sliderValue = audioDecoderThreadsFloat,
          )

          val demuxerThread by preferences.demuxerThread.collectAsState()
          SwitchPreference(
            value = demuxerThread,
            onValueChange = preferences.demuxerThread::set,
            title = { Text(stringResource(R.string.pref_network_demuxer_thread)) },
            summary = { Text(stringResource(R.string.pref_network_demuxer_thread_summary)) },
          )

          PreferenceCategory(
            title = { Text(stringResource(R.string.pref_network_category_streaming)) },
          )

          val optimizeForNetwork by preferences.optimizeForNetwork.collectAsState()
          SwitchPreference(
            value = optimizeForNetwork,
            onValueChange = preferences.optimizeForNetwork::set,
            title = { Text(stringResource(R.string.pref_network_optimize_streaming)) },
            summary = { Text(stringResource(R.string.pref_network_optimize_streaming_summary)) },
          )

          val prefetchPlaylist by preferences.prefetchPlaylist.collectAsState()
          SwitchPreference(
            value = prefetchPlaylist,
            onValueChange = preferences.prefetchPlaylist::set,
            title = { Text(stringResource(R.string.pref_network_prefetch_playlist)) },
            summary = { Text(stringResource(R.string.pref_network_prefetch_playlist_summary)) },
          )

          val networkTimeoutSecs by preferences.networkTimeoutSecs.collectAsState()
          val networkTimeoutSecsFloat = networkTimeoutSecs.toFloat()
          SliderPreference(
            value = networkTimeoutSecsFloat,
            onValueChange = { value ->
              preferences.networkTimeoutSecs.set(value.roundToInt().coerceIn(5, 300))
            },
            title = { Text(stringResource(R.string.pref_network_timeout)) },
            valueRange = 5f..300f,
            summary = {
              Text(stringResource(R.string.pref_network_value_secs, networkTimeoutSecs))
            },
            onSliderValueChange = { value ->
              preferences.networkTimeoutSecs.set(value.roundToInt().coerceIn(5, 300))
            },
            sliderValue = networkTimeoutSecsFloat,
          )

          val streamBufferSizeKb by preferences.streamBufferSizeKb.collectAsState()
          val streamBufferSizeKbFloat = streamBufferSizeKb.toFloat()
          SliderPreference(
            value = streamBufferSizeKbFloat,
            onValueChange = { value ->
              preferences.streamBufferSizeKb.set(value.roundToInt().coerceIn(16, 2048))
            },
            title = { Text(stringResource(R.string.pref_network_stream_buffer_size)) },
            valueRange = 16f..2048f,
            summary = {
              Text(stringResource(R.string.pref_network_value_kb, streamBufferSizeKb))
            },
            onSliderValueChange = { value ->
              preferences.streamBufferSizeKb.set(value.roundToInt().coerceIn(16, 2048))
            },
            sliderValue = streamBufferSizeKbFloat,
          )

          val tlsVerify by preferences.tlsVerify.collectAsState()
          SwitchPreference(
            value = tlsVerify,
            onValueChange = preferences.tlsVerify::set,
            title = { Text(stringResource(R.string.pref_network_tls_verify)) },
            summary = { Text(stringResource(R.string.pref_network_tls_verify_summary)) },
          )
        }
      }
    }
  }
}

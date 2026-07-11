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
          SliderPreference(
            value = demuxerMaxCacheMb.toFloat(),
            onValueChange = { preferences.demuxerMaxCacheMb.set(it.toInt().coerceIn(8, 512)) },
            title = { Text(stringResource(R.string.pref_network_demuxer_max_cache)) },
            summary = {
              Text(stringResource(R.string.pref_network_value_mb, demuxerMaxCacheMb))
            },
            valueRange = 8f..512f,
            steps = 62,
            sliderValue = demuxerMaxCacheMb.toFloat(),
            onSliderValueChange = {
              preferences.demuxerMaxCacheMb.set(it.toInt().coerceIn(8, 512))
            },
          )

          val demuxerMaxBackCacheMb by preferences.demuxerMaxBackCacheMb.collectAsState()
          SliderPreference(
            value = demuxerMaxBackCacheMb.toFloat(),
            onValueChange = { preferences.demuxerMaxBackCacheMb.set(it.toInt().coerceIn(8, 512)) },
            title = { Text(stringResource(R.string.pref_network_demuxer_max_back_cache)) },
            summary = {
              Text(stringResource(R.string.pref_network_value_mb, demuxerMaxBackCacheMb))
            },
            valueRange = 8f..512f,
            steps = 62,
            sliderValue = demuxerMaxBackCacheMb.toFloat(),
            onSliderValueChange = {
              preferences.demuxerMaxBackCacheMb.set(it.toInt().coerceIn(8, 512))
            },
          )

          val demuxerReadaheadSecs by preferences.demuxerReadaheadSecs.collectAsState()
          SliderPreference(
            value = demuxerReadaheadSecs.toFloat(),
            onValueChange = { preferences.demuxerReadaheadSecs.set(it.toInt().coerceIn(0, 120)) },
            title = { Text(stringResource(R.string.pref_network_demuxer_readahead_secs)) },
            summary = {
              Text(
                if (demuxerReadaheadSecs == 0) {
                  stringResource(R.string.generic_disabled)
                } else {
                  stringResource(R.string.pref_network_value_secs, demuxerReadaheadSecs)
                },
              )
            },
            valueRange = 0f..120f,
            steps = 119,
            sliderValue = demuxerReadaheadSecs.toFloat(),
            onSliderValueChange = {
              preferences.demuxerReadaheadSecs.set(it.toInt().coerceIn(0, 120))
            },
          )

          val cacheSecs by preferences.cacheSecs.collectAsState()
          SliderPreference(
            value = cacheSecs.toFloat(),
            onValueChange = { preferences.cacheSecs.set(it.toInt().coerceIn(1, 300)) },
            title = { Text(stringResource(R.string.pref_network_cache_secs)) },
            summary = {
              Text(stringResource(R.string.pref_network_value_secs, cacheSecs))
            },
            valueRange = 1f..300f,
            steps = 298,
            sliderValue = cacheSecs.toFloat(),
            onSliderValueChange = {
              preferences.cacheSecs.set(it.toInt().coerceIn(1, 300))
            },
          )

          val cachePauseInitial by preferences.cachePauseInitial.collectAsState()
          SwitchPreference(
            value = cachePauseInitial,
            onValueChange = preferences.cachePauseInitial::set,
            title = { Text(stringResource(R.string.pref_network_cache_pause_initial)) },
            summary = { Text(stringResource(R.string.pref_network_cache_pause_initial_summary)) },
          )

          val cachePauseWaitSecs by preferences.cachePauseWaitSecs.collectAsState()
          SliderPreference(
            value = cachePauseWaitSecs.toFloat(),
            onValueChange = { preferences.cachePauseWaitSecs.set(it.toInt().coerceIn(0, 30)) },
            title = { Text(stringResource(R.string.pref_network_cache_pause_wait)) },
            summary = {
              Text(stringResource(R.string.pref_network_value_secs, cachePauseWaitSecs))
            },
            valueRange = 0f..30f,
            steps = 29,
            sliderValue = cachePauseWaitSecs.toFloat(),
            onSliderValueChange = {
              preferences.cachePauseWaitSecs.set(it.toInt().coerceIn(0, 30))
            },
          )

          PreferenceCategory(
            title = { Text(stringResource(R.string.pref_network_category_threads)) },
          )

          val videoDecoderThreads by preferences.videoDecoderThreads.collectAsState()
          SliderPreference(
            value = videoDecoderThreads.toFloat(),
            onValueChange = { preferences.videoDecoderThreads.set(it.toInt().coerceIn(0, 16)) },
            title = { Text(stringResource(R.string.pref_network_video_decoder_threads)) },
            summary = {
              Text(
                if (videoDecoderThreads == 0) {
                  stringResource(R.string.pref_network_threads_auto)
                } else {
                  stringResource(R.string.pref_network_value_threads, videoDecoderThreads)
                },
              )
            },
            valueRange = 0f..16f,
            steps = 15,
            sliderValue = videoDecoderThreads.toFloat(),
            onSliderValueChange = {
              preferences.videoDecoderThreads.set(it.toInt().coerceIn(0, 16))
            },
          )

          val audioDecoderThreads by preferences.audioDecoderThreads.collectAsState()
          SliderPreference(
            value = audioDecoderThreads.toFloat(),
            onValueChange = { preferences.audioDecoderThreads.set(it.toInt().coerceIn(0, 8)) },
            title = { Text(stringResource(R.string.pref_network_audio_decoder_threads)) },
            summary = {
              Text(
                if (audioDecoderThreads == 0) {
                  stringResource(R.string.pref_network_threads_auto)
                } else {
                  stringResource(R.string.pref_network_value_threads, audioDecoderThreads)
                },
              )
            },
            valueRange = 0f..8f,
            steps = 7,
            sliderValue = audioDecoderThreads.toFloat(),
            onSliderValueChange = {
              preferences.audioDecoderThreads.set(it.toInt().coerceIn(0, 8))
            },
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
          SliderPreference(
            value = networkTimeoutSecs.toFloat(),
            onValueChange = { preferences.networkTimeoutSecs.set(it.toInt().coerceIn(5, 300)) },
            title = { Text(stringResource(R.string.pref_network_timeout)) },
            summary = {
              Text(stringResource(R.string.pref_network_value_secs, networkTimeoutSecs))
            },
            valueRange = 5f..300f,
            steps = 58,
            sliderValue = networkTimeoutSecs.toFloat(),
            onSliderValueChange = {
              preferences.networkTimeoutSecs.set(it.toInt().coerceIn(5, 300))
            },
          )

          val streamBufferSizeKb by preferences.streamBufferSizeKb.collectAsState()
          SliderPreference(
            value = streamBufferSizeKb.toFloat(),
            onValueChange = {
              preferences.streamBufferSizeKb.set(it.toInt().coerceIn(16, 2048))
            },
            title = { Text(stringResource(R.string.pref_network_stream_buffer_size)) },
            summary = {
              Text(stringResource(R.string.pref_network_value_kb, streamBufferSizeKb))
            },
            valueRange = 16f..2048f,
            steps = 126,
            sliderValue = streamBufferSizeKb.toFloat(),
            onSliderValueChange = {
              preferences.streamBufferSizeKb.set(it.toInt().coerceIn(16, 2048))
            },
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

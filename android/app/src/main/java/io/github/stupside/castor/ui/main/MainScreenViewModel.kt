package io.github.stupside.castor.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import castor.v1.DeviceOuterClass.Target
import castor.v1.StreamOuterClass.Stream
import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.github.stupside.castor.BrowserResolver
import io.github.stupside.castor.data.DataRepository
import io.github.stupside.castor.data.Rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class CastPhase {
  Preparing,
  Ready,
  Connecting,
  Extracting,
  Measuring,
  Casting,
  Completed,
  Stopped,
  Failed,
  Unavailable,
}

data class MainScreenUiState(
  val ready: Boolean = false,
  val discovering: Boolean = false,
  val devices: List<Device> = emptyList(),
  val castId: String? = null,
  val activeCast: Cast? = null,
  val submitting: Boolean = false,
  val status: String = "Starting the casting engine…",
  val error: String? = null,
  val phase: CastPhase = CastPhase.Preparing,
  val discoveryComplete: Boolean = false,
  val discoveryError: String? = null,
  val stopping: Boolean = false,
  // The engine's existing cast is being looked up; no new cast may start meanwhile.
  val recovering: Boolean = false,
)

class MainScreenViewModel : ViewModel() {
  val uiState = MutableStateFlow(MainScreenUiState())
  private var repository: DataRepository? = null
  private var endpoint: Rpc? = null
  private var watch: Job? = null
  private var discovery: Job? = null
  private var submission: Job? = null
  private var recovery: Job? = null

  fun connect(api: Rpc?) {
    // Activity recreation re-emits the service's existing endpoint; its cast is still running.
    if (api != null && api === endpoint) return
    endpoint = api
    watch?.cancel()
    discovery?.cancel()
    submission?.cancel()
    recovery?.cancel()
    repository = api?.let(::DataRepository)
    uiState.value =
      if (api != null) MainScreenUiState()
      else
        uiState.value.copy(
          ready = false,
          castId = null,
          activeCast = null,
          submitting = false,
          status = "Casting engine unavailable",
          phase = CastPhase.Unavailable,
          stopping = false,
        )
    val data = repository
    if (data != null) recover(data)
  }

  // The service outlives the screen. Recover its session before accepting another cast,
  // including when a Cast response was lost or a different session still owns the TV.
  private fun recover(data: DataRepository) {
    recovery?.cancel()
    uiState.value = uiState.value.copy(recovering = true)
    recovery =
      viewModelScope.launch {
        try {
          val cast = data.casts().firstOrNull()
          currentCoroutineContext().ensureActive()
          if (cast != null) follow(cast)
          uiState.value =
            uiState.value.copy(
              ready = true,
              recovering = false,
              phase =
                if (cast == null && uiState.value.error == null) CastPhase.Ready
                else uiState.value.phase,
              status =
                if (cast == null && uiState.value.error == null) "Ready to cast"
                else uiState.value.status,
            )
        } catch (error: Exception) {
          if (error is CancellationException) throw error
          // The engine answered before; keep its error visible without locking casting. A later
          // busy refusal recovers again and restores Stop for the cast that owns the TV.
          fail(error)
          uiState.value = uiState.value.copy(ready = true, recovering = false)
        }
      }
  }

  fun fail(error: Throwable) {
    if (error !is kotlinx.coroutines.CancellationException)
      uiState.value =
        uiState.value.copy(
          error = error.message ?: "Casting failed",
          phase = CastPhase.Failed,
          stopping = false,
        )
  }

  fun dismissError() {
    uiState.value =
      uiState.value.copy(
        error = null,
        phase =
          if (uiState.value.castId == null && uiState.value.ready) CastPhase.Ready
          else uiState.value.phase,
      )
  }

  fun discover() {
    if (discovery?.isActive == true) return
    discovery =
      viewModelScope.launch {
        val data = repository ?: return@launch
        uiState.value =
          uiState.value.copy(
            discovering = true,
            devices = emptyList(),
            discoveryComplete = false,
            discoveryError = null,
          )
        try {
          val devices = data.devices()
          uiState.value =
            uiState.value.copy(
              devices = devices,
              status =
                if (devices.isEmpty()) "No TVs found. Check Wi-Fi or enter the TV address."
                else "Select a TV",
            )
        } catch (error: Exception) {
          // A search failure belongs to the TV picker, not to a cast.
          if (error !is CancellationException)
            uiState.value = uiState.value.copy(discoveryError = error.message ?: "Search failed")
        } finally {
          uiState.value = uiState.value.copy(discovering = false, discoveryComplete = true)
        }
      }
  }

  fun cast(
    url: String,
    page: Boolean,
    target: Target,
    maxHeight: String,
    subtitles: String,
    receiverName: String = "",
    subtitleLanguage: String = "en",
  ) {
    if (
      !uiState.value.ready ||
        uiState.value.recovering ||
        submission?.isActive == true ||
        uiState.value.castId != null
    )
      return
    submission =
      viewModelScope.launch {
        val data = repository ?: return@launch
        try {
          val request = request(url, page, target, maxHeight, subtitles, subtitleLanguage)
          val device =
            uiState.value.devices.firstOrNull {
              if (target.hasDeviceId()) it.id == target.deviceId
              else it.address == target.pinned.address && it.type == target.pinned.type
            }
              ?: Device.newBuilder()
                .apply {
                  if (target.hasPinned()) {
                    name = receiverName.ifBlank { target.pinned.address }
                    address = target.pinned.address
                    type = target.pinned.type
                  } else {
                    id = target.deviceId
                    name = receiverName
                  }
                }
                .build()
          val pending = Cast.newBuilder().setSource(request.source).setDevice(device).build()
          uiState.value =
            uiState.value.copy(
              error = null,
              status = "Connecting…",
              submitting = true,
              phase = CastPhase.Connecting,
              activeCast = pending,
            )
          follow(pending.toBuilder().setId(data.cast(request)).build())
        } catch (error: Exception) {
          fail(error)
          if (error !is CancellationException) recover(data)
        } finally {
          uiState.value = uiState.value.copy(submitting = false)
        }
      }
  }

  private fun follow(cast: Cast) {
    watch?.cancel()
    uiState.value =
      uiState.value.copy(castId = cast.id, activeCast = cast, phase = phase(cast.status))
    val data = repository ?: return
    watch =
      viewModelScope.launch {
        try {
          data.watch(cast.id) { update ->
            if (update.hasEnded()) {
              val result = update.ended
              uiState.value =
                uiState.value.copy(
                  castId = null,
                  activeCast = null,
                  status =
                    when {
                      result.hasStopped() -> "Stopped"
                      result.hasCompleted() -> "Playback completed or handed off to the TV"
                      else -> "Casting failed"
                    },
                  error = if (result.hasFailed()) result.failed.message else null,
                  phase =
                    when {
                      result.hasStopped() -> CastPhase.Stopped
                      result.hasCompleted() -> CastPhase.Completed
                      else -> CastPhase.Failed
                    },
                  stopping = false,
                )
              if (result.hasFailed()) recover(data)
            } else {
              val status = update.status
              val text =
                when {
                  status.hasConnecting() -> "Connecting to the TV…"
                  status.hasExtracting() -> "Finding video… You can start playback in the browser."
                  status.hasMeasuring() -> "Checking ${status.measuring.streams} streams…"
                  status.hasCasting() -> "Casting · attempt ${status.casting.attempt}"
                  else -> "Waiting for status…"
                }
              uiState.value = uiState.value.copy(status = text, phase = phase(status))
            }
          }
        } catch (error: Exception) {
          if (ended(error)) forget(cast.id) else fail(error)
        }
      }
  }

  // The engine no longer knows this cast, so neither watching nor stopping it can succeed.
  private fun ended(error: Exception) = (error.cause as? ConnectException)?.code == Code.NOT_FOUND

  private fun forget(id: String) {
    if (uiState.value.castId == id)
      uiState.value =
        uiState.value.copy(
          castId = null,
          activeCast = null,
          stopping = false,
          phase = CastPhase.Ready,
        )
  }

  fun stop() =
    viewModelScope.launch {
      val id = uiState.value.castId ?: return@launch
      if (uiState.value.stopping) return@launch
      uiState.value = uiState.value.copy(stopping = true)
      try {
        repository?.stop(id)
      } catch (error: Exception) {
        if (ended(error)) forget(id) else fail(error)
      }
    }

  private fun phase(status: CastStatus): CastPhase =
    when {
      status.hasExtracting() -> CastPhase.Extracting
      status.hasMeasuring() -> CastPhase.Measuring
      status.hasCasting() -> CastPhase.Casting
      else -> CastPhase.Connecting
    }

  companion object {
    internal fun request(
      url: String,
      page: Boolean,
      target: Target,
      maxHeight: String,
      subtitles: String = "off",
      subtitleLanguage: String = "en",
    ): CastRequest {
      require(BrowserResolver.isHttp(url) && url.length <= 8192) {
        "Enter a complete HTTP or HTTPS video URL."
      }
      val height =
        if (maxHeight == NATIVE_QUALITY) NATIVE_HEIGHT_CEILING else maxHeight.toIntOrNull()
      require(height != null && height >= 2) {
        "Video height must be a whole number of at least 2 pixels."
      }
      require(
        target.hasDeviceId() && target.deviceId.isNotBlank() ||
          target.hasPinned() && target.pinned.address.isNotBlank()
      ) {
        "Select a TV or enter its address."
      }
      val source = Source.newBuilder()
      if (page) source.pages = Source.Pages.newBuilder().addUrls(url).build()
      else source.stream = Stream.newBuilder().setUrl(url).build()
      val selection = SubtitleSelection.newBuilder()
      when (subtitles) {
        "off" -> selection.setDisabled(com.google.protobuf.Empty.getDefaultInstance())
        else -> {
          val language = if (subtitles == "auto") subtitleLanguage else subtitles
          require(Regex("^[a-z]{2,3}$").matches(language)) {
            "Use off, auto, or a two- or three-letter subtitle language code."
          }
          selection.setPreferences(
            SubtitlePreferences.newBuilder().addLanguages(language).setTranscribeIfMissing(true)
          )
        }
      }
      return CastRequest.newBuilder()
        .setTarget(target)
        .setSource(source)
        .setPreferences(Preferences.newBuilder().setMaxHeight(height).setSubtitles(selection))
        .build()
    }
  }
}

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
import io.github.stupside.castor.LocalVideo
import io.github.stupside.castor.LocalVideos
import io.github.stupside.castor.VideoSources
import io.github.stupside.castor.data.DataRepository
import io.github.stupside.castor.data.Rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
  Restarting,
  Completed,
  Stopped,
  Failed,
  Unavailable,
}

data class CastSessionUiState(
  val cast: Cast,
  val phase: CastPhase = CastPhase.Connecting,
  val error: String? = null,
  val nativeIncompatible: Boolean = false,
  val stopping: Boolean = false,
  val restarting: Boolean = false,
  val playback: PlaybackUiState = PlaybackUiState(),
)

data class MainScreenUiState(
  val ready: Boolean = false,
  val discovering: Boolean = false,
  val devices: List<Device> = emptyList(),
  val sessions: List<CastSessionUiState> = emptyList(),
  val selectedCastId: String? = null,
  val pendingCast: Cast? = null,
  val submitting: Boolean = false,
  val idleStatus: String = "Starting the casting engine…",
  val failure: String? = null,
  val idleNativeIncompatible: Boolean = false,
  val idlePhase: CastPhase = CastPhase.Preparing,
  val discoveryComplete: Boolean = false,
  val discoveryError: String? = null,
  val recovering: Boolean = false,
  // What a Native cast keeps on each receiver protocol, by verdictKey.
  val verdicts: Map<String, Fidelity> = emptyMap(),
  // A TV that asked to be paired before it plays, while the user enters its PIN.
  val pairing: PairingUiState? = null,
) {
  val selected: CastSessionUiState?
    get() = sessions.firstOrNull { it.cast.id == selectedCastId }

  val castId: String?
    get() = selected?.cast?.id

  val activeCast: Cast?
    get() = selected?.cast ?: pendingCast

  val phase: CastPhase
    get() = selected?.phase ?: if (submitting) CastPhase.Connecting else idlePhase

  val error: String?
    get() = failure ?: selected?.error

  val nativeIncompatible: Boolean
    get() = selected?.nativeIncompatible ?: idleNativeIncompatible

  val stopping: Boolean
    get() = selected?.stopping == true

  val restarting: Boolean
    get() = selected?.restarting == true

  val playback: PlaybackUiState
    get() = selected?.playback ?: PlaybackUiState()

  val status: String
    get() = idleStatus

  fun occupying(tv: SavedTv): CastSessionUiState? = sessions.firstOrNull {
    val device = it.cast.device
    device.type == tv.type &&
      (tv.id.isNotBlank() && tv.id == device.id ||
        tv.address.isNotBlank() && tv.address == device.address)
  }
}

data class PairingUiState(
  val tv: String,
  val target: Target,
  // Set once the TV shows its PIN; a wrong PIN ends that pairing and the next one shows a new PIN.
  val pairingId: String? = null,
  val busy: Boolean = false,
  val wrongPin: Boolean = false,
  val error: String? = null,
)

internal fun verdictKey(tv: SavedTv) = "${tv.type.number}|${tv.address}"

data class PlaybackUiState(
  val positionMs: Long? = null,
  val durationMs: Long? = null,
  val paused: Boolean = false,
  val canPause: Boolean = false,
  val canSeek: Boolean = false,
  val available: Boolean = false,
  val changingPause: Boolean = false,
  val seekTargetMs: Long? = null,
  val error: String? = null,
) {
  val canTogglePause: Boolean
    get() = available && canPause && !changingPause && seekTargetMs == null
}

class MainScreenViewModel : ViewModel() {
  val uiState = MutableStateFlow(MainScreenUiState())
  val selectedVideo = MutableStateFlow<LocalVideo?>(null)
  val selectingVideo = MutableStateFlow(false)
  private var videoSelection: Job? = null
  private var selectionGeneration = 0
  private var videos: LocalVideos? = null
  private var repository: DataRepository? = null
  private var endpoint: Rpc? = null
  private var discovery: Job? = null
  private var submission: Job? = null
  // The last cast asked for, cast again once the TV it was refused by is paired.
  private var lastCast: (() -> Unit)? = null
  private var recovery: Job? = null
  private var composing = false

  // Every cast has independent observation and control work. Selecting another TV changes only
  // presentation; it cannot cancel that TV's in-flight seek or another cast's watcher.
  private class SessionJobs {
    var watch: Job? = null
    var control: Job? = null
    var seek: Job? = null
    var restart: Job? = null
    var stop: Job? = null
    var queuedSeek: Long? = null
    var generation = 0L
    var seekGeneration = 0L
  }

  private val jobs = mutableMapOf<String, SessionJobs>()

  private fun session(id: String) = uiState.value.sessions.firstOrNull { it.cast.id == id }

  private fun update(id: String, change: (CastSessionUiState) -> CastSessionUiState) {
    uiState.value =
      uiState.value.copy(
        sessions =
          uiState.value.sessions.map {
            if (it.cast.id == id) change(it) else it
          }
      )
  }

  fun connect(api: Rpc?, files: LocalVideos? = null) {
    videos = files
    if (api != null && api === endpoint) return
    endpoint = api
    jobs.keys.toList().forEach(::cancelSession)
    discovery?.cancel()
    submission?.cancel()
    recovery?.cancel()
    repository = api?.let(::DataRepository)
    uiState.value =
      if (api != null) MainScreenUiState()
      else
        MainScreenUiState(
          idleStatus = "Casting engine unavailable",
          idlePhase = CastPhase.Unavailable,
        )
    repository?.let(::recover)
  }

  fun selectCast(id: String) {
    if (session(id) == null) return
    composing = false
    uiState.value = uiState.value.copy(selectedCastId = id, failure = null)
  }

  fun newCast() {
    if (uiState.value.submitting) return
    composing = true
    uiState.value =
      uiState.value.copy(
        selectedCastId = null,
        pendingCast = null,
        failure = null,
        idleNativeIncompatible = false,
        idlePhase = CastPhase.Ready,
      )
  }

  fun selectVideo(resolver: android.content.ContentResolver, uri: android.net.Uri) {
    val generation = ++selectionGeneration
    videoSelection?.cancel()
    selectingVideo.value = true
    videoSelection = viewModelScope.launch {
      try {
        val video =
          kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val files = videos
            if (files != null) files.retainSelection(uri)
            else LocalVideos.retainSelection(resolver, uri)
            LocalVideos.describe(resolver, uri)
          }
        currentCoroutineContext().ensureActive()
        selectedVideo.value = video
        newCast()
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        fail(error)
      } finally {
        if (generation == selectionGeneration) selectingVideo.value = false
      }
    }
  }

  fun clearVideo() {
    selectionGeneration++
    videoSelection?.cancel()
    selectedVideo.value = null
    selectingVideo.value = false
  }

  fun videoName(url: String): String? = videos?.name(url)

  private fun recover(data: DataRepository) {
    recovery?.cancel()
    uiState.value = uiState.value.copy(recovering = true)
    recovery = viewModelScope.launch {
      try {
        val casts = data.casts()
        currentCoroutineContext().ensureActive()
        for (cast in casts) if (session(cast.id) == null) follow(cast)
        val selected =
          uiState.value.selectedCastId ?: casts.firstOrNull()?.id?.takeUnless { composing }
        uiState.value =
          uiState.value.copy(
            ready = true,
            recovering = false,
            selectedCastId = selected,
            idlePhase =
              if (uiState.value.failure == null) CastPhase.Ready else uiState.value.idlePhase,
            idleStatus = "Ready to cast",
          )
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        fail(error)
        uiState.value = uiState.value.copy(ready = true, recovering = false)
      }
    }
  }

  fun fail(error: Throwable) {
    if (error !is CancellationException)
      uiState.value =
        uiState.value.copy(
          failure = error.message ?: "Casting failed",
          idleNativeIncompatible = false,
          idlePhase = CastPhase.Failed,
        )
  }

  private fun target(device: Device): Target =
    Target.newBuilder()
      .apply {
        if (device.id.isNotBlank()) deviceId = device.id
        else setPinned(Target.Pinned.newBuilder().setAddress(device.address).setType(device.type))
      }
      .build()

  // Asks each protocol of a TV what a Native cast keeps there, once per protocol.
  fun describe(tvs: List<SavedTv>) {
    val data = repository ?: return
    for (tv in tvs) {
      val key = verdictKey(tv)
      if (key in uiState.value.verdicts) continue
      viewModelScope.launch {
        val fidelity =
          try {
            data.describe(tv.target())
          } catch (error: Exception) {
            if (error is CancellationException) throw error
            return@launch
          }
        uiState.value = uiState.value.copy(verdicts = uiState.value.verdicts + (key to fidelity))
      }
    }
  }

  // Asks the TV to show a PIN.
  fun startPairing() {
    val pairing = uiState.value.pairing ?: return
    val data = repository ?: return
    uiState.value = uiState.value.copy(pairing = pairing.copy(pairingId = null, busy = true, error = null))
    viewModelScope.launch {
      val next =
        try {
          pairing.copy(pairingId = data.startPairing(pairing.target), busy = false, wrongPin = false)
        } catch (error: Exception) {
          if (error is CancellationException) throw error
          pairing.copy(pairingId = null, busy = false, error = error.message ?: "Pairing failed")
        }
      if (uiState.value.pairing?.target == pairing.target)
        uiState.value = uiState.value.copy(pairing = next)
    }
  }

  // Proves the PIN; once paired, the cast the TV refused is asked again.
  fun finishPairing(pin: String) {
    val pairing = uiState.value.pairing ?: return
    val id = pairing.pairingId ?: return
    val data = repository ?: return
    uiState.value = uiState.value.copy(pairing = pairing.copy(busy = true, error = null))
    viewModelScope.launch {
      try {
        data.finishPairing(id, pin.trim())
        uiState.value = uiState.value.copy(pairing = null)
        lastCast?.invoke()
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        val wrong = (error.cause as? ConnectException)?.code == Code.INVALID_ARGUMENT
        uiState.value =
          uiState.value.copy(
            pairing =
              pairing.copy(
                pairingId = null,
                busy = false,
                wrongPin = wrong,
                error = if (wrong) null else error.message ?: "Pairing failed",
              )
          )
      }
    }
  }

  fun cancelPairing() {
    val pairing = uiState.value.pairing ?: return
    uiState.value = uiState.value.copy(pairing = null)
    val id = pairing.pairingId ?: return
    val data = repository ?: return
    viewModelScope.launch {
      try {
        data.cancelPairing(id)
      } catch (error: Exception) {
        if (error is CancellationException) throw error
      }
    }
  }

  fun dismissError() {
    uiState.value.castId?.let { update(it) { it.copy(error = null, nativeIncompatible = false) } }
    uiState.value =
      uiState.value.copy(
        failure = null,
        idleNativeIncompatible = false,
        idlePhase = if (uiState.value.ready) CastPhase.Ready else uiState.value.idlePhase,
      )
  }

  // restart begins again a search already running, so it also covers an address just typed.
  fun discover(restart: Boolean = false) {
    if (discovery?.isActive == true) {
      if (!restart) return
      discovery?.cancel()
    }
    discovery = viewModelScope.launch {
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
        uiState.value = uiState.value.copy(devices = devices)
      } catch (error: Exception) {
        if (error !is CancellationException)
          uiState.value = uiState.value.copy(discoveryError = error.message ?: "Search failed")
      } finally {
        // A search replaced by a restart must not end the one now running.
        if (discovery === coroutineContext[Job])
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
    localVideo: LocalVideo? = null,
    automatic: Boolean = false,
  ) {
    if (!uiState.value.ready || uiState.value.recovering || submission?.isActive == true) return
    val data = repository ?: return
    lastCast = {
      cast(url, page, target, maxHeight, subtitles, receiverName, subtitleLanguage, localVideo, automatic)
    }
    submission = viewModelScope.launch {
      try {
        val videoUrl =
          localVideo?.let {
            checkNotNull(videos) { "The casting engine is not ready to read this video." }
              .register(it)
          } ?: url
        var request =
          request(
            videoUrl,
            if (localVideo != null) false else page,
            target,
            maxHeight,
            subtitles,
            subtitleLanguage,
          )
        if (localVideo != null) {
          request =
            request
              .toBuilder()
              .setSource(
                VideoSources.direct(
                  videoUrl,
                  localVideo.contentType.takeUnless { it == "application/octet-stream" }.orEmpty(),
                )
              )
              .setPreferences(
                request.preferences
                  .toBuilder()
                  .setDelivery(castor.v1.StreamOuterClass.Delivery.DELIVERY_SERVE)
              )
              .setMediaInfo(MediaInfo.newBuilder().setTitle(localVideo.name.take(256)))
              .build()
        }
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
        check(
          uiState.value.sessions.none {
            val other = it.cast.device
            (device.id.isNotBlank() && other.id == device.id) ||
              (device.address.isNotBlank() &&
                other.type == device.type &&
                other.address == device.address)
          }
        ) {
          "This TV is already casting. Select its transmission to restart or stop it."
        }
        val pending = Cast.newBuilder().setSource(request.source).setDevice(device).build()
        uiState.value =
          uiState.value.copy(
            failure = null,
            idleNativeIncompatible = false,
            submitting = true,
            selectedCastId = null,
            pendingCast = pending,
          )
        if (localVideo == null && automatic && page) {
          val source = VideoSources.automatic(url)
          request =
            request
              .toBuilder()
              .setSource(source)
              .apply {
                if (source.hasStream())
                  setPreferences(
                    request.preferences
                      .toBuilder()
                      .setDelivery(castor.v1.StreamOuterClass.Delivery.DELIVERY_SERVE)
                  )
              }
              .build()
        }
        val cast = pending.toBuilder().setSource(request.source).setId(data.cast(request)).build()
        composing = false
        follow(cast)
        uiState.value = uiState.value.copy(selectedCastId = cast.id)
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        fail(error)
        recover(data)
      } finally {
        uiState.value = uiState.value.copy(submitting = false, pendingCast = null)
      }
    }
  }

  private fun follow(cast: Cast) {
    if (jobs[cast.id]?.watch?.isActive == true) return
    val work = jobs.getOrPut(cast.id) { SessionJobs() }
    if (session(cast.id) == null)
      uiState.value =
        uiState.value.copy(
          sessions = uiState.value.sessions + CastSessionUiState(cast, phase(cast.status))
        )
    val data = repository ?: return
    work.watch = viewModelScope.launch {
      try {
        data.watch(cast.id) { event ->
          if (repository !== data || session(cast.id) == null) return@watch
          if (event.hasEnded()) {
            val ended = event.ended
            forget(cast.id)
            val unpaired =
              ended.hasFailed() && ended.failed.code == FailureCode.FAILURE_CODE_PAIRING_REQUIRED
            uiState.value =
              uiState.value.copy(
                idlePhase =
                  when {
                    ended.hasStopped() -> CastPhase.Stopped
                    ended.hasCompleted() -> CastPhase.Completed
                    else -> CastPhase.Failed
                  },
                failure =
                  if (ended.hasFailed() && !unpaired) ended.failed.message else uiState.value.failure,
                pairing =
                  if (unpaired)
                    PairingUiState(cast.device.name.ifBlank { cast.device.address }, target(cast.device))
                  else uiState.value.pairing,
                idleNativeIncompatible =
                  ended.hasFailed() &&
                    ended.failed.code == FailureCode.FAILURE_CODE_NATIVE_INCOMPATIBLE,
              )
            if (unpaired) startPairing()
            if (ended.hasFailed()) recover(data)
          } else
            update(cast.id) {
              it.copy(
                cast = it.cast.toBuilder().setStatus(event.status).build(),
                phase = phase(event.status),
              )
            }
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (ended(error)) forget(cast.id)
        else
          update(cast.id) {
            it.copy(
              error = error.message ?: "Casting failed",
              phase = CastPhase.Failed,
              stopping = false,
            )
          }
      }
    }
  }

  private fun ended(error: Exception) = (error.cause as? ConnectException)?.code == Code.NOT_FOUND

  private fun forget(id: String) {
    cancelSession(id)
    uiState.value =
      uiState.value.copy(
        sessions = uiState.value.sessions.filterNot { it.cast.id == id },
        selectedCastId = uiState.value.selectedCastId.takeUnless { it == id },
      )
  }

  fun stop() {
    val id = uiState.value.castId ?: return
    val work = jobs.getOrPut(id) { SessionJobs() }
    if (session(id)?.stopping != false) return
    cancelPlaybackControls(id)
    update(id) { it.copy(stopping = true) }
    work.stop = viewModelScope.launch {
      try {
        repository?.stop(id)
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (ended(error)) forget(id)
        else update(id) { it.copy(error = error.message, stopping = false) }
      }
    }
  }

  fun restartCasting() {
    val id = uiState.value.castId ?: return
    val data = repository ?: return
    val current = session(id) ?: return
    if (current.stopping || current.restarting) return
    val work = jobs.getOrPut(id) { SessionJobs() }
    cancelPlaybackControls(id)
    update(id) { it.copy(restarting = true, error = null, playback = PlaybackUiState()) }
    work.restart = viewModelScope.launch {
      try {
        data.restart(id)
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (ended(error)) forget(id) else update(id) { it.copy(error = error.message) }
      } finally {
        update(id) { it.copy(restarting = false) }
      }
    }
  }

  suspend fun refreshPlayback(id: String) {
    val data = repository ?: return
    val before = session(id) ?: return
    if (
      before.stopping ||
        before.restarting ||
        before.playback.changingPause ||
        before.playback.seekTargetMs != null
    )
      return
    val work = jobs.getOrPut(id) { SessionJobs() }
    val generation = work.generation
    try {
      val remote = data.playback(id)
      if (samePlayback(id, data) && generation == work.generation) showPlayback(id, remote)
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      if (samePlayback(id, data) && generation == work.generation)
        update(id) { it.copy(playback = it.playback.copy(available = false)) }
    }
  }

  fun togglePlaybackPause() {
    val id = uiState.value.castId ?: return
    val data = repository ?: return
    val entry = session(id) ?: return
    val current = entry.playback
    if (entry.stopping || entry.restarting || !current.canTogglePause) return
    val work = jobs.getOrPut(id) { SessionJobs() }
    work.generation++
    update(id) { it.copy(playback = it.playback.copy(changingPause = true, error = null)) }
    work.control = viewModelScope.launch {
      try {
        val remote = data.pause(id, !current.paused)
        if (samePlayback(id, data)) showPlayback(id, remote)
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (samePlayback(id, data)) playbackFailed(id, error)
      } finally {
        update(id) { it.copy(playback = it.playback.copy(changingPause = false)) }
      }
    }
  }

  fun seekBy(deltaMs: Long) {
    val current = uiState.value.playback
    val position = current.seekTargetMs ?: current.positionMs ?: return
    seekTo(position + deltaMs)
  }

  fun seekTo(positionMs: Long) {
    val id = uiState.value.castId ?: return
    val data = repository ?: return
    val entry = session(id) ?: return
    val current = entry.playback
    val duration = current.durationMs ?: return
    if (
      entry.stopping ||
        entry.restarting ||
        !current.available ||
        !current.canSeek ||
        current.changingPause ||
        duration <= 0
    )
      return
    val work = jobs.getOrPut(id) { SessionJobs() }
    val target = positionMs.coerceIn(0, duration)
    work.generation++
    work.queuedSeek = target
    update(id) { it.copy(playback = it.playback.copy(seekTargetMs = target, error = null)) }
    if (work.seek?.isActive == true) return
    val generation = ++work.seekGeneration
    work.seek = viewModelScope.launch {
      try {
        while (true) {
          val next = work.queuedSeek ?: break
          work.queuedSeek = null
          val remote = data.seek(id, next)
          if (!samePlayback(id, data) || generation != work.seekGeneration) return@launch
          showPlayback(id, remote)
          if (work.queuedSeek == null)
            update(id) { it.copy(playback = it.playback.copy(seekTargetMs = null)) }
          delay(400)
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (samePlayback(id, data) && generation == work.seekGeneration) {
          work.queuedSeek = null
          playbackFailed(id, error)
        }
      } finally {
        if (generation == work.seekGeneration) work.seek = null
      }
    }
  }

  fun dismissPlaybackError() {
    uiState.value.castId?.let { id ->
      update(id) { it.copy(playback = it.playback.copy(error = null)) }
    }
  }

  private fun showPlayback(id: String, remote: PlaybackState) {
    fun ms(value: com.google.protobuf.Duration) = value.seconds * 1000 + value.nanos / 1_000_000
    update(id) {
      it.copy(
        playback =
          it.playback.copy(
            positionMs = if (remote.hasPosition()) ms(remote.position) else null,
            durationMs = if (remote.hasDuration()) ms(remote.duration) else null,
            paused = remote.paused,
            canPause = remote.canPause,
            canSeek = remote.canSeek,
            available = remote.hasPosition(),
          )
      )
    }
  }

  private fun playbackFailed(id: String, error: Exception) {
    update(id) {
      it.copy(
        playback =
          it.playback.copy(
            available = false,
            seekTargetMs = null,
            error = error.message ?: "Playback control failed",
          )
      )
    }
  }

  private fun samePlayback(id: String, data: DataRepository) =
    session(id) != null && repository === data

  private fun cancelPlaybackControls(id: String) {
    jobs[id]?.let {
      it.generation++
      it.seekGeneration++
      it.control?.cancel()
      it.seek?.cancel()
      it.control = null
      it.seek = null
      it.queuedSeek = null
    }
    update(id) { it.copy(playback = it.playback.copy(seekTargetMs = null, changingPause = false)) }
  }

  private fun cancelSession(id: String) {
    cancelPlaybackControls(id)
    jobs.remove(id)?.let {
      it.watch?.cancel()
      it.restart?.cancel()
      it.stop?.cancel()
    }
  }

  private fun phase(status: CastStatus): CastPhase =
    when {
      status.hasExtracting() -> CastPhase.Extracting
      status.hasMeasuring() -> CastPhase.Measuring
      status.hasCasting() -> CastPhase.Casting
      status.hasRestarting() -> CastPhase.Restarting
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
        when (maxHeight) {
          NATIVE_QUALITY -> NATIVE_HEIGHT_CEILING
          OPTIMIZED_QUALITY -> OPTIMIZED_HEIGHT_CEILING
          else -> maxHeight.toIntOrNull()
        }
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
            SubtitlePreferences.newBuilder()
              .addLanguages(language)
              .setTranscribeIfMissing(maxHeight != NATIVE_QUALITY)
          )
        }
      }
      return CastRequest.newBuilder()
        .setTarget(target)
        .setSource(source)
        .setPreferences(
          Preferences.newBuilder()
            .setManagedPlayback(true)
            .setMaxHeight(height)
            .setSubtitles(selection)
            .setPreserveSource(maxHeight == NATIVE_QUALITY)
        )
        .build()
    }
  }
}

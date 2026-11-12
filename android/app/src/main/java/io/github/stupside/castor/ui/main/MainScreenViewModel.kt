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
import io.github.stupside.castor.EngineDemand
import io.github.stupside.castor.LocalVideo
import io.github.stupside.castor.LocalVideos
import io.github.stupside.castor.VideoSources
import io.github.stupside.castor.data.DataRepository
import io.github.stupside.castor.data.PlaybackObservation
import io.github.stupside.castor.data.Rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
  // The same repository, for work that waits until the engine answers.
  private val engines = MutableStateFlow<DataRepository?>(null)
  private var endpoint: Rpc? = null
  // How the screen asks for the engine. Absent where a test hands the model an endpoint itself: then nothing is asked.
  private var engine: EngineDemand? = null
  // How the screen finds TVs without the engine, which runs only for casts. Absent where a test hands the model
  // an endpoint itself: then the search asks that endpoint, and never asks for an engine.
  private var search: (suspend () -> List<Device>)? = null
  // The engine hold of the pairing on screen, with the TV it is for: the engine remembers a pairing only while it
  // runs. Each pairing has its own, so the late end of one never lets go of the engine the next one needs.
  private class PairingHold(val target: Target, val hold: AutoCloseable?)

  private var pairingHold: PairingHold? = null
  private var discovery: Job? = null
  private var submission: Job? = null
  // The last cast asked for, cast again once the TV it was refused by is paired.
  private var lastCast: (() -> Unit)? = null
  private var recovery: Job? = null
  private var composing = false
  // The work that is using the running engine, as opposed to work still waiting for one.
  private val using = mutableSetOf<Job>()

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

  internal fun searchWith(find: suspend () -> List<Device>) {
    search = find
  }

  // With an engine that starts when asked, the screen is ready before any engine runs.
  internal fun demand(engine: EngineDemand) {
    this.engine = engine
    if (repository == null)
      uiState.value =
        uiState.value.copy(
          ready = true,
          idlePhase = CastPhase.Ready,
          idleStatus = "Ready to cast",
        )
  }

  // Runs work that needs the engine: asks for it, waits until it answers, and lets it go afterwards.
  private suspend fun <T> engaged(work: suspend (DataRepository) -> T): T {
    val demand = engine ?: return work(checkNotNull(repository) { "The casting engine is not running." })
    val hold = demand.hold()
    try {
      val data =
        withTimeoutOrNull(20_000) {
          merge(
              // Only the engine that answers now is used: not one that is being stopped.
              combine(engines, demand.endpoint) { data, api ->
                  data.takeIf { api != null && api === endpoint }
                }
                .filterNotNull(),
              // An engine that failed to start ends the wait with its reason.
              demand.failure.filterNotNull().map<String, DataRepository> {
                throw IllegalStateException(it)
              },
            )
            .first()
        } ?: error("The casting engine did not start.")
      val job = currentCoroutineContext()[Job]
      job?.let(using::add)
      try {
        return work(data)
      } finally {
        job?.let(using::remove)
      }
    } finally {
      hold.close()
    }
  }

  // The one place the repository changes, so work waiting for the engine sees the same change.
  private fun useRepository(data: DataRepository?) {
    repository = data
    engines.value = data
  }

  // Ends the hold of the pairing on record, if any: the engine no longer needs to stay for its PIN.
  private fun pairingOver() {
    pairingHold?.hold?.close()
    pairingHold = null
  }

  override fun onCleared() {
    pairingOver()
    super.onCleared()
  }

  fun connect(api: Rpc?, files: LocalVideos? = null) {
    videos = files
    // Nothing changed: a screen that is recreated hears the same engine, or the same absence, again.
    if (api === endpoint && (api != null || engine != null)) return
    // The engine the screen asked for has come up: what the screen is doing goes on, with it.
    val arrived = api != null && endpoint == null && engine != null
    endpoint = api
    if (!arrived) {
      pairingOver()
      jobs.keys.toList().forEach(::cancelSession)
      recovery?.cancel()
      if (api == null && engine != null) {
        // The engine stopped: what was using it ends with it; what still waits for one goes on with the next.
        using.toList().forEach { it.cancel() }
      } else {
        discovery?.cancel()
        submission?.cancel()
      }
    }
    useRepository(api?.let(::DataRepository))
    uiState.value =
      when {
        arrived -> uiState.value
        api != null -> MainScreenUiState()
        // The engine stopped: the screen keeps what it found.
        engine != null ->
          uiState.value.copy(
            sessions = emptyList(),
            selectedCastId = null,
            pendingCast = null,
            submitting = false,
            recovering = false,
            // A search still waiting for the next engine is still a search.
            discovering = discovery?.isActive == true,
            ready = true,
            idlePhase =
              if (uiState.value.failure == null) CastPhase.Ready else uiState.value.idlePhase,
            idleStatus = "Ready to cast",
            // A pairing begun in that engine is gone with it.
            pairing = null,
          )
        else ->
          MainScreenUiState(
            idleStatus = "Casting engine unavailable",
            idlePhase = CastPhase.Unavailable,
          )
      }
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

  // Asks each protocol of a TV what a Native cast keeps there, once per protocol, and only of an engine that
  // already runs for a cast: a verdict is never worth starting the engine for. The screen keeps what it learns.
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
    if (engine == null && repository == null) return
    val held = pairingHold
    if (held == null || held.target != pairing.target) {
      // A pairing for another TV takes its own hold before the previous one's is given back, so the engine never
      // drops between them.
      pairingHold = PairingHold(pairing.target, engine?.hold())
      held?.hold?.close()
    }
    uiState.value = uiState.value.copy(pairing = pairing.copy(pairingId = null, busy = true, error = null))
    viewModelScope.launch {
      val next =
        try {
          val id = engaged { data -> data.startPairing(pairing.target) }
          pairing.copy(pairingId = id, busy = false, wrongPin = false)
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
    if (engine == null && repository == null) return
    uiState.value = uiState.value.copy(pairing = pairing.copy(busy = true, error = null))
    viewModelScope.launch {
      try {
        engaged { data -> data.finishPairing(id, pin.trim()) }
        // Only this pairing ends here: another TV may have begun its own while the PIN was being proved.
        if (pairingHold?.target == pairing.target) pairingOver()
        if (uiState.value.pairing?.target == pairing.target)
          uiState.value = uiState.value.copy(pairing = null)
        lastCast?.invoke()
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        val wrong = (error.cause as? ConnectException)?.code == Code.INVALID_ARGUMENT
        if (uiState.value.pairing?.target == pairing.target)
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
    // The cancelled pairing's hold leaves the record at once, so a pairing begun before the engine has answered
    // this cancel takes its own; the answer then gives back only this one.
    val held = pairingHold?.takeIf { it.target == pairing.target }
    if (held != null) pairingHold = null
    val id = pairing.pairingId
    val data = repository
    if (id == null || data == null) {
      held?.hold?.close()
      return
    }
    viewModelScope
      .launch {
        try {
          data.cancelPairing(id)
        } catch (error: Exception) {
          if (error is CancellationException) throw error
        }
      }
      .invokeOnCompletion { held?.hold?.close() }
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
    val find = search ?: repository?.let { data -> suspend { data.devices() } } ?: return
    if (discovery?.isActive == true) {
      if (!restart) return
      discovery?.cancel()
    }
    // Known as the running search before it runs: a search that answers at once ends while being launched.
    val next = viewModelScope.launch(start = CoroutineStart.LAZY) {
      uiState.value =
        uiState.value.copy(
          discovering = true,
          devices = emptyList(),
          discoveryComplete = false,
          discoveryError = null,
        )
      try {
        val devices = find()
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
    discovery = next
    next.start()
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
    if (engine == null && repository == null) return
    lastCast = {
      cast(url, page, target, maxHeight, subtitles, receiverName, subtitleLanguage, localVideo, automatic)
    }
    submission = viewModelScope.launch {
      try {
        // The engine stays for the request, and for the cast once it is followed.
        engaged { data ->
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
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        fail(error)
        repository?.let(::recover)
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
    // A followed cast keeps the engine until it is no longer watched.
    val held = engine?.hold()
    val watch = viewModelScope.launch {
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
    work.watch = watch
    watch.invokeOnCompletion { held?.close() }
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

  private fun showPlayback(id: String, remote: PlaybackObservation) {
    update(id) {
      it.copy(
        playback =
          it.playback.copy(
            positionMs = remote.positionMs,
            durationMs = remote.durationMs,
            paused = remote.paused,
            canPause = remote.canPause,
            canSeek = remote.canSeek,
            available = remote.positionMs != null,
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

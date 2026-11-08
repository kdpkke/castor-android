package io.github.stupside.castor.data

import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import castor.v1.DeviceOuterClass.Target

class DataRepository(private val api: Rpc) {
  suspend fun devices(): List<Device> =
    api.result { api.devices.listDevices(ListDevicesRequest.getDefaultInstance()) }.devicesList

  // The most of a source a Native cast keeps on target, as the engine judges its protocol.
  suspend fun describe(target: Target): Fidelity =
    api.result { api.devices.describe(DescribeRequest.newBuilder().setTarget(target).build()) }.fidelity

  // The TV shows a PIN until finishPairing proves it or cancelPairing abandons it.
  suspend fun startPairing(target: Target): String =
    api
      .result { api.devices.startPairing(StartPairingRequest.newBuilder().setTarget(target).build()) }
      .pairingId
      .also { check(it.isNotBlank()) { "The server returned an empty pairing ID" } }

  suspend fun finishPairing(id: String, pin: String) {
    api.result {
      api.devices.finishPairing(FinishPairingRequest.newBuilder().setPairingId(id).setPin(pin).build())
    }
  }

  suspend fun cancelPairing(id: String) {
    api.result { api.devices.cancelPairing(CancelPairingRequest.newBuilder().setPairingId(id).build()) }
  }

  suspend fun casts(): List<Cast> =
    api.result { api.casts.listCasts(ListCastsRequest.getDefaultInstance()) }.castsList

  suspend fun cast(request: CastRequest): String =
    api
      .result { api.casts.cast(request) }
      .castId
      .also { check(it.isNotBlank()) { "The server returned an empty cast ID" } }

  suspend fun stop(id: String) {
    api.result { api.casts.stop(StopRequest.newBuilder().setCastId(id).build()) }
  }

  suspend fun restart(id: String) {
    api.result { api.casts.restart(RestartRequest.newBuilder().setCastId(id).build()) }
  }

  // Where the TV plays, as the engine last heard it; Pause, Resume and Seek act on it.
  suspend fun playback(id: String): PlaybackObservation =
    casts().firstOrNull { it.id == id }?.status?.let(::observation)
      ?: throw IllegalStateException("This cast has ended")

  suspend fun pause(id: String, paused: Boolean): PlaybackObservation {
    if (paused) api.result { api.casts.pause(PauseRequest.newBuilder().setCastId(id).build()) }
    else api.result { api.casts.resume(ResumeRequest.newBuilder().setCastId(id).build()) }
    return playback(id).copy(paused = paused)
  }

  // The engine moves the TV itself or prepares the stream again from there; the playhead follows.
  suspend fun seek(id: String, positionMs: Long): PlaybackObservation {
    require(positionMs >= 0)
    api.result {
      api.casts.seek(
        SeekRequest.newBuilder()
          .setCastId(id)
          .setPosition(
            com.google.protobuf.Duration.newBuilder()
              .setSeconds(positionMs / 1000)
              .setNanos(((positionMs % 1000) * 1_000_000).toInt())
          )
          .build()
      )
    }
    return playback(id).copy(positionMs = positionMs)
  }

  suspend fun watch(id: String, update: suspend (WatchResponse) -> Unit) =
    api.watch(WatchRequest.newBuilder().setCastId(id).build(), update)
}

// PlaybackObservation is the playhead the engine publishes; an absent position is not an invented zero.
data class PlaybackObservation(
  val positionMs: Long?,
  val durationMs: Long?,
  val paused: Boolean,
  val canPause: Boolean,
  val canSeek: Boolean,
)

internal fun observation(status: CastStatus): PlaybackObservation {
  fun ms(value: com.google.protobuf.Duration) = value.seconds * 1000 + value.nanos / 1_000_000
  val head = status.casting.playhead.takeIf { status.hasCasting() && status.casting.hasPlayhead() }
  return PlaybackObservation(
    positionMs = head?.takeIf { it.hasPosition() }?.let { ms(it.position) },
    durationMs = head?.takeIf { it.hasDuration() }?.let { ms(it.duration) },
    paused = head?.paused == true,
    canPause = head != null,
    canSeek = head?.hasDuration() == true,
  )
}

package io.github.stupside.castor.data

import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*

class DataRepository(private val api: Rpc) {
  suspend fun devices(): List<Device> =
    api.result { api.devices.listDevices(ListDevicesRequest.getDefaultInstance()) }.devicesList

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

  suspend fun playback(id: String): PlaybackState =
    api
      .result { api.casts.playback(PlaybackRequest.newBuilder().setCastId(id).build()) }
      .let {
        check(it.hasPlayback()) { "The server returned no playback state" }
        it.playback
      }

  suspend fun pause(id: String, paused: Boolean): PlaybackState =
    control(
      ControlRequest.newBuilder()
        .setCastId(id)
        .apply {
          if (paused) setPause(com.google.protobuf.Empty.getDefaultInstance())
          else setResume(com.google.protobuf.Empty.getDefaultInstance())
        }
        .build()
    )

  suspend fun seek(id: String, positionMs: Long): PlaybackState {
    require(positionMs >= 0)
    return control(
      ControlRequest.newBuilder()
        .setCastId(id)
        .setSeek(
          com.google.protobuf.Duration.newBuilder()
            .setSeconds(positionMs / 1000)
            .setNanos(((positionMs % 1000) * 1_000_000).toInt())
        )
        .build()
    )
  }

  private suspend fun control(request: ControlRequest): PlaybackState =
    api
      .result { api.casts.control(request) }
      .let {
        check(it.hasPlayback()) { "The server returned no playback state" }
        it.playback
      }

  suspend fun watch(id: String, update: suspend (WatchResponse) -> Unit) =
    api.watch(WatchRequest.newBuilder().setCastId(id).build(), update)
}

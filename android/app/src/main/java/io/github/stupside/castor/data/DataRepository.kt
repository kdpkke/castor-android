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

  suspend fun watch(id: String, update: suspend (WatchResponse) -> Unit) =
    api.watch(WatchRequest.newBuilder().setCastId(id).build(), update)
}

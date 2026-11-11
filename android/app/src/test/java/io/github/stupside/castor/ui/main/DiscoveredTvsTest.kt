package io.github.stupside.castor.ui.main

import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import castor.v1.DeviceOuterClass.Fidelity
import org.junit.Assert.*
import org.junit.Test

class DiscoveredTvsTest {
  private fun device(id: String, name: String, address: String, type: DeviceType) =
    Device.newBuilder().setId(id).setName(name).setAddress(address).setType(type).build()

  @Test
  fun oneTVHasItsOwnProtocolChoicesWithoutMergingAnotherTVWithTheSameName() {
    val cast =
      device("chromecast:one", "Living room", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val dlna =
      device(
        "dlna:one",
        "LG renderer",
        "http://192.0.2.10:1676/device.xml",
        DeviceType.DEVICE_TYPE_DLNA,
      )
    val other =
      device("chromecast:two", "Living room", "192.0.2.20:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val groups = discoveredTvs(listOf(cast, dlna, other, cast), null)
    assertEquals(2, groups.size)
    val first = groups.single { it.host == "192.0.2.10" }
    assertEquals("Living room", first.name)
    assertEquals("192.0.2.10 · Chromecast, DLNA", first.summary)
    assertEquals(listOf(cast.id, dlna.id), first.protocols.map { it.target().deviceId })
    assertEquals(dlna.address, first.protocols[1].address)
  }

  @Test
  fun confirmedSavedTVIsFirstAndUsesTheFreshProtocolAddress() {
    val old =
      device("dlna:saved", "Bedroom", "http://192.0.2.20:1000/old.xml", DeviceType.DEVICE_TYPE_DLNA)
    val fresh = old.toBuilder().setAddress("http://192.0.2.20:2000/current.xml").build()
    val other =
      device(
        "dlna:other",
        "Living room",
        "http://192.0.2.10:3000/device.xml",
        DeviceType.DEVICE_TYPE_DLNA,
      )
    val groups = discoveredTvs(listOf(other, fresh), SavedTv(old))
    assertEquals("192.0.2.20", groups.first().host)
    assertEquals(fresh.address, groups.first().protocols.single().address)
    assertEquals("192.0.2.20 · ${protocolName(fresh.type)}", groups.first().summary)
    assertTrue(discoveredTvs(emptyList(), SavedTv(old)).isEmpty())
  }

  @Test
  fun addressesShowPortsIncludingDefaultsAndIpv6WithoutDescriptionPaths() {
    assertEquals(
      "192.0.2.10:8009",
      receiverEndpoint(SavedTv("TV", "192.0.2.10", DeviceType.DEVICE_TYPE_CHROMECAST)),
    )
    assertEquals(
      "192.0.2.10:8060",
      receiverEndpoint(SavedTv("TV", "192.0.2.10", DeviceType.DEVICE_TYPE_ROKU)),
    )
    assertEquals(
      "192.0.2.10:443",
      receiverEndpoint(
        SavedTv("TV", "https://192.0.2.10/private/description.xml", DeviceType.DEVICE_TYPE_DLNA)
      ),
    )
    assertEquals(
      "[2001:db8::1]:8009",
      receiverEndpoint(SavedTv("TV", "[2001:db8::1]:8009", DeviceType.DEVICE_TYPE_CHROMECAST)),
    )
  }

  @Test
  fun theProtocolKeepingMostOfANativeCastIsListedFirstAndRecommendedOnlyWhenItKeepsMore() {
    val dlna = SavedTv("TV", "http://192.0.2.10:1676/d.xml", DeviceType.DEVICE_TYPE_DLNA)
    val cast = SavedTv("TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val airplay = SavedTv("TV", "192.0.2.10:7000", DeviceType.DEVICE_TYPE_AIRPLAY)
    val verdicts =
      mapOf(
        verdictKey(cast) to Fidelity.FIDELITY_ORIGINAL_TRACKS,
        verdictKey(airplay) to Fidelity.FIDELITY_ORIGINAL_TRACKS,
        verdictKey(dlna) to Fidelity.FIDELITY_ORIGINAL_FILE,
      )
    val ranked = rankedProtocols(listOf(cast, airplay, dlna), verdicts)
    assertEquals(listOf(dlna, cast, airplay), ranked)
    assertEquals(dlna, recommendedProtocol(ranked, verdicts))
    // A tie, or no verdict yet, recommends nothing.
    val tied = rankedProtocols(listOf(cast, airplay), verdicts)
    assertNull(recommendedProtocol(tied, verdicts))
    assertNull(recommendedProtocol(rankedProtocols(listOf(cast, dlna), emptyMap()), emptyMap()))
  }

  @Test
  fun onlyATypedIpv4AddressIsSearchedOnEveryProtocol() {
    assertEquals("192.0.2.21", bareHost(" 192.0.2.21 "))
    for (typed in listOf("192.0.2.21:7000", "http://192.0.2.21/", "192.168.1", "192.168.1.256", "tv.local", "", "1.2.3.4.5")) {
      assertNull(typed, bareHost(typed))
    }
  }

  @Test
  fun oneHostWrittenTwoWaysIsTheSameHost() {
    assertTrue(sameHost("[2001:DB8::10]", "2001:db8:0:0:0:0:0:10"))
    assertTrue(sameHost("fe80::1%wlan0", "fe80::1"))
    assertTrue(sameHost("192.0.2.10", "192.0.2.10"))
    assertTrue(sameHost("TV.local", "tv.local"))
    assertFalse(sameHost("192.0.2.10", "192.0.2.100"))
    assertFalse(sameHost("2001:db8::10", "2001:db8::11"))
    assertFalse(sameHost("192.0.2.10", "tv.local"))
  }

  @Test
  fun aBareIPv6AddressIsItsOwnHost() {
    assertEquals("2001:db8::10", receiverHost("2001:db8::10"))
    assertEquals("2001:db8::10", receiverHost("2001:DB8::10"))
    assertEquals("2001:db8::10", receiverHost("[2001:db8::10]:40000"))
    assertEquals("192.0.2.10", receiverHost("192.0.2.10"))
    assertEquals("192.0.2.10", receiverHost("192.0.2.10:8009"))
    assertEquals(
      "[2001:db8::10]:46899",
      receiverEndpoint(SavedTv("TV", "2001:db8::10", DeviceType.DEVICE_TYPE_FCAST)),
    )
  }
}

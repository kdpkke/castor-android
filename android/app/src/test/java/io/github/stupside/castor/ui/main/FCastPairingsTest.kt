package io.github.stupside.castor.ui.main

import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import io.github.stupside.castor.FCastQr
import org.junit.Assert.*
import org.junit.Test

class FCastPairingsTest {
  private val pin = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

  private fun saved(type: DeviceType, address: String, id: String) = SavedTv("TV", address, type, id)

  private fun tv(host: String, vararg protocols: SavedTv) = DiscoveredTv(host, "TV", protocols.toList())

  // A TV with the same Chromecast identity wherever it answers.
  private fun tvAt(host: String) =
    tv(host, saved(DeviceType.DEVICE_TYPE_CHROMECAST, "$host:8009", "chromecast:a"))

  private fun device(id: String, name: String, address: String, type: DeviceType): Device =
    Device.newBuilder().setId(id).setName(name).setAddress(address).setType(type).build()

  // A Chromecast with the given identity, answering at host on its Cast port.
  private fun chromecast(id: String, host: String) =
    device(id, "TV", "$host:8009", DeviceType.DEVICE_TYPE_CHROMECAST)

  private fun plainReceiver(host: String, port: Int = 46899) =
    FCastQr.Receiver("Receiver", listOf(host), port, null, null)

  private fun encryptedReceiver(host: String) = FCastQr.Receiver("Receiver", listOf(host), 46899, 4, pin)

  @Test
  fun stableIdentitiesAreTheIdsOfTheOtherProtocols() {
    val target =
      tv(
        "192.0.2.10",
        saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.10:8009", "chromecast:a"),
        saved(DeviceType.DEVICE_TYPE_DLNA, "http://192.0.2.10:1676/d.xml", "dlna:a"),
        saved(DeviceType.DEVICE_TYPE_FCAST, "192.0.2.10", "Receiver@192.0.2.10"),
        saved(DeviceType.DEVICE_TYPE_AIRPLAY, "192.0.2.10:7000", ""),
      )
    assertEquals(
      setOf(
        "${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:a",
        "${DeviceType.DEVICE_TYPE_DLNA.number}|dlna:a",
      ),
      stableIdentities(target),
    )
  }

  @Test
  fun aTVWithNoReceiverKnownAsksForItsCode() {
    assertEquals(FCastChoice.Scan, fcastChoice(tvAt("192.0.2.10"), emptyList()))
  }

  @Test
  fun anAnnouncedReceiverIsUsedAsItIs() {
    val announced = saved(DeviceType.DEVICE_TYPE_FCAST, "192.0.2.10", "Receiver@192.0.2.10")
    val target =
      tv("192.0.2.10", saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.10:8009", "chromecast:a"), announced)
    assertEquals(FCastChoice.Ready(announced, paired = false), fcastChoice(target, emptyList()))
  }

  @Test
  fun aPairedTVIsCastToAtItsCurrentAddress() {
    val pairing = paired(tvAt("192.0.2.10"), plainReceiver("192.0.2.10"))
    assertEquals(
      FCastChoice.Ready(
        SavedTv(
          "Receiver",
          "192.0.2.77",
          DeviceType.DEVICE_TYPE_FCAST,
          id = "",
          manual = false,
          pairedFor = stableIdentities(tvAt("192.0.2.77")),
          once = false,
        ),
        paired = true,
      ),
      fcastChoice(tvAt("192.0.2.77"), listOf(pairing)),
    )
    val onPort = paired(tvAt("192.0.2.10"), plainReceiver("192.0.2.10", port = 40000))
    assertEquals(
      FCastChoice.Ready(
        SavedTv(
          "Receiver",
          "192.0.2.77:40000",
          DeviceType.DEVICE_TYPE_FCAST,
          id = "",
          manual = false,
          pairedFor = stableIdentities(tvAt("192.0.2.77")),
          once = false,
        ),
        paired = true,
      ),
      fcastChoice(tvAt("192.0.2.77"), listOf(onPort)),
    )
  }

  @Test
  fun anotherTVAtTheSameAddressInheritsNothing() {
    val first = tvAt("192.0.2.10")
    val second =
      tv("192.0.2.10", saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.10:8009", "chromecast:b"))
    val firstPairing = paired(first, plainReceiver("192.0.2.10"))
    val pairings = withPairing(emptyList(), firstPairing)
    assertEquals(FCastChoice.Scan, fcastChoice(second, pairings))
    assertNull(pairingFor(second, pairings))

    val secondPairing = paired(second, plainReceiver("192.0.2.10"))
    val both = withPairing(pairings, secondPairing)
    assertEquals(2, both.size)
    assertEquals(firstPairing, pairingFor(first, both))
    assertEquals(secondPairing, pairingFor(second, both))
  }

  @Test
  fun aNewScanReplacesTheTVsPairing() {
    val target = tvAt("192.0.2.10")
    val first = paired(target, plainReceiver("192.0.2.10"))
    val second = paired(target, plainReceiver("192.0.2.10", port = 40000))
    assertEquals(listOf(second), withPairing(listOf(first), second))
  }

  @Test
  fun aTVWithoutIdentityIsChosenOnlyForNow() {
    val target = tv("192.0.2.30", saved(DeviceType.DEVICE_TYPE_DLNA, "http://192.0.2.30:1676/d.xml", ""))
    val p = paired(target, plainReceiver("192.0.2.30"))
    assertEquals(emptySet<String>(), p.identities)
    assertEquals(listOf(p), withPairing(emptyList(), p))
    assertEquals(
      FCastChoice.Ready(
        SavedTv("Receiver", "192.0.2.30", DeviceType.DEVICE_TYPE_FCAST, manual = true, once = true),
        paired = true,
      ),
      fcastChoice(target, listOf(p)),
    )
    val elsewhere =
      tv("192.0.2.20", saved(DeviceType.DEVICE_TYPE_DLNA, "http://192.0.2.20:1676/d.xml", ""))
    assertEquals(FCastChoice.Scan, fcastChoice(elsewhere, listOf(p)))
    val onPort40000 = paired(target, plainReceiver("192.0.2.30", port = 40000))
    assertEquals(listOf(onPort40000), withPairing(listOf(p), onPort40000))
  }

  @Test
  fun anEncryptedReceiverIsNeverChosen() {
    val target = tvAt("192.0.2.10")
    val pairings = withPairing(emptyList(), paired(target, encryptedReceiver("192.0.2.10")))
    assertEquals(FCastChoice.Unsupported("Receiver"), fcastChoice(target, pairings))

    val announced =
      tv(
        "192.0.2.10",
        saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.10:8009", "chromecast:a"),
        saved(DeviceType.DEVICE_TYPE_FCAST, "192.0.2.10", "Receiver@192.0.2.10"),
      )
    assertEquals(FCastChoice.Unsupported("Receiver"), fcastChoice(announced, pairings))
  }

  @Test
  fun anEncryptedCodeOfATVWithoutIdentityStaysBlockedAfterItsChoiceIsOver() {
    val target = tv("192.0.2.30", saved(DeviceType.DEVICE_TYPE_DLNA, "http://192.0.2.30:1676/d.xml", ""))
    val session = withPairing(emptyList(), paired(target, encryptedReceiver("192.0.2.30")))
    assertEquals(FCastChoice.Unsupported("Receiver"), fcastChoice(target, session))

    // Later the same host also announces an FCast receiver.
    val announced = SavedTv("Receiver", "192.0.2.30", DeviceType.DEVICE_TYPE_FCAST)
    val later =
      tv(
        "192.0.2.30",
        saved(DeviceType.DEVICE_TYPE_DLNA, "http://192.0.2.30:1676/d.xml", ""),
        announced,
      )
    assertEquals(FCastChoice.Unsupported("Receiver"), fcastChoice(later, session))
    assertTrue(fcastBlocked(announced, listOf(later), session))

    val handEntered = SavedTv("R", "192.0.2.30", DeviceType.DEVICE_TYPE_FCAST, manual = true)
    assertTrue(fcastBlocked(handEntered, emptyList(), session))
    assertFalse(fcastBlocked(handEntered, emptyList(), emptyList()))
  }

  @Test
  fun anotherTVAtTheFirstOnesAddressIsNotHeldBackByItsCode() {
    val tvA = tvAt("192.0.2.10")
    val pairings = listOf(paired(tvA, encryptedReceiver("192.0.2.10")))

    val bReceiver = SavedTv("B receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST)
    val tvB =
      tv("192.0.2.10", saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.10:8009", "chromecast:b"), bReceiver)
    assertFalse(fcastBlocked(bReceiver, listOf(tvB), pairings))
    assertEquals(FCastChoice.Ready(bReceiver, paired = false), fcastChoice(tvB, pairings))

    // A is now at .77 and announces its receiver there: its own code still holds.
    val aReceiver = SavedTv("A receiver", "192.0.2.77", DeviceType.DEVICE_TYPE_FCAST)
    assertTrue(fcastBlocked(aReceiver, listOf(tvAt("192.0.2.77")), pairings))

    // Without any discovery, the address the code was scanned at is blocked.
    assertTrue(fcastBlocked(SavedTv("X", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST), emptyList(), pairings))
    assertFalse(fcastBlocked(SavedTv("X", "192.0.2.10", DeviceType.DEVICE_TYPE_CHROMECAST), emptyList(), pairings))
  }

  @Test
  fun aPairedChoiceFollowsItsTVToANewAddress() {
    val tvA = tvAt("192.0.2.10")
    val pairing = paired(tvA, plainReceiver("192.0.2.10"))
    val pairings = decodePairings(encodePairings(withPairing(emptyList(), pairing)))
    val choice = (fcastChoice(tvA, pairings) as FCastChoice.Ready).tv
    assertEquals(pairing.identities, choice.pairedFor)
    assertFalse(choice.manual)
    assertFalse(choice.once)
    assertEquals("192.0.2.10", choice.address)

    // On a later start DHCP moved A to .77, and B took .10.
    val devices =
      listOf(chromecast("chromecast:a", "192.0.2.77"), chromecast("chromecast:b", "192.0.2.10"))
    assertEquals(
      SavedTv(
        "Receiver",
        "192.0.2.77",
        DeviceType.DEVICE_TYPE_FCAST,
        pairedFor = pairing.identities,
      ),
      pairedReceiver(choice, devices, pairings),
    )
    assertNull(pairedReceiver(choice, listOf(chromecast("chromecast:b", "192.0.2.10")), pairings))
    assertNull(pairedReceiver(choice, devices, emptyList()))
    val encrypted = listOf(paired(tvA, encryptedReceiver("192.0.2.10")))
    assertNull(pairedReceiver(choice, devices, encrypted))
    assertNull(pairedReceiver(SavedTv("Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST), devices, pairings))
  }

  @Test
  fun aPairingOfATVWithoutIdentityIsNotWrittenBack() {
    val target = tv("192.0.2.30", saved(DeviceType.DEVICE_TYPE_DLNA, "http://192.0.2.30:1676/d.xml", ""))
    val anonymousPairing = paired(target, plainReceiver("192.0.2.30"))
    assertEquals(emptyList<FCastPairing>(), decodePairings(encodePairings(listOf(anonymousPairing))))
    assertEquals(
      listOf(anonymousPairing),
      decodePairings(encodePairings(listOf(anonymousPairing)), anonymous = true),
    )
  }

  @Test
  fun aSavedPairingIsStillKnownWhileTheReceiverIsAnnounced() {
    val announced = saved(DeviceType.DEVICE_TYPE_FCAST, "192.0.2.10", "Receiver@192.0.2.10")
    val target =
      tv("192.0.2.10", saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.10:8009", "chromecast:a"), announced)
    val pairings = listOf(paired(tvAt("192.0.2.10"), plainReceiver("192.0.2.10")))
    val a = "${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:a"
    assertEquals(FCastChoice.Ready(announced.copy(pairedFor = setOf(a)), paired = false), fcastChoice(target, pairings))
    assertNotNull(pairingFor(target, pairings))
  }

  @Test
  fun pairingsSurviveBeingSaved() {
    val both =
      tv(
        "192.0.2.10",
        saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.10:8009", "chromecast:a"),
        saved(DeviceType.DEVICE_TYPE_DLNA, "http://192.0.2.10:1676/d.xml", "dlna:a"),
      )
    val list =
      listOf(
        paired(both, plainReceiver("192.0.2.10")),
        paired(
          tv("192.0.2.20", saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.20:8009", "chromecast:c")),
          encryptedReceiver("192.0.2.20"),
        ),
      )
    assertEquals(2, list[0].identities.size)
    assertEquals(list, decodePairings(encodePairings(list)))
  }

  @Test
  fun unreadableSavedPairingsAreDropped() {
    assertTrue(decodePairings(null).isEmpty())
    assertTrue(decodePairings("").isEmpty())
    assertTrue(decodePairings("not json").isEmpty())

    val good = paired(tvAt("192.0.2.10"), plainReceiver("192.0.2.10"))
    val entry = encodePairings(listOf(good)).trim().removePrefix("[").removeSuffix("]")
    assertEquals(listOf(good), decodePairings("""[$entry,{"name":"Receiver","identities":[]}]"""))
  }

  @Test
  fun anEncryptedCodeReadBeforeTheTVHadAnIdentityStillRefusesItOnceTheIdentityArrives() {
    val thatTv = tv("192.0.2.30", saved(DeviceType.DEVICE_TYPE_DLNA, "http://192.0.2.30:1676/d.xml", ""))
    val session = withPairing(emptyList(), paired(thatTv, encryptedReceiver("192.0.2.30")))
    assertEquals(FCastChoice.Unsupported("Receiver"), fcastChoice(thatTv, session))

    // Later the same host is found with an identity, and also announces its receiver.
    val fcast = SavedTv("Receiver", "192.0.2.30", DeviceType.DEVICE_TYPE_FCAST, "Receiver@192.0.2.30")
    val later =
      tv(
        "192.0.2.30",
        saved(DeviceType.DEVICE_TYPE_DLNA, "http://192.0.2.30:1676/d.xml", ""),
        saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.30:8009", "chromecast:late"),
        fcast,
      )
    assertEquals(FCastChoice.Unsupported("Receiver"), fcastChoice(later, session))
    assertTrue(fcastBlocked(fcast, listOf(later), session))

    // A plain pairing saved earlier for that identity does not undo the refusal.
    val older =
      FCastPairing(
        setOf("${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:late"),
        "Receiver",
        46899,
        null,
        null,
        "192.0.2.30",
      )
    assertEquals(FCastChoice.Unsupported("Receiver"), fcastChoice(later, listOf(older) + session))
    assertTrue(fcastBlocked(fcast, listOf(later), listOf(older) + session))

    // A new scan of the TV, now that it has an identity, settles it.
    val settled = withPairing(listOf(older) + session, paired(later, plainReceiver("192.0.2.30")))
    assertTrue(fcastChoice(later, settled) is FCastChoice.Ready)
    assertFalse(fcastBlocked(fcast, listOf(later), settled))
    assertTrue(settled.none { it.identities.isEmpty() && sameHost(it.host, "192.0.2.30") })

    // Another TV with an identity at another host is not refused by this session's code.
    val other =
      tv(
        "192.0.2.31",
        saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.31:8009", "chromecast:other"),
        SavedTv("Receiver", "192.0.2.31", DeviceType.DEVICE_TYPE_FCAST, "Receiver@192.0.2.31"),
      )
    assertTrue(fcastChoice(other, session) is FCastChoice.Ready)
  }

  @Test
  fun anAnnouncedReceiverOfAPairedTVKeepsTheTVsIdentity() {
    val a = "${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:a"
    val announced = SavedTv("Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST, "Receiver@192.0.2.10")
    val tvA =
      tv("192.0.2.10", saved(DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.10:8009", "chromecast:a"), announced)
    val pairings = listOf(paired(tvA, plainReceiver("192.0.2.10")))
    val choice = fcastChoice(tvA, pairings) as FCastChoice.Ready
    assertFalse(choice.paired)
    assertEquals(announced.copy(pairedFor = setOf(a)), choice.tv)
    assertEquals(FCastChoice.Ready(announced, paired = false), fcastChoice(tvA, emptyList()))
  }

  @Test
  fun anAnchoredAnnouncedReceiverFollowsItsTVAndNotItsOldAddress() {
    val a = "${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:a"
    val pairings = listOf(FCastPairing(setOf(a), "Receiver", 46899, null, null, "192.0.2.10"))
    val choice =
      SavedTv(
        "Receiver",
        "192.0.2.10",
        DeviceType.DEVICE_TYPE_FCAST,
        "Receiver@192.0.2.10",
        pairedFor = setOf(a),
      )
    // After a DHCP change, A is at .77 and announces its receiver there; another TV took .10 with the same name.
    val aChromecast = device("chromecast:a", "Living room TV", "192.0.2.77:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val aReceiver = device("Receiver@192.0.2.77", "Receiver", "192.0.2.77", DeviceType.DEVICE_TYPE_FCAST)
    val bChromecast = device("chromecast:b", "Bedroom TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val bReceiver = device("Receiver@192.0.2.10", "Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST)
    assertEquals(
      SavedTv(
        "Receiver",
        "192.0.2.77",
        DeviceType.DEVICE_TYPE_FCAST,
        "Receiver@192.0.2.77",
        pairedFor = setOf(a),
      ),
      pairedReceiver(choice, listOf(aChromecast, aReceiver, bChromecast, bReceiver), pairings),
    )
    // Without the receiver A announces, the paired endpoint at its new address is used.
    assertEquals(
      SavedTv("Receiver", "192.0.2.77", DeviceType.DEVICE_TYPE_FCAST, pairedFor = setOf(a)),
      pairedReceiver(choice, listOf(aChromecast, bChromecast, bReceiver), pairings),
    )
    assertNull(pairedReceiver(choice, listOf(bChromecast, bReceiver), pairings))
  }

  @Test
  fun aReceiverAtAnIPv6AddressIsRefusedInTheFormTheAppItselfSaves() {
    val v6 = tv("2001:db8::10", saved(DeviceType.DEVICE_TYPE_DLNA, "http://[2001:db8::10]:1000/d.xml", ""))
    val plain = FCastQr.Receiver("Receiver", listOf("2001:db8::10"), 46899, null, null)
    var session = withPairing(emptyList(), paired(v6, plain))
    val choice = (fcastChoice(v6, session) as FCastChoice.Ready).tv
    assertEquals("2001:db8::10", choice.address)
    assertEquals(DeviceType.DEVICE_TYPE_FCAST, choice.type)

    // Its code is scanned again, and now says the receiver is encrypted.
    session =
      withPairing(
        session,
        paired(v6, FCastQr.Receiver("Receiver", listOf("2001:db8::10"), 46899, 4, pin)),
      )
    assertEquals(FCastChoice.Unsupported("Receiver"), fcastChoice(v6, session))

    assertTrue(fcastBlocked(choice, listOf(v6), session))
    assertTrue(fcastBlocked(choice, emptyList(), session))
    assertTrue(
      fcastBlocked(
        SavedTv("Receiver", "[2001:db8::10]:40000", DeviceType.DEVICE_TYPE_FCAST),
        listOf(v6),
        session,
      )
    )
    assertTrue(
      fcastBlocked(
        SavedTv("Receiver", "2001:db8:0:0:0:0:0:10", DeviceType.DEVICE_TYPE_FCAST),
        emptyList(),
        session,
      )
    )

    // A TV with an identity at that IPv6 address, whose encrypted code is known for that identity.
    val a = tv("2001:db8::10", saved(DeviceType.DEVICE_TYPE_CHROMECAST, "[2001:db8::10]:8009", "chromecast:a"))
    val known =
      listOf(paired(a, FCastQr.Receiver("Receiver", listOf("2001:db8::10"), 46899, 4, pin)))
    assertTrue(
      fcastBlocked(SavedTv("Receiver", "2001:db8::10", DeviceType.DEVICE_TYPE_FCAST), listOf(a), known)
    )

    // A different IPv6 address is not refused.
    assertFalse(
      fcastBlocked(
        SavedTv("Receiver", "2001:db8::11", DeviceType.DEVICE_TYPE_FCAST),
        emptyList(),
        session,
      )
    )
  }

  @Test
  fun anAddressThatCannotBeReadIsNotTakenAsSafe() {
    val unreadable = SavedTv("Receiver", "not an address :: at all", DeviceType.DEVICE_TYPE_FCAST)
    val v6 = tv("2001:db8::10", saved(DeviceType.DEVICE_TYPE_DLNA, "http://[2001:db8::10]:1000/d.xml", ""))
    val encrypted = withPairing(emptyList(), paired(v6, encryptedReceiver("2001:db8::10")))
    assertTrue(fcastBlocked(unreadable, emptyList(), encrypted))
    assertFalse(fcastBlocked(unreadable, emptyList(), emptyList()))
    val plainOnly = withPairing(emptyList(), paired(v6, plainReceiver("2001:db8::10")))
    assertFalse(fcastBlocked(unreadable, emptyList(), plainOnly))
  }

  @Test
  fun aTVListedUnderTwoSpellingsOfItsAddressIsStillKnownByItsIdentity() {
    val announced =
      SavedTv("Receiver", "2001:db8:0:0:0:0:0:10", DeviceType.DEVICE_TYPE_FCAST, "Receiver@2001:db8:0:0:0:0:0:10")
    val first = tv("2001:db8:0:0:0:0:0:10", announced)
    val second =
      tv("2001:db8::10", saved(DeviceType.DEVICE_TYPE_CHROMECAST, "[2001:db8::10]:8009", "chromecast:a"))
    val a = "${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:a"

    // The TV's code was scanned as encrypted when it answered elsewhere.
    val known = listOf(FCastPairing(setOf(a), "Receiver", 46899, 4, pin, "192.0.2.99"))
    assertTrue(fcastBlocked(announced, listOf(first, second), known))
    assertTrue(fcastBlocked(announced, listOf(second, first), known))

    // With a plain pairing for that identity instead, nothing is refused.
    val plain = listOf(FCastPairing(setOf(a), "Receiver", 46899, null, null, "192.0.2.99"))
    assertFalse(fcastBlocked(announced, listOf(first, second), plain))
    assertFalse(fcastBlocked(announced, listOf(second, first), plain))
  }
}

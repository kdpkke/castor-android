package io.github.stupside.castor.ui.main

import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import castor.v1.DeviceOuterClass.Target
import io.github.stupside.castor.EngineDemand
import io.github.stupside.castor.data.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// The view model with an engine that runs only while something needs it. The engine is a fake API
// on MockWebServer; the demand is a fake that counts the holds taken on it.
class EngineDemandModelTest {
  private val video = "https://example.org/video.mp4"
  private val models = mutableListOf<MainScreenViewModel>()
  private val engines = mutableListOf<FakeEngine>()

  @After
  fun tearDown() {
    onMain { models.forEach { it.connect(null) } }
    engines.forEach { it.close() }
  }

  @Test
  fun aSearchNeverAsksForTheEngine() {
    // TVs are found by the app itself: a search takes no hold, so no engine starts for it.
    val demand = FakeDemand()
    val model = newModel(demand)
    val devices = listOf(device("Test TV", "192.0.2.10:8009"))
    val searches = AtomicInteger()
    onMain {
      model.searchWith {
        searches.incrementAndGet()
        devices
      }
    }
    assertTrue(model.uiState.value.ready)
    assertEquals(CastPhase.Ready, model.uiState.value.phase)
    onMain { model.discover() }
    until(what = "the search to be answered") { model.uiState.value.discoveryComplete }
    assertEquals(devices, model.uiState.value.devices)
    assertEquals(1, searches.get())
    assertEquals(0, demand.asked.get())
  }

  @Test
  fun aCastAskedWithNoEngineIsSentOnceItAnswersAndKeepsItWhileWatched() {
    // A cast asked with no engine waits for one, is sent once, and keeps the engine while it is watched.
    val demand = FakeDemand()
    val model = newModel(demand)
    val api = newEngine()
    onMain { model.cast(video, false, pinned("192.0.2.10:8009"), "1080", "off") }
    assertTrue(demand.open.get() >= 1)
    assertEquals(0, api.server.requestCount)
    start(demand, model, api)
    until(what = "the cast request to reach the engine") { api.castIds.size == 1 }
    assertEquals(1, api.submissions.size)
    val id = api.castIds.single()
    until(what = "the cast to be watched") { id in api.watched }
    assertTrue(demand.open.get() >= 1)
    assertTrue(model.uiState.value.sessions.any { it.cast.id == id })
    api.end(id, completed())
    until(what = "the cast to leave the screen") { model.uiState.value.sessions.isEmpty() }
    until(what = "the engine to be let go") { demand.open.get() == 0 }
  }

  @Test
  fun anEngineThatCannotStartEndsTheWaitWithItsReason() {
    // A failed start ends a cast waiting for the engine at once with the engine's reason, not at the timeout.
    val demand = FakeDemand()
    val model = newModel(demand)
    onMain { model.cast(video, false, pinned("192.0.2.10:8009"), "1080", "off") }
    demand.failure.value = "No engine for this device"
    until(2_000, "the cast to end with the engine's reason") {
      model.uiState.value.failure?.contains("No engine for this device") == true &&
        !model.uiState.value.submitting
    }
    assertEquals(0, demand.open.get())
  }

  @Test
  fun hearingTheSameAbsenceAgainDoesNotCancelACastBeingAsked() {
    // Hearing that there is no engine again does not cancel a cast that is waiting for one.
    val demand = FakeDemand()
    val model = newModel(demand)
    val api = newEngine()
    onMain { model.cast(video, false, pinned("192.0.2.10:8009"), "1080", "off") }
    onMain { model.connect(null) }
    onMain { model.connect(null) }
    start(demand, model, api)
    until(what = "the cast request to reach the engine") { api.castIds.size == 1 }
    val id = api.castIds.single()
    until(what = "the cast to be watched") { id in api.watched }
    assertEquals(1, api.submissions.size)
    api.end(id, completed())
    until(what = "the engine to be let go") { demand.open.get() == 0 }
  }

  @Test
  fun anEngineBeingStoppedIsNotUsedForNewWork() {
    // Once the stop is decided, a cast does not reach the stopping engine, even before the screen hears it.
    val demand = FakeDemand()
    val model = newModel(demand)
    val first = newEngine()
    val second = newEngine()
    start(demand, model, first)
    demand.endpoint.value = null
    onMain { model.cast(video, false, pinned("192.0.2.10:8009"), "1080", "off") }
    Thread.sleep(500)
    assertTrue(first.castIds.isEmpty())
    onMain { model.connect(null) }
    start(demand, model, second)
    until(
      what =
        "the second engine to receive the cast asked while the first was stopping " +
          "(connect(null) cancels that cast and nothing sends it again)",
    ) {
      second.castIds.size == 1
    }
    assertTrue(first.castIds.isEmpty())
    val id = second.castIds.single()
    until(what = "the cast to be watched") { id in second.watched }
    second.end(id, completed())
    until(what = "the engine to be let go") { demand.open.get() == 0 }
  }

  @Test
  fun whenTheEngineStopsTheScreenKeepsWhatItFoundAndStaysReady() {
    // When the engine stops, the screen keeps its devices and goes back to ready without a followed cast.
    val demand = FakeDemand()
    val model = newModel(demand)
    val api = newEngine(listOf(device("Test TV one", "192.0.2.10:8009")))
    start(demand, model, api)
    onMain { model.discover() }
    until(what = "the search to be answered") { model.uiState.value.discoveryComplete }
    val found = model.uiState.value.devices
    assertEquals(1, found.size)
    onMain { model.cast(video, false, pinned("192.0.2.30:8009"), "1080", "off") }
    until(what = "the cast to be followed and watched") {
      api.castIds.size == 1 &&
        api.castIds.single() in api.watched &&
        model.uiState.value.sessions.size == 1
    }
    demand.endpoint.value = null
    onMain { model.connect(null) }
    val state = model.uiState.value
    assertEquals(found, state.devices)
    assertTrue(state.discoveryComplete)
    assertTrue(state.ready)
    assertEquals(CastPhase.Ready, state.idlePhase)
    assertTrue(state.sessions.isEmpty())
    assertNull(state.pairing)
    assertNotEquals("Casting engine unavailable", state.status)
    until(what = "the engine to be let go") { demand.open.get() == 0 }
  }

  @Test
  fun aPairingKeepsTheEngineUntilItIsOver() {
    // A pairing keeps the engine for its PIN, and cancelling the pairing gives the engine back.
    val demand = FakeDemand()
    val model = newModel(demand)
    val api = newEngine()
    start(demand, model, api)
    onMain { model.cast(video, false, pinned("192.0.2.10:8009"), "1080", "off") }
    until(what = "the cast to be watched") {
      api.castIds.size == 1 && api.castIds.single() in api.watched
    }
    val id = api.castIds.single()
    api.end(id, failed(FailureCode.FAILURE_CODE_PAIRING_REQUIRED, "The TV asks for a pairing"))
    until(what = "the pairing to show its PIN") {
      model.uiState.value.pairing?.pairingId == PAIRING_ID
    }
    assertTrue(model.uiState.value.sessions.isEmpty())
    val end = System.currentTimeMillis() + 500
    while (System.currentTimeMillis() < end) {
      assertTrue("The engine must stay while the PIN is shown", demand.open.get() >= 1)
      Thread.sleep(25)
    }
    onMain { model.cancelPairing() }
    until(what = "the pairing to be cancelled at the engine") {
      api.cancelledPairings == listOf(PAIRING_ID)
    }
    until(what = "the engine to be let go") { demand.open.get() == 0 }
  }

  @Test
  fun aLateAnswerToACancelledPairingDoesNotLetGoOfTheNextPairing() {
    // A cancelled pairing whose answer arrives late does not give back the engine that the next pairing keeps.
    val demand = FakeDemand()
    val model = newModel(demand)
    val api = newEngine()
    start(demand, model, api)
    onMain { model.cast(video, false, pinned("192.0.2.10:8009"), "1080", "off") }
    until(what = "the first cast to be watched") {
      api.castIds.size == 1 && api.castIds.single() in api.watched
    }
    api.end(
      api.castIds.single(),
      failed(FailureCode.FAILURE_CODE_PAIRING_REQUIRED, "The TV asks for a pairing"),
    )
    until(what = "the first pairing to show its PIN") {
      model.uiState.value.pairing?.pairingId == PAIRING_ID
    }
    api.withholdCancelAnswer()
    onMain { model.cancelPairing() }
    until(what = "the cancel of the first pairing to arrive at the engine") {
      PAIRING_ID in api.cancelledPairings
    }
    assertNull(model.uiState.value.pairing)
    until(what = "the screen to finish recovering") {
      model.uiState.value.ready && !model.uiState.value.recovering
    }
    onMain { model.cast(video, false, pinned("192.0.2.20:8009"), "1080", "off") }
    until(what = "the second cast to be watched") {
      api.castIds.size == 2 && api.castIds.last() in api.watched
    }
    api.end(
      api.castIds.last(),
      failed(FailureCode.FAILURE_CODE_PAIRING_REQUIRED, "The TV asks for a pairing"),
    )
    until(what = "the second pairing to show its PIN") {
      model.uiState.value.pairing?.pairingId == SECOND_PAIRING_ID
    }
    api.releaseCancelAnswer()
    val end = System.currentTimeMillis() + 700
    while (System.currentTimeMillis() < end) {
      assertTrue("The engine must stay for the pairing on screen", demand.open.get() >= 1)
      assertEquals(SECOND_PAIRING_ID, model.uiState.value.pairing?.pairingId)
      Thread.sleep(25)
    }
    onMain { model.finishPairing("1234") }
    until(what = "the engine to receive the finish of the second pairing") {
      api.finishedPairings == listOf(SECOND_PAIRING_ID)
    }
    // Once paired, the model asks the cast refused by the second TV again; that cast is ended here.
    until(what = "the cast refused by the second TV to be asked again") { api.castIds.size == 3 }
    val recast = api.castIds.last()
    until(what = "the cast asked again to be watched") { recast in api.watched }
    api.end(recast, completed())
    until(what = "the engine to be let go") { demand.open.get() == 0 }
  }

  @Test
  fun aCastRefusedBeforeTheEngineReportsAnythingLetsItGo() {
    // A cast the engine refuses reports its failure and gives the engine back.
    val demand = FakeDemand()
    val model = newModel(demand)
    val api = newEngine()
    api.rejectCast.set(true)
    onMain { model.cast(video, false, pinned("192.0.2.10:8009"), "1080", "off") }
    start(demand, model, api)
    until(what = "the refused cast to report its failure") {
      model.uiState.value.failure != null && !model.uiState.value.submitting
    }
    assertNotNull(model.uiState.value.failure)
    until(what = "the engine to be let go") { demand.open.get() == 0 }
  }

  @Test
  fun withoutADemandNothingIsAskedAndAnAbsentEngineIsUnavailable() {
    // A screen without a demand has no engine to ask for, so an absent engine shows as unavailable.
    val model = newModel(null)
    onMain { model.connect(null) }
    assertEquals(CastPhase.Unavailable, model.uiState.value.idlePhase)
    assertEquals("Casting engine unavailable", model.uiState.value.status)
    onMain { model.discover() }
    assertFalse(model.uiState.value.discovering)
  }

  // The engine starts the way the activity starts it, then waits until the screen has recovered its casts.
  private fun start(demand: FakeDemand, model: MainScreenViewModel, api: FakeEngine) {
    demand.endpoint.value = api.rpc
    onMain { model.connect(api.rpc) }
    until(what = "the screen to finish recovering from the engine") {
      model.uiState.value.ready && !model.uiState.value.recovering
    }
  }

  private fun newModel(demand: FakeDemand?): MainScreenViewModel {
    lateinit var model: MainScreenViewModel
    onMain {
      model = MainScreenViewModel()
      demand?.let { model.demand(it) }
    }
    models += model
    return model
  }

  private fun newEngine(devices: List<Device> = emptyList()): FakeEngine =
    FakeEngine(devices).also { engines += it }

  // Runs the work on the main thread, where the view model is created and called.
  private fun onMain(work: () -> Unit) =
    InstrumentationRegistry.getInstrumentation().runOnMainSync { work() }

  // Polls until the condition holds; fails with what was awaited if it does not hold in time.
  private fun until(
    timeoutMillis: Long = 5_000,
    what: String = "the condition",
    condition: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
      if (System.currentTimeMillis() > deadline)
        fail("Timed out after $timeoutMillis ms waiting for $what")
      Thread.sleep(20)
    }
  }
}

private const val PAIRING_ID = "pairing-1"
private const val SECOND_PAIRING_ID = "$PAIRING_ID-2"

// Counts the holds taken on the engine and the holds not yet closed.
private class FakeDemand : EngineDemand {
  val asked = AtomicInteger()   // holds ever taken
  val open = AtomicInteger()    // holds not yet closed
  override val endpoint = MutableStateFlow<Rpc?>(null)
  override val failure = MutableStateFlow<String?>(null)
  override fun hold(): AutoCloseable {
    asked.incrementAndGet(); open.incrementAndGet()
    val closed = AtomicBoolean()
    return AutoCloseable { if (closed.compareAndSet(false, true)) open.decrementAndGet() }
  }
}

// A fake engine API. Each watch stays open until the test ends that cast with an event.
private class FakeEngine(private val devices: List<Device>) : AutoCloseable {
  val server = MockWebServer()
  val listDevices = AtomicInteger()
  val submissions = CopyOnWriteArrayList<CastRequest>()
  val castIds = CopyOnWriteArrayList<String>()
  // Casts whose watch request has arrived.
  val watched: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
  val cancelledPairings = CopyOnWriteArrayList<String>()
  val startedPairings = AtomicInteger()
  val finishedPairings = CopyOnWriteArrayList<String>()
  val rejectCast = AtomicBoolean()
  // The answer to CancelPairing waits for this gate; it is open by default.
  @Volatile private var cancelGate = CountDownLatch(0)
  private val ends = ConcurrentHashMap<String, CompletableFuture<WatchResponse>>()

  init {
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when (request.path) {
            "/castor.v1.DeviceService/ListDevices" -> {
              listDevices.incrementAndGet()
              proto(ListDevicesResponse.newBuilder().addAllDevices(devices).build().toByteArray())
            }
            "/castor.v1.DeviceService/StartPairing" -> {
              // Each pairing gets its own id; the first one is the id the other tests expect.
              val call = startedPairings.incrementAndGet()
              val id = if (call == 1) PAIRING_ID else "$PAIRING_ID-$call"
              proto(StartPairingResponse.newBuilder().setPairingId(id).build().toByteArray())
            }
            "/castor.v1.DeviceService/CancelPairing" -> {
              // The request is recorded on arrival, then its answer waits for the gate.
              cancelledPairings += CancelPairingRequest.parseFrom(request.body.readByteArray()).pairingId
              cancelGate.await(20, TimeUnit.SECONDS)
              proto(CancelPairingResponse.getDefaultInstance().toByteArray())
            }
            "/castor.v1.DeviceService/FinishPairing" -> {
              finishedPairings += FinishPairingRequest.parseFrom(request.body.readByteArray()).pairingId
              proto(FinishPairingResponse.getDefaultInstance().toByteArray())
            }
            "/castor.v1.CastService/ListCasts" ->
              proto(ListCastsResponse.getDefaultInstance().toByteArray())
            "/castor.v1.CastService/Cast" ->
              if (rejectCast.get())
                MockResponse()
                  .setResponseCode(503)
                  .setHeader("Content-Type", "application/json")
                  .setBody("{\"code\":\"unavailable\",\"message\":\"The TV refused the cast\"}")
              else {
                submissions += CastRequest.parseFrom(request.body.readByteArray())
                val id = "cast-${castIds.size + 1}"
                castIds += id
                proto(CastResponse.newBuilder().setCastId(id).build().toByteArray())
              }
            "/castor.v1.CastService/Watch" -> {
              // Connect stream requests carry a five-byte envelope before the message.
              request.body.skip(5)
              val id = WatchRequest.parseFrom(request.body.readByteArray()).castId
              watched += id
              val event =
                ends.computeIfAbsent(id) { CompletableFuture<WatchResponse>() }.get(20, TimeUnit.SECONDS)
              MockResponse()
                .setHeader("Content-Type", "application/connect+proto")
                .setBody(Buffer().write(frame(event)))
            }
            else -> MockResponse().setResponseCode(404)
          }
      }
    server.start()
  }

  val rpc = Rpc(server.url("/").toString().removeSuffix("/"), "fixture")

  // Sends an event to the watch of a cast; the watch ends with it when the event ends the cast.
  fun end(id: String, event: WatchResponse) {
    ends.computeIfAbsent(id) { CompletableFuture<WatchResponse>() }.complete(event)
  }

  // Withholds the answer to the next CancelPairing until releaseCancelAnswer is called.
  fun withholdCancelAnswer() {
    cancelGate = CountDownLatch(1)
  }

  fun releaseCancelAnswer() {
    cancelGate.countDown()
  }

  override fun close() {
    // Releases any watch still open and any answer still withheld, so the server does not wait for them.
    cancelGate.countDown()
    ends.values.forEach { it.complete(completed()) }
    server.shutdown()
  }
}

private fun proto(bytes: ByteArray) =
  MockResponse().setHeader("Content-Type", "application/proto").setBody(Buffer().write(bytes))

private fun frame(message: WatchResponse): ByteArray {
  val bytes = message.toByteArray()
  return Buffer().writeByte(0).writeInt(bytes.size).write(bytes).readByteArray()
}

private fun completed(): WatchResponse =
  WatchResponse.newBuilder()
    .setEnded(Ended.newBuilder().setCompleted(com.google.protobuf.Empty.getDefaultInstance()))
    .build()

private fun failed(code: FailureCode, message: String): WatchResponse =
  WatchResponse.newBuilder()
    .setEnded(
      Ended.newBuilder().setFailed(Failure.newBuilder().setCode(code).setMessage(message))
    )
    .build()

private fun device(name: String, address: String): Device =
  Device.newBuilder()
    .setId("test:$address")
    .setName(name)
    .setAddress(address)
    .setType(DeviceType.DEVICE_TYPE_CHROMECAST)
    .build()

private fun pinned(address: String): Target =
  Target.newBuilder()
    .setPinned(
      Target.Pinned.newBuilder().setAddress(address).setType(DeviceType.DEVICE_TYPE_CHROMECAST)
    )
    .build()

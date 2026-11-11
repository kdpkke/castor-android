package io.github.stupside.castor

import io.github.stupside.castor.EngineLifecycle.Phase
import org.junit.Assert.*
import org.junit.Test

class EngineLifecycleTest {
  private val calls = mutableListOf<String>()

  private fun build(): EngineLifecycle =
    EngineLifecycle(
      start = { calls.add("start $it") },
      stop = { calls.add("stop $it") },
      linger = { calls.add("linger $it") },
    )

  private fun assertCalls(vararg expected: String) {
    assertEquals(expected.toList(), calls)
  }

  @Test
  fun nothingRunsUntilSomethingAsks() {
    val lifecycle = build()
    assertEquals(Phase.Stopped, lifecycle.phase)
    assertEquals(0, lifecycle.generation)
    assertCalls()
  }

  @Test
  fun aHoldStartsTheEngineAndItsReleaseLetsItGoAfterTheLinger() {
    val lifecycle = build()
    val hold = lifecycle.hold()
    assertCalls("start 1")
    assertEquals(Phase.Starting, lifecycle.phase)
    assertEquals(1, lifecycle.holds)

    lifecycle.ready(1)
    assertEquals(Phase.Running, lifecycle.phase)
    assertCalls("start 1")

    hold.close()
    assertCalls("start 1", "linger 1")
    assertEquals(Phase.Running, lifecycle.phase)

    lifecycle.lingered(1)
    assertCalls("start 1", "linger 1", "stop 1")
    assertEquals(Phase.Stopping, lifecycle.phase)

    lifecycle.stopped(1, restart = true)
    assertEquals(Phase.Stopped, lifecycle.phase)
    assertCalls("start 1", "linger 1", "stop 1")
  }

  @Test
  fun workThatComesBackDuringTheLingerKeepsTheEngine() {
    val lifecycle = build()
    val hold = lifecycle.hold()
    lifecycle.ready(1)
    hold.close()
    assertCalls("start 1", "linger 1")

    lifecycle.hold()
    assertCalls("start 1", "linger 1")

    lifecycle.lingered(1)
    assertCalls("start 1", "linger 1")
    assertEquals(Phase.Running, lifecycle.phase)
  }

  @Test
  fun aLiveCastKeepsTheEngineWithoutAnyHold() {
    val lifecycle = build()
    val hold = lifecycle.hold()
    lifecycle.ready(1)
    lifecycle.active(1, true)
    assertTrue(lifecycle.active)

    hold.close()
    assertCalls("start 1")

    lifecycle.active(1, false)
    assertFalse(lifecycle.active)
    assertCalls("start 1", "linger 1")

    lifecycle.lingered(1)
    assertCalls("start 1", "linger 1", "stop 1")
    assertEquals(Phase.Stopping, lifecycle.phase)
  }

  @Test
  fun twoCastsMayEndInEitherOrder() {
    for (aFirst in listOf(true, false)) {
      calls.clear()
      val lifecycle = build()
      val a = lifecycle.hold()
      val b = lifecycle.hold()
      assertCalls("start 1")

      lifecycle.ready(1)
      lifecycle.active(1, true)
      val (first, second) = if (aFirst) a to b else b to a
      first.close()
      assertCalls("start 1")

      second.close()
      assertCalls("start 1")

      lifecycle.active(1, false)
      assertCalls("start 1", "linger 1")
    }

    calls.clear()
    val lifecycle = build()
    val a = lifecycle.hold()
    val b = lifecycle.hold()
    lifecycle.ready(1)
    lifecycle.active(1, true)
    a.close()
    lifecycle.active(1, false)
    assertCalls("start 1")

    b.close()
    assertCalls("start 1", "linger 1")
  }

  @Test
  fun workThatEndsBeforeTheEngineEverSaidActiveIsLetGo() {
    val lifecycle = build()
    val hold = lifecycle.hold()
    lifecycle.ready(1)
    hold.close()
    assertCalls("start 1", "linger 1")

    lifecycle.lingered(1)
    assertCalls("start 1", "linger 1", "stop 1")
    assertEquals(Phase.Stopping, lifecycle.phase)
  }

  @Test
  fun workGivenUpBeforeTheEngineAnswersIsLetGoOnceItDoes() {
    val lifecycle = build()
    val hold = lifecycle.hold()
    hold.close()
    assertCalls("start 1")
    assertEquals(Phase.Starting, lifecycle.phase)

    lifecycle.ready(1)
    assertCalls("start 1", "linger 1")
    assertEquals(Phase.Running, lifecycle.phase)
  }

  @Test
  fun aRequestDuringTheStopIsNotLost() {
    val lifecycle = build()
    val hold = lifecycle.hold()
    lifecycle.ready(1)
    hold.close()
    lifecycle.lingered(1)
    assertEquals(Phase.Stopping, lifecycle.phase)

    lifecycle.hold()
    assertCalls("start 1", "linger 1", "stop 1")
    assertEquals(Phase.Stopping, lifecycle.phase)

    lifecycle.stopped(1, restart = true)
    assertCalls("start 1", "linger 1", "stop 1", "start 2")
    assertEquals(Phase.Starting, lifecycle.phase)
    assertEquals(2, lifecycle.generation)
  }

  @Test
  fun requestsDuringTheStartShareIt() {
    val lifecycle = build()
    lifecycle.hold()
    lifecycle.hold()
    lifecycle.hold()
    assertCalls("start 1")
    assertEquals(Phase.Starting, lifecycle.phase)
    assertEquals(3, lifecycle.holds)
  }

  @Test
  fun signalsOfAnEarlierEngineAreIgnored() {
    val lifecycle = build()
    val first = lifecycle.hold()
    lifecycle.ready(1)
    first.close()
    lifecycle.lingered(1)
    val second = lifecycle.hold()
    lifecycle.stopped(1, restart = true)
    lifecycle.ready(2)
    assertCalls("start 1", "linger 1", "stop 1", "start 2")
    assertEquals(Phase.Running, lifecycle.phase)
    assertEquals(2, lifecycle.generation)

    lifecycle.active(1, true)
    lifecycle.ready(1)
    lifecycle.lingered(1)
    lifecycle.stopped(1, restart = true)
    assertEquals(Phase.Running, lifecycle.phase)
    assertEquals(2, lifecycle.generation)
    assertFalse(lifecycle.active)
    assertCalls("start 1", "linger 1", "stop 1", "start 2")

    second.close()
    assertCalls("start 1", "linger 1", "stop 1", "start 2", "linger 2")

    lifecycle.active(1, false)
    assertCalls("start 1", "linger 1", "stop 1", "start 2", "linger 2")
  }

  @Test
  fun aDeadEngineIsNotRestartedByItselfButTheNextRequestStartsIt() {
    val lifecycle = build()
    val a = lifecycle.hold()
    lifecycle.ready(1)
    lifecycle.active(1, true)
    lifecycle.stopped(1, restart = false)
    assertEquals(Phase.Stopped, lifecycle.phase)
    assertFalse(lifecycle.active)
    assertCalls("start 1")

    a.close()
    assertCalls("start 1")

    lifecycle.hold()
    assertCalls("start 1", "start 2")
  }

  @Test
  fun aFailedStartDoesNotLoop() {
    val lifecycle = build()
    lifecycle.hold()
    assertCalls("start 1")
    assertEquals(Phase.Starting, lifecycle.phase)

    lifecycle.stopped(1, restart = false)
    assertEquals(Phase.Stopped, lifecycle.phase)
    assertCalls("start 1")

    lifecycle.hold()
    assertCalls("start 1", "start 2")
    assertEquals(Phase.Starting, lifecycle.phase)
  }

  @Test
  fun silenceIsNotInactivity() {
    val lifecycle = build()
    val hold = lifecycle.hold()
    lifecycle.ready(1)
    lifecycle.active(1, true)
    hold.close()
    assertCalls("start 1")
    assertEquals(Phase.Running, lifecycle.phase)
    assertTrue(lifecycle.active)
  }

  @Test
  fun anEngineStartedByABoundClientIsLeftToIt() {
    val lifecycle = build()
    assertEquals(1, lifecycle.created())
    assertEquals(1, lifecycle.generation)
    assertEquals(Phase.Running, lifecycle.phase)
    assertCalls()

    lifecycle.lingered(1)
    assertCalls()
    assertEquals(Phase.Running, lifecycle.phase)

    val bound = lifecycle.hold()
    bound.close()
    assertCalls()
    assertEquals(Phase.Running, lifecycle.phase)

    lifecycle.stopped(1, restart = false)
    assertEquals(Phase.Stopped, lifecycle.phase)
    assertCalls()

    val second = lifecycle.hold()
    assertCalls("start 2")
    assertEquals(2, lifecycle.created())
    assertEquals(Phase.Starting, lifecycle.phase)

    lifecycle.ready(2)
    second.close()
    assertCalls("start 2", "linger 2")
  }

  @Test
  fun closingAHoldTwiceCountsOnce() {
    val lifecycle = build()
    val a = lifecycle.hold()
    val b = lifecycle.hold()
    lifecycle.ready(1)

    a.close()
    a.close()
    assertEquals(1, lifecycle.holds)
    assertCalls("start 1")

    b.close()
    assertEquals(0, lifecycle.holds)
    assertCalls("start 1", "linger 1")
  }

  @Test
  fun theUserStoppingTheEngineLetsWorkWaitForTheNextRequest() {
    val lifecycle = build()
    val a = lifecycle.hold()
    lifecycle.ready(1)
    lifecycle.stopped(1, restart = false)
    assertEquals(Phase.Stopped, lifecycle.phase)
    assertCalls("start 1")

    a.close()
    assertEquals(Phase.Stopped, lifecycle.phase)
    assertCalls("start 1")

    lifecycle.hold()
    assertCalls("start 1", "start 2")
    assertEquals(Phase.Starting, lifecycle.phase)
  }
}

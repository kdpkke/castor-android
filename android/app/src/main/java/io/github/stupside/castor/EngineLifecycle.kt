package io.github.stupside.castor

// When the casting engine runs: while a cast is live in it, or while the app holds it for work of its own
// (a search, a pairing, a cast being asked). Without either it is stopped, and the next hold starts it again.
// Starts and stops never overlap: a new engine starts only once the previous one is gone. Nothing here
// touches Android, so the rules are tested on their own; the callbacks do the work.
internal class EngineLifecycle(
  // Asked to start the engine of this generation.
  private val start: (generation: Int) -> Unit,
  // Asked to stop the engine of this generation.
  private val stop: (generation: Int) -> Unit,
  // Asked to call lingered(generation) after the pause an idle engine is given before it is stopped.
  private val linger: (generation: Int) -> Unit,
) {
  enum class Phase { Stopped, Starting, Running, Stopping }

  var phase = Phase.Stopped
    private set

  // Counts the engines started so far; what an engine reports carries the generation it was started as.
  var generation = 0
    private set

  var holds = 0
    private set

  // Whether the running engine says it has casts.
  var active = false
    private set

  // The generation of an engine a bound client started and this class never stops; 0 when none.
  private var left = 0

  private val wanted: Boolean
    get() = holds > 0 || active

  // Keeps the engine for some work until the returned hold is closed. Closing twice counts once.
  @Synchronized
  fun hold(): AutoCloseable {
    holds++
    if (phase == Phase.Stopped) begin()
    return object : AutoCloseable {
      private var closed = false

      override fun close() {
        synchronized(this@EngineLifecycle) {
          if (closed) return
          closed = true
          holds--
          settle()
        }
      }
    }
  }

  // The engine process of a service that has just been created: the generation this class asked to start,
  // or, for a service someone else started (a bound client), a new generation that this class never stops.
  @Synchronized
  fun created(): Int {
    if (phase == Phase.Starting) return generation
    generation++
    active = false
    phase = Phase.Running
    left = generation
    return generation
  }

  // The engine of that generation answers.
  @Synchronized
  fun ready(generation: Int): Unit {
    if (generation != this.generation || phase != Phase.Starting) return
    phase = Phase.Running
    settle()
  }

  // The engine of that generation says whether it has casts. It only speaks when that changes.
  @Synchronized
  fun active(generation: Int, now: Boolean): Unit {
    if (generation != this.generation || phase != Phase.Running) return
    active = now
    settle()
  }

  // The pause given to an idle engine of that generation is over.
  @Synchronized
  fun lingered(generation: Int): Unit {
    if (generation != this.generation || phase != Phase.Running || wanted || generation == left) return
    phase = Phase.Stopping
    stop(generation)
  }

  // The engine of that generation is gone. restart is false when it failed, died or was stopped by the user:
  // then work still held is not retried by itself, and only the next hold starts an engine again.
  @Synchronized
  fun stopped(generation: Int, restart: Boolean): Unit {
    if (generation != this.generation || phase == Phase.Stopped) return
    phase = Phase.Stopped
    active = false
    if (left == generation) left = 0
    if (restart && holds > 0) begin()
  }

  private fun begin() {
    generation++
    active = false
    phase = Phase.Starting
    start(generation)
  }

  private fun settle() {
    if (phase == Phase.Running && !wanted && generation != left) linger(generation)
  }
}

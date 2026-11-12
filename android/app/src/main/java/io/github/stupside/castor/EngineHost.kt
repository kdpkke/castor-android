package io.github.stupside.castor

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.stupside.castor.data.Rpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// How the app asks for the engine, which runs only while something needs it.
internal interface EngineDemand {
  // Keeps the engine for some work until the returned hold is closed.
  fun hold(): AutoCloseable

  // The engine that answers now: null while none does, and from the moment one is asked to stop.
  val endpoint: StateFlow<Rpc?>

  // Why the engine last failed; null after a clean stop or a new request.
  val failure: StateFlow<String?>
}

// The one place that starts and stops the engine's service, following EngineLifecycle.
internal object EngineHost : EngineDemand {
  // How long an engine with nothing to do is kept before it is stopped, so one request after another shares it.
  // The pause must stay well above the second the engine takes to report a cast it has just accepted, since during that second only the pause keeps it.
  @Volatile var lingerMillis = 15_000L

  // The running engine's API; null while none answers.
  override val endpoint: MutableStateFlow<Rpc?> = MutableStateFlow(null)

  // Why the engine last failed; null after a clean stop or a new request.
  override val failure: MutableStateFlow<String?> = MutableStateFlow(null)

  val phase: EngineLifecycle.Phase
    get() = lifecycle.phase

  val videos: LocalVideos?
    get() = service?.videos

  private val main = Handler(Looper.getMainLooper())
  @Volatile private var context: Context? = null
  @Volatile private var service: CastorService? = null

  // The linger waiting to fire; it is only read and written on the main thread.
  private var pause: Runnable? = null

  private val lifecycle =
    EngineLifecycle(
      start = { generation -> main.post { launch(generation) } },
      stop = { generation ->
        // From here the engine is going: new work waits for the next one.
        endpoint.value = null
        main.post { service?.takeIf { it.generation == generation }?.shutdown() }
      },
      linger = { generation -> main.post { rest(generation) } },
    )

  // Runs on the main thread. Only the latest idle moment counts, so work that came and went restarts the pause.
  private fun rest(generation: Int) {
    pause?.let { main.removeCallbacks(it) }
    val next = Runnable { lifecycle.lingered(generation) }
    pause = next
    main.postDelayed(next, lingerMillis)
  }

  // Runs on the main thread. A service that cannot start is reported and does not count as started.
  private fun launch(generation: Int) {
    val context = context
    if (context == null) {
      failure.value = "Castor cannot start its engine before the app is open."
      lifecycle.stopped(generation, restart = false)
      return
    }
    try {
      context.startForegroundService(Intent(context, CastorService::class.java))
    } catch (error: RuntimeException) {
      failure.value = error.message ?: "Unable to start Castor"
      lifecycle.stopped(generation, restart = false)
    }
  }

  fun attach(context: Context) {
    this.context = context.applicationContext
  }

  override fun hold(): AutoCloseable {
    failure.value = null
    return lifecycle.hold()
  }

  // Called by the service as it starts, stops and reports.
  internal fun created(service: CastorService): Int {
    this.service = service
    return lifecycle.created()
  }

  internal fun ready(service: CastorService, rpc: Rpc) {
    if (this.service === service) endpoint.value = rpc
    lifecycle.ready(service.generation)
  }

  internal fun active(service: CastorService, now: Boolean) = lifecycle.active(service.generation, now)

  // The engine is going: nothing may use its API any more.
  internal fun closing(service: CastorService) {
    if (this.service === service) endpoint.value = null
  }

  internal fun stopped(service: CastorService, failure: String?, restart: Boolean) {
    if (this.service === service) {
      this.service = null
      endpoint.value = null
    }
    if (failure != null) this.failure.value = failure
    lifecycle.stopped(service.generation, restart)
    // An app update that waited for the casts to end can close Castor now.
    context?.let(AppUpdates::engineStopped)
  }

  // The user asked for the engine to stop, with whatever it is casting.
  fun stopNow() {
    main.post { service?.stopByUser() }
  }
}

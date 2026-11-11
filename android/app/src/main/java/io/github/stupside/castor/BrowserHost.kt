package io.github.stupside.castor

import android.content.Context

// The in-app browser outlives the engine: the screen browses with it while nothing is cast, and a cast keeps the
// pages it reads from. It is one browser for the app, closed once neither the screen nor an engine uses it.
internal object BrowserHost {
  private val owners = mutableSetOf<Any>()
  private var resolver: BrowserResolver? = null

  // Owners are named, so a screen recreated by a rotation is the same owner and keeps its pages.
  @Synchronized
  fun acquire(owner: Any, context: Context): BrowserResolver {
    owners += owner
    return resolver ?: BrowserResolver(context.applicationContext).also { resolver = it }
  }

  @Synchronized
  fun release(owner: Any) {
    if (owners.remove(owner) && owners.isEmpty()) {
      resolver?.close()
      resolver = null
    }
  }
}

package com.muratoreware.castorandroid

import android.app.Application

class CastorApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    // The browser's own processes start this class too; the debug log belongs to Castor's main process only.
    if (getProcessName() == packageName) DebugLog.start(this)
  }
}

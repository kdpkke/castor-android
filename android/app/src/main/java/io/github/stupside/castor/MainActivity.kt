package io.github.stupside.castor

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import io.github.stupside.castor.theme.*
import io.github.stupside.castor.ui.main.BrowserRequestDialog
import io.github.stupside.castor.ui.main.CastOptions
import io.github.stupside.castor.ui.main.MainScreen
import io.github.stupside.castor.ui.main.MainScreenViewModel

class MainActivity : ComponentActivity() {
  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(CastOptions(newBase).localizedContext())
  }

  private var service by mutableStateOf<CastorService?>(null)
  private var permissionDenied by mutableStateOf(false)
  private var bound = false
  private lateinit var model: MainScreenViewModel
  private val permissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
      permissionDenied =
        Build.VERSION.SDK_INT >= 37 &&
          granted["android.permission.ACCESS_LOCAL_NETWORK"] != true &&
          checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
            PackageManager.PERMISSION_GRANTED
      if (!permissionDenied) startEngine()
    }
  private val connection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as CastorService.LocalBinder).service
      }

      override fun onServiceDisconnected(name: ComponentName) {
        service = null
        model.connect(null)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    model = ViewModelProvider(this)[MainScreenViewModel::class.java]
    enableEdgeToEdge()
    setContent {
      val options = remember { CastOptions(this) }
      var theme by remember { mutableStateOf(options.theme()) }
      LaunchedEffect(service) { service?.endpoint?.collect { model.connect(it) } }
      LaunchedEffect(service) {
        service?.failure?.collect { message ->
          message?.let { model.fail(IllegalStateException(it)) }
        }
      }
      CastorTheme(theme) {
        val dark = MaterialTheme.colorScheme.background.luminance() < .2f
        SideEffect {
          WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
          }
        }
        LaunchedEffect(service) {
          val current = service ?: return@LaunchedEffect
          current.stopped.collect { stopped ->
            if (stopped) {
              if (bound) {
                unbindService(connection)
                bound = false
              }
              service = null
              model.connect(null)
              model.fail(
                IllegalStateException(current.failure.value ?: "The casting engine stopped.")
              )
            }
          }
        }
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
          if (permissionDenied)
            Box(
              Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
              contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
              GlassPanel {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                  Text(
                    stringResource(R.string.permission_title),
                    style = MaterialTheme.typography.headlineMedium,
                  )
                  Text(stringResource(R.string.permission_help))
                  GlassButton(onClick = { requestPermissions() }, prominent = true) {
                    Text(stringResource(R.string.allow_network))
                  }
                  TextButton(
                    onClick = {
                      startActivity(
                        Intent(
                          android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          android.net.Uri.parse("package:$packageName"),
                        )
                      )
                    }
                  ) {
                    Text(stringResource(R.string.open_settings))
                  }
                }
              }
            }
          service?.let { current ->
            val asking by current.browserRequest.collectAsState()
            if (asking) BrowserRequestDialog(current::answerBrowser)
          }
          if (!permissionDenied)
            MainScreen(
              model,
              service?.browser,
              intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty(),
              restart = { restartEngine() },
              close = {
                stopService(Intent(this, CastorService::class.java))
                model.connect(null)
                finish()
              },
              language = CastOptions(this).language(),
              selectLanguage = { selected ->
                val options = CastOptions(this)
                if (options.language() != selected) {
                  options.language(selected)
                  recreate()
                }
              },
              theme = theme,
              selectTheme = { selected ->
                options.theme(selected)
                theme = selected
              },
            )
        }
      }
    }
    requestPermissions()
  }

  private fun requestPermissions() {
    val needed = buildList {
      if (
        Build.VERSION.SDK_INT >= 37 &&
          checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
            PackageManager.PERMISSION_GRANTED
      )
        add("android.permission.ACCESS_LOCAL_NETWORK")
      if (
        Build.VERSION.SDK_INT >= 33 &&
          checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
      )
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (needed.isEmpty()) {
      permissionDenied = false
      startEngine()
    } else permissions.launch(needed.toTypedArray())
  }

  // A stopped engine leaves its service bound to this activity; binding again alone does nothing.
  // Unbind first so a stopped service is destroyed and a running one is reconnected and recovered.
  private fun restartEngine() {
    if (bound) {
      unbindService(connection)
      bound = false
      service = null
      model.connect(null)
    }
    startEngine()
  }

  private fun startEngine() {
    if (bound) return
    val intent = Intent(this, CastorService::class.java)
    startForegroundService(intent)
    bound = bindService(intent, connection, BIND_AUTO_CREATE)
  }

  override fun onResume() {
    super.onResume()
    if (
      permissionDenied &&
        Build.VERSION.SDK_INT >= 37 &&
        checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") ==
          PackageManager.PERMISSION_GRANTED
    ) {
      permissionDenied = false
      startEngine()
    }
  }

  override fun onDestroy() {
    if (bound) unbindService(connection)
    super.onDestroy()
  }
}

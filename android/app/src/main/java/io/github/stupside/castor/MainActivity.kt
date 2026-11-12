package io.github.stupside.castor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import io.github.stupside.castor.ui.main.CastOptions
import io.github.stupside.castor.ui.main.MainScreen
import io.github.stupside.castor.ui.main.MainScreenViewModel

class MainActivity : ComponentActivity() {
  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(CastOptions(newBase).localizedContext())
  }

  private var permissionDenied by mutableStateOf(false)
  private lateinit var model: MainScreenViewModel
  private lateinit var browser: BrowserResolver
  private var sharedUrl by mutableStateOf("")
  private val videoPicker =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { model.selectVideo(applicationContext.contentResolver, it) }
    }
  private val permissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
      permissionDenied =
        Build.VERSION.SDK_INT >= 37 &&
          granted["android.permission.ACCESS_LOCAL_NETWORK"] != true &&
          checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
            PackageManager.PERMISSION_GRANTED
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    model = ViewModelProvider(this)[MainScreenViewModel::class.java]
    EngineHost.attach(applicationContext)
    model.demand(EngineHost)
    // The share of the browser belongs to the view model: a recreated screen keeps it,
    // and each screen open at once has its own.
    browser = BrowserHost.acquire(model, this)
    sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    if (savedInstanceState == null) receiveVideo(intent)
    AppUpdates.check(applicationContext)
    enableEdgeToEdge()
    setContent {
      val options = remember { CastOptions(this) }
      var theme by remember { mutableStateOf(options.theme()) }
      val engine by EngineHost.endpoint.collectAsState()
      LaunchedEffect(Unit) { EngineHost.endpoint.collect { model.connect(it, EngineHost.videos) } }
      LaunchedEffect(Unit) {
        EngineHost.failure.collect { message ->
          message?.let { model.fail(IllegalStateException(it)) }
        }
      }
      LaunchedEffect(engine) {
        if (engine == null) return@LaunchedEffect
        model.selectedVideo.collect { video ->
          // Only a running engine's service gets the grant, so this never starts one nobody asked for.
          if (
            EngineHost.endpoint.value != null &&
              video != null &&
              contentResolver.persistedUriPermissions.none { it.uri == video.uri }
          ) {
            try {
              // Non-persistable shares keep their temporary grant through background playback.
              startService(videoIntent(video.uri))
            } catch (error: SecurityException) {
              model.clearVideo()
              model.fail(error)
            }
          }
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
          if (!permissionDenied)
            MainScreen(
              model,
              browser,
              sharedUrl,
              restart = { model.dismissError() },
              close = {
                EngineHost.stopNow()
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
              pickVideo = { videoPicker.launch(arrayOf("video/*")) },
            )
        }
      }
    }
    requestPermissions()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    if (sharedUrl.isNotBlank()) model.clearVideo()
    receiveVideo(intent)
  }

  private fun receiveVideo(intent: Intent) {
    if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
      val uri =
        androidx.core.content.IntentCompat.getParcelableExtra(
          intent,
          Intent.EXTRA_STREAM,
          android.net.Uri::class.java,
        )
      uri?.let { model.selectVideo(applicationContext.contentResolver, it) }
    }
  }

  private fun videoIntent(uri: android.net.Uri) =
    Intent(this, CastorService::class.java)
      .setData(uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

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
    } else permissions.launch(needed.toTypedArray())
  }

  override fun onResume() {
    super.onResume()
    AppUpdates.shown(true)
    if (
      permissionDenied &&
        Build.VERSION.SDK_INT >= 37 &&
        checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") ==
          PackageManager.PERMISSION_GRANTED
    ) {
      permissionDenied = false
    }
  }

  override fun onPause() {
    AppUpdates.shown(false)
    super.onPause()
  }

  override fun onDestroy() {
    // A recreated screen keeps its browser; a screen that is gone gives its share back.
    if (!isChangingConfigurations) BrowserHost.release(model)
    super.onDestroy()
  }
}

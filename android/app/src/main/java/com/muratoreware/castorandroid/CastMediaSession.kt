package com.muratoreware.castorandroid

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import castor.v1.CastOuterClass.Cast
import com.muratoreware.castorandroid.data.DataRepository
import com.muratoreware.castorandroid.data.observation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the phone's media controls show and act on: the newest cast the engine is casting. */
internal data class MediaControls(
  val castId: String,
  val tv: String,
  val video: String,
  val paused: Boolean,
  val positionMs: Long?,
  val durationMs: Long?,
  val canPause: Boolean,
  val canSeek: Boolean,
) {
  /** Where a step of [deltaMs] from the last reported position lands, within the video; null without a position. */
  fun stepped(deltaMs: Long): Long? {
    val at = positionMs ?: return null
    val end = durationMs ?: Long.MAX_VALUE
    return (at + deltaMs).coerceIn(0, end)
  }

  companion object {
    const val STEP_MS = 10_000L

    /** The newest cast that is casting, as listed oldest first; null when none is. */
    fun of(
      casts: List<Cast>,
      videoName: (String) -> String?,
      tvName: (castor.v1.DeviceOuterClass.Device) -> String = { it.name },
    ): MediaControls? {
      val cast = casts.lastOrNull { it.status.hasCasting() } ?: return null
      val head = observation(cast.status)
      val source =
        when {
          cast.source.hasPages() -> cast.source.pages.urlsList.firstOrNull().orEmpty()
          cast.source.hasStream() -> cast.source.stream.url
          else -> ""
        }
      return MediaControls(
        castId = cast.id,
        tv = tvName(cast.device),
        video = videoName(source) ?: runCatching { java.net.URI(source).host }.getOrNull().orEmpty(),
        paused = head.paused,
        positionMs = head.positionMs,
        durationMs = head.durationMs,
        canPause = head.canPause,
        canSeek = head.canSeek,
      )
    }
  }
}

/**
 * The newest cast as the phone's media session: its video and TV in the notification and on the lock screen, with pause,
 * resume, ten-second steps and stop, which reach that cast through the engine as the app's own controls do. The engine
 * keeps casting without it; it only reflects and drives what the engine reports.
 */
internal class CastMediaSession(
  private val context: Context,
  private val scope: CoroutineScope,
  private val engineNotification: () -> Notification,
  private val show: (Notification) -> Unit,
  private val videoName: (String) -> String?,
  private val tvName: (castor.v1.DeviceOuterClass.Device) -> String,
) {
  private val session = MediaSession(context, "castor-cast")
  private val refreshing = Mutex()
  @Volatile private var repository: DataRepository? = null
  @Volatile private var current: MediaControls? = null
  // Once released, nothing is shown again: the service is removing its notification.
  @Volatile private var released = false

  init {
    session.setCallback(
      object : MediaSession.Callback() {
        override fun onPlay() = act { repository, controls -> repository.pause(controls.castId, false) }

        override fun onPause() = act { repository, controls -> repository.pause(controls.castId, true) }

        override fun onSeekTo(pos: Long) = act { repository, controls -> repository.seek(controls.castId, pos.coerceAtLeast(0)) }

        override fun onStop() = act { repository, controls -> repository.stop(controls.castId) }

        override fun onCustomAction(action: String, extras: android.os.Bundle?) {
          when (action) {
            ACTION_BACK -> step(-MediaControls.STEP_MS)
            ACTION_FORWARD -> step(MediaControls.STEP_MS)
          }
        }
      }
    )
  }

  /** The engine this session reads and controls casts through. */
  fun attach(repository: DataRepository) {
    this.repository = repository
  }

  /** Reads the casts again and shows the newest casting one, or the engine's own notification when none is. */
  suspend fun refresh() {
    val repository = repository ?: return publish(null)
    val controls =
      try {
        MediaControls.of(repository.casts(), videoName, tvName)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // An engine that did not answer this time says nothing about the cast; the next reading decides.
        return
      }
    publish(controls)
  }

  /** A press from the notification of an older Android, which shows its actions instead of the session's. */
  fun command(action: String) {
    when (action) {
      ACTION_BACK -> step(-MediaControls.STEP_MS)
      ACTION_FORWARD -> step(MediaControls.STEP_MS)
      ACTION_TOGGLE ->
        act { repository, controls -> repository.pause(controls.castId, !controls.paused) }
      ACTION_STOP -> act { repository, controls -> repository.stop(controls.castId) }
    }
  }

  /** Ends the session for good; call on the main thread. */
  fun release() {
    released = true
    repository = null
    session.isActive = false
    session.release()
  }

  private fun step(deltaMs: Long) = act { repository, controls ->
    if (controls.canSeek) controls.stepped(deltaMs)?.let { repository.seek(controls.castId, it) }
  }

  // Each press acts on the cast shown when it was pressed; a failed press leaves the cast as the engine reports it.
  private fun act(command: suspend (DataRepository, MediaControls) -> Unit) {
    val repository = repository ?: return
    val controls = current ?: return
    scope.launch {
      try {
        command(repository, controls)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        android.util.Log.w("CastorMedia", "A media control failed", failure)
      }
      refresh()
    }
  }

  // The playhead moves every reading and the session's state carries it; the notification and metadata change only
  // when what they show does, so they are not posted again every second.
  private suspend fun publish(controls: MediaControls?) =
    refreshing.withLock {
      withContext(Dispatchers.Main) {
        if (released || controls == current) return@withContext
        val before = current
        current = controls
        if (controls == null) {
          session.isActive = false
          show(engineNotification())
          return@withContext
        }
        session.setPlaybackState(state(controls))
        if (before?.copy(positionMs = null) != controls.copy(positionMs = null)) {
          session.setMetadata(metadata(controls))
          session.isActive = true
          show(notification(controls))
        }
      }
    }

  private fun metadata(controls: MediaControls): MediaMetadata =
    MediaMetadata.Builder()
      .putString(MediaMetadata.METADATA_KEY_TITLE, controls.video.ifBlank { controls.tv })
      .putString(MediaMetadata.METADATA_KEY_ARTIST, context.getString(R.string.notification_casting_to, controls.tv))
      .apply { controls.durationMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) } }
      .build()

  private fun state(controls: MediaControls): PlaybackState {
    var actions = PlaybackState.ACTION_STOP
    if (controls.canPause) actions = actions or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
    if (controls.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
    val playing =
      when {
        controls.positionMs == null -> PlaybackState.STATE_BUFFERING
        controls.paused -> PlaybackState.STATE_PAUSED
        else -> PlaybackState.STATE_PLAYING
      }
    return PlaybackState.Builder()
      .setActions(actions)
      .setState(
        playing,
        controls.positionMs ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
        if (playing == PlaybackState.STATE_PLAYING) 1f else 0f,
        SystemClock.elapsedRealtime(),
      )
      .apply {
        if (controls.canSeek) {
          addCustomAction(
            PlaybackState.CustomAction.Builder(ACTION_BACK, context.getString(R.string.playback_back_ten), R.drawable.ic_replay_10)
              .build()
          )
          addCustomAction(
            PlaybackState.CustomAction.Builder(ACTION_FORWARD, context.getString(R.string.playback_forward_ten), R.drawable.ic_forward_10)
              .build()
          )
        }
      }
      .build()
  }

  private fun notification(controls: MediaControls): Notification {
    val compact = mutableListOf<Int>()
    val actions = mutableListOf<Notification.Action>()
    fun add(icon: Int, label: Int, action: String, inCompact: Boolean) {
      if (inCompact) compact += actions.size
      actions += Notification.Action.Builder(Icon.createWithResource(context, icon), context.getString(label), press(action)).build()
    }
    if (controls.canSeek) add(R.drawable.ic_replay_10, R.string.playback_back_ten, ACTION_BACK, true)
    if (controls.canPause) {
      if (controls.paused) add(R.drawable.ic_play, R.string.playback_resume, ACTION_TOGGLE, true)
      else add(R.drawable.ic_pause, R.string.playback_pause, ACTION_TOGGLE, true)
    }
    if (controls.canSeek) add(R.drawable.ic_forward_10, R.string.playback_forward_ten, ACTION_FORWARD, true)
    add(R.drawable.ic_stop, R.string.stop_casting, ACTION_STOP, false)
    return Notification.Builder(context, CastorService.CHANNEL)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(controls.video.ifBlank { controls.tv })
      .setContentText(context.getString(R.string.notification_casting_to, controls.tv))
      .setContentIntent(CastorService.openApp(context))
      .setOngoing(true)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .apply { actions.forEach(::addAction) }
      .setStyle(
        Notification.MediaStyle()
          .setMediaSession(session.sessionToken)
          .setShowActionsInCompactView(*compact.take(3).toIntArray())
      )
      .build()
  }

  private fun press(action: String): PendingIntent =
    PendingIntent.getService(
      context,
      action.hashCode(),
      Intent(context, CastorService::class.java).setAction(action),
      PendingIntent.FLAG_IMMUTABLE,
    )

  companion object {
    const val ACTION_BACK = "com.muratoreware.castorandroid.media.BACK"
    const val ACTION_FORWARD = "com.muratoreware.castorandroid.media.FORWARD"
    const val ACTION_TOGGLE = "com.muratoreware.castorandroid.media.TOGGLE"
    const val ACTION_STOP = "com.muratoreware.castorandroid.media.STOP"
  }
}

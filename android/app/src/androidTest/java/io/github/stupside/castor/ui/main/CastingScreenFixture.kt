package io.github.stupside.castor.ui.main

import castor.v1.CastOuterClass.Cast

// Synthetic single-session state for browser, theme and control fixtures.
internal fun castingScreenState(
  ready: Boolean = true,
  castId: String,
  activeCast: Cast = Cast.newBuilder().setId(castId).build(),
  phase: CastPhase = CastPhase.Connecting,
  playback: PlaybackUiState = PlaybackUiState(),
) =
  MainScreenUiState(
    ready = ready,
    sessions =
      listOf(
        CastSessionUiState(activeCast.toBuilder().setId(castId).build(), phase, playback = playback)
      ),
    selectedCastId = castId,
  )

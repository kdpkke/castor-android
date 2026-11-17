package com.muratoreware.castorandroid.ui.main

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CameraZoomTest {
  @Test
  fun theStepsAreThoseTheCameraReachesFromOneTimes() {
    assertEquals(listOf(1f, 2f, 5f, 10f), zoomSteps(0.6f..20f))
    assertEquals(listOf(1f, 2f), zoomSteps(1f..4f))
    // A camera without zoom offers no step but 1×, so the scanner shows no zoom at all.
    assertEquals(listOf(1f), zoomSteps(1f..1f))
  }

  @Test
  fun aPinchLightsTheLargestStepItReached() {
    val steps = listOf(1f, 2f, 5f, 10f)
    assertEquals(1f, zoomStep(1f, steps))
    assertEquals(1f, zoomStep(1.9f, steps))
    assertEquals(2f, zoomStep(2.7f, steps))
    // A ratio the camera rounds just under a step still counts as it.
    assertEquals(5f, zoomStep(4.98f, steps))
    assertEquals(10f, zoomStep(12f, steps))
    // Below 1×, on an ultra-wide camera, 1× stays lit.
    assertEquals(1f, zoomStep(0.6f, steps))
  }

  @Test
  fun aRatioReadsAsPeopleWriteIt() {
    assertEquals("2×", zoomLabel(2f, Locale.ITALIAN))
    assertEquals("2,7×", zoomLabel(2.66f, Locale.ITALIAN))
    assertEquals("2.7×", zoomLabel(2.66f, Locale.ENGLISH))
    assertEquals("10×", zoomLabel(9.98f, Locale.ITALIAN))
  }

  @Test
  fun aZoomWithNoCameraBoundOffersNothingAndIgnoresAsks() {
    val zoom = CameraZoom()
    assertFalse(zoom.zooms)
    zoom.set(4f)
    assertEquals(1f, zoom.ratio)
  }
}

package io.github.stupside.castor.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stupside.castor.ExtensionAccess
import io.github.stupside.castor.theme.AppTheme
import io.github.stupside.castor.theme.CastorTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BrowserAccessTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  @After
  fun forgetBrowsers() {
    ExtensionAccess(context).revokeAll()
  }

  @Test
  fun removingPermissionsWithdrawsEveryGrantedToken() {
    val access = ExtensionAccess(context)
    val first = access.grant()
    val second = access.grant()
    assertTrue(access.allowed(first) && access.allowed(second))
    compose.setContent { CastorTheme(AppTheme.System) { BrowserAccess(access) {} } }
    compose.onNodeWithText("2 browsers allowed").assertIsDisplayed()
    compose.onNodeWithTag("remove-browsers").performClick()
    compose.onNodeWithText("No browser allowed yet").assertIsDisplayed()
    compose.onNodeWithTag("remove-browsers").assertIsNotEnabled()
    assertFalse(access.allowed(first) || access.allowed(second))
    assertEquals(0, access.count())
  }

  @Test
  fun theInAppQuestionAnswersTheBrowsersRequest() {
    val answers = mutableListOf<Boolean>()
    compose.setContent { CastorTheme(AppTheme.System) { BrowserRequestDialog { answers += it } } }
    compose.onNodeWithText("Allow Firefox to cast?").assertIsDisplayed()
    compose.onNodeWithTag("allow-browser").performClick()
    compose.onNodeWithText("Don’t allow").performClick()
    assertEquals(listOf(true, false), answers)
  }
}

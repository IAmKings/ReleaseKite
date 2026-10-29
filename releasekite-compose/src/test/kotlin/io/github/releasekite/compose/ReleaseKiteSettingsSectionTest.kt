package io.github.releasekite.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.releasekite.core.RouteHealth
import io.github.releasekite.core.UpdateChannel
import io.github.releasekite.core.UpdateRouteSelector
import io.github.releasekite.core.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 更新设置区渲染与回调验收（英文默认资源）：
 * 自动检查开关、Beta 开关（受 `betaChannelEnabled` 控制显隐）、线路健康与重置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class ReleaseKiteSettingsSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: UpdateUiState = UpdateUiState(currentVersionName = "0.1.6"),
        onAutoCheckChange: (Boolean) -> Unit = {},
        onChannelChange: (UpdateChannel) -> Unit = {},
        onResetRouteHealth: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ReleaseKiteSettingsSection(
                    state = state,
                    onAutoCheckChange = onAutoCheckChange,
                    onChannelChange = onChannelChange,
                    onResetRouteHealth = onResetRouteHealth,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun showsTitleAndBothSwitches() {
        render()
        composeRule.onNodeWithText("Update settings").assertExists()
        composeRule.onNodeWithText("Check for updates automatically").assertExists()
        composeRule.onNodeWithText("Receive pre-release (Beta) builds").assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS_AUTO_SWITCH).assertExists()
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS_BETA_SWITCH).assertExists()
        composeRule.onNodeWithText("Reset route status").assertExists()
    }

    @Test
    fun autoCheckSwitchToggleInvokesCallback() {
        var autoCheckChange: Boolean? = null
        render(onAutoCheckChange = { autoCheckChange = it })
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS_AUTO_SWITCH).performClick()
        composeRule.waitForIdle()
        assertEquals(false, autoCheckChange)
    }

    @Test
    fun betaSwitchOnInvokesBetaChannel() {
        var channelChange: UpdateChannel? = null
        render(onChannelChange = { channelChange = it })
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS_BETA_SWITCH).performClick()
        composeRule.waitForIdle()
        assertEquals(UpdateChannel.BETA, channelChange)
    }

    @Test
    fun betaSwitchOffInvokesStableChannel() {
        var channelChange: UpdateChannel? = null
        render(
            state = UpdateUiState(currentVersionName = "0.1.6", channel = UpdateChannel.BETA),
            onChannelChange = { channelChange = it },
        )
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS_BETA_SWITCH).performClick()
        composeRule.waitForIdle()
        assertEquals(UpdateChannel.STABLE, channelChange)
    }

    @Test
    fun betaSwitchHiddenWhenBetaChannelDisabled() {
        render(state = UpdateUiState(currentVersionName = "0.1.6", betaChannelEnabled = false))
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS_BETA_SWITCH).assertDoesNotExist()
        composeRule.onNodeWithText("Receive pre-release (Beta) builds").assertDoesNotExist()
        // 自动检查开关不受影响。
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS_AUTO_SWITCH).assertExists()
    }

    @Test
    fun resetRouteHealthInvokesCallback() {
        var resets = 0
        render(onResetRouteHealth = { resets++ })
        composeRule.onNodeWithTag(ReleaseKiteTestTags.SETTINGS_RESET_ROUTES).performClick()
        composeRule.waitForIdle()
        assertEquals(1, resets)
    }

    @Test
    fun routeHealthShowsHealthyDirectAndCoolingMirror() {
        val now = System.currentTimeMillis()
        render(
            state = UpdateUiState(
                currentVersionName = "0.1.6",
                routeHealth = mapOf(
                    UpdateRouteSelector.DIRECT_ID to RouteHealth(
                        id = UpdateRouteSelector.DIRECT_ID,
                        lastSuccessAt = now - 1_000L,
                        lastLatencyMs = 120L,
                    ),
                    "mirror-a" to RouteHealth(
                        id = "mirror-a",
                        lastFailureAt = now - 1_000L,
                        consecutiveFailures = 2,
                        cooldownUntil = now + 60_000L,
                    ),
                ),
            ),
        )
        composeRule.onNodeWithText("Update routes").assertExists()
        composeRule.onNodeWithText("Direct Healthy", substring = true).assertExists()
        composeRule.onNodeWithText("Mirror mirror-a Cooling down", substring = true).assertExists()
    }

    @Test
    fun emptyRouteHealthShowsNoData() {
        render()
        composeRule.onNodeWithText("No data yet", substring = true).assertExists()
    }
}

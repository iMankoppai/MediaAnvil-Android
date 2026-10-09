package com.imankoppai.mediaanvil

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.ui.UiTestTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

@Suppress("DEPRECATION")
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
class LanguageSwitchTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun languageChipsUpdateTextWithoutRecreatingTheMainPage() {
        assertTrue("Runtime language test needs Android 13+", Build.VERSION.SDK_INT >= 33)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val locales = context.getSystemService(LocaleManager::class.java)
        val preferences = PlaybackPreferences(context)
        val originalLocales = locales.applicationLocales
        val originalLanguage = preferences.language
        var activity: MainActivity? = null
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun resumedActivity(): MainActivity = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            locales.applicationLocales = LocaleList.forLanguageTags("zh-CN")
            preferences.language = "zh-CN"
            instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.imankoppai.mediaanvil.MainActivity").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            }
            main { activity = resumedActivity() }
            compose.waitUntil(10000) { compose.onAllNodesWithTag(UiTestTags.SettingsTab).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(UiTestTags.SettingsTab).performClick()
            compose.onNodeWithText("English").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() }
            main {
                assertSame("Locale change must retain the activity instead of showing startup again", activity, resumedActivity())
                assertEquals("en", activity!!.resources.configuration.locales[0].language)
            }
            compose.onNodeWithTag(UiTestTags.SettingsTab).assertIsSelected()
            compose.onNodeWithText("简体中文").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty() }
            main { assertSame(activity, resumedActivity()); assertEquals("zh", activity!!.resources.configuration.locales[0].language) }
        } finally {
            preferences.language = originalLanguage
            locales.applicationLocales = originalLocales
            main { activity?.finish() }
            preferences.flushPendingWrites()
        }
    }
}

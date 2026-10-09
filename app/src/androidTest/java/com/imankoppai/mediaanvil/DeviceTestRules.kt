package com.imankoppai.mediaanvil

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.FileInputStream

internal fun launchDeviceTestActivity(): DebugTestActivity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.imankoppai.mediaanvil.DebugTestActivity").use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
    }
    var activity: DebugTestActivity? = null
    instrumentation.runOnMainSync {
        activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<DebugTestActivity>().single()
    }
    return checkNotNull(activity)
}

class DeviceActivityRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val activity = launchDeviceTestActivity()
            try { base.evaluate() }
            finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() } }
        }
    }
}

@Suppress("DEPRECATION")
class DeviceComposeRule(private val delegate: ComposeTestRule = createEmptyComposeRule()) :
    ComposeContentTestRule, ComposeTestRule by delegate {
    private var activity: DebugTestActivity? = null
    override fun setContent(composable: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { checkNotNull(activity).setContent(content = composable) }
    }
    override fun apply(base: Statement, description: Description): Statement = delegate.apply(object : Statement() {
        override fun evaluate() {
            activity = launchDeviceTestActivity()
            try { base.evaluate() }
            finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { activity?.finish() } }
        }
    }, description)
}

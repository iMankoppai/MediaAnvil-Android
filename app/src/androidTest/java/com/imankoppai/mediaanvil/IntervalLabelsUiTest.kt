package com.imankoppai.mediaanvil

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.imankoppai.mediaanvil.ui.IntervalTagEditor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IntervalLabelsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun oldPointNeedsExplicitEndAndInvalidEditsCannotBeSaved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var saved: Triple<Long, Long, String>? = null
        compose.setContent { MaterialTheme {
            IntervalTagEditor(113000, null, "旧备注", 267000, { 150000 }, {}, { a,b,n -> saved = Triple(a,b,n) })
        } }
        compose.onNodeWithTag("interval_save").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("interval_end").performTextReplacement("1:00")
        compose.onNodeWithTag("interval_save").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText(context.getString(R.string.interval_current_end)).performClick()
        compose.onNodeWithTag("interval_name").performTextReplacement("副歌")
        compose.onNodeWithTag("interval_save").performClick()
        compose.runOnIdle { assertEquals(Triple(113000L,150000L,"副歌"),saved) }
    }
}

package com.assistant.adi

import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.adi.ui.DashboardActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingRegressionTest {
    @Test fun introductionRequiresNameAndPermissionChecklistIsInline() {
        ActivityScenario.launch(DashboardActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            scenario.onActivity { it.navigateSection("onboarding", detail = false) }
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                activity.findText("Kenalan dulu")?.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val name = activity.findViewById<TextInputEditText>(0x504201)
                assertNotNull(name)
                name.setText("")
                activity.findText("Lanjut")?.performClick()
                val layout = name.parent as TextInputLayout
                assertTrue(layout.error?.isNotBlank() == true)
                assertNotNull(activity.findViewById<TextInputEditText>(0x504201))
            }
            scenario.onActivity { activity ->
                activity.findViewById<TextInputEditText>(0x504201).setText("Naya")
                activity.findText("Lanjut")?.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull(activity.findText("Akses penggunaan"))
                assertNotNull(activity.findText("Selesai"))
            }
        }
    }

    private fun DashboardActivity.findText(label: String): TextView? = findText(findViewById(android.R.id.content), label)

    private fun findText(view: View, label: String): TextView? {
        if (view is TextView && view.text?.toString() == label) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findText(view.getChildAt(index), label)?.let { return it }
        }
        return null
    }
}

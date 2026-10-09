package com.assistant.adi.ui.buddy

import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.fragment.app.Fragment
import com.assistant.adi.R
import com.google.android.material.bottomsheet.BottomSheetDialog

enum class MonitorDetailPanel { SUMMARY, HISTORY, SETTINGS }

/** Shared presentation for monitor detail controls; panel state and data stay with each Fragment. */
class MonitorDetailUi(private val fragment: Fragment) {
    private val ui get() = BuddyPageUi(fragment.requireContext())

    fun installInfoAction(
        menuHost: MenuHost,
        lifecycleOwner: LifecycleOwner,
        title: String,
        meaning: String,
        source: String? = null
    ) = installInfoAction(menuHost, lifecycleOwner, title, { meaning }, source?.let { { it } })

    fun installInfoAction(
        menuHost: MenuHost,
        lifecycleOwner: LifecycleOwner,
        title: String,
        meaning: () -> String,
        source: (() -> String)? = null
    ) {
        val itemId = View.generateViewId()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: android.view.MenuInflater) {
                menu.add(Menu.NONE, itemId, Menu.NONE, "Informasi $title").apply {
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    actionView = FrameLayout(fragment.requireContext()).apply {
                        setPadding(0, 0, ui.ui.dp(12), 0)
                        addView(ui.ui.headerAction(R.drawable.ic_ms_info, "Informasi $title") { button ->
                            showInfoSheet(title, meaning(), source?.invoke(), button)
                        })
                    }
                }
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean = false
        }, lifecycleOwner, Lifecycle.State.RESUMED)
    }

    fun showInfoSheet(title: String, meaning: String, source: String? = null, trigger: View? = null,
        actionLabel: String? = null, actionIcon: Int = 0, action: (() -> Unit)? = null) {
        val context = fragment.requireContext()
        val viewLifecycle = fragment.viewLifecycleOwner.lifecycle
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.ui.dp(24), ui.ui.dp(20), ui.ui.dp(24), ui.ui.dp(24))
        }
        lateinit var dialog: BottomSheetDialog
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ui.text(title, 22f, bold = true).apply { ViewCompat.setAccessibilityHeading(this, true) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.ui.iconButton(R.drawable.ic_ms_close, "Tutup informasi $title") { dialog.dismiss() },
                LinearLayout.LayoutParams(ui.ui.dp(48), ui.ui.dp(48)))
        }, ui.ui.margin(bottom = 16))
        content.addView(ui.text(meaning, 15f, secondary = true), ui.ui.margin(bottom = 16))
        if (!source.isNullOrBlank()) {
            content.addView(ui.text("Sumber data", 16f, bold = true), ui.ui.margin(bottom = 4))
            content.addView(ui.text(source, 15f, secondary = true), ui.ui.margin(bottom = 8))
        }
        if (actionLabel != null && action != null) {
            content.addView(ui.button(actionLabel, icon = actionIcon) {
                dialog.dismiss()
                action()
            }, ui.ui.margin(top = 8))
        }

        dialog = BottomSheetDialog(context)
        dialog.setContentView(ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        })
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
        }
        viewLifecycle.addObserver(observer)
        dialog.setOnDismissListener {
            viewLifecycle.removeObserver(observer)
            if (viewLifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED) && trigger?.isAttachedToWindow == true) {
                trigger.requestFocus()
            }
        }
        dialog.setOnShowListener { dialog.window?.decorView?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) }
        dialog.show()
    }
}

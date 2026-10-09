package com.assistant.adi.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.assistant.adi.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class AppLimitSheet : BottomSheetDialogFragment() {
    private val packageName get() = requireArguments().getString(ARG_PACKAGE).orEmpty()
    private val initialLimit get() = requireArguments().getInt(ARG_LIMIT)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.sheet_app_limit, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        val appName = requireArguments().getString(ARG_NAME).orEmpty()
        view.findViewById<TextView>(R.id.tv_limit_app_name).text = appName
        view.findViewById<TextView>(R.id.tv_current_app_limit).text = if (initialLimit > 0) "Batas saat ini: $initialLimit menit per hari" else "Belum ada batas harian."
        val input = view.findViewById<TextInputEditText>(R.id.input_limit_minutes)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.input_limit_layout)
        if (state == null && initialLimit > 0) input.setText(initialLimit.toString())
        val presets = listOf(
            R.id.btn_limit_15 to 15,
            R.id.btn_limit_30 to 30,
            R.id.btn_limit_60 to 60,
            R.id.btn_limit_120 to 120
        ).map { (id, minutes) -> view.findViewById<MaterialButton>(id) to minutes }
        var syncingPreset = false

        fun updatePresetSelection(value: String?) {
            val matchingPreset = presets.firstOrNull { (_, preset) -> value == preset.toString() }?.second
            syncingPreset = true
            presets.forEach { (button, preset) ->
                val selected = matchingPreset == preset
                button.isChecked = selected
                button.backgroundTintList = ColorStateList.valueOf(
                    if (selected) ContextCompat.getColor(requireContext(), R.color.buddy_selection) else Color.TRANSPARENT
                )
                button.strokeWidth = (resources.displayMetrics.density * if (selected) 2 else 1).toInt()
                button.strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), if (selected) R.color.buddy_action else R.color.buddy_outline)
                )
                button.setTextColor(ContextCompat.getColor(
                    requireContext(),
                    if (selected) R.color.buddy_ink else R.color.buddy_action
                ))
            }
            syncingPreset = false
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePresetSelection(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val group = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.group_limit_presets)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!syncingPreset && isChecked) {
                presets.firstOrNull { (button, _) -> button.id == checkedId }?.second?.let { minutes ->
                    input.setText(minutes.toString())
                    inputLayout.error = null
                }
            }
        }
        val icon = view.findViewById<ImageView>(R.id.iv_limit_app_icon)
        try { icon.setImageDrawable(requireContext().packageManager.getApplicationIcon(packageName)) }
        catch (_: Exception) { icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), android.R.drawable.sym_def_app_icon)) }

        listOf(R.id.btn_limit_15 to 15, R.id.btn_limit_30 to 30, R.id.btn_limit_60 to 60, R.id.btn_limit_120 to 120).forEach { (id, minutes) ->
            view.findViewById<MaterialButton>(id).setOnClickListener { input.setText(minutes.toString()); inputLayout.error = null }
        }
        view.findViewById<MaterialButton>(R.id.btn_delete_app_limit).apply {
            visibility = if (initialLimit > 0) View.VISIBLE else View.GONE
            setOnClickListener { publish(-1); dismiss() }
        }
        view.findViewById<MaterialButton>(R.id.btn_cancel_app_limit).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.btn_save_app_limit).setOnClickListener {
            val minutes = input.text?.toString()?.trim()?.toIntOrNull()
            if (minutes !in 1..1440) {
                inputLayout.error = "Masukkan bilangan bulat 1 sampai 1440 menit."
                input.requestFocus()
            } else {
                publish(minutes!!)
                dismiss()
            }
        }
    }

    override fun onViewStateRestored(state: Bundle?) {
        super.onViewStateRestored(state)
        view?.findViewById<TextInputEditText>(R.id.input_limit_minutes)?.let { input ->
            input.setText(input.text?.toString().orEmpty())
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.apply {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    private fun publish(minutes: Int) {
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
            putString(ARG_PACKAGE, packageName)
            putInt(RESULT_MINUTES, minutes)
        })
    }

    companion object {
        const val RESULT_KEY = "app_limit_result"
        const val RESULT_MINUTES = "minutes"
        private const val ARG_PACKAGE = "package_name"
        private const val ARG_NAME = "app_name"
        private const val ARG_LIMIT = "initial_limit"

        fun newInstance(info: AppUsageInfo) = AppLimitSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_PACKAGE, info.packageName)
                putString(ARG_NAME, info.appName)
                putInt(ARG_LIMIT, info.limitMinutes)
            }
        }
    }
}

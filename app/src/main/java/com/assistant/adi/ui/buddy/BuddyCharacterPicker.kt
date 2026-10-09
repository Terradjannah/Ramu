package com.assistant.adi.ui.buddy

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.assistant.adi.R
import com.google.android.material.card.MaterialCardView

/** Single-selection character control shared by onboarding and Profile. */
class BuddyCharacterPicker(
    context: Context,
    initialCharacter: BuddyCharacter,
    private val onSelectionChanged: (BuddyCharacter) -> Unit = {}
) : LinearLayout(context) {
    private val ui = BuddyUi(context)
    private val options = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val cards = mutableMapOf<BuddyCharacter, MaterialCardView>()
    private var selected = if (initialCharacter.availableInProfile) initialCharacter else BuddyCharacter.PANDA
    private var vertical = false

    init {
        orientation = VERTICAL
        addView(ui.text("Pilih karakter", 17f, true), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = ui.dp(8)
        })
        addView(options, LinearLayout.LayoutParams(-1, -2))
        BuddyCharacter.entries.forEach { character -> addOption(character) }
        reflow(width, force = true)
        updateSelection(notify = false)
    }

    fun selectedCharacter(): BuddyCharacter = selected

    fun select(character: BuddyCharacter) {
        if (!character.availableInProfile) return
        if (selected == character) return
        selected = character
        updateSelection(notify = true)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        reflow(width)
    }

    private fun addOption(character: BuddyCharacter) {
        val preview = BuddyPetView(context).apply {
            characterType = character.rendererId
            motionEnabled = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = ui.text(character.label, 14f, true).apply { gravity = Gravity.CENTER }
        val state = ui.text("", 12f, secondary = true).apply { gravity = Gravity.CENTER }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            addView(preview, LinearLayout.LayoutParams(-1, ui.dp(88)))
            addView(label, LinearLayout.LayoutParams(-1, -2))
            addView(state, LinearLayout.LayoutParams(-1, -2))
        }
        val card = MaterialCardView(context).apply {
            radius = ui.dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = ui.dp(2)
            minimumHeight = ui.dp(144)
            isClickable = character.availableInProfile
            isFocusable = character.availableInProfile
            isCheckable = character.availableInProfile
            addView(content)
            if (character.availableInProfile) {
                setOnClickListener { select(character) }
                setOnFocusChangeListener { _, _ -> updateSelection(notify = false) }
            }
            ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (character.availableInProfile) {
                        info.className = android.widget.RadioButton::class.java.name
                        info.isCheckable = true
                        info.isChecked = selected == character
                    } else {
                        info.className = android.widget.TextView::class.java.name
                        info.isCheckable = false
                        info.isChecked = false
                    }
                }
            })
        }
        card.tag = state
        cards[character] = card
        options.addView(card)
    }

    private fun reflow(width: Int, force: Boolean = false) {
        val narrow = resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale > 1.3f ||
            (width > 0 && width < ui.dp(360))
        if (!force && vertical == narrow && options.childCount > 0) return
        vertical = narrow
        options.orientation = if (vertical) VERTICAL else HORIZONTAL
        options.gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        cards.values.forEach { card ->
            card.layoutParams = if (vertical) {
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }
            } else {
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) }
            }
        }
    }

    private fun updateSelection(notify: Boolean) {
        cards.forEach { (character, card) ->
            val isSelected = character.availableInProfile && selected == character
            card.isChecked = isSelected
            card.strokeColor = ui.color(if (isSelected) R.color.buddy_action else R.color.buddy_outline)
            card.setCardBackgroundColor(ui.color(if (isSelected) R.color.buddy_mint_fill else R.color.buddy_tile))
            val status = when {
                isSelected -> "Dipilih"
                !character.availableInProfile -> "Belum tersedia"
                else -> "Pilih"
            }
            (card.tag as android.widget.TextView).text = status
            ViewCompat.setStateDescription(card, status)
            card.contentDescription = "${character.label}. $status"
        }
        if (notify) onSelectionChanged(selected)
    }
}

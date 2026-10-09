package com.assistant.adi.ui.ai.adapter

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.assistant.adi.data.ChatMessage
import com.assistant.adi.databinding.ChatItemAiBinding
import com.assistant.adi.databinding.ChatItemUserBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class ChatMessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AI = 2
        const val PAYLOAD_TEXT_CHANGE = "payload_text_change"

        private val BOLD_PATTERN = Pattern.compile("\\*\\*(.*?)\\*\\*")
        private val BULLET_REGEX = Regex("(?m)^\\s*\\*\\s+")

        private val DiffCallback = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                // If both have id == 0, use role and temp session identifier
                return if (oldItem.id == 0L && newItem.id == 0L) {
                    oldItem.role == newItem.role && oldItem.sessionId == newItem.sessionId
                } else {
                    oldItem.id == newItem.id
                }
            }

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return oldItem.content == newItem.content && oldItem.timestamp == newItem.timestamp && oldItem.status == newItem.status
            }

            override fun getChangePayload(oldItem: ChatMessage, newItem: ChatMessage): Any? {
                return if (oldItem.content != newItem.content) {
                    PAYLOAD_TEXT_CHANGE
                } else {
                    null
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role == "user") VIEW_TYPE_USER else VIEW_TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val binding = ChatItemUserBinding.inflate(inflater, parent, false)
            UserViewHolder(binding)
        } else {
            val binding = ChatItemAiBinding.inflate(inflater, parent, false)
            AiViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        if (holder is UserViewHolder) {
            holder.bind(message)
        } else if (holder is AiViewHolder) {
            holder.bind(message)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_TEXT_CHANGE)) {
            val message = getItem(position)
            if (holder is AiViewHolder) {
                holder.updateText(message.content)
                return
            } else if (holder is UserViewHolder) {
                holder.updateText(message.content)
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    class UserViewHolder(private val binding: ChatItemUserBinding) : RecyclerView.ViewHolder(binding.root) {
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(message: ChatMessage) {
            binding.tvUserAuthor.text = com.assistant.adi.ui.buddy.BuddyProfile(binding.root.context).userName.ifBlank { "Kamu" }
            binding.tvUserMessage.text = message.content
            binding.tvUserTime.text = timeFormat.format(Date(message.timestamp)) + when (message.status) { "cancelled" -> " · Dibatalkan"; "failed" -> " · Gagal"; "interrupted" -> " · Terputus"; "pending" -> " · Menunggu"; else -> "" }
            binding.root.setOnLongClickListener {
                val cm = it.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Pesan", message.content))
                android.widget.Toast.makeText(it.context, "Pesan disalin", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }

        fun updateText(newText: String) {
            binding.tvUserMessage.text = newText
        }
    }

    class AiViewHolder(private val binding: ChatItemAiBinding) : RecyclerView.ViewHolder(binding.root) {
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(message: ChatMessage) {
            binding.tvAiAuthor.text = com.assistant.adi.ui.buddy.BuddyProfile(binding.root.context).buddyName.ifBlank { "Buddy" }
            binding.tvAiMessage.text = parseMarkdown(message.content)
            binding.tvAiTime.text = timeFormat.format(Date(message.timestamp))
            binding.root.setOnLongClickListener {
                val cm = it.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Jawaban", message.content))
                android.widget.Toast.makeText(it.context, "Jawaban disalin", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }

        fun updateText(newText: String) {
            binding.tvAiMessage.text = parseMarkdown(newText)
        }

        private fun parseMarkdown(text: String): CharSequence {
            if (text.isEmpty()) return ""
            return try {
                // Replace bullets
                val formattedText = text.replace(BULLET_REGEX, "• ")
                val builder = SpannableStringBuilder(formattedText)
                val matcher = BOLD_PATTERN.matcher(builder)

                while (matcher.find()) {
                    val start = matcher.start()
                    val end = matcher.end()
                    val content = matcher.group(1) ?: ""

                    builder.replace(start, end, content)
                    builder.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        start + content.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    // Adjust matcher to updated string length
                    matcher.reset(builder)
                    val nextStart = start + content.length
                    if (nextStart < builder.length) {
                        matcher.region(nextStart, builder.length)
                    } else {
                        break
                    }
                }
                builder
            } catch (e: Exception) {
                text
            }
        }
    }
}

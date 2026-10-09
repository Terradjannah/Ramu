package com.assistant.adi.ui.ai

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.assistant.adi.data.ChatMessage
import com.assistant.adi.ui.ai.adapter.ChatMessageAdapter

/** Owns viewport position; message and session state remain with the Fragment and ViewModel. */
class ChatScrollController(
    private val list: RecyclerView,
    private val onFollowChanged: (Boolean) -> Unit
) {
    data class Anchor(val session: String, val messageId: Long, val role: String, val top: Int, val index: Int, val following: Boolean)

    private val manager get() = list.layoutManager as LinearLayoutManager
    private val threshold = (48 * list.resources.displayMetrics.density).toInt()
    private var session = ""
    private var following = true
    private var dragging = false
    private var applying = false
    private var generation = 0
    private var disposed = false
    private var pending: Runnable? = null
    private var restored: Anchor? = null
    private var readerAnchor: Anchor? = null
    private var lastMessages: List<ChatMessage> = emptyList()
    private val anchors = mutableMapOf<String, Anchor>()

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            dragging = newState == RecyclerView.SCROLL_STATE_DRAGGING
            if (newState == RecyclerView.SCROLL_STATE_IDLE && !applying) refreshFollow()
        }

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (applying) return
            if (dragging && dy < 0) setFollowing(false)
            else if (dragging) refreshFollow()
            if (dragging && !following) readerAnchor = snapshot()
        }
    }

    init { list.addOnScrollListener(scrollListener) }

    fun restore(anchor: Anchor?) { restored = anchor }

    fun snapshot(): Anchor? {
        val first = manager.findFirstVisibleItemPosition()
        val message = (list.adapter as? ChatMessageAdapter)?.currentList?.getOrNull(first) ?: return null
        val top = manager.findViewByPosition(first)?.top ?: list.paddingTop
        return Anchor(session, message.id, message.role, top, first, following)
    }

    fun submit(sessionId: String, messages: List<ChatMessage>, submitList: (List<ChatMessage>, () -> Unit) -> Unit) {
        val oldSession = session
        val anchor = snapshot()
        if (oldSession != sessionId && oldSession.isNotBlank() && anchor != null) anchors[oldSession] = anchor
        val chosen = restored?.takeIf { it.session == sessionId }
            ?: if (oldSession == sessionId) anchor else anchors[sessionId]
        restored = null
        val shouldFollow = if (oldSession != sessionId) chosen?.following ?: true else chosen?.following ?: following
        session = sessionId
        lastMessages = messages
        setFollowing(shouldFollow)
        readerAnchor = if (shouldFollow) null else chosen
        val token = ++generation
        cancelPending()
        submitList(messages) {
            if (disposed || token != generation) return@submitList
            schedule(token) {
                if (following) alignBottom()
                else if (chosen != null) restorePosition(chosen, messages)
            }
        }
    }

    fun onViewportChanged() {
        val token = generation
        cancelPending()
        schedule(token) {
            if (following) alignBottom()
            else readerAnchor?.takeIf { it.session == session }?.let { restorePosition(it, lastMessages) }
        }
    }

    fun followLatest() {
        setFollowing(true)
        val token = generation
        cancelPending()
        schedule(token) { alignBottom() }
    }

    private fun restorePosition(anchor: Anchor, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val index = messages.indexOfFirst { it.id == anchor.messageId && it.role == anchor.role && it.id != 0L }
            .takeIf { it >= 0 } ?: anchor.index.coerceIn(0, messages.lastIndex)
        applying = true
        manager.scrollToPositionWithOffset(index, anchor.top - list.paddingTop)
        list.post {
            applying = false
            if (!disposed && list.isAttachedToWindow) {
                readerAnchor = snapshot()
                refreshFollowIndicator()
            }
        }
    }

    private fun alignBottom() {
        if (lastMessages.isEmpty() || list.height == 0) return
        val last = lastMessages.lastIndex
        applying = true
        if (manager.findLastVisibleItemPosition() != last) manager.scrollToPositionWithOffset(last, 0)
        list.post {
            if (disposed || !list.isAttachedToWindow || !following) { applying = false; return@post }
            val bottom = manager.findViewByPosition(last)?.bottom
            if (bottom != null) list.scrollBy(0, bottom - (list.height - list.paddingBottom))
            applying = false
            refreshFollowIndicator()
        }
    }

    private fun refreshFollow() { setFollowing(isAtBottom()) }

    private fun isAtBottom(): Boolean {
        if (lastMessages.isEmpty()) return true
        val last = lastMessages.lastIndex
        if (manager.findLastVisibleItemPosition() != last) return false
        val bottom = manager.findViewByPosition(last)?.bottom ?: return false
        return bottom <= list.height - list.paddingBottom + threshold
    }

    private fun refreshFollowIndicator() { onFollowChanged(following || isAtBottom()) }

    private fun setFollowing(value: Boolean) {
        following = value
        if (value) readerAnchor = null
        else if (readerAnchor == null) readerAnchor = snapshot()
        refreshFollowIndicator()
    }

    private fun schedule(token: Int, action: () -> Unit) {
        val task = Runnable { if (!disposed && token == generation && list.isAttachedToWindow) action() }
        pending = task
        list.post(task)
    }

    private fun cancelPending() { pending?.let(list::removeCallbacks); pending = null }

    fun dispose() {
        disposed = true
        generation++
        cancelPending()
        list.removeOnScrollListener(scrollListener)
    }
}

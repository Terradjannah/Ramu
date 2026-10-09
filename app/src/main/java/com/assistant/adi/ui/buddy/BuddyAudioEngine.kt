package com.assistant.adi.ui.buddy

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.assistant.adi.R
import com.assistant.adi.data.PrefsManager

/**
 * Low-latency character voices for the playground. The tiny clips are decoded
 * once into a [SoundPool] at construction, so a pet or a treat reacts without
 * touching disk on the UI thread.
 */
class BuddyAudioEngine(context: Context, private val alwaysOn: Boolean = false) {
    private val appContext = context.applicationContext
    private val prefs = PrefsManager(appContext)
    private val pool: SoundPool
    private val soundIds = HashMap<Int, Int>()
    private val decoded = HashSet<Int>()
    private val homeReactionCues = BuddyHomeReactionCueController(SystemClock::elapsedRealtime)
    private var lastBounceAtMillis = Long.MIN_VALUE

    init {
        pool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                decoded += sampleId
                homeReactionCues.onClipLoaded()
            }
        }
        SOUND_RES.forEach { soundIds[it] = pool.load(appContext, it, 1) }
    }

    /** A stroke gets the cat's meow, the panda's squeak or the duck's quack. */
    fun playPet(characterId: String) = play(voiceRes(characterId, pet = true))

    /** A treat gets the cat's purr, or the character's own excited call. */
    fun playFeed(characterId: String) = play(voiceRes(characterId, pet = false))

    fun playEating() = play(R.raw.buddy_eating)

    fun playDrinking() = play(R.raw.buddy_drink)

    fun playBounce(normalizedImpact: Float) {
        if (normalizedImpact < MIN_BOUNCE_IMPACT) return
        val now = SystemClock.elapsedRealtime()
        if (lastBounceAtMillis != NO_BOUNCE && now - lastBounceAtMillis < BOUNCE_INTERVAL_MILLIS) return
        lastBounceAtMillis = now
        val impact = normalizedImpact.coerceIn(0f, 1f)
        play(R.raw.buddy_squeak, VOLUME * (.55f + impact * .45f), .9f + impact * .2f)
    }

    fun playHomeDelight(characterId: String, expressionIsCurrent: () -> Boolean) =
        requestHomeReaction(characterId, HomeReactionCue.DELIGHT, RATE_NORMAL, expressionIsCurrent)

    fun playHomeLaugh(characterId: String, expressionIsCurrent: () -> Boolean) =
        requestHomeReaction(characterId, HomeReactionCue.LAUGH, RATE_NORMAL, expressionIsCurrent)

    fun playHomeAnnoyed(characterId: String, expressionIsCurrent: () -> Boolean) =
        requestHomeReaction(characterId, HomeReactionCue.ANNOYED, RATE_ANNOYED, expressionIsCurrent)

    fun playSpicyCall(characterId: String) = requestHomeReaction(characterId, HomeReactionCue.LAUGH, RATE_NORMAL) { true }

    private fun requestHomeReaction(
        characterId: String,
        cue: HomeReactionCue,
        rate: Float,
        expressionIsCurrent: () -> Boolean
    ) {
        val res = voiceRes(characterId, pet = true)
        homeReactionCues.request(cue, expressionIsCurrent) { tryPlayHomeReaction(res, rate) }
    }

    private fun tryPlayHomeReaction(res: Int, rate: Float): CuePlayResult {
        if (muted) return CuePlayResult.FAILED
        val id = soundIds[res] ?: return CuePlayResult.FAILED
        if (id !in decoded) return CuePlayResult.UNLOADED
        return if (pool.play(id, VOLUME, VOLUME, PRIORITY, 0, rate) != 0) CuePlayResult.STARTED else CuePlayResult.FAILED
    }

    var muted: Boolean
        get() = !alwaysOn && prefs.buddyAudioMuted
        set(value) { prefs.buddyAudioMuted = value }

    private fun play(res: Int, volume: Float = VOLUME, rate: Float = 1f): Boolean {
        if (muted) return false
        val id = soundIds[res] ?: return false
        if (id !in decoded) return false
        return pool.play(id, volume, volume, PRIORITY, 0, rate) != 0
    }

    fun release() {
        pool.setOnLoadCompleteListener(null)
        homeReactionCues.clear()
        pool.release()
    }

    private fun voiceRes(characterId: String, pet: Boolean): Int = when (characterId) {
        "panda" -> R.raw.buddy_squeak
        "duck" -> R.raw.buddy_quack
        else -> if (pet) R.raw.buddy_meow else R.raw.buddy_purr
    }

    private companion object {
        const val MAX_STREAMS = 3
        const val VOLUME = 0.9f
        const val PRIORITY = 1
        const val REACTION_CALL_INTERVAL_MILLIS = 300L
        const val RATE_NORMAL = 1f
        const val RATE_ANNOYED = .78f
        const val BOUNCE_INTERVAL_MILLIS = 100L
        const val NO_BOUNCE = Long.MIN_VALUE
        const val MIN_BOUNCE_IMPACT = .08f
        val SOUND_RES = intArrayOf(
            R.raw.buddy_meow,
            R.raw.buddy_purr,
            R.raw.buddy_squeak,
            R.raw.buddy_quack,
            R.raw.buddy_eating,
            R.raw.buddy_drink
        )
    }
}

internal enum class HomeReactionCue { DELIGHT, LAUGH, ANNOYED }
internal enum class CuePlayResult { STARTED, UNLOADED, FAILED }

/** Holds only the newest audible Home reaction until its SoundPool sample is ready. */
internal class BuddyHomeReactionCueController(private val nowMillis: () -> Long) {
    private var lastLaughStartedAtMillis = Long.MIN_VALUE
    private var pending: PendingReaction? = null

    fun request(cue: HomeReactionCue, isCurrent: () -> Boolean, tryPlay: () -> CuePlayResult) {
        if (!isCurrent() || (cue == HomeReactionCue.LAUGH && isLaughCoolingDown())) return
        when (tryPlay()) {
            CuePlayResult.STARTED -> onStarted(cue)
            CuePlayResult.UNLOADED -> pending = PendingReaction(cue, isCurrent, tryPlay)
            CuePlayResult.FAILED -> Unit
        }
    }

    fun onClipLoaded() {
        val reaction = pending ?: return
        if (!reaction.isCurrent()) {
            pending = null
        } else when (reaction.tryPlay()) {
            CuePlayResult.STARTED -> {
                pending = null
                onStarted(reaction.cue)
            }
            CuePlayResult.FAILED -> pending = null
            CuePlayResult.UNLOADED -> Unit
        }
    }

    fun clear() { pending = null }

    private fun onStarted(cue: HomeReactionCue) {
        if (cue == HomeReactionCue.LAUGH) lastLaughStartedAtMillis = nowMillis()
    }

    private fun isLaughCoolingDown(): Boolean =
        lastLaughStartedAtMillis != Long.MIN_VALUE && nowMillis() - lastLaughStartedAtMillis < LAUGH_INTERVAL_MILLIS

    private data class PendingReaction(
        val cue: HomeReactionCue,
        val isCurrent: () -> Boolean,
        val tryPlay: () -> CuePlayResult
    )

    private companion object {
        const val LAUGH_INTERVAL_MILLIS = 300L
    }
}

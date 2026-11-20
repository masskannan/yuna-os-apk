package com.puyal.os.voice

import java.util.concurrent.atomic.AtomicReference

object MicSessionManager {
    enum class MicOwner(val priority: Int) {
        NONE(0),
        BG_VAD(1),
        FG_WHISPER(2),
        FG_LIVE_TRANSCRIPT(2),
        EXTERNAL_PAUSED(3)
    }

    private val currentOwner = AtomicReference(MicOwner.NONE)
    private val activeBlockers = mutableSetOf<String>()
    @Volatile private var preemptCallback: (() -> Unit)? = null

    @Synchronized
    fun acquire(owner: MicOwner, onPreempt: (() -> Unit)? = null): Boolean {
        val cur = currentOwner.get()
        if (activeBlockers.isNotEmpty() && owner != MicOwner.EXTERNAL_PAUSED) return false
        if (cur.priority > owner.priority) return false
        if (cur != MicOwner.NONE && cur != owner) {
            try { preemptCallback?.invoke() } catch (_: Exception) {}
        }
        currentOwner.set(owner)
        preemptCallback = onPreempt
        return true
    }

    @Synchronized
    fun release(owner: MicOwner) {
        if (currentOwner.get() == owner) {
            currentOwner.set(MicOwner.NONE)
            preemptCallback = null
        }
    }

    @Synchronized
    fun addExternalBlocker(tag: String) {
        activeBlockers.add(tag)
        if (currentOwner.get() != MicOwner.EXTERNAL_PAUSED) {
            try { preemptCallback?.invoke() } catch (_: Exception) {}
            currentOwner.set(MicOwner.EXTERNAL_PAUSED)
            preemptCallback = null
        }
    }

    @Synchronized
    fun removeExternalBlocker(tag: String) {
        activeBlockers.remove(tag)
        if (activeBlockers.isEmpty() && currentOwner.get() == MicOwner.EXTERNAL_PAUSED) {
            currentOwner.set(MicOwner.NONE)
        }
    }

    fun getOwner(): MicOwner = currentOwner.get()
}

/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the single most recently used native model [R] loaded, and never frees one while it is in use.
 *
 * Building a recognizer loads the model into native memory (100 MB to 700 MB, about a second), so the
 * last one stays alive between transcriptions and is only rebuilt when a different one is asked for: a
 * different model, or for Whisper and Canary a different language, since that is baked in at build time.
 * It is freed on idle ([idleUnloadMillis]) or on Android memory pressure ([unload]).
 *
 * The one rule that matters is that freeing a recognizer some decode is still using crashes the process
 * in native code. Users are therefore counted *per instance*, not in one total. That used to be one
 * counter, and it could not tell which recognizer its users had: asking for another language replaced
 * the cached recognizer and freed the old one on the spot, even while a decode was still inside it. Two
 * decodes at once is not hypothetical: long-form dictation transcribes segments concurrently, and the
 * hedge against a silent provider (see `hedgedTranscription`) leaves a losing local decode to finish
 * its current pass after the next dictation may already have started.
 *
 * Now a recognizer that is replaced while in use is retired instead: it is no longer handed out, and it
 * is freed when its last user returns it.
 *
 * [onResident] is told when a model becomes ready in RAM and when it leaves, by the label [acquire] was
 * given, so the Smartbar can show which model is loaded (upstream's `residentModels`).
 *
 * All state is guarded by this object's monitor. [acquire] builds under it too, as before: two callers
 * asking for the same model at once must not both pay to load it.
 */
internal class SharedModelCache<R : Any>(
    threadName: String,
    private val release: (R) -> Unit,
    private val onResident: (label: String, loaded: Boolean) -> Unit = { _, _ -> },
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, threadName).apply { isDaemon = true }
    },
) {
    private var key: String? = null
    private var current: R? = null

    /** Users per instance: the current one and any retired ones still in use. Identity, not equality. */
    private val users = IdentityHashMap<R, Int>()

    /** The label each live instance was built under, for [onResident]. */
    private val labels = IdentityHashMap<R, String>()

    /** An [unload] arrived while [current] was in use; it is freed when its last user returns. */
    private var releasePending = false

    /** Idle-unload timeout (ms); 0 disables the timer (freed only via [unload] on memory pressure). */
    @Volatile
    var idleUnloadMillis: Long = 0L

    private var idleFuture: ScheduledFuture<*>? = null

    /**
     * Returns the cached recognizer for [cacheKey], building it with [build] if the cached one is for
     * another key or there is none. [label] names the model for [onResident]. Every call must be paired with exactly one [endUse] of the returned
     * instance, in a `finally`.
     */
    @Synchronized
    fun acquire(cacheKey: String, label: String = cacheKey, build: () -> R): R {
        // In use again: cancel any pending idle unload.
        cancelIdle()
        val existing = current
        val rec = if (existing != null && cacheKey == key) {
            existing
        } else {
            current = null
            key = null
            existing?.let { retire(it) }
            build().also {
                current = it
                key = cacheKey
                labels[it] = label
                onResident(label, true)
            }
        }
        // A fresh use cancels any pending unload for the now-current model and marks it in use.
        releasePending = false
        users[rec] = (users[rec] ?: 0) + 1
        return rec
    }

    /**
     * Returns [recognizer], borrowed via [acquire]. A retired one is freed once its last user is back;
     * the current one is freed if an unload was requested while it was in use, otherwise its idle timer
     * is (re)armed.
     */
    @Synchronized
    fun endUse(recognizer: R) {
        val count = users[recognizer] ?: return
        if (count > 1) {
            users[recognizer] = count - 1
            return
        }
        users.remove(recognizer)
        if (recognizer !== current) {
            // Retired while in use, and this was its last user.
            free(recognizer)
            return
        }
        if (releasePending) freeNow() else armIdle()
    }

    /** Requests freeing the current model. Deferred until its last user returns if one is in flight. */
    @Synchronized
    fun unload() {
        val rec = current ?: return
        if ((users[rec] ?: 0) > 0) {
            releasePending = true
            return
        }
        freeNow()
    }

    /** How many users [recognizer] has right now. For tests. */
    @Synchronized
    internal fun usersOf(recognizer: R): Int = users[recognizer] ?: 0

    /** The recognizer a new [acquire] for the same key would get, or null. For tests. */
    @Synchronized
    internal fun cached(): R? = current

    private fun retire(recognizer: R) {
        // Nobody is using it: free it now. Otherwise the last [endUse] does, since it is no longer current.
        if ((users[recognizer] ?: 0) == 0) free(recognizer)
    }

    private fun freeNow() {
        cancelIdle()
        val rec = current
        current = null
        key = null
        releasePending = false
        rec?.let { free(it) }
    }

    private fun free(recognizer: R) {
        release(recognizer)
        val label = labels.remove(recognizer) ?: return
        // A retired instance of the model that is current again (another language of it) leaves the model
        // resident: only the instance went.
        if (current?.let { labels[it] } != label) onResident(label, false)
    }

    private fun cancelIdle() {
        idleFuture?.cancel(false)
        idleFuture = null
    }

    private fun armIdle() {
        cancelIdle()
        val delay = idleUnloadMillis
        if (delay > 0 && current != null) {
            idleFuture = scheduler.schedule({ unload() }, delay, TimeUnit.MILLISECONDS)
        }
    }
}

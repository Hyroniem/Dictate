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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rule [SharedModelCache] exists for: a native recognizer is never freed while a decode still uses
 * it. A fake stands in for the recognizer; "released" is what would have crashed the process.
 */
class SharedModelCacheTest {

    private class FakeRecognizer(val name: String) {
        var released = false
        override fun toString() = name
    }

    private val built = mutableListOf<FakeRecognizer>()

    private fun cache() = SharedModelCache<FakeRecognizer>(
        threadName = "test-idle-unload",
        release = { check(!it.released) { "$it released twice" }; it.released = true },
    )

    private fun SharedModelCache<FakeRecognizer>.get(key: String) =
        acquire(key) { FakeRecognizer(key).also { built += it } }

    @Test
    fun theSameKeyReusesTheLoadedModel() {
        val cache = cache()
        val first = cache.get("whisper|nl")
        cache.endUse(first)
        val second = cache.get("whisper|nl")
        assertSame(first, second)
        assertEquals(1, built.size)
        assertFalse(first.released)
    }

    @Test
    fun anIdleModelIsFreedAtOnceWhenAnotherIsAskedFor() {
        val cache = cache()
        val dutch = cache.get("whisper|nl")
        cache.endUse(dutch)
        val english = cache.get("whisper|en")
        assertTrue(dutch.released)
        assertFalse(english.released)
    }

    @Test
    fun aModelReplacedMidDecodeIsFreedOnlyWhenThatDecodeEnds() {
        // The crash: a losing hedge decode still inside the Dutch model while the next dictation, now in
        // English, asks for a rebuild. The old cache freed the Dutch model right there.
        val cache = cache()
        val dutch = cache.get("whisper|nl")
        val english = cache.get("whisper|en")
        assertNotSame(dutch, english)
        assertFalse(dutch.released, "freed while a decode was still using it")
        assertSame(english, cache.cached())

        cache.endUse(dutch)
        assertTrue(dutch.released)
        assertFalse(english.released)

        cache.endUse(english)
        assertFalse(english.released, "the current model stays cached when idle")
    }

    @Test
    fun aRetiredModelWaitsForEveryOneOfItsUsers() {
        val cache = cache()
        val a = cache.get("whisper|nl")
        cache.get("whisper|nl") // a second segment decoding at the same time
        val b = cache.get("whisper|en")
        cache.endUse(a)
        assertFalse(a.released)
        cache.endUse(a)
        assertTrue(a.released)
        cache.endUse(b)
    }

    @Test
    fun anUnloadDuringADecodeWaitsForIt() {
        val cache = cache()
        val rec = cache.get("whisper|nl")
        cache.unload()
        assertFalse(rec.released)
        cache.endUse(rec)
        assertTrue(rec.released)
        assertNull(cache.cached())
    }

    @Test
    fun anUnloadOfAnIdleModelFreesItAtOnce() {
        val cache = cache()
        val rec = cache.get("whisper|nl")
        cache.endUse(rec)
        cache.unload()
        assertTrue(rec.released)
        assertNull(cache.cached())
    }

    @Test
    fun aNewUseCancelsAPendingUnload() {
        val cache = cache()
        val rec = cache.get("whisper|nl")
        cache.unload()
        val again = cache.get("whisper|nl")
        assertSame(rec, again)
        cache.endUse(rec)
        cache.endUse(again)
        assertFalse(rec.released)
    }

    @Test
    fun anUnloadLeavesARetiredModelToItsOwnUser() {
        val cache = cache()
        val dutch = cache.get("whisper|nl")
        val english = cache.get("whisper|en")
        cache.endUse(english)
        cache.unload() // memory pressure: frees the idle current model only
        assertTrue(english.released)
        assertFalse(dutch.released)
        cache.endUse(dutch)
        assertTrue(dutch.released)
    }

    @Test
    fun aFailedBuildStillReleasesTheModelItReplaced() {
        val cache = cache()
        val dutch = cache.get("whisper|nl")
        runCatching { cache.acquire("broken") { error("model files missing") } }
        assertNull(cache.cached())
        assertFalse(dutch.released)
        cache.endUse(dutch)
        assertTrue(dutch.released)
    }

    @Test
    fun theIdleTimerFreesAnUnusedModel() {
        val freed = CountDownLatch(1)
        val cache = SharedModelCache<FakeRecognizer>(
            threadName = "test-idle-unload",
            release = { it.released = true; freed.countDown() },
        )
        cache.idleUnloadMillis = 10
        val rec = cache.acquire("whisper|nl") { FakeRecognizer("nl") }
        cache.endUse(rec)
        assertTrue(freed.await(5, TimeUnit.SECONDS))
        assertTrue(rec.released)
        assertNull(cache.cached())
    }

    @Test
    fun residencyFollowsTheModelNotTheInstance() {
        // Upstream's residentModels (the Smartbar's "loaded" icon) is told by model, while the cache swaps
        // instances: another language of the same model must not read as unloaded when the old one goes.
        val resident = mutableSetOf<String>()
        val cache = SharedModelCache<FakeRecognizer>(
            threadName = "test-idle-unload",
            release = { it.released = true },
            onResident = { label, loaded -> if (loaded) resident += label else resident -= label },
        )
        val dutch = cache.acquire("whisper|nl", label = "whisper") { FakeRecognizer("nl") }
        assertEquals(setOf("whisper"), resident)
        val english = cache.acquire("whisper|en", label = "whisper") { FakeRecognizer("en") }
        cache.endUse(dutch)
        assertTrue(dutch.released)
        assertEquals(setOf("whisper"), resident, "still loaded: only the Dutch instance went")
        cache.endUse(english)
        val parakeet = cache.acquire("parakeet|", label = "parakeet") { FakeRecognizer("parakeet") }
        assertTrue(english.released)
        assertEquals(setOf("parakeet"), resident)
        cache.endUse(parakeet)
        cache.unload()
        assertEquals(emptySet(), resident)
    }
}

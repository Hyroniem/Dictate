/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The race between a silent cloud call and the on-device engine (see [hedgedTranscription]). Virtual
 * time throughout: "the server took 120 s" costs nothing to test.
 */
class HedgedTranscriptionTest {

    private val networkOrTimeout: (DictateApiException) -> Boolean = {
        it.kind == DictateApiException.Kind.NETWORK || it.kind == DictateApiException.Kind.TIMEOUT
    }

    private fun timeout() = DictateApiException(DictateApiException.Kind.TIMEOUT, "read timed out")
    private fun network() = DictateApiException(DictateApiException.Kind.NETWORK, "unreachable")
    private fun badKey() = DictateApiException(DictateApiException.Kind.INVALID_API_KEY, "bad key")

    @Test
    fun aFastCloudAnswerNeverWakesTheLocalEngine() = runTest {
        var started = false
        var used = false
        val text = hedgedTranscription(
            hedgeAfterMillis = 10_000,
            primary = { delay(2_000); "cloud" },
            backup = { started = true; "local" },
            canFallBack = networkOrTimeout,
            onBackupStarted = { started = true },
            onBackupUsed = { used = true },
        )
        assertEquals("cloud", text)
        assertFalse(started)
        assertFalse(used)
    }

    @Test
    fun aBusyServerIsOvertakenByTheLocalEngine() = runTest {
        // The reported case: the server accepts the upload and then says nothing for two minutes.
        var cloudCancelled = false
        var started = false
        var used = false
        val text = hedgedTranscription(
            hedgeAfterMillis = 10_000,
            primary = {
                try {
                    delay(120_000)
                    throw timeout()
                } catch (c: CancellationException) {
                    cloudCancelled = true
                    throw c
                }
            },
            backup = { delay(5_000); "local" },
            canFallBack = networkOrTimeout,
            onBackupStarted = { started = true },
            onBackupUsed = { used = true },
        )
        runCurrent() // let the cancelled cloud call unwind
        assertEquals("local", text)
        assertEquals(15_000, currentTime)
        assertTrue(started)
        assertTrue(used)
        assertTrue(cloudCancelled, "the losing cloud call must be cancelled, not left running")
    }

    @Test
    fun aCloudAnswerThatArrivesDuringTheRaceStillWins() = runTest {
        // A slow but healthy provider: the hedge fires, and the cloud transcript is still the one used.
        var localCancelled = false
        var used = false
        val text = hedgedTranscription(
            hedgeAfterMillis = 10_000,
            primary = { delay(12_000); "cloud" },
            backup = {
                try {
                    delay(30_000)
                    "local"
                } catch (c: CancellationException) {
                    localCancelled = true
                    throw c
                }
            },
            canFallBack = networkOrTimeout,
            onBackupUsed = { used = true },
        )
        runCurrent() // let the cancelled local decode unwind
        assertEquals("cloud", text)
        assertEquals(12_000, currentTime)
        assertFalse(used)
        assertTrue(localCancelled)
    }

    @Test
    fun aCloudFailureDuringTheRaceWaitsForTheLocalEngine() = runTest {
        var used = false
        val text = hedgedTranscription(
            hedgeAfterMillis = 10_000,
            primary = { delay(11_000); throw network() },
            backup = { delay(5_000); "local" },
            canFallBack = networkOrTimeout,
            onBackupUsed = { used = true },
        )
        assertEquals("local", text)
        assertEquals(15_000, currentTime)
        assertTrue(used)
    }

    @Test
    fun aCloudFailureTheLocalEngineMayNotHideIsReportedEvenMidRace() = runTest {
        var used = false
        val error = assertFailsWith<DictateApiException> {
            hedgedTranscription(
                hedgeAfterMillis = 10_000,
                primary = { delay(11_000); throw badKey() },
                backup = { delay(5_000); "local" },
                canFallBack = networkOrTimeout,
                onBackupUsed = { used = true },
            )
        }
        assertEquals(DictateApiException.Kind.INVALID_API_KEY, error.kind)
        assertFalse(used)
    }

    @Test
    fun aLocalFailureDuringTheRaceLeavesTheCloudCallRunning() = runTest {
        var used = false
        val text = hedgedTranscription(
            hedgeAfterMillis = 10_000,
            primary = { delay(40_000); "cloud" },
            backup = { delay(1_000); throw DictateApiException(DictateApiException.Kind.UNKNOWN, "decode failed") },
            canFallBack = networkOrTimeout,
            onBackupUsed = { used = true },
        )
        assertEquals("cloud", text)
        assertEquals(40_000, currentTime)
        assertFalse(used)
    }

    @Test
    fun whenBothFailTheLocalErrorIsReportedLikeTheSequentialFallback() = runTest {
        var used = false
        val error = assertFailsWith<DictateApiException> {
            hedgedTranscription(
                hedgeAfterMillis = 10_000,
                primary = { delay(120_000); throw timeout() },
                backup = { delay(1_000); throw DictateApiException(DictateApiException.Kind.UNKNOWN, "decode failed") },
                canFallBack = networkOrTimeout,
                onBackupUsed = { used = true },
            )
        }
        assertEquals(DictateApiException.Kind.UNKNOWN, error.kind)
        assertTrue(used)
    }

    @Test
    fun anEarlyCloudFailureFallsBackSequentiallyAsBefore() = runTest {
        var started = false
        var used = false
        val text = hedgedTranscription(
            hedgeAfterMillis = 10_000,
            primary = { delay(3_000); throw network() },
            backup = { delay(4_000); "local" },
            canFallBack = networkOrTimeout,
            onBackupStarted = { started = true },
            onBackupUsed = { used = true },
        )
        assertEquals("local", text)
        assertEquals(7_000, currentTime)
        assertFalse(started, "no race: the cloud call had already failed")
        assertTrue(used)
    }

    @Test
    fun anEarlyCloudFailureTheLocalEngineMayNotHideIsThrown() = runTest {
        val error = assertFailsWith<DictateApiException> {
            hedgedTranscription(
                hedgeAfterMillis = 10_000,
                primary = { throw badKey() },
                backup = { "local" },
                canFallBack = networkOrTimeout,
            )
        }
        assertEquals(DictateApiException.Kind.INVALID_API_KEY, error.kind)
    }

    @Test
    fun withoutABackupItIsJustTheCloudCall() = runTest {
        val error = assertFailsWith<DictateApiException> {
            hedgedTranscription<String>(
                hedgeAfterMillis = 10_000,
                primary = { delay(120_000); throw timeout() },
                backup = null,
                canFallBack = networkOrTimeout,
            )
        }
        assertEquals(DictateApiException.Kind.TIMEOUT, error.kind)
        assertEquals(120_000, currentTime)
    }

    @Test
    fun stoppingTheDictationCancelsBothSides() = runTest {
        var cloudCancelled = false
        var localCancelled = false
        val job = launch {
            hedgedTranscription<String>(
                hedgeAfterMillis = 10_000,
                primary = {
                    try { awaitCancellation() } finally { cloudCancelled = true }
                },
                backup = {
                    try { awaitCancellation() } finally { localCancelled = true }
                },
                canFallBack = networkOrTimeout,
            )
        }
        delay(20_000)
        job.cancel()
        job.join()
        runCurrent()
        assertTrue(cloudCancelled)
        assertTrue(localCancelled)
    }

    @Test
    fun theHedgeGrowsWithTheRecordingAndNeedsALength() {
        assertNull(hedgeAfterMillis(null))
        assertNull(hedgeAfterMillis(0.0))
        assertEquals(HEDGE_BASE_MS + 2_000, hedgeAfterMillis(10.0))
        assertEquals(HEDGE_BASE_MS + 12_000, hedgeAfterMillis(60.0))
    }

    @Test
    fun wavLengthIsReadFromTheHeader() {
        val file = File.createTempFile("hedge", ".wav")
        try {
            val byteRate = 32_000 // 16 kHz, mono, 16-bit
            val dataBytes = byteRate * 3 // three seconds
            val header = ByteArray(44)
            "RIFF".toByteArray().copyInto(header, 0)
            "WAVE".toByteArray().copyInto(header, 8)
            for (i in 0 until 4) header[28 + i] = (byteRate shr (8 * i)).toByte()
            file.writeBytes(header + ByteArray(dataBytes))
            assertEquals(3.0, wavSeconds(file))

            file.writeBytes(ByteArray(1_000)) // not a WAV
            assertNull(wavSeconds(file))
        } finally {
            file.delete()
        }
    }
}

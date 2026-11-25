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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile

/**
 * Runs a cloud transcription with the on-device engine as a hedge behind it.
 *
 * [FastFallback] covers a provider that cannot be *reached*. It cannot cover one that answers the door
 * and then says nothing: a self-hosted Whisper server busy with someone else's job accepts the
 * connection, takes the upload and queues it. From the phone that is a silent socket, and the only
 * thing that ends it is the full request timeout, two minutes by default, before the fallback runs.
 *
 * Cutting that request short would be wrong, because a silent socket is also what a model thinking
 * about a long recording looks like, and walking away from it trades a good cloud transcript for a
 * worse local one. So this does not cut anything. When the cloud call is still silent after
 * [hedgeAfterMillis], the on-device engine starts *alongside* it, and whichever finishes first wins:
 *
 *  - The cloud answers first: its transcript is used and the local decode is cancelled.
 *  - The local engine finishes first: its transcript is used and the cloud call is cancelled.
 *  - One of them fails: the other one is awaited.
 *
 * Which failures may hand over to the local engine is [canFallBack]'s to say, the same rule the
 * sequential fallback has always used: a connectivity error or a timeout may, a rejected API key may
 * not, because quietly papering over that would hide a problem the user has to fix.
 *
 * A cloud failure that arrives before the hedge fires behaves exactly as before: the local engine
 * then runs on its own, one after the other.
 *
 * Both calls run detached from the caller's job on purpose. A local decode is a blocking native pass
 * that only notices cancellation between passes, and a structured child would make the winner wait for
 * the loser to get there. Detached, the loser is cancelled and simply left to wind down; the caller's
 * own cancellation (the stop button) still reaches both through the `finally` below.
 *
 * @param backup the on-device transcription, or null when none is available, in which case this is
 *   just [primary].
 * @param onBackupStarted called once, when the local engine starts racing the cloud call.
 * @param onBackupUsed called right before the local engine's outcome, its result or its error, becomes
 *   this call's outcome.
 */
internal suspend fun <T> hedgedTranscription(
    hedgeAfterMillis: Long,
    primary: suspend () -> T,
    backup: (suspend () -> T)?,
    canFallBack: (DictateApiException) -> Boolean,
    onBackupStarted: () -> Unit = {},
    onBackupUsed: () -> Unit = {},
): T {
    if (backup == null) return primary()

    fun fallsBack(error: Throwable): Boolean = error is DictateApiException && canFallBack(error)

    val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
    try {
        val cloud: Deferred<Result<T>> = scope.async { runCatching { primary() } }

        // Phase one: the cloud call alone, for as long as it is given before the hedge.
        val early = withTimeoutOrNull(hedgeAfterMillis.coerceAtLeast(0L)) { cloud.await() }
        if (early != null) {
            early.onSuccess { return it }
            val error = early.exceptionOrNull()!!
            if (!fallsBack(error)) throw error
            onBackupUsed()
            return backup()
        }

        // Phase two: still silent. Start the local engine and take whichever finishes first.
        onBackupStarted()
        val local: Deferred<Result<T>> = scope.async { runCatching { backup() } }
        val cloudFinishedFirst = select<Boolean> {
            cloud.onAwait { true }
            local.onAwait { false }
        }

        if (cloudFinishedFirst) {
            val cloudResult = cloud.await()
            cloudResult.onSuccess { return it }
            val error = cloudResult.exceptionOrNull()!!
            if (!fallsBack(error)) throw error
            val localResult = local.await()
            onBackupUsed()
            return localResult.getOrThrow()
        }

        val localResult = local.await()
        localResult.onSuccess {
            onBackupUsed()
            return it
        }
        // The local engine gave up first; the cloud call is the only one left.
        val cloudResult = cloud.await()
        cloudResult.onSuccess { return it }
        val cloudError = cloudResult.exceptionOrNull()!!
        if (!fallsBack(cloudError)) throw cloudError
        // Both failed, and the cloud's failure was one the local engine was there to absorb. Report the
        // local engine's error, as the sequential fallback would have: it is the one that ran last.
        onBackupUsed()
        throw localResult.exceptionOrNull()!!
    } finally {
        scope.cancel()
    }
}

/**
 * How long a cloud transcription may stay silent before the on-device engine starts racing it.
 *
 * A fixed floor, so a short dictation to a healthy provider never wakes the local engine, plus a share
 * of the recording's length, so a long one gets the extra time a provider honestly needs for it. The
 * hedge does not cancel the cloud call, so firing a little early only costs some battery; firing late
 * is what the user notices.
 *
 * Null [audioSeconds] (the length is not known) means no hedge at all: the fallback then runs only
 * after the cloud call has failed, as it always did. A picked file can be an hour long, and starting an
 * on-device pass over that on a guess is not a cheap mistake.
 */
internal fun hedgeAfterMillis(audioSeconds: Double?): Long? {
    if (audioSeconds == null || audioSeconds <= 0.0) return null
    return HEDGE_BASE_MS + (audioSeconds * HEDGE_PER_AUDIO_SECOND_MS).toLong()
}

/** Silence every cloud transcription is allowed before the hedge, however short the recording. */
internal const val HEDGE_BASE_MS = 8_000L

/** Extra silence allowed per second of audio: a fifth of the recording's length. */
internal const val HEDGE_PER_AUDIO_SECOND_MS = 200L

/**
 * Length of a PCM WAV file in seconds, read from its header, or null when [file] is not one.
 *
 * Only the header: the byte rate at offset 28 and the file's size. Enough for [hedgeAfterMillis], which
 * needs a length and not a sample-exact one, and it never decodes anything.
 */
internal fun wavSeconds(file: File): Double? = runCatching {
    RandomAccessFile(file, "r").use { raf ->
        if (raf.length() <= WAV_HEADER_BYTES) return@use null
        val header = ByteArray(WAV_HEADER_BYTES)
        raf.readFully(header)
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") return@use null
        val byteRate = (header[28].toInt() and 0xFF) or
            ((header[29].toInt() and 0xFF) shl 8) or
            ((header[30].toInt() and 0xFF) shl 16) or
            ((header[31].toInt() and 0xFF) shl 24)
        if (byteRate <= 0) return@use null
        (raf.length() - WAV_HEADER_BYTES).toDouble() / byteRate
    }
}.getOrNull()

private const val WAV_HEADER_BYTES = 44

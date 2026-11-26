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

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.getSystemService
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.dictate.provider.NetworkBudget
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import java.util.concurrent.atomic.AtomicReference

/**
 * How quickly a cloud transcription gives up in favour of the on-device engine (issue #104 follow-up).
 *
 * The offline fallback itself already works: when a cloud call fails with a connectivity error, the
 * downloaded model transcribes the recording instead. What it lacks is urgency. The cloud call is
 * allowed four attempts three seconds apart, each with an eight-second connection budget, so a provider
 * that simply is not reachable costs about 41 seconds of spinner before the phone does the thing it
 * could have done immediately.
 *
 * That budget is right when failing means losing the dictation. It is the wrong trade entirely once a
 * model is sitting on the device: there, an unreachable provider costs a hand-off, not a transcript.
 * So when — and only when — the fallback is genuinely armed, this shortens the reaching-out phase and,
 * if the OS already knows there is no working internet, skips it altogether.
 *
 * What it deliberately does not touch is a request that is already under way. See [NetworkBudget.FAST_FAIL].
 * A provider that is reachable but silent, such as a self-hosted server busy with another job, is not
 * cut short either: the on-device engine races it instead. See [hedgedTranscription].
 */
object FastFallback {

    /**
     * Whether the on-device engine is standing by behind this dictation: [fallbackId] is the fallback that
     * applies (`DictateController.fallbackIdFor`), and it is [ProviderRegistry.LOCAL]'s.
     *
     * Since v6.5 the user picks one transcription fallback (#455) instead of switching the offline one on,
     * and that choice only applies while the active provider is a cloud one and, for the on-device engine,
     * a local model is downloaded. Those are the conditions this used to check one by one. Another cloud
     * provider as the fallback does not arm this: upstream's own short connect timeout covers that case,
     * and failing fast there would only hand the recording to a second network call.
     */
    fun armed(fallbackId: String?): Boolean = fallbackId == ProviderRegistry.LOCAL.id

    /** The reaching-out budget to give a cloud call, given whether the on-device engine [armed] behind it. */
    fun budget(armed: Boolean): NetworkBudget =
        if (armed) NetworkBudget.FAST_FAIL else NetworkBudget.DEFAULT

    /**
     * Throws a connectivity error — the one the caller's existing fallback path already knows how to
     * handle — when there is no point making the call: the OS says there is no usable internet, or this
     * provider was unreachable moments ago.
     *
     * Airplane mode, a captive portal that was never signed into, mobile data switched off: in every one
     * of these the platform already knows the answer, and asking a socket to find it out again is three
     * seconds spent confirming what [ConnectivityManager] would have said instantly.
     *
     * Only ever called with [armed] true, so a user without a downloaded model still gets a real attempt
     * and the real error — including the "you could transcribe on-device" hint that error carries.
     *
     * Best-effort in one direction only: if the capabilities cannot be read, this stays silent and lets
     * the call proceed. It never reports offline on a doubt, because a false offline would silently
     * downgrade a perfectly good cloud transcription.
     */
    fun requireReachable(context: Context, preset: ProviderPreset) {
        if (recentlyUnreachable(preset.id)) {
            throw DictateApiException(
                DictateApiException.Kind.NETWORK,
                "Provider was unreachable moments ago; transcribing on-device instead.",
            )
        }
        val cm = context.getSystemService<ConnectivityManager>() ?: return
        val active = runCatching { cm.activeNetwork }.getOrElse { return }
        val offline = if (active == null) {
            // No active network at all — airplane mode, everything switched off. Nothing to try.
            true
        } else {
            // A network exists but has not been validated (captive portal, connected-but-no-internet), or
            // carries no internet capability. A null here is a race, not an answer: let the call proceed.
            val capabilities = runCatching { cm.getNetworkCapabilities(active) }.getOrNull() ?: return
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        if (offline) {
            throw DictateApiException(
                DictateApiException.Kind.NETWORK,
                "No validated network connection; transcribing on-device instead.",
            )
        }
    }

    /**
     * Records that [providerId] could not be reached, so the next dictation does not pay to find out again.
     *
     * This covers the one case the shortened connect budget cannot: a server that completes the TCP
     * handshake and then says nothing. From outside there is no telling that apart from a model thinking
     * hard about a long recording, so the first dictation has to wait out the full request timeout — but
     * the second does not have to repeat it. Called where the hand-off actually happens, so it only ever
     * remembers a failure the on-device engine was able to absorb.
     *
     * That includes the local engine winning a race against a provider that stayed silent (see
     * [hedgedTranscription]): a server that was too busy to answer one dictation is probably still busy
     * for the next, and skipping it for a moment saves the wait before the race even starts.
     */
    fun noteUnreachable(providerId: String) {
        lastUnreachable.set(providerId to SystemClock.elapsedRealtime())
    }

    /**
     * Whether [providerId] failed recently enough to skip.
     *
     * Deliberately short, and deliberately without a matching "it worked" signal to clear it. The window
     * expiring *is* the recovery: at worst one dictation goes on-device while the provider is already back,
     * and the one after that finds it. A breaker that stayed open until something proved otherwise would
     * need that proof to come from a call it is refusing to make.
     */
    private fun recentlyUnreachable(providerId: String): Boolean {
        val (id, at) = lastUnreachable.get() ?: return false
        if (id != providerId) return false
        if (SystemClock.elapsedRealtime() - at > UNREACHABLE_COOLDOWN_MS) {
            lastUnreachable.set(null)
            return false
        }
        return true
    }

    /** How long a provider stays skipped after it could not be reached. */
    private const val UNREACHABLE_COOLDOWN_MS = 90_000L

    /** The provider that last failed to answer, and when — process-lifetime only, by design. */
    private val lastUnreachable = AtomicReference<Pair<String, Long>?>(null)
}

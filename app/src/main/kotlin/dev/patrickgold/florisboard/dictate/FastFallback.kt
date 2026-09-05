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
import androidx.core.content.getSystemService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.NetworkBudget
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionApi

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
 */
object FastFallback {

    private val prefs by FlorisPreferenceStore

    /**
     * Whether the on-device engine can take over for [preset] right now: the fallback is switched on, the
     * active provider is a cloud one, and a local model is actually downloaded.
     *
     * The same three conditions gate the existing fallback in `DictateController.localFallbackProvider`,
     * which is what decides the hand-off after the fact. This answers them before the call, because a
     * budget has to be chosen before there is an error to inspect.
     */
    fun armed(context: Context, preset: ProviderPreset): Boolean {
        if (!prefs.dictate.localFallbackEnabled.get()) return false
        if (preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE) return false
        val account = prefs.dictate.providerAccounts.get().getOrEmpty(ProviderRegistry.LOCAL.id)
        // Same resolution order as DictateController.transcriptionModelFor for a local preset: the chosen
        // (or preset-default) model if it is downloaded, otherwise the realtime one if that is.
        val chosen = account.transcriptionModel.takeIf { it.isNotBlank() }
            ?: ProviderRegistry.LOCAL.defaultTranscriptionModel
            ?: ""
        if (chosen.isNotBlank() && LocalModelManager.isInstalled(context, chosen)) return true
        return account.realtimeModel.let { it.isNotBlank() && LocalModelManager.isInstalled(context, it) }
    }

    /** The reaching-out budget to give a cloud call, given whether the on-device engine [armed] behind it. */
    fun budget(armed: Boolean): NetworkBudget =
        if (armed) NetworkBudget.FAST_FAIL else NetworkBudget.DEFAULT

    /**
     * Throws a connectivity error — the one the caller's existing fallback path already knows how to
     * handle — when the OS says there is no usable internet, so the hand-off costs nothing at all.
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
    fun requireOnline(context: Context) {
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
}

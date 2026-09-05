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

/**
 * How long a call is allowed to keep trying to *reach* a provider before it gives up.
 *
 * This is deliberately separate from [ProviderConfig.timeoutSeconds], which budgets a request that is
 * already under way: bytes uploading, a model thinking. That budget has to stay generous — a long
 * dictation on a slow model legitimately takes a minute. The budget here covers the part where nothing
 * is happening yet, and it is the part a caller with an offline fallback ready wants to cut short.
 *
 * The default reproduces the behaviour these numbers were hard-coded to before they were made per-call.
 */
data class NetworkBudget(
    /** Extra attempts after the first. 0 means a transport error is final. */
    val maxRetries: Int = 3,
    /** Pause between attempts. */
    val retryDelayMs: Long = 3_000L,
    /** Per-route budget for establishing the connection (OkHttp `connectTimeout`). */
    val connectTimeoutSeconds: Long = 8L,
) {
    companion object {
        /** The normal budget: worth waiting through a blip, because failing means failing the dictation. */
        val DEFAULT = NetworkBudget()

        /**
         * Give up on the network almost at once (issue #104 follow-up).
         *
         * Only for callers that have a downloaded on-device model standing by, where "unreachable" costs
         * a hand-off rather than a lost dictation. Retrying four times over 41 seconds to reach a provider
         * that is not there, when the answer is already on the phone, is time the user spends staring at
         * a spinner for nothing.
         *
         * Note what this does *not* shorten: once the connection is up and bytes are moving, the full
         * [ProviderConfig.timeoutSeconds] still applies. A provider that accepted the upload and is
         * working on it is not a provider we should walk away from — that would trade a good cloud
         * transcript for a worse local one, which is the opposite of the point.
         */
        val FAST_FAIL = NetworkBudget(
            maxRetries = 0,
            retryDelayMs = 0L,
            connectTimeoutSeconds = 3L,
        )
    }
}

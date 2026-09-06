# Dictate — fast offline fallback fork

A fork of [DevEmperor/DictateKeyboard](https://github.com/DevEmperor/DictateKeyboard) that changes
exactly one thing: how fast a failing cloud transcription gives up in favour of the on-device model.

Everything else is upstream, unmodified, and meant to stay that way. The fork is deliberately shaped
so it can be rebased onto each new upstream release with as little friction as possible.

---

## Why this exists

Upstream already has an offline fallback (issue #104): when a cloud transcription fails on a
connectivity error, the downloaded local model transcribes the recording instead. It works — it just
takes a long time to trigger, because the cloud call first exhausts a retry budget that was set for a
situation where failing means losing the dictation.

That budget lives in `OpenAiCompatibleClient`:

| | |
|---|---|
| `executeForBody(maxRetries = 3)` | 4 attempts |
| `RETRY_DELAY_MS = 3000` | 9 s of pauses |
| `NETWORK_CONNECT_TIMEOUT_SECONDS = 8` | up to 8 s per attempt |

Once a model is sitting on the phone, that trade is wrong: an unreachable provider costs a hand-off,
not a transcript. Waiting 41 seconds to establish that a provider is absent, when the answer is
already on the device, buys nothing.

## What it does

| situation | upstream v6.1.2 | this fork |
|---|---|---|
| airplane mode | ~9 s | immediate |
| provider unreachable | ~41 s | ~3 s |
| provider silent, 1st dictation | up to ~8 min | unchanged |
| provider silent, 2nd dictation | up to ~8 min | immediate |

Three mechanisms, in order of how early they fire:

1. **Pre-flight.** If `ConnectivityManager` reports no validated network, the call is skipped entirely
   and the existing fallback path handles it.
2. **Short reaching-out budget.** `NetworkBudget.FAST_FAIL` — no retries, 3 s connect timeout —
   instead of the default, but only when the fallback is armed.
3. **Circuit breaker.** After a hand-off, that provider is skipped for 90 s, so the next dictation
   does not repeat the discovery.

### What it deliberately does not do

Shorten `ProviderConfig.timeoutSeconds`. Once the connection is up and bytes are moving, the full
budget still applies. A provider that accepted the upload and is working on a long recording is not
one to walk away from — cutting that short would trade a good cloud transcript for a worse local one,
which is the opposite of the point.

That leaves one honest slow case: a server that completes the TCP handshake and then says nothing
still costs the full request timeout on the first dictation, because from the outside that is
indistinguishable from a model thinking hard. The circuit breaker covers the second.

### Prerequisites

None of this changes any behaviour unless **both** are true:

- the offline fallback is switched on in settings (`localFallbackEnabled`, upstream default: **off**), and
- a local model is downloaded.

Without them the code paths are never entered, which is what makes the patch safe to carry.

---

## What changed

| commit | what |
|---|---|
| `a7e19dcb` | pre-flight check + per-call network budget |
| `6435f8a1` | circuit breaker (90 s) |
| `a4ab645d` | GitHub Actions: build, tests, signed APK artifact |
| `0f425a98` | CI: fetch the vendored sherpa-onnx libs |
| `62ecf71f` | CI: 45-minute job timeout |
| `52ffd525` | raise the test JVM heap to 2 GB (upstream bug, see below) |
| `6116e5ff` | CI: read the `STORE_PASSWORD` secret, assemble `:app` only |
| `6f3ca490` | CI: skip `lintVitalRelease` (upstream false positive, see below) |

### Footprint in upstream files

This is what determines rebase cost. New files cannot conflict; only these can.

| file | lines |
|---|---|
| `app/src/main/AndroidManifest.xml` | +4 |
| `app/src/main/kotlin/.../dictate/DictateController.kt` | +14 |
| `lib/dictate-core/src/main/kotlin/.../provider/OpenAiCompatibleClient.kt` | +7 / −5 |
| `lib/dictate-core/src/main/kotlin/.../provider/ProviderConfig.kt` | +6 |
| `app/build.gradle.kts` | +4 |
| **new** `.../provider/NetworkBudget.kt`, `.../dictate/FastFallback.kt` | 205 |

The manifest line is `ACCESS_NETWORK_STATE` — a normal permission, no runtime prompt, but it does
appear in the app's permission list.

---

## Rebasing onto a new upstream release

```bash
git fetch upstream --tags
git rebase --onto v6.2.0 v6.1.2 fast-fallback
git push --force-with-lease origin fast-fallback
```

Then let CI answer whether it still builds. Keep the changes as few commits touching as few upstream
lines as possible — that property is the whole maintenance strategy, not a nicety.

If a rebase gets messy, `git format-patch` output of the two functional commits is enough to
reapply by hand.

---

## CI

`.github/workflows/build.yml` runs on every push: unit tests, then `:app:assembleRelease`, then
uploads the APK as an artifact. A full green run takes about 15 minutes and produces a ~46 MB signed
APK, retained for 90 days.

`lintVitalRelease` is skipped — see issue #332 below. That is not free: a genuinely fatal lint issue in
a release build will not be reported by this job.

Signing is optional and read from repository secrets — `KEYSTORE_BASE64`, `STORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. With them the APK upgrades in place on the phone; without them
`app/build.gradle.kts` falls back to an unsigned release on its own.

The repo is public, so Actions minutes and artifact storage are free. That stops being true if the
fork is ever made private.

---

## Upstream

Both filed against DevEmperor/DictateKeyboard:

- **[discussion #330](https://github.com/DevEmperor/DictateKeyboard/discussions/330)** — the proposal
  to take this behaviour upstream. If accepted, most of this fork disappears.
- **[issue #331](https://github.com/DevEmperor/DictateKeyboard/issues/331)** — `:app:testDebugUnitTest`
  runs out of heap on a clean v6.1.2 checkout, in `ImeWindowControllerEditorMoveTest`. Reproduced on a
  clean runner with none of this fork's code; `maxHeapSize = "2g"` fixes it. Commit `52ffd525` carries
  that fix here until upstream takes it.
- **[issue #332](https://github.com/DevEmperor/DictateKeyboard/issues/332)** — `:app:assembleRelease`
  fails on a clean checkout, because `lint { baseline = file("lint.xml") }` points at a lint *config*
  file rather than a baseline, so a false-positive `InvalidFragmentVersionForActivityResult` on a
  `ComponentActivity` becomes fatal. Worked around in the workflow by commit `6f3ca490`.

---

## Open actions

- [ ] Watch #330. If the maintainer takes the change, drop `a7e19dcb`/`6435f8a1` and go back to
      running upstream directly.
- [ ] Watch #331. When upstream sets a test heap, drop `52ffd525` on the next rebase.
- [ ] Watch #332. When upstream separates the lint baseline from the config, drop the
      `-x lintVitalRelease` from the workflow so release lint runs again.
- [ ] Push the archived pre-rewrite fork history if it is worth keeping:
      `git push origin archive/legacy-fork`. The old fork sat on the now-frozen `legacy-java` branch
      and shares no history with the current codebase, so it can never be merged forward.
- [ ] Point `upstream` at the current name — the repository was renamed to `DictateKeyboard`:
      `git remote set-url upstream https://github.com/DevEmperor/DictateKeyboard.git`
- [ ] Before installing a fork build over the store version: take an in-app backup (Settings →
      Backup). Different signing key means a clean install.

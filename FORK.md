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

| situation | upstream v6.1.2–v6.3.0 | this fork |
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

### Installing beside the store version

The fork builds as `net.devemperor.dictate.fork`, labelled **Dictate (Fork)**, so it installs
alongside the Play release rather than colliding with it. That is not cosmetic: a self-built APK is
signed with a different key, and Android refuses to install it over one signed with another — the only
alternative would be uninstalling the app that currently works.

The label lives in `app/src/release/res/values/strings.xml` as an override of `floris_app_name`, not
in `app_name`. `app_name` is translated into some thirty locales, so editing `values/strings.xml`
renames the app for English only and leaves every other device showing the same "Dictate" as the store
build. `floris_app_name` is what every manifest label actually points at and is translated nowhere;
the `debug` and `beta` source sets already override it the same way. Verify a build with:

```
aapt2 dump badging app-release.apk | grep -E '^package:|application-label'
```

All 97 locale labels should read `Dictate (Fork)`.

Consequences worth knowing:

- Both appear separately in Android's keyboard list, under names that tell them apart.
- The id is deliberately a *suffix* of the original, because `Restore.PACKAGE_NAME` identifies a
  genuine Dictate backup by prefix. Backups therefore move between the two in both directions.
- The Wear module carries the same id — the Wearable data layer pairs watch and phone by package name.
- **Dictate Cloud in-app purchases do not work under this id.** Play billing is bound to the package it
  was sold under. Bring-your-own-key providers and the on-device models are unaffected; if you rely on
  Cloud credit, keep using the Play build for that — which side-by-side installation makes possible.
- It is a separate app, so it starts with empty settings. Restore a backup from the store version to
  carry yours over.

### Prerequisites

None of this changes any behaviour unless **both** are true:

- the offline fallback is switched on in settings (`localFallbackEnabled`, upstream default: **off**), and
- a local model is downloaded.

Without them the code paths are never entered, which is what makes the patch safe to carry.

---

## What changed

| commit | what |
|---|---|
| `e7f83a12` | pre-flight check + per-call network budget |
| `f36b5899` | circuit breaker (90 s) |
| `89481e32` | GitHub Actions: build, tests, signed APK artifact |
| `560adf9e` | CI: fetch the vendored sherpa-onnx libs |
| `1fbc4178` | CI: 45-minute job timeout |
| `9b00ffd0` | CI: read the `STORE_PASSWORD` secret, assemble `:app` only |
| `c3c6453a` | fork application id, so it installs beside the store version |
| `62d5bce1` | fork app label via a `release` source set override |
| `13c6c5d5` | CI: build Bergamot (on-device translation), required since v6.4.0 |
| `9a204cbc` | CI: automatic rebase onto each new upstream release |

Hashes are as of the v6.4.0 rebase; every rebase rewrites them, so after an automatic one
`git log --oneline $(git merge-base main <tag>)..main` is the authoritative list.

The test-heap and lint-baseline workarounds that used to live here are gone: upstream fixed both
directly (`1232346a`, `d905f9b2`, see below), which made the local patches conflict on rebase. They
were dropped rather than reapplied on top of the real fix; the workflow's `-x lintVitalRelease` was
then removed in `cef2c779` for the same reason.

Rebased onto v6.3.0 on 2026-09-23: five conflicts, all in the first commit, all two sides adding a line
at the same spot (upstream's `callTimeoutSeconds` beside this fork's `networkBudget`); both kept. The
retry and connect budget is unchanged in v6.3.0 and it still has no pre-flight or circuit breaker, so the
patch is still needed. The realtime dictation mode added upstream is not covered by the fast fallback.

Rebased onto v6.4.0 on 2026-10-07: no conflicts. The retry and connect budget and
`localFallbackProvider`'s conditions are unchanged in v6.4.0, so the patch still applies as written.
v6.4.0 makes the build depend on a natively compiled Bergamot library, which CI now builds
(`13c6c5d5`). xAI's new `stt` transcription goes through `executeForBody` like the others, so it gets
the fast-fail budget too.

### Footprint in upstream files

This is what determines rebase cost. New files cannot conflict; only these can.

| file | lines |
|---|---|
| `app/src/main/AndroidManifest.xml` | +4 |
| `app/src/main/kotlin/.../dictate/DictateController.kt` | +14 |
| `lib/dictate-core/src/main/kotlin/.../provider/OpenAiCompatibleClient.kt` | +7 / −5 |
| `lib/dictate-core/src/main/kotlin/.../provider/ProviderConfig.kt` | +6 |
| `app/build.gradle.kts` | +5 / −1 |
| **new** `.../provider/NetworkBudget.kt`, `.../dictate/FastFallback.kt` | 205 |

The manifest line is `ACCESS_NETWORK_STATE` — a normal permission, no runtime prompt, but it does
appear in the app's permission list.

---

## Rebasing onto a new upstream release

```bash
git fetch upstream --tags
git rebase --onto <new-tag> $(git merge-base fast-fallback <new-tag>) fast-fallback
git push --force-with-lease origin fast-fallback
```

The old base is the merge-base, not the tag the fork was last "on": a rebase that pulled in part of
upstream leaves the fork sitting between two tags, and `--onto <new-tag> <old-tag>` would then replay
those upstream commits as if they were the fork's own. Check `git log --oneline <merge-base>..fast-fallback`
lists only the fork's commits before running it.

Normally you do not run this yourself: `upstream-sync.yml` does it (see **Automatic upstream sync**
below). By hand, it is for when that workflow reports a conflict.

Then let CI answer whether it still builds. Keep the changes as few commits touching as few upstream
lines as possible — that property is the whole maintenance strategy, not a nicety.

If a rebase gets messy, `git format-patch` output of the two functional commits is enough to
reapply by hand.

---

## CI

`.github/workflows/build.yml` runs on every push: unit tests, then `:app:assembleRelease` (including
`lintVitalRelease`, no longer skipped — see issue #332 below), then uploads the APK as an artifact. A
full green run takes about 15-17 minutes and produces a signed ~104 MB APK (a ~46 MB artifact
download), retained for 90 days. Fetch it with `gh run download <run-id> --repo Hyroniem/Dictate`, or
from the Actions tab.

Signing is optional and read from repository secrets — `KEYSTORE_BASE64`, `STORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. With them the APK upgrades in place on the phone; without them
`app/build.gradle.kts` falls back to an unsigned release on its own.

Native libraries: `tools/fetch-sherpa-onnx.sh` downloads sherpa-onnx, and since v6.4.0
`tools/bergamot/build-android.sh` compiles Bergamot with the NDK and CMake versions pinned in
`gradle/tools.versions.toml`. Both land in `app/libs` / `app/src/main/jniLibs` and are cached on the
scripts that pin them, so only a run after one of those changes pays for the native build.

The repo is public, so Actions minutes and artifact storage are free. That stops being true if the
fork is ever made private.

### Automatic upstream sync

`.github/workflows/upstream-sync.yml` runs daily (05:17 UTC) and on demand from the Actions tab:

1. Looks for the newest `vX.Y.Z` tag on DevEmperor/DictateKeyboard that `main` does not contain.
   Nothing new: it stops there.
2. Rebases the fork onto that tag from the merge-base, exactly as in the section above, and pushes the
   result to `sync/<tag>`.
3. Builds `sync/<tag>` with `build.yml` (tests, release APK).
4. Only if that is green, force-pushes it to `main` (with a lease on the `main` it started from) and
   deletes `sync/<tag>`. The push to `main` triggers the regular build, whose artifact is the APK to
   install.

If the rebase conflicts or the build fails, `main` is left as it was and an issue titled
"Upstream sync failed for <tag>" is opened (or commented on). Fix it by hand, then re-run the
workflow, optionally with the tag as input.

It needs a repository secret `SYNC_TOKEN`: a fine-grained personal access token for this repository
with **Contents: read and write** and **Workflows: read and write**. The default `GITHUB_TOKEN` cannot
do it, because the rebased commits touch `.github/workflows/`, which that token may never push.

---

## Upstream

Filed against DevEmperor/DictateKeyboard:

- **[discussion #330](https://github.com/DevEmperor/DictateKeyboard/discussions/330)** — the proposal
  to take this behaviour upstream. If accepted, most of this fork disappears.
- **[issue #331](https://github.com/DevEmperor/DictateKeyboard/issues/331)** — `:app:testDebugUnitTest`
  ran out of heap on a clean checkout, in `ImeWindowControllerEditorMoveTest`. **Fixed upstream** in
  `1232346a` (`maxHeapSize = "2g"`); the local workaround was dropped on the rebase that pulled it in.
- **[issue #332](https://github.com/DevEmperor/DictateKeyboard/issues/332)** — `:app:assembleRelease`
  failed on a clean checkout, because `lint { baseline = file("lint.xml") }` pointed at a lint *config*
  file rather than a baseline, so a false-positive `InvalidFragmentVersionForActivityResult` on a
  `ComponentActivity` became fatal. **Fixed upstream** in `d905f9b2`; the workflow's `-x
  lintVitalRelease` skip was removed in `cef2c779` once that landed.

---

## Open actions

- [x] Watch #331. Fixed upstream (`1232346a`) and merged in on the 2026-09-07 rebase.
- [x] Watch #332. Fixed upstream (`d905f9b2`) and merged in on the 2026-09-07 rebase; the workflow
      skip was removed in `cef2c779`.
- [ ] Watch #330. If the maintainer takes the change, drop `e7f83a12`/`f36b5899` and go back to
      running upstream directly.
- [ ] Push the archived pre-rewrite fork history if it is worth keeping:
      `git push origin archive/legacy-fork`. The old fork sat on the now-frozen `legacy-java` branch
      and shares no history with the current codebase, so it can never be merged forward.
- [x] Point `upstream` at the current name — the repository was renamed to `DictateKeyboard`
      (`upstream-sync.yml` uses the new URL).
- [ ] Add the `SYNC_TOKEN` secret, or the automatic upstream sync fails on its first step.
- [ ] Before installing a fork build over the store version: take an in-app backup (Settings →
      Backup). Different signing key means a clean install.

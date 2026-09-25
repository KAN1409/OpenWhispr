# OpenWispr Engineering Rules

## Goal
Preserve exact, verbatim multilingual speech recognition. Egyptian Arabic must remain Egyptian Arabic; English must remain English; mixed Arabic/English code-switching must keep the spoken word order. Do not translate, summarize, paraphrase, normalize into MSA, or silently clean raw ASR text.

## Working method
- Inspect the real code before editing.
- Prefer one complete batch: inspect -> plan -> implement -> self-review -> tests -> build -> artifact.
- Make surgical changes and preserve known-good transcription fixes.
- Do not report success beyond what was actually built or tested.
- For non-trivial Android/library behavior, verify against current authoritative upstream docs/source first.

## Build / compatibility
- Debug APK must remain update-compatible with the checked-in permanent debug keystore.
- Preserve applicationId `com.edib.openwhispr`.
- Increment versionCode for installable update builds.
- Build command: `./gradlew testDebugUnitTest assembleDebug --no-daemon`.
- Target device is arm64 Android; app currently targets SDK 35.
- Do not commit local diagnostic files such as `audit.db` or `audit-latest.wav`.

## Local ASR
- sherpa model download and activation are separate operations. Never auto-activate a model from the download callback.
- Avoid concurrent native recognizers for large models. Model load, inference, and release must remain serialized.
- Benchmark mode is diagnostic only. The primary Voice Note transcript remains authoritative.
- Run benchmark models sequentially and keep outputs isolated from the primary transcript.
- Model-install checks must validate required files, not only directory existence.
- Whisper multilingual uses `task=transcribe`; empty language means auto-detect.
- Raw ASR evidence must not be trimmed or post-processed before storage.

## UI
- Mixed Arabic/English display issues must not be “fixed” by mutating raw transcript order.
- Use Android BiDi-aware rendering while preserving the underlying string.
- Keep benchmark/testing UI isolated from normal product UI where practical.

## Release artifact
When asked for an APK, finish with a verified APK artifact, not only source changes or a prompt.

# Gemma 3n E2B device observation, 2026-10-11

The connected POCO 2511FPC34G ran Ramu 0.51-beta (versionCode 6) on Android API 36, arm64-v8a. The installed APK SHA-256 was read on the device with `adb shell sha256sum` and matched the local debug APK: `4a14081add5b2e10f25f5dced731e885aa2c5b4bf685214a4678494bacdbd247`. Gradle declares LiteRT-LM 0.16.1; Ramu initializes its CPU backend.

The already-installed `gemma-3n-E2B-it-int4.litertlm` was measured on the device at 3,655,827,456 bytes with SHA-256 `2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6`. Both values match the pinned [Google Hugging Face artifact metadata](https://huggingface.co/api/models/google/gemma-3n-E2B-it-litert-lm/revision/c03b6f60b8da6c5400b6838a2cf26420f80c0a01?blobs=true). No model bytes were downloaded or copied.

During the device session, the user selected E2B and sent a short text prompt in Ramu. A local model response appeared in the chat and was observed on screen. This is a manual observation; no separate logcat trace or benchmark was retained. No Gemma 4 inference was attempted. E4B remained installed, but its initialization was blocked by the app's available-RAM guard, so no E4B inference claim is made.

The catalog limits this download variant to API 36 and arm64-v8a, the tested Android/API/ABI combination. The 2,500 MiB free-RAM guard comes from Ramu's inference code; the 8 GB device-RAM recommendation comes from Google AI Edge Gallery's [allowlist](https://github.com/google-ai-edge/gallery/blob/main/model_allowlists/1_0_9.json). The storage threshold is the exact artifact size plus the downloader's 128 MiB reserve. This observation does not establish performance or success on other devices.

# Amiki voice provider setup

## Local offline speech recognition

Amiki offers an optional bundled-runtime path using Vosk Android. The APK includes the native recognition runtime but does not bundle a large acoustic model. The owner explicitly downloads the small US English model once from a pinned upstream revision. Amiki verifies the archive SHA-256 before extraction, applies download/extraction size limits, rejects ZIP path traversal, and stores the model in app-private storage.

After installation, the Vosk recognizer processes microphone audio locally. The offline provider does not call the Android system recognition provider and does not transmit microphone audio to a remote speech service. The model is loaded only for an explicit assistant turn and released afterward to reduce memory pressure.

The upstream archive is approximately 41.2 MB. Vosk lists the small model as suitable for mobile/embedded devices, but runtime memory can be about 300 MB; this has not yet been measured on the Infinix HOT 9. The model is US English and local recognition quality for Zambian English/accent, command latency and sustained stability still require physical testing.

## Provider policy

- **Automatic:** prefer Android's platform on-device recognizer where available; otherwise prefer Amiki Offline English when installed; otherwise use the selected Android system recognition provider.
- **Amiki Offline English:** requires the model to be installed and never silently falls back to a network-capable provider.
- **Android system provider:** explicit external compatibility option. Whether it uses a network depends on the provider; Amiki does not claim otherwise.

The optional “Hey Amiki” wake-word detector remains a separate opt-in component. Its current Picovoice implementation requires an owner-provisioned AccessKey and phrase-model provisioning; wake detection itself runs locally after provisioning. It is not required to use Amiki Offline English.

## References

- Vosk Android SDK: https://github.com/alphacep/vosk-android-demo
- Vosk Android installation: https://alphacephei.com/vosk/install
- Pinned model archive: https://huggingface.co/rhasspy/vosk-models/blob/e7ac2109d134b5f2404ba95389b2fb51916d4cab/en/vosk-model-small-en-us-0.15.zip
- Archive SHA-256: `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`

# Amiki-owned Hey Amiki wake word

## Runtime

Hey Amiki uses the sherpa-onnx streaming Zipformer keyword-spotting runtime
(v2.0.1) with the English GigaSpeech 3.3M KWS model. The phrase is passed to
the runtime as `hey amiki`; it is not trained or provisioned through a
commercial provider. The Android AAR is downloaded during Gradle builds from
the official sherpa-onnx release and checked against its pinned SHA-256.

The app downloads five quantized/required model files on first installation.
Each file is size- and SHA-256-verified before the staged model is moved into
app-private storage. The inference runtime uses one CPU thread, 16 kHz mono
microphone input, 100 ms PCM chunks, and local CPU inference.

## Privacy and lifecycle

- Wake-word detection is disabled by default and requires the owner to enable it.
- Microphone permission is checked before the engine starts.
- Detection only runs inside the visible, owner-enabled foreground service.
- A persistent notification provides a Stop listening action.
- Standby audio is processed on-device. No audio or transcript is sent to a server.
- The wake-word engine is stopped and its microphone is released before Amiki
  starts a foreground assistant turn; it resumes after the assistant session.
- The wake-word service is not started from BOOT_COMPLETED. The selected Android
  VoiceInteractionService may restore the previously owner-enabled state only
  when permissions and the verified model are still present.

## Model provenance and licences

- sherpa-onnx runtime: Apache License 2.0, Copyright sherpa-onnx contributors.
- English keyword-spotting model: Apache-2.0, trained on GigaSpeech XL.
- Model source and file hashes: Hugging Face model card for
  `mobilebytesensei/sherpa-onnx-kws-zipformer-gigaspeech-3.3M`.
- The model files are unmodified exports from the upstream sherpa-onnx
  `kws-models` release. Their SHA-256 values are pinned in
  `AmikiWakeWordModel.java`.

## Device validation still required

Unit tests and CI can validate phrase normalization, compile the Android API
integration, and build the APK. Real wake-word recall, false-accept rate,
battery impact, screen-off reliability, and microphone lifecycle must still be
measured on the target Android handset before this feature is declared
production-ready.

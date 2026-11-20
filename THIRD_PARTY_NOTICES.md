# Third-party notices

Amiki Android includes the Vosk Android speech-recognition runtime from Alpha Cephei and an optional download of the Vosk small US English speech model. These are distributed under Apache License 2.0. The model is downloaded separately from the APK, is SHA-256 verified before use, and remains in app-private storage.

The Vosk Android runtime uses Java Native Access (JNA), distributed under its applicable dual-license terms (LGPL 2.1 or Apache License 2.0). Consult the upstream JNA distribution for the full license text.

- Vosk: https://github.com/alphacep/vosk-api
- Vosk Android: https://github.com/alphacep/vosk-android-demo
- JNA: https://github.com/java-native-access/jna
- Model source: https://huggingface.co/rhasspy/vosk-models

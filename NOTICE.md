# CallVault

CallVault is a modified fork of ShizuCallRecorder
(https://github.com/kitsumed/ShizuCallRecorder), Copyright (C) kitsumed (Med),
licensed under GNU GPL-3.0 with additional Section 7 terms (see LICENSE).

This is a MODIFIED version, distinct from the original. It is not endorsed by,
affiliated with, or supported by the original author. The names, trademarks, and
logos of the original project are the property of their respective owner and are
not used here beyond this attribution.

CallVault remains licensed under GPL-3.0. Source: this repository.

## Bundled third-party model weights

`app/src/main/assets/ggml-silero-v5.1.2.bin` is the Silero voice-activity-detection
model, converted to ggml format. It is used to trim non-speech audio before
transcription and is redistributed here under the MIT licence.

- Silero VAD — Copyright (c) 2024 Silero Team — MIT licence
  (https://github.com/snakers4/silero-vad)
- ggml conversion — https://huggingface.co/ggml-org/whisper-vad — MIT licence

MIT licence text:

> Permission is hereby granted, free of charge, to any person obtaining a copy of
> this software and associated documentation files (the "Software"), to deal in
> the Software without restriction, including without limitation the rights to
> use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
> the Software, and to permit persons to whom the Software is furnished to do so,
> subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
> FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
> COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
> IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
> CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Downloaded third-party model weights

These are **not** bundled: the app downloads them only when the user chooses them.

- **Persian-tuned speech model** (offered when transcription is pinned to Persian, GitHub #55) —
  "Whisper large-v3-turbo — Persian (Farsi)" by givia
  (https://huggingface.co/givia/whisper-large-v3-turbo-persian), licensed under
  CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). It is a fine-tune of
  openai/whisper-large-v3-turbo (MIT). Changes: quantised to q5_0 for whisper.cpp,
  as published at https://huggingface.co/pejmans21/whisper-large-v3-turbo-persian-ggml
  (file `ggml-large-v3-turbo-persian-q5_0.bin`, SHA-256
  d832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8). CallVault does
  not modify the file. The credit is also shown in the app under the model's download rows.

## ABP Call (Ploshadki.kz fork of CallVault)

ABP Call is a MODIFIED version (FORK) of CallVault (https://github.com/madkongo/CallVault),
which is itself a fork of ShizuCallRecorder (https://github.com/kitsumed/ShizuCallRecorder).
It is not affiliated with, endorsed or supported by the authors of CallVault or
ShizuCallRecorder. Their names are used here only for attribution.

Modified by Ploshadki.kz since 2026-11-07, based on CallVault v2.4.9
(commit ec6e526c21d962793ce1b10bfd180d850c8cb022). Changes so far:
- 2026-11-07: renamed to "ABP Call" in all user-facing strings; own application id
  `kz.ploshadki.recorder` and provider authority; visible FORK marking and links to
  CallVault and ShizuCallRecorder on the first-run disclaimer and in Settings → About;
  debug CI workflow `.github/workflows/ploshadki-debug-apk.yml`.
- 2026-11-07: own launcher icon ("ABP"); app calls recorded only for WhatsApp Business
  (`com.whatsapp.w4b`), fail-closed — personal WhatsApp and unidentified apps are not recorded;
  upstream self-update from GitHub, donation links and community card switched off
  (`ploshadki/PloshadkiFork.kt`).
- 2026-11-07: connection to the company server (`ploshadki/`): phone login with a one-time
  enrollment link (DPoP, RFC 9449, key in Android Keystore) and automatic, resumable upload of every
  call recording over Wi-Fi or mobile data, using only the open contract `air.wa-capture.upload.v1`.
  Uses OkHttp (Apache-2.0).
- 2026-11-08: the recorder is also built as an Android library (`recorder/`, artifact
  `kz.ploshadki:abp-recorder`) so it can be embedded in another app; `app/` became a thin host
  (`app/host/`); provider authority is now `${applicationId}.recorder`.
- 2026-11-08: Capacitor plugin `AbpRecorder` (`capacitor-abp-recorder/`, artifact
  `kz.ploshadki:abp-recorder-capacitor`): status, upload queue, setup screen and connection
  link for a host app; host can hide the recorder's launcher icon (`abp_show_launcher_icon`).
- 2026-11-08: the `abpcall://enroll` link handler can be switched off by the host
  (`abp_enroll_deeplink_enabled`); the connection screen is no longer exported.
- 2026-11-08: recordings and login are sent to the company server over HTTPS only;
  plain-HTTP links are rejected and redirects are not followed.
- 2026-11-08: a host app can protect the recorder screens from screenshots and screen
  recording (`abp_secure_screens`, sets FLAG_SECURE on the recorder's activities).
- 2026-11-10: the on-device whisper transcript (and the on-device summary, when one exists) of a
  recording ABP Call uploaded is sent to the company server under the open contract
  `air.wa-capture.transcript.v1` (`ploshadki/TranscriptReport.kt`, `ploshadki/AbpTranscriptWorker.kt`,
  hook in `transcription/TranscriptionRunner.kt`); an enrolled phone defaults to transcribing after
  each call (`data/AppPreferences.kt`); Kazakh (`kk`) added to the transcription languages.

ABP Call remains licensed under GPL-3.0-or-later with the Section 7 terms in LICENSE.
Source: https://github.com/frce552-collab/CallVault

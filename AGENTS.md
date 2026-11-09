# Project requirements agreed with the owner

- Narration engines, voice selection, generation and setup must work inside
  GlideLive. Downloading model packages inside GlideLive is allowed. Requiring
  another app, system TTS engine installation or external model import is not.
- Prioritize character voices and story performance over tiny downloads or
  minimum latency. Do not substitute pitch/stretch effects for new neural voices.
- Validate speech fidelity separately from successful inference, WAV validity
  and duration. None of those establish that every word was spoken. Report the
  limits of automatic transcription and checks still pending on the phone.
- The owner authorized integrating and pushing completed, checked changes to
  main without asking again. Pushes trigger APK publication; check CI and the
  actual published release after pushing. Do not publish a build with failed checks.


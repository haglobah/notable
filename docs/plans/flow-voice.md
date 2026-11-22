# Plan: talking to Claude through Flow (voice first)

Goal (Beat, 2026-11-20): a voice mode on the Boox, modelled on Flow's chat (flow.humane.tools,
repo `~/projects/hotlustre`, runs on cirdan), with a microphone circle as the main control. Every
job started from the tablet is an ordinary Flow conversation, so it can be seen and inspected in
Flow. Access only inside Notable for now; an OS-wide overlay is later.

## Decisions

- **Flow is the backend; the tablet runs no agent loop.** It uses the routes Flow's own page uses
  (live version v0465 on cirdan, `server/agent/routes.ts` there): `POST /api/chat`
  `{cid, conv, text}` starts or continues a conversation, `POST /api/chat/stop`,
  `GET /api/chat/events?since=` (SSE, every conversation's events, no filter).
- **Sign-in is the humane tools cookie,** as in the browser: `POST /_auth/sign-in {email, password}`
  with `Origin` set, the `Set-Cookie`s kept sealed with the Keystore key (`files/flow/session`).
  Only a navigation renews the session (hotlustre `core/main.ts` `htGate`), so each connect makes
  one `GET /api/chat/status` with `Accept: text/html` and keeps the cookies it gets back.
  A Flow without sign-in (a local one) answers 404 to sign-in; the client then accepts it with no
  cookies, if `/api/chat/status` is open.
- **Voice goes through Flow's own transcription,** `POST /api/voice/transcribe` with 16 kHz mono
  PCM16 (ElevenLabs Scribe, Flow's key and key terms). The tablet records with `AudioRecord`. The
  Boox has Google's and Qualcomm's `RecognitionService`s, which are an option for offline use later.
- **Tap to talk, tap to send.** The transcript is sent at once; Stop ends a running turn; a
  keyboard toggle sends typed text.
- **The microphone is a dot over the screen (Beat, 2026-11-22),** not a screen of its own:
  `FlowMicDot` floats over the library and over a note while its toolbar is open. Tap: it turns
  black and listens; tap again: sent, with a small label beside it (listening, transcribing, a
  failure to tap away). Drag it anywhere (kept in the `flow-dot` preferences, dp; at first at the
  right, 76 dp down, under the top bar); a long press opens the conversation screen (`flow`). The
  library bar's microphone shows and hides it. Over a note its place is left out of raw drawing
  (`CanvasEventBus.floatingControl`, an exclude rect in `setupSurface`), so the pen taps it.
- **The dot starts a new conversation by default; while it listens, a window beside it lists the
  open conversations started here** (Beat, 2026-11-22): tap the dot again and what was said starts
  a new conversation; tap a conversation in the window and it goes there instead (Cancel ends the
  recording). Open = not ticked off. While a recording or its message is under way the event stream
  stays open (`FlowClient.holding`), so the list catches up with ticks made in Flow; the window's
  place is left out of raw drawing with the dot's (one union rect).
- **The conversation screen is laid out like Flow's chat** (Beat, 2026-11-22): a sidebar with New
  conversation, the open conversations (most recently active first) and the ticked-off ones under a
  "Done" fold, the chosen one beside it (the bar's sidebar button hides the list). The box on a row
  ticks it off or opens it again in Flow (`POST /api/chat/tick {conv, done}`, as Flow's checkbox);
  the fold reads Flow's `tick` events, and a new message (`user`, `resumed`) opens one again, as in
  Flow. The screen's microphone and typing go to the shown conversation, or start one under New.
- **Conversations started here are scoped to `notable`** (Beat, 2026-11-22): the first message of
  a new conversation carries `scope: ["notable"]`, Flow's scope pills; later ones leave the scope
  as set in Flow.
- **Where the stream starts:** without `since` Flow replays its whole log (73 MB on gondor). Flow
  has no route for the log's head, so the first connect reads `chat.seq` from `/api/state` (the
  page's saved model, ~10 MB) with a streaming scan (`chatSeq`), once; after that the tablet keeps
  its own seq in `files/flow/chat.json`.
- **The tablet keeps only conversations it started,** folded from the events (`FlowChat.apply`).
  A subagent's own events (with `agent`) stay in Flow. Up to 400 entries per conversation.
- **E-ink:** the screen samples the chat at 900 ms, since replies arrive as 300 ms deltas.
- **Cleartext only to loopback** (`res/xml/network_security_config.xml`), for a laptop's Flow
  through `adb reverse tcp:8040 tcp:8040`.

## Files

- `app/src/main/java/com/ethran/notable/flow/`: `FlowChat.kt` (model + fold, pure), `FlowApi.kt`
  (HTTP, SSE reader, cookie merge, `chatSeq`), `FlowClient.kt` (Hilt singleton: account, stream
  while a screen is attached, voice → transcript → send, persistence), `VoiceRecorder.kt`.
- `ui/views/FlowDot.kt` (the microphone dot), `ui/views/FlowView.kt` (route `flow`, the
  conversation, from a long press on the dot), `ui/views/FlowSection.kt`
  (Settings → Sync). Settings take a tab argument (`settings?tab=3`).
- Tests: `app/src/test/java/com/ethran/notable/flow/FlowChatTest.kt` (10), `FlowApiTest.kt` (9).

## Validation (2026-11-20, Note Air 5C)

- `./gradlew :app:testDebugUnitTest`: 548 tests, 1 failure in
  `LocalDocWriterTest` ("two devices moving the same quick page…"), which passed on two reruns:
  flaky, sync code not touched here.
- On the device against gondor's Flow (v0374, no sign-in) through `adb reverse`: sign-in, the
  stream going live, a recording transcribed by Flow (silence → "Nothing was heard"), a typed
  message starting a Flow conversation, its `user` event confirming the bubble, and the reply
  event shown (an error: gondor's Flow has no Claude login). The test conversation was ticked off
  in Flow (v0374 has no delete).

## Open

- **Not tried against flow.humane.tools:** it needs Beat's password. humane tools' own origin check
  on sign-in is unconfirmed.
- A streamed reply (`text` deltas) on the device: only unit-tested.
- Flow could add `GET /api/chat/head` (or `since=end`) and a `conv` filter on the event stream;
  then the 10 MB first read and the other conversations' traffic go away.
- Conversations started in Flow's browser can't be opened on the tablet yet.
- Later: the OS-wide overlay (accessibility service / Navi ball), and Notable's live image as the
  thing Claude changes on the tablet (ADR-002).

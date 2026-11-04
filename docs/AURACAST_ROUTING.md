# Shared-headset premise, source routing and Auracast

## Current runtime contract

The live product premise is two people sharing one stereo TWS pair (a JBL pair
in testing), one bud each, speaking in turns. Android exposes the pair as one
HFP communication microphone and cannot report which bud picked up an
utterance. Headset identity is therefore not evidence of who is speaking or in
which language.

The source language is chosen per bounded utterance by **dual-ASR confidence
routing**: GIPFormer VI and Parakeet EN both score the same PCM, and the app
compares average emitted-token log-probability with a floor, a margin, an EN
calibration offset and a soft turn prior. Ambiguous turns fail closed. Details
are in [OFFLINE_PIPELINE.md](OFFLINE_PIPELINE.md) and `docs/ARCHITECTURE.md`.
SpeechFlow LID remains only as a build-time A/B (`-ProutingMode=LID`).

Audio devices come from a persisted Dashboard choice (`AudioRoutePreference`),
resolved at every IDLE -> CAPTURING edge. It selects devices only, never the
direction:

| Route | Capture | Playback |
|---|---|---|
| Auto (default) | Bluetooth when one communication headset is usable (the sole one, or the one Android selected), else Phone | follows the chosen route |
| Phone | built-in mic, `VOICE_RECOGNITION`, 300 ms post-playback echo guard | built-in loudspeaker, waveform on both channels |
| Bluetooth | `setCommunicationDevice()` + SCO, `VOICE_COMMUNICATION`; fails closed when no headset is usable, or several are and Android has selected none | matching A2DP sink must be confirmed; target VI on the left channel, target EN on the right |

Stereo split on the shared pair puts each listener's target language in the
bud they wear. It does not prove per-bud microphone identity or independent
output to unrelated headsets.

Settings still lists connected Bluetooth endpoints and lets the user save a
VI/EN preference per headset (`ConnectedHeadsetRepository`,
`HeadsetLanguagePreferences`). These are inventory and preference only; they
do not select the source direction. `BluetoothSpeakerRouteResolver` and
`HeadsetRoutePlanner` (one-speaker/many-listener policy) remain in the
`headset` package but are not on the production capture path.

## Why Auracast is not source evidence

Auracast is a one-to-many LE Audio downlink. It provides no microphone uplink
and does not identify which listener is speaking, so it can never replace the
per-utterance language decision. Passive Auracast listeners are
connectionless: `AudioManager.getDevices()` may show a logical broadcast route,
but cannot enumerate who is listening.

Auracast is therefore at most a possible downlink for more listeners. The
current app does not target it: the Phone route prefers the built-in
loudspeaker, and the Bluetooth route requires the A2DP sink matching the
communication headset and fails closed otherwise.

## Android API 36 privilege boundary

Verified on the connected Galaxy Z Flip5 (`SM-F731B`, `SM8550`, Android 16/API
36):

- LE Audio, Broadcast Source and Broadcast Assistant report
  `FEATURE_SUPPORTED`;
- Samsung's Bluetooth service contains both broadcast source and assistant
  profiles;
- public capability probes and `BluetoothLeAudio.getGroupId()` are available;
- a normally installed target-36 APK is denied `BLUETOOTH_PRIVILEGED`;
- `BluetoothLeBroadcast` and `BluetoothLeBroadcastAssistant` are absent from
  public SDK stubs, even though the framework contains them.

Therefore OneVoice cannot create/update/stop a broadcast or provision receivers
directly. `BLUETOOTH_ADVERTISE` and a normal BLE advertiser are not substitutes.
The app requests only `BLUETOOTH_CONNECT`, and `AuracastCapabilityProbe` reads
public Source/Assistant support for display in Settings.

Full per-group control would need a preinstalled OEM component:

```text
OneVoice inference app
  -> signature-protected Binder/AIDL request
  -> privileged OEM Auracast service
  -> broadcast lifecycle, keys and receiver provisioning
```

## Samsung system broadcast (for experiments)

Samsung exposes the broadcast in system UI. On One UI 8.0 and earlier:
**Settings -> Connections -> Bluetooth -> three-dot menu -> Broadcast sound
using Auracast**, set a name/password and **Start broadcast**; each compatible
LE Audio receiver then chooses **Listen to Auracast broadcast**. Newer One UI
releases call it **Audio broadcast**.

Constraints observed or still open:

- The paired JBL Wave Beam exposes classic HFP/A2DP, not an Auracast sink; it
  can be the shared mic and stereo output, but not a passive listener.
- Some Samsung releases do not allow a Bluetooth microphone while media
  Auracast is active. Combining the Bluetooth capture route with a broadcast
  must be validated on the exact headset; the Phone route avoids that conflict
  for capture.
- An Auracast downlink would need a playback route that accepts the broadcast
  device; neither current route does.

Official references:

- <https://www.samsung.com/us/support/answer/ANS10001042/>
- <https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview>
- <https://developer.android.com/reference/android/media/AudioManager>
- <https://developer.android.com/reference/android/media/AudioRecord>
- <https://developer.android.com/develop/connectivity/bluetooth/bt-permissions>

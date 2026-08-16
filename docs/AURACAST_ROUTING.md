# Headset language assignment and future Auracast routing

## Current scope

The Android app can currently:

- list Bluetooth audio output endpoints that Android reports as connected;
- persist a preferred listening language (`VI` or `EN`) for an endpoint whose
  Bluetooth address is visible;
- construct an immutable one-speaker/many-listener route plan;
- reject a plan when the active speaker is missing, ambiguous or unassigned.

This code is configuration and a routing contract only. It does **not** claim
that an endpoint is an individual Auracast listener and it does not send PCM to
several headsets yet. Until an authenticated headset/floor integration supplies
the active speaker, acoustic LID remains the working direction fallback.

## One speaker, many listeners

At an utterance boundary, the future floor-control layer supplies exactly one
stable `activeSpeakerId`. The app snapshots the connected assignments once and
keeps that snapshot until the utterance has completed:

The first routing contract assumes a person's configured receive language is
also the language they speak. If bilingual users need independent preferences,
the persisted model must be split into `spokenLanguage` and `listeningLanguage`
before enabling real delivery; the planner must not infer one from the other.

```text
active speaker assigned VI
  -> route VI -> EN
  -> translated EN audio targets endpoints assigned EN

active speaker assigned EN
  -> route EN -> VI
  -> translated VI audio targets endpoints assigned VI
```

No active speaker, more than one active speaker, an unknown endpoint, an
unassigned speaker or no target-language listener all fail closed. A Settings
change made during ASR/MT/TTS applies to the next utterance, never the current
one.

The pure policy lives in `headset/HeadsetRoutePlanner.kt`. It intentionally does
not open Bluetooth, change an `AudioRecord` route or call hidden APIs.

## Why Auracast alone cannot select the input language

Auracast is a one-to-many LE Audio **downlink**. It does not provide the phone
with a microphone uplink or identify which listener is currently speaking. The
speaker identity must therefore come from a separate, exclusive floor-control
channel and an actual microphone route (for example HFP/LE Audio unicast). The
app must verify the routed input endpoint before trusting its language
assignment; an unknown route must not silently fall back to VI or EN.

`AudioManager.getDevices()` and `AudioDeviceCallback` expose Android audio
endpoints, not the membership of a connectionless Auracast broadcast. A logical
`TYPE_BLE_BROADCAST` route is not a list of all people listening to it.

## Android privilege boundary

An ordinary target-SDK-36 APK can inventory connected audio endpoints and query
public LE Audio capabilities with the user's `BLUETOOTH_CONNECT` permission.
It cannot create or provision production Auracast sessions using
`BluetoothLeBroadcast` / `BluetoothLeBroadcastAssistant`: those APIs are system
APIs and require `BLUETOOTH_PRIVILEGED`, which a normally installed APK cannot
obtain. `BLUETOOTH_ADVERTISE` and a normal BLE advertiser are not substitutes
for LE Audio broadcast.

Production per-group delivery therefore needs an OEM/system component:

```text
OneVoice inference app
  -> signature-protected Binder/AIDL route request
  -> preinstalled privileged Auracast service
  -> LE Audio broadcast(s) / sink provisioning
```

The inference app should send only a language, immutable recipient assignment
snapshot and completed PCM buffer. The privileged service owns broadcast keys,
session lifecycle, provisioning and the distinction between assigned,
provisioned and listening-unknown states.

Public Android references:

- <https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview>
- <https://developer.android.com/reference/android/media/AudioManager>
- <https://developer.android.com/reference/android/media/AudioDeviceInfo>
- <https://developer.android.com/develop/connectivity/bluetooth/bt-permissions>
- <https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothLeBroadcast.java>

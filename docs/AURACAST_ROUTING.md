# Headset-routed direction and Auracast output

## Current runtime contract

There is no acoustic language-classifier model and no default translation
direction. The app:

- lists connected Bluetooth audio endpoints exposed by Android;
- persists a spoken/preferred language (`VI` or `EN`) using a hash of the
  Bluetooth address;
- selects the sole assigned HFP/LE Audio communication endpoint;
- verifies the actual `AudioRecord` input identity when VAD opens an utterance;
- snapshots that identity and assignment until the utterance completes;
- fails closed for a built-in mic, missing permission, redacted identity,
  missing assignment or ambiguous speaker route.

The verified speaker assignment selects the branch:

```text
speaker assigned VI -> VI -> EN -> English target waveform
speaker assigned EN -> EN -> VI -> Vietnamese target waveform
```

`BluetoothSpeakerRouteResolver` owns public communication-device selection and
input verification. `HeadsetRoutePlanner` is the pure one-speaker/many-listener
recipient policy. Neither calls hidden Auracast APIs.

## Why the input is unicast

Auracast is a one-to-many LE Audio downlink. It does not provide a microphone
uplink and does not identify which listener is speaking. The sole talker must
therefore use HFP or LE Audio unicast for the microphone. `AudioRecord` is the
proof of that route; an Auracast receiver list cannot replace it.

Passive Auracast listeners are connectionless. `AudioManager.getDevices()` may
show a logical broadcast route, but it cannot enumerate every person listening.
Settings assignments are intended recipient policy, not delivery receipts.

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
The current app asks only for `BLUETOOTH_CONNECT` and sends completed target PCM
to Android's current media route.

Full per-group control needs a preinstalled OEM component:

```text
OneVoice inference app
  -> signature-protected Binder/AIDL request
  -> privileged OEM Auracast service
  -> broadcast lifecycle, keys and receiver provisioning
```

## Using Auracast on this phone now

The device dump shows Samsung `BluetoothCast`, with audio sharing disabled until
the operator starts a broadcast. On One UI 8.0 and earlier:

1. Open **Settings -> Connections -> Bluetooth**.
2. Open the three-dot menu and choose **Broadcast sound using Auracast**.
3. Set a name/password and tap **Start broadcast**.
4. On each compatible LE Audio receiver, choose **Listen to Auracast broadcast**
   and join the stream.
5. In OneVoice Settings, assign the unicast speaking headset VI or EN, then
   start translation. OneVoice's media `AudioTrack` follows the system route.

Samsung calls the feature **Audio broadcast** on newer One UI releases. The
currently paired JBL Wave Beam exposes classic HFP/A2DP rather than an Auracast
sink: it can provide the speaker microphone, but it cannot be a passive
Auracast listener.

One practical session needs two roles:

- one assigned unicast headset for the sole speaker microphone;
- one or more Auracast-compatible receivers for the translated downlink.

Some Samsung releases do not support using a Bluetooth microphone while media
Auracast is active. This concurrency must be validated with the exact production
headset; if it fails, the uplink needs a USB microphone or an OEM audio policy.

Official references:

- <https://www.samsung.com/us/support/answer/ANS10001042/>
- <https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview>
- <https://developer.android.com/reference/android/media/AudioManager>
- <https://developer.android.com/reference/android/media/AudioRecord>
- <https://developer.android.com/develop/connectivity/bluetooth/bt-permissions>

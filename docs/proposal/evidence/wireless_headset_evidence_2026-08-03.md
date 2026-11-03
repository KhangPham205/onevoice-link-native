# Wireless Headset, MEMS Microphone and Jobsite Evidence — 3 August 2026

## 1. Scope and evidence rules

This audit supports §5.2 of the OneVoice proposal. It covers a wireless headset
with both microphone uplink and speech playback, not a playback-only Bluetooth
earphone. Only manufacturer product pages, technical sheets and Android's
official API documentation are used for specifications.

The terms are kept separate:

- **MEMS** identifies a microphone transducer; it does not prove beamforming,
  environmental-noise cancellation, wind rejection or ASR accuracy.
- **Call/microphone noise reduction** concerns the signal transmitted to the
  phone. It is different from passive attenuation or ANC experienced by the
  wearer.
- **NRR/SNR/EN 352** concerns hearing protection. A headset without an
  applicable occupational rating is not presented as PPE, regardless of its
  microphone quality.
- NRR and SNR are produced under different standards and are not ranked as if
  their numeric values were directly interchangeable.
- Vendor battery hours are screening claims. OneVoice must measure the exact
  bidirectional route and reconnect policy over its own shift script.

No listed web price is used as a Vietnamese landed-cost quotation. Procurement
requires current quotes including shipping, tax, warranty, replacement hygiene
parts and the exact certified SKU.

## 2. Manufacturer-supported candidate matrix

| Candidate | Microphone evidence | Bluetooth/audio evidence | Jobsite/PPE evidence | Published battery evidence | Audit interpretation |
|---|---|---|---|---|---|
| [Elgin Discord](https://elginusa.com/products/ruckus-discord-wireless-noise-cancelling-earbuds) | Manufacturer lists a noise-isolating, omnidirectional **MEMS** microphone and describes background-noise cancellation during calls | Bluetooth 5.3; documentation lists HFP, HSP and A2DP | IP65; manufacturer states EPA/ANSI-certified **31 dB NRR** | Up to 14 h playback | Only shortlisted low-cost candidate combining a disclosed MEMS speech mic and a published attenuation rating. The page does not establish dual-mic beamforming or 14 h of continuous microphone uplink, so high-noise ASR and battery tests remain mandatory. |
| [BlueParrott M300-XT SE](https://www.jabra.com/_/media/BlueParrott_Product-Documentation/BP-M300-XT-SE/Technical-specifications/RevA/tech_specs%2C-d-%2Cblueparrott_m300SE-xt%2C-d-%2Cen-us%2C-d-%2C20230913.pdf) | **2 × MEMS**; manufacturer states 80% call-noise cancellation | Bluetooth 5.1; HFP 1.7, HSP 1.2, A2DP 1.3; up to 100 m | IP54; no EN 352, NRR or SNR in the cited sheet | Up to 14 h talk | Useful low-mass dual-MEMS ASR comparator, but not an occupational hearing protector. |
| [Jabra Perform 75](https://www.jabra.com/_/media/Jabra_VXi_Product-Documentation/Jabra-Perform-75/Technical-specifications/RevC/EN-Perform-75-Tech-Sheet-NA-SKUs-011225.pdf) | **2 × MEMS**; call-noise and wind-noise reduction | Bluetooth 5.3; HFP 1.8, HSP 1.2, A2DP 1.4; CVSD/mSBC; up to 100 m | IP65; no EN 352, NRR or SNR in the cited sheet | Up to 20 h talk; user-replaceable battery | Strongest documented dual-MEMS frontline candidate, but the manufacturer positions it for retail shiftwork. It is not PPE and must not compromise another protector's fit. |
| [ISOtunes ULTRACOMM Aware](https://isotunes.eu/products/ultracomm-aware) | Detachable noise-isolating speech boom; transducer technology is not stated, so MEMS is not claimed | Bluetooth 5.2 | EN 352; **SNR 30 dB**; output limited to 82 dB; IP67 without and IP56 with the boom; situational-awareness mode | Up to 10 h; charging case extends time before a plug but requires an off-ear recharge | Cost-oriented industrial fallback with a close-talk boom. Its 10 h headline leaves limited field margin and does not replace a continuous OneVoice rundown. |
| [3M PELTOR WS ALERT XP+/XPI+](https://multimedia.3m.com/mws/media/2523608O/3m-peltor-ws-alert-xp-xpi-fm-radio-headset.pdf?fn=tds-emea-ws-alert-xp-xpi-headset.pdf) | Manufacturer explicitly specifies a noise-cancelling **electret**, not MEMS, boom microphone | Bluetooth 5.2; HFP 1.8, HSP 1.2, A2DP 1.3.2; up to 10 m | EN 352-1:2020/352-3:2020; **SNR 29 dB with foam or 30 dB with gel** for the headband; construction use is named | Up to 78 h streaming | Highest disclosed runtime and an established hearing-protection form factor. Select the cheaper XP+ unless a measured requirement needs an XPI+-only feature. |

The matrix does **not** support the claim that every construction headset uses a
MEMS boom microphone. In particular, 3M explicitly specifies electret, while
ISOtunes does not disclose the transducer. OneVoice therefore treats MEMS as a
preference beneath safety certification, fit and measured ASR quality.

## 3. Provisional selection logic

1. Request current landed-cost quotes for the exact certified Elgin Discord,
   ISOtunes ULTRACOMM Aware and 3M PELTOR WS ALERT XP+ SKUs, plus replacement
   tips/cushions and windscreens.
2. Use Elgin Discord as the provisional cost-first MEMS candidate because it is
   the only shortlisted product whose manufacturer publishes both MEMS and an
   occupational attenuation rating.
3. Include Jabra Perform 75 or BlueParrott M300-XT SE in the acoustic bench test
   to quantify what a dual-MEMS array changes, but do not deploy either as sole
   hearing protection.
4. If Elgin fails local certification, fit, WER, reconnect or battery gates,
   advance to ISOtunes; advance to 3M when the site PPE assessment or the
   measured test requires its earmuff form factor/runtime.
5. Freeze the lowest landed-cost candidate passing every gate. A more expensive
   headset is not justified solely by brand or a higher vendor noise-reduction
   percentage measured under an unlike protocol.

## 4. Android routing and codec limits

The initial candidates advertise Classic Bluetooth HFP/HSP for bidirectional
communications. A Bluetooth version such as 5.2 or 5.3 does **not** by itself
prove LE Audio support.

On the production S23 (Android API 31+), the app should enumerate
`getAvailableCommunicationDevices()` and call
[`AudioManager.setCommunicationDevice()`](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))
with the approved `TYPE_BLUETOOTH_SCO` device or, only when actually exposed by
the exact pair, `TYPE_BLE_HEADSET`. Android selects the matching input device.
The production app must also:

- request `RECORD_AUDIO` and `BLUETOOTH_CONNECT` as applicable;
- wait for route confirmation before opening/accepting speech;
- measure the negotiated input format and resample explicitly to the ASR's mono
  16 kHz PCM contract;
- measure codec/route latency rather than treating Bluetooth as a zero-cost
  cable replacement;
- fail closed on disconnect instead of silently using the phone mic/speaker;
- test the headset's DSP together with Android voice processing to prevent
  harmful double suppression; and
- keep cellular/Wi-Fi disabled without disabling the allowlisted headset route.

Android's [BLE Audio recording guide](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording)
describes higher-quality bidirectional BLE Audio, but OneVoice will not claim
LC3/LE Audio until the exact phone, headset and Android build expose and pass
that route.

## 5. Acceptance evidence still required

For each candidate, record the exact SKU, firmware, ear-tip/cushion configuration,
phone build, Bluetooth profile/codec and input sample rate, then measure:

- clean and 85/90/95 dBA jobsite-noise WER/CER on the same speech set;
- p50/p95 capture, route and end-to-end translation latency;
- reconnect time after 30 deliberate range/power interruptions;
- screen-off route retention and fail-closed behavior;
- microphone wind rejection and speech clipping near impulsive tools;
- personal hearing-protector fit according to the deployment site's process;
- at least 10 h of scripted lab runtime and at least one 8 h field shift without
  charging or battery replacement; and
- current landed cost, warranty, consumables and replacement availability.

Until these tests exist, Elgin Discord is **provisionally selected**, not a
validated production winner.

package com.onevoice.link.headset

/** What the current runtime can honestly do with a planned translated waveform. */
enum class TargetAudioRoutingCapability {
    /** Android's ordinary app-wide output route is the only available destination. */
    DEFAULT_SYSTEM_ROUTE_ONLY,

    /** Per-headset/Auracast delivery needs a privileged or OEM-managed service. */
    REQUIRES_PRIVILEGED_SERVICE,
}

data class TargetAudioRoutingRequest(
    val outputLanguage: HeadsetLanguage,
    val listenerIds: Set<String>,
)

/**
 * Transport-neutral capability boundary for future playback integration.
 *
 * There is intentionally no success/delivered result: this repository contains
 * no per-headset playback implementation and must never pretend otherwise.
 */
interface TargetAudioRouter {
    fun capabilityFor(request: TargetAudioRoutingRequest): TargetAudioRoutingCapability
}

/**
 * Honest baseline for a normal Android application using the system audio route.
 * Any named listener is a targeted-delivery request and therefore cannot be
 * satisfied by a plain AudioTrack, even when the set contains only one endpoint.
 */
object DefaultSystemTargetAudioRouter : TargetAudioRouter {
    override fun capabilityFor(
        request: TargetAudioRoutingRequest,
    ): TargetAudioRoutingCapability =
        if (request.listenerIds.isEmpty()) {
            TargetAudioRoutingCapability.DEFAULT_SYSTEM_ROUTE_ONLY
        } else {
            TargetAudioRoutingCapability.REQUIRES_PRIVILEGED_SERVICE
        }
}

// ============================================================================
// ring_buffer.cpp — explicit template instantiations
// ----------------------------------------------------------------------------
// SpscRingBuffer<T> is fully defined in the header (templates can't be split
// the classic way without header bloat or per-call instantiations). This TU
// exists to (a) satisfy the requested file-pair layout and (b) pre-instantiate
// the concrete sample types used across the pipeline, so the compiler emits the
// methods once here instead of in every translation unit that includes the
// header — faster compiles and a smaller .so.
// ============================================================================

#include "ring_buffer.h"

namespace onevoice {

// INT16 PCM — the wire format between Oboe, the AI thread, and the model I/O.
template class SpscRingBuffer<int16_t>;
// FLOAT32 — used if a future backend prefers normalized float PCM on the
// application/HTP boundary (kept here so the linker symbol exists up-front).
template class SpscRingBuffer<float>;

}  // namespace onevoice

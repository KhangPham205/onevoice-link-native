#pragma once
// ============================================================================
// onevoice::SpscRingBuffer  —  lock-free SPSC ring buffer
// ----------------------------------------------------------------------------
// Used to move fixed-size audio chunks between the realtime audio callback
// thread (single producer: Oboe capture) and the AI cascade thread (single
// consumer), and again from the AI thread (producer) to the playback callback
// (consumer). No mutex, no lock, no allocation on the hot path.
//
// Correctness model
// ------------------
//  * Capacity is forced to a power of two so the producer/consumer indices can
//    be masked instead of modulo'd — a single `and` instruction, branch-free.
//  * `head_` is written ONLY by the producer and read (acquire) by the consumer.
//  * `tail_` is written ONLY by the consumer and read (acquire) by the producer.
//  * This is the canonical single-producer / single-consumer pattern: each
//    variable has a single writer, so plain stores with release semantics +
//    acquire loads give a happens-before edge with no fence on ARM64.
//  * Indices are monotonic 64-bit counters that wrap naturally; the in-use
//    count is `head - tail` and is correct across the 2^64 wrap because the
//    live element count never approaches 2^63.
//  * `head_` and `tail_` are each padded to their own cache line (alignas(64))
//    so the producer and consumer never false-share a line — critical for
//    keeping the audio callback off the coherence bus.
//
// Overflow policy: on a full ring, `write()` drops the *newest* incoming
// samples (returns the count actually accepted). Strict SPSC cannot advance the
// consumer-owned tail from the producer, so dropping-newest is the safe default.
// Size the buffer (>= 2 s of audio) so a transient AI stall degrades to a
// momentary gap rather than corruption.
// ============================================================================

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>          // std::memcpy
#include <stdexcept>
#include <type_traits>
#include <vector>

namespace onevoice {

// L1/L2 cache line size on the target (ARM64 big.LITTLE + Hexagon). Pinned to
// 64 rather than std::hardware_destructive_interference_size so the layout (and
// thus the public ABI of any struct embedding this buffer) stays stable across
// NDK toolchain/tuning changes.
inline constexpr std::size_t kCacheLineSize = 64;

template <typename T>
class SpscRingBuffer {
    static_assert(std::is_trivially_copyable_v<T>,
                  "SpscRingBuffer requires a trivially-copyable T (memcpy safety).");

public:
    // Capacity is rounded UP to the next power of two.
    explicit SpscRingBuffer(std::size_t requestedCapacity) {
        if (requestedCapacity == 0)
            throw std::invalid_argument("SpscRingBuffer: capacity must be > 0");
        std::size_t cap = 1;
        while (cap < requestedCapacity) cap <<= 1;
        capacity_ = cap;
        mask_     = cap - 1;
        buffer_.resize(capacity_);   // value-initialized (zeroed for int16_t)
    }

    // Non-copyable / non-movable: atomics + shared producer/consumer contract.
    SpscRingBuffer(const SpscRingBuffer&) = delete;
    SpscRingBuffer& operator=(const SpscRingBuffer&) = delete;

    // ----------------------------------------------------------------------
    // Producer API — call from exactly ONE thread (the audio capture callback).
    // Copies `count` items from `src` into the ring; returns items accepted.
    // ----------------------------------------------------------------------
    std::size_t write(const T* src, std::size_t count) noexcept {
        const std::size_t head = head_.load(std::memory_order_relaxed);
        const std::size_t tail = tail_.load(std::memory_order_acquire); // sync with consumer release
        const std::size_t free = capacity_ - (head - tail);
        const std::size_t n    = count < free ? count : free;

        const std::size_t idx   = head & mask_;
        std::size_t first = capacity_ - idx;        // tail-end of the linear store
        if (first > n) first = n;
        std::memcpy(&buffer_[idx], src, first * sizeof(T));
        std::memcpy(&buffer_[0], src + first, (n - first) * sizeof(T));

        head_.store(head + n, std::memory_order_release); // publish to consumer
        return n;
    }

    // ----------------------------------------------------------------------
    // Consumer API — call from exactly ONE thread (the AI / playback thread).
    // Copies up to `count` items into `dst`; returns items actually read.
    // ----------------------------------------------------------------------
    std::size_t read(T* dst, std::size_t count) noexcept {
        const std::size_t tail = tail_.load(std::memory_order_relaxed);
        const std::size_t head = head_.load(std::memory_order_acquire); // sync with producer release
        const std::size_t avail = head - tail;
        const std::size_t n = count < avail ? count : avail;

        const std::size_t idx   = tail & mask_;
        std::size_t first = capacity_ - idx;
        if (first > n) first = n;
        std::memcpy(dst, &buffer_[idx], first * sizeof(T));
        std::memcpy(dst + first, &buffer_[0], (n - first) * sizeof(T));

        tail_.store(tail + n, std::memory_order_release); // reclaim to producer
        return n;
    }

    // Best-effort snapshots (safe under SPSC, approximate if cross-thread).
    std::size_t availableRead() const noexcept {
        return head_.load(std::memory_order_acquire)
             - tail_.load(std::memory_order_acquire);
    }
    std::size_t availableWrite() const noexcept { return capacity_ - availableRead(); }
    std::size_t capacity() const noexcept { return capacity_; }

private:
    // Data buffer — touched by both threads but each slot by only one at a time.
    std::vector<T> buffer_;
    std::size_t capacity_{0};
    std::size_t mask_{0};

    // Producer-owned write cursor. Padded so the consumer's read of `tail_`
    // never drags this line (and vice-versa) — eliminates false sharing.
    alignas(kCacheLineSize) std::atomic<std::size_t> head_{0};
    // Consumer-owned read cursor, on its own cache line.
    alignas(kCacheLineSize) std::atomic<std::size_t> tail_{0};
};

}  // namespace onevoice

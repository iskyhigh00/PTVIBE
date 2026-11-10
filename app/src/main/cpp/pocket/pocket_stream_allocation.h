#pragma once
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <stdexcept>

namespace glidelive_pocket {
// Allocation failures must reach the streaming error path, rather than look
// like an accepted early end after some PCM has already been delivered.
inline float* copy_audio_chunk(const float* samples, size_t count,
                              void* (*allocate)(size_t) = std::malloc) {
    if (count > std::numeric_limits<size_t>::max() / sizeof(float))
        throw std::length_error("Pocket audio chunk exceeds addressable memory");
    if (count == 0) return nullptr;
    auto* copy = static_cast<float*>(allocate(count * sizeof(float)));
    if (!copy) throw std::bad_alloc();
    std::memcpy(copy, samples, count * sizeof(float));
    return copy;
}
}

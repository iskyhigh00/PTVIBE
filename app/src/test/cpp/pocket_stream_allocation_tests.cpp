#include "../../main/cpp/pocket/pocket_stream_allocation.h"

#include <iostream>

static void* fail_allocation(size_t) { return nullptr; }
int main() {
    const float input[] = {0.0f, -0.5f, 0.75f, 1.0f};
    float* copy = glidelive_pocket::copy_audio_chunk(input, 4);
    if (std::memcmp(input, copy, sizeof(input)) != 0) return 1;
    std::free(copy);
    bool failed = false;
    try { glidelive_pocket::copy_audio_chunk(input, 4, fail_allocation); }
    catch (const std::bad_alloc&) { failed = true; }
    if (!failed) return 2; // Returning nullptr/false would incorrectly allow stream success.
    if (glidelive_pocket::copy_audio_chunk(nullptr, 0, fail_allocation) != nullptr) return 3;
    bool overflow = false;
    try { glidelive_pocket::copy_audio_chunk(input, std::numeric_limits<size_t>::max()); }
    catch (const std::length_error&) { overflow = true; }
    if (!overflow) return 4;
    std::cout << "PASS Pocket PCM allocation failure, overflow and bit-preserving copy\n";
}

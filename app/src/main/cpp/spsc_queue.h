#pragma once
#include <atomic>
#include <cstddef>

// Cola lock-free de un productor y un consumidor. N debe ser potencia de 2.
template <typename T, size_t N>
class SpscQueue {
    static_assert((N & (N - 1)) == 0, "N debe ser potencia de 2");

public:
    bool push(const T& v) {
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t n = (h + 1) & (N - 1);
        if (n == tail_.load(std::memory_order_acquire)) return false;
        buf_[h] = v;
        head_.store(n, std::memory_order_release);
        return true;
    }

    bool pop(T& v) {
        const size_t t = tail_.load(std::memory_order_relaxed);
        if (t == head_.load(std::memory_order_acquire)) return false;
        v = buf_[t];
        tail_.store((t + 1) & (N - 1), std::memory_order_release);
        return true;
    }

private:
    T buf_[N];
    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
};

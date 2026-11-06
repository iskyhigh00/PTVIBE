#pragma once
#include <vector>

struct Sample {
    std::vector<float> data;  // PCM float mono
};

// Kit sintetizado: 0 bombo, 1 caja, 2 hi-hat cerrado, 3 tom.
void buildSynthKit(int sampleRate, std::vector<Sample>& out);

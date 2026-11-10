#include "../../main/cpp/stem_player.h"
#include <cmath>
#include <iostream>

int main() {
    // A final nonzero sample used to be skipped because interpolation required
    // a following source sample, even though playback had not reached the end.
    for (int channels : {1, 2}) {
        StemPlayer player;
        const int16_t mono[] = {0, 0, 0, 16000};
        const int16_t stereo[] = {0,0,0,0,0,0,16000,-16000};
        player.load(0, channels == 1 ? mono : stereo, 4, channels, 8000);
        player.setMaster(1.0f, 1.0f);
        player.play(true);
        float out[8] = {};
        player.render(out, 4, 8000);
        if (std::abs(out[6] - 16000.f / 32768.f) > 1e-6f) return 1;
        const float expectedRight = (channels == 1 ? 16000.f : -16000.f) / 32768.f;
        if (std::abs(out[7] - expectedRight) > 1e-6f) return 2;
        float after[8] = {};
        player.render(after, 4, 8000);
        for (float v : after) if (v != 0.f) return 3;
        if (player.playing()) return 4;
    }
    std::cout << "PASS final mono/stereo sample retained through playback\n";
}

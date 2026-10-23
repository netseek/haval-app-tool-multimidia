#include "avc_configuration.h"

#include <cstdlib>
#include <iostream>
#include <random>

using carplay_experiment::parseAvcConfiguration;

static void require(bool condition) {
    if (!condition) {
        std::cerr << "AVC configuration regression failed\n";
        std::exit(1);
    }
}

int main() {
    const std::vector<std::uint8_t> valid{1, 66, 0, 30, 0xff, 0xe1,
        0, 4, 0x67, 66, 0, 30, 1, 0, 2, 0x68, 0x88};
    const auto result = parseAvcConfiguration(valid, 64);
    require(result.valid && result.lengthBytes == 4);
    require(result.sequenceParameters == std::vector<std::uint8_t>({0, 0, 0, 1, 0x67, 66, 0, 30}));
    require(result.pictureParameters == std::vector<std::uint8_t>({0, 0, 0, 1, 0x68, 0x88}));
    for (std::size_t length = 0; length < valid.size(); ++length) {
        const std::vector<std::uint8_t> truncated(valid.begin(), valid.begin() + length);
        require(!parseAvcConfiguration(truncated, 64).valid);
    }
    require(!parseAvcConfiguration(valid, 13).valid);
    auto changed = valid;
    changed[4] = 0xfe;
    require(!parseAvcConfiguration(changed, 64).valid);
    changed[4] = 0xfc;
    require(parseAvcConfiguration(changed, 64).lengthBytes == 1);
    changed[4] = 0xfd;
    require(parseAvcConfiguration(changed, 64).lengthBytes == 2);
    changed[5] = 0xe0;
    require(!parseAvcConfiguration(changed, 64).valid);
    changed = valid;
    changed[8] = 0xe7;
    require(!parseAvcConfiguration(changed, 64).valid);
    changed = valid;
    changed.push_back(0);
    require(!parseAvcConfiguration(changed, 64).valid);
    std::mt19937 generator(74129);
    for (std::size_t iteration = 0; iteration < 10000; ++iteration) {
        std::vector<std::uint8_t> data(generator() % 128);
        for (auto& value : data) {
            value = static_cast<std::uint8_t>(generator());
        }
        const auto before = data;
        const auto parsed = parseAvcConfiguration(data, 256);
        require(data == before);
        require(parsed.sequenceParameters.size() + parsed.pictureParameters.size() <= 256);
        require(parsed.valid || (parsed.sequenceParameters.empty() && parsed.pictureParameters.empty()));
    }
    std::cout << "AVC configuration fixtures, truncations and 10000 seeded cases passed\n";
}

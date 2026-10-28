#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace carplay_experiment {

struct AvcConfiguration {
    bool valid = false;
    std::uint8_t lengthBytes = 0;
    std::vector<std::uint8_t> sequenceParameters;
    std::vector<std::uint8_t> pictureParameters;
};

AvcConfiguration parseAvcConfiguration(const std::vector<std::uint8_t>& bytes,
                                      std::size_t maximumBytes);

}

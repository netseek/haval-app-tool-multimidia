#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace carplay_experiment {

enum class PacketStatus {
    ready,
    invalid_length_width,
    empty_packet,
    truncated_length,
    empty_nal,
    truncated_nal,
    output_limit
};

struct PacketConversion {
    PacketStatus status;
    std::vector<std::uint8_t> bytes;
};

PacketConversion convertLengthPrefixedPacket(
    const std::vector<std::uint8_t>& packet,
    std::uint8_t lengthBytes,
    std::size_t maximumOutputBytes);

}

#include "packet_conversion.h"

namespace carplay_experiment {

static std::uint32_t readLength(
    const std::vector<std::uint8_t>& packet,
    std::size_t offset,
    std::uint8_t lengthBytes) {
    std::uint32_t length = 0;
    for (std::uint8_t index = 0; index < lengthBytes; ++index) {
        length = (length << 8) | packet[offset + index];
    }
    return length;
}

PacketConversion convertLengthPrefixedPacket(
    const std::vector<std::uint8_t>& packet,
    std::uint8_t lengthBytes,
    std::size_t maximumOutputBytes) {
    if (lengthBytes != 1 && lengthBytes != 2 && lengthBytes != 4) {
        return {PacketStatus::invalid_length_width, {}};
    }
    if (packet.empty()) {
        return {PacketStatus::empty_packet, {}};
    }
    if (packet.size() > maximumOutputBytes) {
        return {PacketStatus::output_limit, {}};
    }
    std::size_t cursor = 0;
    std::size_t outputSize = 0;
    while (cursor < packet.size()) {
        if (packet.size() - cursor < lengthBytes) {
            return {PacketStatus::truncated_length, {}};
        }
        const auto length = readLength(packet, cursor, lengthBytes);
        cursor += lengthBytes;
        if (length == 0) {
            return {PacketStatus::empty_nal, {}};
        }
        if (length > packet.size() - cursor) {
            return {PacketStatus::truncated_nal, {}};
        }
        if (maximumOutputBytes - outputSize < 4 ||
            length > maximumOutputBytes - outputSize - 4) {
            return {PacketStatus::output_limit, {}};
        }
        outputSize += 4 + static_cast<std::size_t>(length);
        cursor += length;
    }
    PacketConversion result{PacketStatus::ready, {}};
    result.bytes.reserve(outputSize);
    cursor = 0;
    while (cursor < packet.size()) {
        const auto length = readLength(packet, cursor, lengthBytes);
        cursor += lengthBytes;
        result.bytes.insert(result.bytes.end(), {0, 0, 0, 1});
        result.bytes.insert(result.bytes.end(), packet.begin() + cursor,
                            packet.begin() + cursor + length);
        cursor += length;
    }
    return result;
}

}

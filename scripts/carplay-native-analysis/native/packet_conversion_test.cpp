#include "packet_conversion.h"
#include "observed_video_abi.h"

#include <cstdlib>
#include <iostream>
#include <random>

using carplay_experiment::convertLengthPrefixedPacket;
using carplay_experiment::PacketStatus;

static void require(bool condition) {
    if (!condition) {
        std::cerr << "Packet conversion regression failed\n";
        std::exit(1);
    }
}

int main() {
    const std::vector<std::uint8_t> expected{0, 0, 0, 1, 0x65, 0x88};
    require(convertLengthPrefixedPacket({2, 0x65, 0x88}, 1, 6).bytes == expected);
    require(convertLengthPrefixedPacket({0, 2, 0x65, 0x88}, 2, 6).bytes == expected);
    require(convertLengthPrefixedPacket({0, 0, 0, 2, 0x65, 0x88}, 4, 6).bytes == expected);
    require(convertLengthPrefixedPacket({2, 0x65, 0x88}, 1, 5).status == PacketStatus::output_limit);
    require(convertLengthPrefixedPacket({}, 1, 8).status == PacketStatus::empty_packet);
    require(convertLengthPrefixedPacket({1}, 3, 8).status == PacketStatus::invalid_length_width);
    require(convertLengthPrefixedPacket({0}, 1, 8).status == PacketStatus::empty_nal);
    require(convertLengthPrefixedPacket({0}, 2, 8).status == PacketStatus::truncated_length);
    require(convertLengthPrefixedPacket({2, 0x65}, 1, 8).status == PacketStatus::truncated_nal);
    require(convertLengthPrefixedPacket({0xff, 0xff, 0xff, 0xff}, 4, 8).status == PacketStatus::truncated_nal);
    require(convertLengthPrefixedPacket({1, 0x65}, 1, 0).status == PacketStatus::output_limit);
    const std::vector<std::uint8_t> multiple{1, 0x67, 2, 0x68, 0x99};
    const std::vector<std::uint8_t> multipleExpected{0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68, 0x99};
    require(convertLengthPrefixedPacket(multiple, 1, 11).bytes == multipleExpected);
    const auto failed = convertLengthPrefixedPacket({1, 0x67, 2, 0x68}, 1, 16);
    require(failed.status == PacketStatus::truncated_nal && failed.bytes.empty());
    require(convertLengthPrefixedPacket({0, 1, 0x65, 0}, 2, 16).status == PacketStatus::truncated_length);
    std::mt19937 generator(74128);
    for (std::size_t iteration = 0; iteration < 10000; ++iteration) {
        std::vector<std::uint8_t> packet(generator() % 128);
        for (auto& value : packet) {
            value = static_cast<std::uint8_t>(generator());
        }
        const auto original = packet;
        const auto limit = static_cast<std::size_t>(generator() % 256);
        const auto result = convertLengthPrefixedPacket(packet, static_cast<std::uint8_t>(generator() % 6), limit);
        require(packet == original);
        require(result.bytes.size() <= limit);
        require(result.status == PacketStatus::ready || result.bytes.empty());
    }
    std::cout << "14 packet fixtures and 10000 seeded malformed-input cases passed\n";
}

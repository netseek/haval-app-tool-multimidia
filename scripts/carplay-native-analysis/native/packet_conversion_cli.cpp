#include "packet_conversion.h"

#include <fstream>
#include <iostream>

int main(int argumentCount, char** arguments) {
    if (argumentCount != 2) {
        std::cerr << "usage: packet-converter INPUT_WITH_FOUR_BYTE_NAL_LENGTHS\n";
        return 2;
    }
    constexpr std::size_t maximumBytes = 4 * 1024 * 1024;
    std::ifstream input(arguments[1], std::ios::binary);
    if (!input) {
        return 3;
    }
    std::vector<std::uint8_t> packet(maximumBytes + 1);
    input.read(reinterpret_cast<char*>(packet.data()), static_cast<std::streamsize>(packet.size()));
    if (input.bad() || input.gcount() > static_cast<std::streamsize>(maximumBytes)) {
        return 4;
    }
    packet.resize(static_cast<std::size_t>(input.gcount()));
    const auto result = carplay_experiment::convertLengthPrefixedPacket(packet, 4, maximumBytes);
    if (result.status != carplay_experiment::PacketStatus::ready) {
        return 5;
    }
    std::cout.write(reinterpret_cast<const char*>(result.bytes.data()),
                    static_cast<std::streamsize>(result.bytes.size()));
    return std::cout ? 0 : 6;
}

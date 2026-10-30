#include "avc_configuration.h"

namespace carplay_experiment {

AvcConfiguration parseAvcConfiguration(const std::vector<std::uint8_t>& bytes,
                                      std::size_t maximumBytes) {
    if (bytes.size() < 7 || bytes.size() > maximumBytes || bytes[0] != 1 ||
        (bytes[4] & 0xfc) != 0xfc || (bytes[5] & 0xe0) != 0xe0) {
        return {};
    }
    const auto lengthBytes = static_cast<std::uint8_t>((bytes[4] & 3) + 1);
    if (lengthBytes == 3) {
        return {};
    }
    AvcConfiguration result;
    result.lengthBytes = lengthBytes;
    std::size_t cursor = 6;
    std::size_t totalOutput = 0;
    const auto sequenceCount = static_cast<std::size_t>(bytes[5] & 31);
    if (!sequenceCount) {
        return {};
    }
    auto append = [&](std::size_t count, std::uint8_t expectedType,
                      std::vector<std::uint8_t>& output) {
        for (std::size_t index = 0; index < count; ++index) {
            if (bytes.size() - cursor < 2) {
                return false;
            }
            const auto length = (static_cast<std::size_t>(bytes[cursor]) << 8) | bytes[cursor + 1];
            cursor += 2;
            if (!length || length > bytes.size() - cursor ||
                (bytes[cursor] & 0x80) || (bytes[cursor] & 31) != expectedType ||
                maximumBytes - totalOutput < 4 || length > maximumBytes - totalOutput - 4) {
                return false;
            }
            if (expectedType == 7 && (length < 4 || bytes[cursor + 1] != bytes[1])) {
                return false;
            }
            output.insert(output.end(), {0, 0, 0, 1});
            output.insert(output.end(), bytes.begin() + cursor, bytes.begin() + cursor + length);
            totalOutput += 4 + length;
            cursor += length;
        }
        return true;
    };
    if (!append(sequenceCount, 7, result.sequenceParameters) || cursor >= bytes.size()) {
        return {};
    }
    const auto pictureCount = static_cast<std::size_t>(bytes[cursor++]);
    if (!pictureCount || sequenceCount + pictureCount > 32 ||
        !append(pictureCount, 8, result.pictureParameters) || cursor != bytes.size()) {
        return {};
    }
    result.valid = true;
    return result;
}

}

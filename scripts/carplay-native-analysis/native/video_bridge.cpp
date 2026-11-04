#include "video_bridge.h"
#include "packet_conversion.h"
#include "avc_configuration.h"

#include <algorithm>
#include <stdexcept>
#include <utility>

namespace carplay_experiment {

VideoBridge::VideoBridge(observed_abi::VideoCallbacks original, BridgeConfig config, DecoderFactory factory)
    : original_(original), config_(std::move(config)), factory_(std::move(factory)) {
    if (!original_.initialize || !original_.finalize || !original_.setWidthHeight ||
        !original_.start || !original_.stop || !original_.setAVCC || !original_.processData) {
        throw std::invalid_argument("Complete original callback table required");
    }
    if (config_.enabled && (!factory_ || config_.principal.uuid.empty() ||
        config_.secondary.uuid.empty() || config_.principal.uuid == config_.secondary.uuid ||
        config_.maximumStreams == 0 || config_.maximumStreams > 64 ||
        config_.maximumPacketBytes == 0 || config_.maximumPacketBytes > 16 * 1024 * 1024 ||
        config_.maximumConfigurationBytes < 7 || config_.maximumConfigurationBytes > 1024 * 1024 ||
        config_.maximumWidth == 0 || config_.maximumHeight == 0)) {
        throw std::invalid_argument("Explicit bounded secondary configuration required");
    }
}

VideoBridge::~VideoBridge() {
    shutdownSecondary();
}

bool VideoBridge::matches(const StreamIdentity& first, const StreamIdentity& second) const {
    return first.type == second.type && first.uuid == second.uuid;
}

std::shared_ptr<VideoBridge::Record> VideoBridge::find(Stream stream) {
    std::lock_guard<std::mutex> lock(recordsMutex_);
    const auto iterator = records_.find(stream);
    return iterator == records_.end() ? nullptr : iterator->second;
}

VideoBridge::Status VideoBridge::initialize(Stream stream, const StreamIdentity* identityBeforeCreation) {
    if (!config_.enabled) {
        return original_.initialize(stream);
    }
    if (!stream || !identityBeforeCreation ||
        (!matches(*identityBeforeCreation, config_.principal) &&
         !matches(*identityBeforeCreation, config_.secondary))) {
        return rejected;
    }
    auto record = std::make_shared<Record>();
    record->secondary = matches(*identityBeforeCreation, config_.secondary);
    record->identity = *identityBeforeCreation;
    std::unique_lock<std::mutex> recordLock(record->mutex);
    {
        std::lock_guard<std::mutex> lock(recordsMutex_);
        if (records_.size() >= config_.maximumStreams || records_.count(stream)) {
            return rejected;
        }
        for (const auto& entry : records_) {
            if (matches(entry.second->identity, record->identity)) {
                return rejected;
            }
        }
        records_.emplace(stream, record);
    }
    if (!record->secondary) {
        recordLock.unlock();
        return original_.initialize(stream);
    }
    if (!acceptingSecondary_.load()) {
        record->retired = true;
        return rejected;
    }
    try {
        record->decoder = factory_(record->identity);
    } catch (...) {
        record->retired = true;
        return decoderFailure;
    }
    if (!record->decoder) {
        record->retired = true;
        return decoderFailure;
    }
    return 0;
}

VideoBridge::Status VideoBridge::adoptPrincipal(Stream stream, const StreamIdentity& identity) {
    if (!config_.enabled || !stream || !matches(identity, config_.principal)) { return rejected; }
    auto record = std::make_shared<Record>();
    record->secondary = false;
    record->identity = identity;
    std::lock_guard<std::mutex> lock(recordsMutex_);
    if (records_.size() >= config_.maximumStreams || records_.count(stream)) { return rejected; }
    for (const auto& entry : records_) {
        if (matches(entry.second->identity, identity)) { return rejected; }
    }
    records_.emplace(stream, std::move(record));
    return 0;
}

void VideoBridge::retire(Record& record) {
    if (!record.retired) {
        record.retired = true;
        record.configured = false;
        record.started = false;
        record.avcc.clear();
        if (record.decoder) {
            record.decoder->stop();
            record.decoder.reset();
        }
    }
}

VideoBridge::Status VideoBridge::configureIfReady(Record& record) {
    if (record.retired) {
        return rejected;
    }
    if (!record.started || !record.width || !record.height || record.avcc.empty()) {
        return 0;
    }
    if (!record.configured) {
        try {
            if (!record.decoder || !record.decoder->configure(record.width, record.height, record.avcc)) {
                retire(record);
                return decoderFailure;
            }
        } catch (...) {
            retire(record);
            return decoderFailure;
        }
        record.configured = true;
    }
    return 0;
}

void VideoBridge::finalize(Stream stream) {
    if (!config_.enabled) {
        original_.finalize(stream);
        return;
    }
    auto record = find(stream);
    if (!record) {
        return;
    }
    if (record->secondary) {
        std::lock_guard<std::mutex> lock(record->mutex);
        retire(*record);
    } else {
        original_.finalize(stream);
    }
    std::lock_guard<std::mutex> lock(recordsMutex_);
    const auto iterator = records_.find(stream);
    if (iterator != records_.end() && iterator->second == record) {
        records_.erase(iterator);
    }
}

VideoBridge::Status VideoBridge::setWidthHeight(Stream stream, std::uint32_t width, std::uint32_t height) {
    auto record = config_.enabled ? find(stream) : nullptr;
    if (!config_.enabled || (record && !record->secondary)) {
        original_.setWidthHeight(stream, width, height);
        return 0;
    }
    if (!record) {
        return rejected;
    }
    std::lock_guard<std::mutex> lock(record->mutex);
    if (record->retired) {
        return rejected;
    }
    if (!width || !height || width > config_.maximumWidth || height > config_.maximumHeight ||
        (record->configured && (width != record->width || height != record->height))) {
        retire(*record);
        return invalidInput;
    }
    record->width = width;
    record->height = height;
    return configureIfReady(*record);
}

VideoBridge::Status VideoBridge::setAVCC(Stream stream, const std::uint8_t* bytes, std::size_t length) {
    auto record = config_.enabled ? find(stream) : nullptr;
    if (!config_.enabled || (record && !record->secondary)) {
        return original_.setAVCC(stream, bytes, length);
    }
    if (!record) {
        return rejected;
    }
    std::lock_guard<std::mutex> lock(record->mutex);
    if (record->retired) {
        return rejected;
    }
    if (!bytes || length < 7 || length > config_.maximumConfigurationBytes || bytes[0] != 1) {
        retire(*record);
        return invalidInput;
    }
    if (record->configured &&
        (record->avcc.size() != length || !std::equal(record->avcc.begin(), record->avcc.end(), bytes))) {
        retire(*record);
        return invalidInput;
    }
    try {
        std::vector<std::uint8_t> configuration(bytes, bytes + length);
        const auto parsed = parseAvcConfiguration(configuration, config_.maximumConfigurationBytes);
        if (!parsed.valid) {
            retire(*record);
            return invalidInput;
        }
        record->avcc = std::move(configuration);
        record->lengthBytes = parsed.lengthBytes;
    } catch (...) {
        retire(*record);
        return decoderFailure;
    }
    return configureIfReady(*record);
}

VideoBridge::Status VideoBridge::start(Stream stream, const StreamIdentity& identity,
                                     observed_abi::String originalUuid) {
    auto record = config_.enabled ? find(stream) : nullptr;
    if (!config_.enabled) {
        return original_.start(stream, identity.type, originalUuid);
    }
    if (!record) {
        return rejected;
    }
    if (!record->secondary) {
        return matches(identity, record->identity)
            ? original_.start(stream, identity.type, originalUuid) : rejected;
    }
    std::lock_guard<std::mutex> lock(record->mutex);
    if (record->retired) {
        return rejected;
    }
    if (!matches(identity, record->identity)) {
        retire(*record);
        return rejected;
    }
    record->started = true;
    return configureIfReady(*record);
}

void VideoBridge::stop(Stream stream) {
    auto record = config_.enabled ? find(stream) : nullptr;
    if (!config_.enabled || (record && !record->secondary)) {
        original_.stop(stream);
    } else if (record) {
        std::lock_guard<std::mutex> lock(record->mutex);
        retire(*record);
    }
}

VideoBridge::Status VideoBridge::processData(Stream stream, const std::uint8_t* bytes, std::size_t length,
    std::uint64_t ticks, observed_abi::Dictionary options,
    observed_abi::Completion completion, void* completionContext) {
    auto record = config_.enabled ? find(stream) : nullptr;
    if (!config_.enabled || (record && !record->secondary)) {
        return original_.processData(stream, bytes, length, ticks, options, completion, completionContext);
    }
    if (!record) {
        return rejected;
    }
    std::lock_guard<std::mutex> lock(record->mutex);
    if (record->retired) {
        return rejected;
    }
    if (options || completion || completionContext) {
        retire(*record);
        return unsupportedCompletion;
    }
    if (!record->configured) {
        return notReady;
    }
    if (!bytes || !length || length > config_.maximumPacketBytes) {
        retire(*record);
        return invalidInput;
    }
    try {
        const std::vector<std::uint8_t> input(bytes, bytes + length);
        auto packet = convertLengthPrefixedPacket(input, record->lengthBytes, config_.maximumPacketBytes);
        if (packet.status != PacketStatus::ready) {
            retire(*record);
            return invalidInput;
        }
        const auto submitted = record->decoder->submit(packet.bytes, ticks);
        if (submitted == SecondaryDecoder::SubmitResult::Retryable) {
            // MediaCodec backpressure is transient. Keep this generation alive; the
            // secondary path may lose this packet but must not tear down the OEM stream.
            return 0;
        }
        if (submitted == SecondaryDecoder::SubmitResult::Fatal) {
            retire(*record);
            return decoderFailure;
        }
    } catch (...) {
        retire(*record);
        return decoderFailure;
    }
    return 0;
}

void VideoBridge::shutdownSecondary() {
    acceptingSecondary_.store(false);
    std::vector<std::shared_ptr<Record>> records;
    {
        std::lock_guard<std::mutex> lock(recordsMutex_);
        for (const auto& entry : records_) {
            if (entry.second->secondary) {
                records.push_back(entry.second);
            }
        }
    }
    for (const auto& record : records) {
        std::lock_guard<std::mutex> lock(record->mutex);
        retire(*record);
    }
}

}

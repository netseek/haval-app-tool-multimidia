#pragma once

#include "observed_video_abi.h"

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace carplay_experiment {

struct StreamIdentity {
    std::uint32_t type;
    std::string uuid;
};

struct BridgeConfig {
    bool enabled = false;
    StreamIdentity principal{0, {}};
    StreamIdentity secondary{0, {}};
    std::size_t maximumStreams = 8;
    std::size_t maximumPacketBytes = 4 * 1024 * 1024;
    std::size_t maximumConfigurationBytes = 64 * 1024;
    std::uint32_t maximumWidth = 4096;
    std::uint32_t maximumHeight = 2160;
};

class SecondaryDecoder {
public:
    enum class SubmitResult { Accepted, Retryable, Fatal };

    virtual ~SecondaryDecoder() = default;
    virtual bool configure(std::uint32_t width, std::uint32_t height,
                           const std::vector<std::uint8_t>& avcc) = 0;
    virtual SubmitResult submit(const std::vector<std::uint8_t>& annexB, std::uint64_t ticks) = 0;
    virtual void stop() noexcept = 0;
};

class VideoBridge {
public:
    using Stream = observed_abi::Stream;
    using Status = observed_abi::Status;
    using DecoderFactory = std::function<std::unique_ptr<SecondaryDecoder>(const StreamIdentity&)>;

    static constexpr Status rejected = -32001;
    static constexpr Status invalidInput = -32002;
    static constexpr Status notReady = -32003;
    static constexpr Status decoderFailure = -32004;
    static constexpr Status unsupportedCompletion = -32005;

    VideoBridge(observed_abi::VideoCallbacks original, BridgeConfig config, DecoderFactory factory);
    ~VideoBridge();
    VideoBridge(const VideoBridge&) = delete;
    VideoBridge& operator=(const VideoBridge&) = delete;

    Status initialize(Stream stream, const StreamIdentity* identityBeforeCreation);
    Status adoptPrincipal(Stream stream, const StreamIdentity& identity);
    void finalize(Stream stream);
    Status setWidthHeight(Stream stream, std::uint32_t width, std::uint32_t height);
    Status setAVCC(Stream stream, const std::uint8_t* bytes, std::size_t length);
    Status start(Stream stream, const StreamIdentity& identity, observed_abi::String originalUuid);
    void stop(Stream stream);
    Status processData(Stream stream, const std::uint8_t* bytes, std::size_t length,
                       std::uint64_t ticks, observed_abi::Dictionary options,
                       observed_abi::Completion completion, void* completionContext);
    void shutdownSecondary();

private:
    struct Record {
        bool secondary;
        StreamIdentity identity;
        std::mutex mutex;
        std::unique_ptr<SecondaryDecoder> decoder;
        std::vector<std::uint8_t> avcc;
        std::uint32_t width = 0;
        std::uint32_t height = 0;
        std::uint8_t lengthBytes = 0;
        bool started = false;
        bool configured = false;
        bool retired = false;
    };

    std::shared_ptr<Record> find(Stream stream);
    bool matches(const StreamIdentity& first, const StreamIdentity& second) const;
    Status configureIfReady(Record& record);
    void retire(Record& record);

    const observed_abi::VideoCallbacks original_;
    const BridgeConfig config_;
    const DecoderFactory factory_;
    std::atomic<bool> acceptingSecondary_{true};
    std::mutex recordsMutex_;
    std::unordered_map<Stream, std::shared_ptr<Record>> records_;
};

}

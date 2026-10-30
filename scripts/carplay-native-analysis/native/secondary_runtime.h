#pragma once

#include "secondary_session.h"

namespace carplay_experiment {

struct SecondaryRuntimeConfig {
    bool enabled = false;
    BridgeConfig bridge;
    SecondaryDisplayConfig display;
};

class SecondaryRuntime final {
public:
    SecondaryRuntime(observed_abi::VideoCallbacks original, OriginalStartSession originalSession,
        SecondaryRuntimeConfig config, VideoBridge::DecoderFactory factory, UuidReader uuidReader,
        std::shared_ptr<SecondarySession> session = {}, std::uint64_t generation = 0,
        DisplayAdvertisementApi displayApi = {}, std::shared_ptr<void> lifetime = {});
    ~SecondaryRuntime();
    bool enabled() const noexcept;
    SecondaryRuntime(const SecondaryRuntime&) = delete;
    SecondaryRuntime& operator=(const SecondaryRuntime&) = delete;

    observed_abi::Status advertise(DisplayArray original, SecondarySession::Publish publish, void* context);
    observed_abi::Status startSession(void* session, observed_abi::String uuid,
        std::uint32_t type, void* context);
    observed_abi::Status initialize(observed_abi::Stream stream);
    observed_abi::Status adoptPrincipal(observed_abi::Stream stream, std::uint32_t type, observed_abi::String uuid);
    void finalize(observed_abi::Stream stream);
    observed_abi::Status dimensions(observed_abi::Stream stream, std::uint32_t width, std::uint32_t height);
    observed_abi::Status start(observed_abi::Stream stream, std::uint32_t type, observed_abi::String uuid);
    void stop(observed_abi::Stream stream);
    observed_abi::Status avcc(observed_abi::Stream stream, const std::uint8_t* bytes, std::size_t length);
    observed_abi::Status data(observed_abi::Stream stream, const std::uint8_t* bytes, std::size_t length,
        std::uint64_t ticks, observed_abi::Dictionary options, observed_abi::Completion completion, void* context);
    void close();

private:
    friend class OemRuntimeAdapter;
    observed_abi::Status advertiseForGetter(DisplayArray original, SecondarySession::Publish publish, void* context);
    const SecondaryRuntimeConfig config_;
    const DisplayAdvertisementApi displayApi_;
    const std::shared_ptr<void> lifetime_;
    const std::shared_ptr<SecondarySession> session_;
    const std::uint64_t generation_;
    std::unique_ptr<OemIngress> ingress_;
    std::atomic<bool> closed_{false};
};

}

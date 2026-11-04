#pragma once

#include "video_bridge.h"

#include <optional>

namespace carplay_experiment {

using OriginalStartSession = observed_abi::Status (*)(void*, observed_abi::String, std::uint32_t, void*);
using StringLength = std::int32_t (*)(observed_abi::String);
using CopyUtf8String = std::uint8_t (*)(observed_abi::String, char*, std::int32_t, std::uint32_t);
using UuidReader = std::function<std::optional<std::string>(observed_abi::String)>;

UuidReader makeCapturedUuidReader(StringLength length, CopyUtf8String copy);

class OemIngress {
public:
    OemIngress(observed_abi::VideoCallbacks original, OriginalStartSession startSession,
               BridgeConfig config, VideoBridge::DecoderFactory factory, UuidReader uuidReader);
    observed_abi::Status startSession(void* session, observed_abi::String uuid,
                                     std::uint32_t type, void* context);
    observed_abi::Status initialize(observed_abi::Stream stream);
    observed_abi::Status adoptPrincipal(observed_abi::Stream stream, std::uint32_t type, observed_abi::String uuid);
    observed_abi::Status start(observed_abi::Stream stream, std::uint32_t type, observed_abi::String uuid);
    VideoBridge& video();

private:
    const OriginalStartSession originalSession_;
    const bool enabled_;
    const UuidReader reader_;
    const StreamIdentity principal_;
    const StreamIdentity secondary_;
    VideoBridge bridge_;
};

bool pinIngressForProcess(std::unique_ptr<OemIngress> ingress);
namespace detail {
bool claimProcessBridge() noexcept;
}
observed_abi::VideoCallbacks ingressCallbacks();

}

extern "C" {
carplay_experiment::observed_abi::Status impulse_carplay_install_passthrough(
    const carplay_experiment::observed_abi::VideoCallbacks* original,
    carplay_experiment::OriginalStartSession session,
    carplay_experiment::StringLength length,
    carplay_experiment::CopyUtf8String copy) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_start_session(
    void* session, carplay_experiment::observed_abi::String uuid,
    std::uint32_t type, void* context) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_stream_initialize(
    carplay_experiment::observed_abi::Stream stream) noexcept;
void impulse_carplay_stream_finalize(carplay_experiment::observed_abi::Stream stream) noexcept;
void impulse_carplay_stream_dimensions(const void* stream, std::uint32_t width, std::uint32_t height) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_stream_start(
    carplay_experiment::observed_abi::Stream stream, std::uint32_t type,
    carplay_experiment::observed_abi::String uuid) noexcept;
void impulse_carplay_stream_stop(carplay_experiment::observed_abi::Stream stream) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_stream_avcc(
    const void* stream, const std::uint8_t* bytes, std::size_t length) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_stream_data(
    carplay_experiment::observed_abi::Stream stream, const std::uint8_t* bytes,
    std::size_t length, std::uint64_t ticks, carplay_experiment::observed_abi::Dictionary options,
    carplay_experiment::observed_abi::Completion completion, void* context) noexcept;
void impulse_carplay_shutdown_secondary() noexcept;
std::uint64_t impulse_carplay_boundary_faults() noexcept;
}

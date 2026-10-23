#pragma once

#include "live_session_capture.h"

namespace carplay_experiment {

class OemEntryBridge final {
public:
    OemEntryBridge(DispatchTargets originals, std::string principalUuid, UuidReader reader,
        LiveSessionCapture::RetainUuid retain, std::shared_ptr<void> nativeOwners);
    const std::shared_ptr<CoordinatedDispatch>& dispatch() const noexcept;
    const std::shared_ptr<LiveSessionCapture>& capture() const noexcept;
    const DispatchTargets& originals() const noexcept;
    const UuidReader& uuidReader() const noexcept;
    const std::shared_ptr<void>& nativeOwners() const noexcept;
    std::shared_ptr<PreparedHooks> prepare(DispatchPreparedHooks::AdapterFactory factory) const;
    void fault() noexcept;
    std::uint64_t faults() const noexcept;
private:
    const DispatchTargets originals_;
    const UuidReader reader_;
    const std::shared_ptr<void> nativeOwners_;
    const std::shared_ptr<LiveSessionCapture> capture_;
    const std::shared_ptr<CoordinatedDispatch> dispatch_;
    std::atomic<std::uint64_t> faults_{0};
};

bool pinOemEntryBridge(std::unique_ptr<OemEntryBridge> bridge, bool explicitlyEnabled = false);
std::shared_ptr<PreparedHooks> preparePinnedOemEntryBridge(
    DispatchPreparedHooks::AdapterFactory factory);
OemEntryBridge* pinnedOemEntryBridge() noexcept;
observed_abi::VideoCallbacks oemEntryCallbacks();
bool oemEntryBridgePinned() noexcept;
std::uint64_t oemEntryPrincipalEpoch() noexcept;

}

extern "C" {
carplay_experiment::observed_abi::Status impulse_carplay_entry_initialize(carplay_experiment::observed_abi::Stream stream) noexcept;
void impulse_carplay_entry_finalize(carplay_experiment::observed_abi::Stream stream) noexcept;
void impulse_carplay_entry_dimensions(const void* stream, std::uint32_t width, std::uint32_t height) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_entry_start(carplay_experiment::observed_abi::Stream stream,
    std::uint32_t type, carplay_experiment::observed_abi::String uuid) noexcept;
void impulse_carplay_entry_stop(carplay_experiment::observed_abi::Stream stream) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_entry_avcc(const void* stream,
    const std::uint8_t* bytes, std::size_t length) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_entry_data(carplay_experiment::observed_abi::Stream stream,
    const std::uint8_t* bytes, std::size_t length, std::uint64_t ticks, carplay_experiment::observed_abi::Dictionary options,
    carplay_experiment::observed_abi::Completion completion, void* context) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_entry_session(void* session,
    carplay_experiment::observed_abi::String uuid, std::uint32_t type, void* context) noexcept;
carplay_experiment::DisplayArray impulse_carplay_entry_displays(carplay_experiment::observed_abi::Status* error) noexcept;
void impulse_carplay_entry_invalidate_session() noexcept;
std::uint64_t impulse_carplay_entry_principal_epoch() noexcept;
}

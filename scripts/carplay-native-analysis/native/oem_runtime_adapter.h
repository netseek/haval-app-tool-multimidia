#pragma once

#include "secondary_runtime.h"

namespace carplay_experiment {

using OriginalGetDisplays = DisplayArray (*)(observed_abi::Status*);
using ReleaseOwnedArray = void (*)(DisplayArray) noexcept;

class OemRuntimeAdapter final {
public:
    OemRuntimeAdapter(std::unique_ptr<SecondaryRuntime> runtime, OriginalGetDisplays original,
        ReleaseOwnedArray release = nullptr, bool originalReturnsOwned = false);
    DisplayArray getDisplays(observed_abi::Status* error) noexcept;
    SecondaryRuntime& runtime() noexcept;
    void fault() noexcept;
    std::uint64_t faults() const noexcept;
    observed_abi::Status advertisementStatus() const noexcept;

private:
    friend class HookInstaller;
    std::shared_ptr<void> installationLifetime_;
    const std::unique_ptr<SecondaryRuntime> runtime_;
    const OriginalGetDisplays original_;
    const ReleaseOwnedArray release_;
    std::atomic<std::uint64_t> faults_{0};
    std::atomic<observed_abi::Status> advertisementStatus_{0};
};

bool pinRuntimeAdapterForProcess(std::unique_ptr<OemRuntimeAdapter> adapter);
observed_abi::VideoCallbacks runtimeVideoCallbacks();

}

extern "C" {
carplay_experiment::observed_abi::Status impulse_carplay_runtime_adopt_principal(
    carplay_experiment::observed_abi::Stream stream, std::uint32_t type,
    carplay_experiment::observed_abi::String uuid) noexcept;
carplay_experiment::DisplayArray impulse_carplay_runtime_displays(
    carplay_experiment::observed_abi::Status* error) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_runtime_session(
    void* session, carplay_experiment::observed_abi::String uuid, std::uint32_t type, void* context) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_runtime_initialize(
    carplay_experiment::observed_abi::Stream stream) noexcept;
void impulse_carplay_runtime_finalize(carplay_experiment::observed_abi::Stream stream) noexcept;
void impulse_carplay_runtime_dimensions(const void* stream, std::uint32_t width, std::uint32_t height) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_runtime_start(
    carplay_experiment::observed_abi::Stream stream, std::uint32_t type,
    carplay_experiment::observed_abi::String uuid) noexcept;
void impulse_carplay_runtime_stop(carplay_experiment::observed_abi::Stream stream) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_runtime_avcc(
    const void* stream, const std::uint8_t* bytes, std::size_t length) noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_runtime_data(
    carplay_experiment::observed_abi::Stream stream, const std::uint8_t* bytes,
    std::size_t length, std::uint64_t ticks, carplay_experiment::observed_abi::Dictionary options,
    carplay_experiment::observed_abi::Completion completion, void* context) noexcept;
void impulse_carplay_runtime_close() noexcept;
std::uint64_t impulse_carplay_runtime_faults() noexcept;
carplay_experiment::observed_abi::Status impulse_carplay_runtime_advertisement_status() noexcept;
}

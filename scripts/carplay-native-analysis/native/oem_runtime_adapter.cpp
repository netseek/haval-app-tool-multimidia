#include "oem_runtime_adapter.h"

#include <stdexcept>
#include <utility>

namespace carplay_experiment {
namespace {
std::atomic<OemRuntimeAdapter*> installed{nullptr};

struct StagedArray {
    DisplayArray value = nullptr;
    ReleaseOwnedArray release;
    ~StagedArray() { if (value) { release(value); } }
    static void publish(DisplayArray array, void* context) noexcept {
        static_cast<StagedArray*>(context)->value = array;
    }
};

template<typename Action>
observed_abi::Status guarded(Action&& action) noexcept {
    auto* adapter = installed.load();
    if (!adapter) { return VideoBridge::notReady; }
    try { return action(adapter->runtime()); }
    catch (...) { adapter->fault(); return VideoBridge::decoderFailure; }
}
}

OemRuntimeAdapter::OemRuntimeAdapter(std::unique_ptr<SecondaryRuntime> runtime,
    OriginalGetDisplays original, ReleaseOwnedArray release, bool originalReturnsOwned)
    : runtime_(std::move(runtime)), original_(original), release_(release) {
    if (!runtime_ || !original_ || (runtime_->enabled() && (!release_ || !originalReturnsOwned))) {
        throw std::invalid_argument("Runtime, original getter and explicit enabled-mode ownership required");
    }
}

DisplayArray OemRuntimeAdapter::getDisplays(observed_abi::Status* error) noexcept {
    if (!error) { fault(); return nullptr; }
    DisplayArray primary = nullptr;
    bool returned = false;
    try {
        primary = original_(error);
        returned = true;
        if (!runtime_->enabled() || !primary || *error != 0) { return primary; }
        StagedArray staged{nullptr, release_};
        advertisementStatus_.store(runtime_->advertiseForGetter(primary, StagedArray::publish, &staged));
        if (!staged.value) { return primary; }
        release_(primary);
        return std::exchange(staged.value, nullptr);
    } catch (...) {
        fault();
        if (!returned) { *error = VideoBridge::decoderFailure; }
        return primary;
    }
}

SecondaryRuntime& OemRuntimeAdapter::runtime() noexcept { return *runtime_; }
void OemRuntimeAdapter::fault() noexcept {
    faults_.fetch_add(1);
    try { runtime_->close(); } catch (...) {}
}
std::uint64_t OemRuntimeAdapter::faults() const noexcept { return faults_.load(); }
observed_abi::Status OemRuntimeAdapter::advertisementStatus() const noexcept { return advertisementStatus_.load(); }

bool pinRuntimeAdapterForProcess(std::unique_ptr<OemRuntimeAdapter> adapter) {
    if (!adapter || !detail::claimProcessBridge()) { return false; }
    installed.store(adapter.release());
    return true;
}

observed_abi::VideoCallbacks runtimeVideoCallbacks() {
    return {impulse_carplay_runtime_initialize, impulse_carplay_runtime_finalize,
        impulse_carplay_runtime_dimensions, impulse_carplay_runtime_start,
        impulse_carplay_runtime_stop, impulse_carplay_runtime_avcc, impulse_carplay_runtime_data};
}
}

using namespace carplay_experiment;

extern "C" observed_abi::Status impulse_carplay_runtime_adopt_principal(observed_abi::Stream stream,
    std::uint32_t type, observed_abi::String uuid) noexcept {
    return guarded([&](SecondaryRuntime& runtime) { return runtime.adoptPrincipal(stream, type, uuid); });
}

extern "C" DisplayArray impulse_carplay_runtime_displays(observed_abi::Status* error) noexcept {
    auto* adapter = installed.load();
    if (!adapter) {
        if (error) { *error = VideoBridge::notReady; }
        return nullptr;
    }
    return adapter->getDisplays(error);
}
extern "C" observed_abi::Status impulse_carplay_runtime_session(void* session, observed_abi::String uuid,
    std::uint32_t type, void* context) noexcept {
    return guarded([&](SecondaryRuntime& runtime) { return runtime.startSession(session, uuid, type, context); });
}
extern "C" observed_abi::Status impulse_carplay_runtime_initialize(observed_abi::Stream stream) noexcept {
    return guarded([&](SecondaryRuntime& runtime) { return runtime.initialize(stream); });
}
extern "C" void impulse_carplay_runtime_finalize(observed_abi::Stream stream) noexcept {
    guarded([&](SecondaryRuntime& runtime) { runtime.finalize(stream); return 0; });
}
extern "C" void impulse_carplay_runtime_dimensions(const void* stream,
    std::uint32_t width, std::uint32_t height) noexcept {
    guarded([&](SecondaryRuntime& runtime) {
        runtime.dimensions(static_cast<observed_abi::Stream>(const_cast<void*>(stream)), width, height);
        return 0;
    });
}
extern "C" observed_abi::Status impulse_carplay_runtime_start(observed_abi::Stream stream,
    std::uint32_t type, observed_abi::String uuid) noexcept {
    return guarded([&](SecondaryRuntime& runtime) { return runtime.start(stream, type, uuid); });
}
extern "C" void impulse_carplay_runtime_stop(observed_abi::Stream stream) noexcept {
    guarded([&](SecondaryRuntime& runtime) { runtime.stop(stream); return 0; });
}
extern "C" observed_abi::Status impulse_carplay_runtime_avcc(const void* stream,
    const std::uint8_t* bytes, std::size_t length) noexcept {
    return guarded([&](SecondaryRuntime& runtime) {
        return runtime.avcc(static_cast<observed_abi::Stream>(const_cast<void*>(stream)), bytes, length);
    });
}
extern "C" observed_abi::Status impulse_carplay_runtime_data(observed_abi::Stream stream,
    const std::uint8_t* bytes, std::size_t length, std::uint64_t ticks, observed_abi::Dictionary options,
    observed_abi::Completion completion, void* context) noexcept {
    return guarded([&](SecondaryRuntime& runtime) {
        return runtime.data(stream, bytes, length, ticks, options, completion, context);
    });
}
extern "C" void impulse_carplay_runtime_close() noexcept {
    guarded([](SecondaryRuntime& runtime) { runtime.close(); return 0; });
}
extern "C" std::uint64_t impulse_carplay_runtime_faults() noexcept {
    auto* adapter = installed.load();
    return adapter ? adapter->faults() : 0;
}
extern "C" observed_abi::Status impulse_carplay_runtime_advertisement_status() noexcept {
    auto* adapter = installed.load();
    return adapter ? adapter->advertisementStatus() : VideoBridge::notReady;
}

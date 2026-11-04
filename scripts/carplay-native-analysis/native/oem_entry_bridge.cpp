#include "oem_entry_bridge.h"

#include <stdexcept>
#include <utility>

namespace carplay_experiment {
namespace {
std::atomic<OemEntryBridge*> entryBridge{nullptr};
template<typename Action>
observed_abi::Status entry(Action&& action) noexcept {
    auto* bridge = entryBridge.load();
    if (!bridge) { return VideoBridge::notReady; }
    try { return action(*bridge->dispatch()); }
    catch (...) { bridge->fault(); return VideoBridge::decoderFailure; }
}
}

OemEntryBridge::OemEntryBridge(DispatchTargets originals, std::string principalUuid, UuidReader reader,
    LiveSessionCapture::RetainUuid retain, std::shared_ptr<void> nativeOwners)
    : originals_(originals), reader_(std::move(reader)), nativeOwners_(std::move(nativeOwners)),
      capture_(std::make_shared<LiveSessionCapture>(std::move(principalUuid), reader_, std::move(retain))),
      dispatch_(std::make_shared<CoordinatedDispatch>(originals_, capture_)) {
    if (!nativeOwners_) { throw std::invalid_argument("Pinned native module ownership required"); }
}
const std::shared_ptr<CoordinatedDispatch>& OemEntryBridge::dispatch() const noexcept { return dispatch_; }
const std::shared_ptr<LiveSessionCapture>& OemEntryBridge::capture() const noexcept { return capture_; }
const DispatchTargets& OemEntryBridge::originals() const noexcept { return originals_; }
const UuidReader& OemEntryBridge::uuidReader() const noexcept { return reader_; }
const std::shared_ptr<void>& OemEntryBridge::nativeOwners() const noexcept { return nativeOwners_; }
std::shared_ptr<PreparedHooks> OemEntryBridge::prepare(
    DispatchPreparedHooks::AdapterFactory factory) const {
    if (!factory) { return {}; }
    return std::make_shared<DispatchPreparedHooks>(dispatch_, std::move(factory),
        [capture = capture_] { return capture->capture(); },
        [capture = capture_](const LivePrincipal& principal) { return capture->isCurrent(principal); },
        nativeOwners_);
}
void OemEntryBridge::fault() noexcept {
    faults_.fetch_add(1);
    try {
        dispatch_->sessionBoundary([&] { capture_->invalidate(); impulse_carplay_runtime_close(); });
    } catch (...) {}
}
std::uint64_t OemEntryBridge::faults() const noexcept { return faults_.load(); }

bool pinOemEntryBridge(std::unique_ptr<OemEntryBridge> bridge, bool explicitlyEnabled) {
    if (!explicitlyEnabled || !bridge) { return false; }
    OemEntryBridge* expected = nullptr;
    if (!entryBridge.compare_exchange_strong(expected, bridge.get())) { return false; }
    bridge.release();
    return true;
}
std::shared_ptr<PreparedHooks> preparePinnedOemEntryBridge(
    DispatchPreparedHooks::AdapterFactory factory) {
    auto* bridge = entryBridge.load();
    return bridge ? bridge->prepare(std::move(factory)) : std::shared_ptr<PreparedHooks>{};
}
OemEntryBridge* pinnedOemEntryBridge() noexcept { return entryBridge.load(); }
observed_abi::VideoCallbacks oemEntryCallbacks() {
    return {impulse_carplay_entry_initialize, impulse_carplay_entry_finalize, impulse_carplay_entry_dimensions,
        impulse_carplay_entry_start, impulse_carplay_entry_stop, impulse_carplay_entry_avcc, impulse_carplay_entry_data};
}
bool oemEntryBridgePinned() noexcept { return entryBridge.load() != nullptr; }
std::uint64_t oemEntryPrincipalEpoch() noexcept {
    auto* bridge = entryBridge.load();
    if (!bridge) { return 0; }
    try { return bridge->capture()->capture().epoch; }
    catch (...) { return 0; }
}
}

using namespace carplay_experiment;
extern "C" observed_abi::Status impulse_carplay_entry_initialize(observed_abi::Stream stream) noexcept {
    return entry([&](CoordinatedDispatch& dispatch) { return dispatch.initialize(stream); });
}
extern "C" void impulse_carplay_entry_finalize(observed_abi::Stream stream) noexcept {
    entry([&](CoordinatedDispatch& dispatch) { dispatch.finalize(stream); return 0; });
}
extern "C" void impulse_carplay_entry_dimensions(const void* stream, std::uint32_t width, std::uint32_t height) noexcept {
    entry([&](CoordinatedDispatch& dispatch) { dispatch.dimensions(stream, width, height); return 0; });
}
extern "C" observed_abi::Status impulse_carplay_entry_start(observed_abi::Stream stream,
    std::uint32_t type, observed_abi::String uuid) noexcept {
    return entry([&](CoordinatedDispatch& dispatch) { return dispatch.start(stream, type, uuid); });
}
extern "C" void impulse_carplay_entry_stop(observed_abi::Stream stream) noexcept {
    entry([&](CoordinatedDispatch& dispatch) { dispatch.stop(stream); return 0; });
}
extern "C" observed_abi::Status impulse_carplay_entry_avcc(const void* stream,
    const std::uint8_t* bytes, std::size_t length) noexcept {
    return entry([&](CoordinatedDispatch& dispatch) { return dispatch.avcc(stream, bytes, length); });
}
extern "C" observed_abi::Status impulse_carplay_entry_data(observed_abi::Stream stream,
    const std::uint8_t* bytes, std::size_t length, std::uint64_t ticks, observed_abi::Dictionary options,
    observed_abi::Completion completion, void* context) noexcept {
    return entry([&](CoordinatedDispatch& dispatch) {
        return dispatch.data(stream, bytes, length, ticks, options, completion, context);
    });
}
extern "C" observed_abi::Status impulse_carplay_entry_session(void* session, observed_abi::String uuid,
    std::uint32_t type, void* context) noexcept {
    return entry([&](CoordinatedDispatch& dispatch) { return dispatch.session(session, uuid, type, context); });
}
extern "C" DisplayArray impulse_carplay_entry_displays(observed_abi::Status* error) noexcept {
    auto* bridge = entryBridge.load();
    if (!bridge) { if (error) { *error = VideoBridge::notReady; } return nullptr; }
    try { return bridge->dispatch()->displays(error); }
    catch (...) { bridge->fault(); if (error) { *error = VideoBridge::decoderFailure; } return nullptr; }
}
extern "C" void impulse_carplay_entry_invalidate_session() noexcept {
    auto* bridge = entryBridge.load();
    if (bridge) {
        entry([&](CoordinatedDispatch& dispatch) {
            dispatch.sessionBoundary([&] { bridge->capture()->invalidate(); impulse_carplay_runtime_close(); });
            return 0;
        });
    }
}
extern "C" std::uint64_t impulse_carplay_entry_principal_epoch() noexcept {
    return oemEntryPrincipalEpoch();
}

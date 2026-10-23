#include "initial_entry_bootstrap.h"

#include <dlfcn.h>
#include <array>
#include <stdexcept>
#include <utility>

extern "C" std::uint32_t impulse_carplay_initial_bootstrap_abi() noexcept { return 1; }
extern "C" std::uint32_t impulse_carplay_initial_bootstrap_state() noexcept {
    return carplay_experiment::oemEntryBridgePinned() ? 1 : 0;
}

namespace carplay_experiment {
namespace {
class NativeModulePins final {
public:
    NativeModulePins() {
        try {
            const std::array<const char*, 3> paths{
                "/vendor/app/TsCarPlayService/lib/arm64/libcarplaystack.so",
                "/vendor/app/TsCarPlayService/lib/arm64/libAirPlay_ts.so",
                "/vendor/app/TsCarPlayService/lib/arm64/libCoreUtils_ts.so"};
            for (std::size_t index = 0; index < paths.size(); ++index) {
                handles_[index] = dlopen(paths[index], RTLD_NOW | RTLD_NOLOAD);
                if (!handles_[index]) { throw std::runtime_error("Attested OEM module no longer loaded"); }
            }
            Dl_info self{};
            if (!dladdr(reinterpret_cast<const void*>(impulse_carplay_bootstrap_initial_entries), &self) || !self.dli_fname) {
                throw std::runtime_error("Bridge image ownership missing");
            }
            handles_[3] = dlopen(self.dli_fname, RTLD_NOW | RTLD_NOLOAD);
            if (!handles_[3]) { throw std::runtime_error("Bridge image cannot be pinned"); }
        } catch (...) { close(); throw; }
    }
    ~NativeModulePins() { close(); }
    bool stringApis(StringLength length, CopyUtf8String copy, RetainCfObject retain, ReleaseCfObject release) const {
        return reinterpret_cast<void*>(length) == dlsym(handles_[2], "CFStringGetLength") &&
            reinterpret_cast<void*>(copy) == dlsym(handles_[2], "CFStringGetCString") &&
            reinterpret_cast<void*>(retain) == dlsym(handles_[2], "CFRetain") &&
            reinterpret_cast<void*>(release) == dlsym(handles_[2], "CFRelease");
    }
private:
    std::array<void*, 4> handles_{};
    void close() noexcept {
        for (auto& handle : handles_) { if (handle) { dlclose(handle); handle = nullptr; } }
    }
};
std::mutex preparationMutex;
std::shared_ptr<NativeModulePins> preparedPins;
bool bootstrapping = false;

class BootstrapStage final {
public:
    BootstrapStage() {
        std::lock_guard<std::mutex> lock(preparationMutex);
        if (!preparedPins || bootstrapping || oemEntryBridgePinned()) { return; }
        owners = preparedPins;
        bootstrapping = true;
    }
    ~BootstrapStage() {
        if (!owners) { return; }
        std::lock_guard<std::mutex> lock(preparationMutex);
        bootstrapping = false;
        if (oemEntryBridgePinned()) { preparedPins.reset(); }
    }
    std::shared_ptr<NativeModulePins> owners;
};
}
}

extern "C" carplay_experiment::observed_abi::Status impulse_carplay_prepare_initial_entries() noexcept {
    using namespace carplay_experiment;
    try {
        std::lock_guard<std::mutex> lock(preparationMutex);
        if (preparedPins || bootstrapping || oemEntryBridgePinned()) { return VideoBridge::rejected; }
        preparedPins = std::make_shared<NativeModulePins>();
        return 0;
    } catch (...) { return VideoBridge::decoderFailure; }
}

extern "C" carplay_experiment::observed_abi::Status impulse_carplay_release_initial_preparation() noexcept {
    using namespace carplay_experiment;
    try {
        std::shared_ptr<NativeModulePins> released;
        {
            std::lock_guard<std::mutex> lock(preparationMutex);
            if (bootstrapping || oemEntryBridgePinned()) { return VideoBridge::rejected; }
            released = std::move(preparedPins);
        }
        return 0;
    } catch (...) { return VideoBridge::decoderFailure; }
}

extern "C" carplay_experiment::observed_abi::Status impulse_carplay_bootstrap_initial_entries(
    const carplay_experiment::observed_abi::VideoCallbacks* original,
    carplay_experiment::OriginalStartSession session, carplay_experiment::OriginalGetDisplays displays,
    carplay_experiment::StringLength length, carplay_experiment::CopyUtf8String copy,
    carplay_experiment::RetainCfObject retain, carplay_experiment::ReleaseCfObject release,
    const char* principalUuid, std::size_t uuidLength) noexcept {
    using namespace carplay_experiment;
    if (!original || !session || !displays || !length || !copy || !retain || !release ||
        !principalUuid || !uuidLength || uuidLength > 128) { return VideoBridge::rejected; }
    for (std::size_t index = 0; index < uuidLength; ++index) {
        const auto value = static_cast<unsigned char>(principalUuid[index]);
        if (!((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
              (value >= '0' && value <= '9') || value == '.' || value == '_' || value == ':' || value == '-')) {
            return VideoBridge::rejected;
        }
    }
    try {
        BootstrapStage stage;
        const auto& owners = stage.owners;
        if (!owners) { return VideoBridge::notReady; }
        if (!owners->stringApis(length, copy, retain, release)) { return VideoBridge::rejected; }
        auto bridge = std::make_unique<OemEntryBridge>(DispatchTargets{*original, session, displays},
            std::string(principalUuid, uuidLength), makeCapturedUuidReader(length, copy),
            makeCapturedUuidOwner(retain, release, owners), owners);
        return pinOemEntryBridge(std::move(bridge), true) ? 0 : VideoBridge::rejected;
    } catch (...) { return VideoBridge::decoderFailure; }
}

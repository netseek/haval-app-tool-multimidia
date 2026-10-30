#include "initial_entry_bootstrap.h"

#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <stdexcept>
#include <utility>

extern "C" std::uint32_t impulse_carplay_initial_bootstrap_abi() noexcept { return 1; }
extern "C" std::uint32_t impulse_carplay_initial_bootstrap_state() noexcept {
    return carplay_experiment::oemEntryBridgePinned() ? 1 : 0;
}

namespace carplay_experiment {
namespace {
bool isSystemLoaderAddress(const void* address) {
    // Android 9 dladdr does not describe the linker itself. Its executable
    // mapping still provides the same exact-path/range check used for OEM code.
    std::ifstream maps("/proc/self/maps");
    std::string line;
    const auto value = reinterpret_cast<std::uintptr_t>(address);
    while (std::getline(maps, line)) {
        unsigned long long start = 0, end = 0;
        char permissions[5]{};
        char path[4096]{};
        if (std::sscanf(line.c_str(), "%llx-%llx %4s %*s %*s %*s %4095[^\n]",
            &start, &end, permissions, path) == 4 && value >= start && value < end &&
            permissions[0] == 'r' && permissions[1] == '-' && permissions[2] == 'x' &&
            std::strcmp(path, "/system/bin/linker64") == 0) { return true; }
    }
    return false;
}

class NativeModulePins final {
public:
    explicit NativeModulePins(const void* attestedOemCaller = nullptr) {
        try {
            const std::array<const char*, 3> paths{
                "/vendor/app/TsCarPlayService/lib/arm64/libcarplaystack.so",
                "/vendor/app/TsCarPlayService/lib/arm64/libAirPlay_ts.so",
                "/vendor/app/TsCarPlayService/lib/arm64/libCoreUtils_ts.so"};
            using OpenInCallerNamespace = void* (*)(const char*, int, const void*);
            OpenInCallerNamespace openInCallerNamespace = nullptr;
            if (attestedOemCaller) {
                Dl_info caller{};
                if (!dladdr(attestedOemCaller, &caller) || !caller.dli_fname ||
                    std::none_of(paths.begin(), paths.end(), [&](const char* path) {
                        return std::strcmp(caller.dli_fname, path) == 0;
                    })) {
                    throw std::invalid_argument("Caller is not in an attested OEM image");
                }
                // Android 9 libdl passes its return address to this loader API.
                // A bridge loaded by Frida belongs to a different namespace.
                // Use an already attested OEM caller solely for NOLOAD pins.
                auto* loader = dlsym(RTLD_DEFAULT, "__loader_dlopen");
                if (!loader || !isSystemLoaderAddress(loader)) {
                    throw std::runtime_error("Reviewed Android caller-aware loader unavailable");
                }
                openInCallerNamespace = reinterpret_cast<OpenInCallerNamespace>(loader);
            }
            for (std::size_t index = 0; index < paths.size(); ++index) {
                handles_[index] = openInCallerNamespace
                    ? openInCallerNamespace(paths[index], RTLD_NOW | RTLD_NOLOAD, attestedOemCaller)
                    : dlopen(paths[index], RTLD_NOW | RTLD_NOLOAD);
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
    void* symbol(const char* module, const char* name) const {
        if (!module || !name) { return nullptr; }
        if (std::strcmp(module, "libAirPlay_ts.so") == 0) { return dlsym(handles_[1], name); }
        if (std::strcmp(module, "libCoreUtils_ts.so") == 0) { return dlsym(handles_[2], name); }
        return nullptr;
    }
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
std::weak_ptr<NativeModulePins> preparedOrPinnedModules;
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

namespace {
carplay_experiment::observed_abi::Status prepareInitialEntries(const void* attestedOemCaller) noexcept {
    using namespace carplay_experiment;
    try {
        std::lock_guard<std::mutex> lock(preparationMutex);
        if (preparedPins || bootstrapping || oemEntryBridgePinned()) { return VideoBridge::rejected; }
        preparedPins = std::make_shared<NativeModulePins>(attestedOemCaller);
        preparedOrPinnedModules = preparedPins;
        return 0;
    } catch (...) { return VideoBridge::decoderFailure; }
}
}

namespace carplay_experiment {
void* pinnedInitialModuleSymbol(const char* module, const char* name) noexcept {
    try {
        if (!oemEntryBridgePinned()) { return nullptr; }
        std::shared_ptr<NativeModulePins> pins;
        {
            std::lock_guard<std::mutex> lock(preparationMutex);
            pins = preparedOrPinnedModules.lock();
        }
        return pins ? pins->symbol(module, name) : nullptr;
    } catch (...) { return nullptr; }
}
}

extern "C" carplay_experiment::observed_abi::Status impulse_carplay_prepare_initial_entries() noexcept {
    return prepareInitialEntries(nullptr);
}

extern "C" carplay_experiment::observed_abi::Status impulse_carplay_prepare_initial_entries_for_oem_namespace(
    const void* attestedOemCaller) noexcept {
    if (!attestedOemCaller) { return carplay_experiment::VideoBridge::rejected; }
    return prepareInitialEntries(attestedOemCaller);
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

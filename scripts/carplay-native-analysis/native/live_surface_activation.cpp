#include "live_surface_activation.h"

#include "hook_permit.h"
#include "surface_runtime_preparation.h"

#include <dlfcn.h>
#include <time.h>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <utility>

namespace carplay_experiment {
namespace {
using CreateArrayCopy = observed_abi::Status (*)(void*, DisplayArray, DisplayArray*);
using CreateString = observed_abi::String (*)(void*, const char*, std::uint32_t);

class LiveDisplayApis final {
public:
    LiveDisplayApis() {
        airplay_ = dlopen("/vendor/app/TsCarPlayService/lib/arm64/libAirPlay_ts.so", RTLD_NOW | RTLD_NOLOAD);
        core_ = dlopen("/vendor/app/TsCarPlayService/lib/arm64/libCoreUtils_ts.so", RTLD_NOW | RTLD_NOLOAD);
        if (!airplay_ || !core_) { throw std::runtime_error("Attested OEM display modules unavailable"); }
        copy = reinterpret_cast<CreateArrayCopy>(dlsym(core_, "CFLArrayCreateCopy"));
        release = reinterpret_cast<ReleaseCfObject>(dlsym(core_, "CFRelease"));
        createString = reinterpret_cast<CreateString>(dlsym(core_, "CFStringCreateWithCString"));
        append = reinterpret_cast<AddAlternateDisplay>(
            dlsym(airplay_, "AirPlayInfoArrayAddAltScreenDisplay"));
        if (!copy || !release || !createString || !append) {
            throw std::runtime_error("Exact OEM display APIs unavailable");
        }
    }
    ~LiveDisplayApis() {
        if (core_) { dlclose(core_); }
        if (airplay_) { dlclose(airplay_); }
    }
    CreateArrayCopy copy = nullptr;
    ReleaseCfObject release = nullptr;
    CreateString createString = nullptr;
    AddAlternateDisplay append = nullptr;
private:
    void* airplay_ = nullptr;
    void* core_ = nullptr;
};

std::atomic<LiveDisplayApis*> activeApis{nullptr};
std::mutex activeApiMutex;
std::mutex activationMutex;
std::shared_ptr<LiveDisplayApis> activeApiOwner;

DisplayArray copyArray(DisplayArray original) {
    auto* api = activeApis.load();
    if (!api || !original) { return nullptr; }
    DisplayArray copy = nullptr;
    return api->copy(nullptr, original, &copy) == 0 ? copy : nullptr;
}

void releaseArray(DisplayArray array) noexcept {
    auto* api = activeApis.load();
    if (api && array) { api->release(array); }
}

std::uint64_t elapsedMilliseconds() {
    timespec value{};
    if (clock_gettime(CLOCK_BOOTTIME, &value) != 0 || value.tv_sec < 0 || value.tv_nsec < 0) {
        throw std::runtime_error("Suspend-aware clock unavailable");
    }
    return static_cast<std::uint64_t>(value.tv_sec) * 1000U +
        static_cast<std::uint64_t>(value.tv_nsec) / 1000000U;
}

std::optional<std::int64_t> nanosecondsToMicroseconds(std::uint64_t ticks) {
    constexpr auto maximum = static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());
    const auto converted = ticks / 1000U;
    return converted <= maximum ? std::optional<std::int64_t>(static_cast<std::int64_t>(converted)) : std::nullopt;
}

struct ActivationLifetime final {
    std::shared_ptr<LiveDisplayApis> api;
    std::shared_ptr<void> secondaryUuid;
};
}

HookInstallResult activateLiveSurface(std::int64_t surfaceHandle, std::uint32_t authorizedUid,
    std::uint64_t requestId, const std::array<std::uint8_t, 32>& token,
    std::uint32_t framesPerSecond, std::uint32_t widthMillimeters,
    std::uint32_t heightMillimeters) noexcept {
    std::shared_ptr<LiveDisplayApis> stagedApi;
    try {
        std::unique_lock<std::mutex> activation(activationMutex, std::try_to_lock);
        if (!activation.owns_lock()) { return HookInstallResult::busy; }
        auto* entry = pinnedOemEntryBridge();
        const auto geometry = surfaceGeometry(surfaceHandle);
        const auto principal = entry ? entry->capture()->capture() : LivePrincipal{};
        const auto principalUuid = entry && principal.uuid ? entry->uuidReader()(principal.uuid) : std::nullopt;
        if (!entry || !geometry || !principalUuid || principal.type != 110 || !principal.epoch ||
            !principal.retainedIdentity || !requestId || !framesPerSecond || framesPerSecond > 60 ||
            !widthMillimeters || widthMillimeters > 2000 ||
            !heightMillimeters || heightMillimeters > 2000) {
            return HookInstallResult::rejected;
        }

        auto api = std::make_shared<LiveDisplayApis>();
        stagedApi = api;
        {
            std::lock_guard<std::mutex> lock(activeApiMutex);
            if (activeApiOwner) { return HookInstallResult::registrationUnavailable; }
            activeApiOwner = api;
            activeApis.store(api.get());
        }
        constexpr const char* secondaryName = "impulse-navigation-v1";
        constexpr std::uint32_t utf8Encoding = 0x08000100U;
        auto* secondary = api->createString(nullptr, secondaryName, utf8Encoding);
        if (!secondary) {
            std::lock_guard<std::mutex> lock(activeApiMutex);
            activeApis.store(nullptr);
            activeApiOwner.reset();
            return HookInstallResult::rejected;
        }
        auto secondaryOwner = std::shared_ptr<void>(static_cast<void*>(secondary),
            [api](void* value) noexcept { if (value) { api->release(value); } });
        auto lifetime = std::make_shared<ActivationLifetime>(ActivationLifetime{api, secondaryOwner});

        SecondaryRuntimeConfig config;
        config.enabled = true;
        config.bridge.enabled = true;
        config.bridge.principal = {110, *principalUuid};
        config.bridge.secondary = {111, secondaryName};
        config.display.enabled = true;
        config.display.principal = config.bridge.principal;
        config.display.secondary = config.bridge.secondary;
        config.display.secondaryUuid = secondary;
        config.display.framesPerSecond = framesPerSecond;
        config.display.widthPixels = geometry->first;
        config.display.heightPixels = geometry->second;
        config.display.widthMillimeters = widthMillimeters;
        config.display.heightMillimeters = heightMillimeters;

        SurfaceRuntimePreparation surface;
        surface.surfaceHandle = surfaceHandle;
        surface.tickConverter = nanosecondsToMicroseconds;
        surface.config = std::move(config);
        surface.displayApi = {copyArray, releaseArray, api->append, entry->uuidReader()};
        surface.releaseOwnedArray = releaseArray;
        surface.originalGetterReturnsOwned = true;
        surface.requestLifetime = std::move(lifetime);

        HookAuthorization authorization;
        authorization.enabled = true;
        authorization.callerUid = authorizedUid;
        authorization.requestId = requestId;
        authorization.token = token;
        const auto issued = elapsedMilliseconds();
        auto permit = std::make_shared<HookPermit>(authorizedUid, principal.epoch, requestId, token,
            issued, issued + 5000U, elapsedMilliseconds, oemEntryPrincipalEpoch);
        HookInstaller installer(authenticatedPreparation(permit,
            [authorizedUid] { return std::optional<std::uint32_t>{authorizedUid}; },
            [surface = std::move(surface)](const HookAuthorization&) mutable {
                return preparePinnedSurfaceRuntime(std::move(surface));
            }));
        const auto result = installer.install(authorization);
        if (result != HookInstallResult::installed &&
            result != HookInstallResult::alreadyInstalled &&
            result != HookInstallResult::publicationFailedPinned) {
            std::lock_guard<std::mutex> lock(activeApiMutex);
            if (activeApiOwner == api) {
                activeApis.store(nullptr);
                activeApiOwner.reset();
            }
        }
        return result;
    } catch (...) {
        std::lock_guard<std::mutex> lock(activeApiMutex);
        if (stagedApi && activeApiOwner == stagedApi) {
            activeApis.store(nullptr);
            activeApiOwner.reset();
        }
        return HookInstallResult::fault;
    }
}

}

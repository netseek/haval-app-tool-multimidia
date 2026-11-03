#include "surface_runtime_preparation.h"

#include <utility>

namespace carplay_experiment {
namespace {
struct RuntimeLifetime final {
    std::shared_ptr<void> nativeOwners;
    std::shared_ptr<void> request;
};
}

std::shared_ptr<PreparedHooks> preparePinnedSurfaceRuntime(
    SurfaceRuntimePreparation preparation) {
    auto* entry = pinnedOemEntryBridge();
    if (!entry || preparation.surfaceHandle <= 0 || !preparation.tickConverter ||
        !preparation.config.enabled || !preparation.config.display.enabled ||
        !preparation.displayApi.copyMutable || !preparation.displayApi.release ||
        !preparation.displayApi.append || !preparation.displayApi.readUuid ||
        !preparation.releaseOwnedArray || !preparation.originalGetterReturnsOwned ||
        !preparation.requestLifetime) {
        return {};
    }

    const auto originals = entry->originals();
    const auto reader = entry->uuidReader();
    auto lifetime = std::make_shared<RuntimeLifetime>(RuntimeLifetime{
        entry->nativeOwners(), std::move(preparation.requestLifetime)});
    const auto handle = preparation.surfaceHandle;
    auto converter = std::move(preparation.tickConverter);
    auto config = std::move(preparation.config);
    auto displayApi = std::move(preparation.displayApi);
    const auto release = preparation.releaseOwnedArray;

    return entry->prepare([handle, converter = std::move(converter), config = std::move(config),
        displayApi = std::move(displayApi), release, lifetime, originals, reader]() mutable {
        auto runtime = runtimeForSurface(handle, converter, originals.video, originals.session,
            config, reader, displayApi, lifetime);
        if (!runtime) { return std::unique_ptr<OemRuntimeAdapter>{}; }
        return std::make_unique<OemRuntimeAdapter>(std::move(runtime), originals.displays,
            release, true);
    });
}

}

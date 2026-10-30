#pragma once

#include "android_surface_transport.h"
#include "oem_entry_bridge.h"

namespace carplay_experiment {

struct SurfaceRuntimePreparation {
    std::int64_t surfaceHandle = 0;
    AndroidSecondaryDecoder::TickConverter tickConverter;
    SecondaryRuntimeConfig config;
    DisplayAdvertisementApi displayApi;
    ReleaseOwnedArray releaseOwnedArray = nullptr;
    bool originalGetterReturnsOwned = false;
    std::shared_ptr<void> requestLifetime;
};

// Builds an unpublished capability around the exact originals captured by the
// pinned cold entry bridge. Authentication and HookInstaller publication remain
// mandatory at the caller; this function never changes a live route itself.
std::shared_ptr<PreparedHooks> preparePinnedSurfaceRuntime(
    SurfaceRuntimePreparation preparation);

}

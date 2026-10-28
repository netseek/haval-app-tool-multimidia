#pragma once

#include "hook_installation.h"

#include <cstdint>

namespace carplay_experiment {

HookInstallResult activateLiveSurface(std::int64_t surfaceHandle, std::uint32_t authorizedUid,
    std::uint64_t requestId, const std::array<std::uint8_t, 32>& token,
    std::uint32_t framesPerSecond, std::uint32_t widthMillimeters,
    std::uint32_t heightMillimeters) noexcept;

}

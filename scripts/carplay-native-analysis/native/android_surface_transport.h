#pragma once

#include "android_secondary_decoder.h"
#include "secondary_session.h"
#include "secondary_runtime.h"

namespace carplay_experiment {

bool surfaceRouteReady(std::int64_t handle);
std::optional<std::pair<std::uint32_t, std::uint32_t>> surfaceGeometry(std::int64_t handle);

struct SurfaceSessionBinding {
    std::shared_ptr<SecondarySession> session;
    std::uint64_t generation = 0;
};

SurfaceSessionBinding surfaceSessionBinding(std::int64_t handle);

VideoBridge::DecoderFactory surfaceDecoderFactory(
    std::int64_t handle, AndroidSecondaryDecoder::TickConverter converter);

std::unique_ptr<SecondaryRuntime> runtimeForSurface(std::int64_t handle,
    AndroidSecondaryDecoder::TickConverter converter, observed_abi::VideoCallbacks original,
    OriginalStartSession originalSession, SecondaryRuntimeConfig config, UuidReader uuidReader,
    DisplayAdvertisementApi displayApi, std::shared_ptr<void> lifetime);

}

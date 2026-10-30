#include "secondary_advertisement.h"

#include <utility>

namespace carplay_experiment {

PreparedDisplays::PreparedDisplays(DisplayArray original, observed_abi::Status status) noexcept
    : value_(original), status_(status) {}

PreparedDisplays::PreparedDisplays(DisplayArray value, void (*release)(DisplayArray)) noexcept
    : value_(value), release_(release), status_(0) {}

PreparedDisplays::PreparedDisplays(PreparedDisplays&& other) noexcept
    : value_(std::exchange(other.value_, nullptr)), release_(std::exchange(other.release_, nullptr)),
      status_(other.status_) {}

PreparedDisplays::~PreparedDisplays() {
    if (value_ && release_) {
        try { release_(value_); } catch (...) {}
    }
}

DisplayArray PreparedDisplays::get() const noexcept { return value_; }
observed_abi::Status PreparedDisplays::status() const noexcept { return status_; }
bool PreparedDisplays::hasSecondary() const noexcept { return value_ && release_ && status_ == 0; }

DisplayArray PreparedDisplays::takePrepared() noexcept {
    if (!hasSecondary()) { return nullptr; }
    release_ = nullptr;
    return std::exchange(value_, nullptr);
}

PreparedDisplays prepareSecondaryDisplay(DisplayArray original, const SecondaryDisplayConfig& config,
    const DisplayAdvertisementApi& api, const std::function<bool()>& routeReady) {
    if (!config.enabled) { return PreparedDisplays(original); }
    if (!original || !api.copyMutable || !api.release || !api.append || !api.readUuid || !routeReady ||
        config.principal.type != 110 || config.secondary.type != 111 ||
        config.principal.uuid.empty() || config.principal.uuid.size() > 128 ||
        config.secondary.uuid.empty() || config.secondary.uuid.size() > 128 ||
        config.principal.uuid == config.secondary.uuid || !config.secondaryUuid ||
        !config.framesPerSecond || config.framesPerSecond > 60 ||
        !config.widthPixels || config.widthPixels > 4096 ||
        !config.heightPixels || config.heightPixels > 2160 ||
        !config.widthMillimeters || config.widthMillimeters > 2000 ||
        !config.heightMillimeters || config.heightMillimeters > 2000) {
        return PreparedDisplays(original, VideoBridge::rejected);
    }
    try {
        const auto uuid = api.readUuid(config.secondaryUuid);
        if (!uuid || *uuid != config.secondary.uuid || !routeReady()) {
            return PreparedDisplays(original, VideoBridge::notReady);
        }
        const auto copy = api.copyMutable(original);
        if (!copy || copy == original) { return PreparedDisplays(original, VideoBridge::rejected); }
        PreparedDisplays owned(copy, api.release);
        auto candidate = copy;
        const auto status = api.append(&candidate, config.secondaryUuid, config.framesPerSecond,
            config.widthPixels, config.heightPixels, config.widthMillimeters,
            config.heightMillimeters, nullptr, nullptr, 0, nullptr);
        if (candidate != copy) {
            if (candidate && candidate != original) {
                PreparedDisplays unexpected(candidate, api.release);
            }
            return PreparedDisplays(original, VideoBridge::rejected);
        }
        if (status != 0) { return PreparedDisplays(original, status); }
        if (!routeReady()) { return PreparedDisplays(original, VideoBridge::notReady); }
        return owned;
    } catch (...) {
        return PreparedDisplays(original, VideoBridge::decoderFailure);
    }
}

}

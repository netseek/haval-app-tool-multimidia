#include "secondary_session.h"

#include <limits>
#include <utility>

namespace carplay_experiment {

SecondarySession::SecondarySession(bool enabled) : enabled_(enabled) {}
SecondarySession::~SecondarySession() { close(); }

std::uint64_t SecondarySession::arm(std::shared_ptr<DecoderLease> lease) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!enabled_ || closing_ || phase_ != Phase::Idle || !lease ||
        generation_ == std::numeric_limits<std::uint64_t>::max()) { return 0; }
    if (!lease->bindSession()) { return 0; }
    lease_ = std::move(lease);
    ++generation_;
    phase_ = Phase::Ready;
    runtimeBound_ = false;
    return generation_;
}

bool SecondarySession::readyLocked(std::uint64_t generation) const {
    return phase_ == Phase::Ready && activeLocked(generation);
}

bool SecondarySession::activeLocked(std::uint64_t generation) const {
    return enabled_ && !closing_ && generation != 0 && generation == generation_ &&
        (phase_ == Phase::Ready || phase_ == Phase::Advertised) && lease_ && lease_->active();
}

bool SecondarySession::ready(std::uint64_t generation) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return readyLocked(generation);
}

bool SecondarySession::active(std::uint64_t generation) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return activeLocked(generation);
}

bool SecondarySession::advertised(std::uint64_t generation) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return enabled_ && !closing_ && generation != 0 && generation == generation_ &&
        phase_ == Phase::Advertised && lease_ && lease_->active();
}

bool SecondarySession::bindRuntime(std::uint64_t generation) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (runtimeBound_ || !readyLocked(generation)) { return false; }
    runtimeBound_ = true;
    return true;
}

observed_abi::Status SecondarySession::advertise(std::uint64_t generation, DisplayArray original,
    const SecondaryDisplayConfig& config, const DisplayAdvertisementApi& api,
    Publish publish, void* context) {
    return prepareAndPublish(generation, original, config, api, publish, context, false);
}

observed_abi::Status SecondarySession::advertiseForGetter(std::uint64_t generation, DisplayArray original,
    const SecondaryDisplayConfig& config, const DisplayAdvertisementApi& api, Publish publish, void* context) {
    return prepareAndPublish(generation, original, config, api, publish, context, true);
}

observed_abi::Status SecondarySession::prepareAndPublish(std::uint64_t generation, DisplayArray original,
    const SecondaryDisplayConfig& config, const DisplayAdvertisementApi& api,
    Publish publish, void* context, bool repeat) {
    if (!config.enabled || !publish || !(repeat ? active(generation) : ready(generation))) {
        return VideoBridge::notReady;
    }
    auto prepared = prepareSecondaryDisplay(original, config, api,
        [this, generation, repeat] { return repeat ? active(generation) : ready(generation); });
    if (!prepared.hasSecondary()) {
        return prepared.status() != 0 ? prepared.status() : VideoBridge::notReady;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (!(repeat ? activeLocked(generation) : readyLocked(generation))) { return VideoBridge::notReady; }
    const bool published = lease_->withActive([&] {
        phase_ = Phase::Advertised;
        publish(prepared.takePrepared(), context);
    });
    return published ? 0 : VideoBridge::notReady;
}

bool SecondarySession::revoke(std::uint64_t generation) {
    std::shared_ptr<DecoderLease> retired;
    {
        std::unique_lock<std::mutex> lock(mutex_);
        if (generation == 0 || generation != generation_) { return false; }
        if (phase_ == Phase::Revoking) {
            changed_.wait(lock, [this, generation] {
                return phase_ != Phase::Revoking || generation_ != generation;
            });
            return true;
        }
        if (phase_ == Phase::Idle || phase_ == Phase::Closed) { return true; }
        phase_ = Phase::Revoking;
        retired = std::move(lease_);
    }
    if (retired) { retired->revoke(); }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        phase_ = Phase::Idle;
        changed_.notify_all();
    }
    return true;
}

void SecondarySession::close() {
    std::uint64_t generation = 0;
    {
        std::unique_lock<std::mutex> lock(mutex_);
        if (closing_) {
            changed_.wait(lock, [this] { return phase_ == Phase::Closed; });
            return;
        }
        closing_ = true;
        generation = generation_;
    }
    revoke(generation);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        phase_ = Phase::Closed;
        changed_.notify_all();
    }
}

}

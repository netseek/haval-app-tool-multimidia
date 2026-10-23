#pragma once

#include "decoder_lease.h"
#include "secondary_advertisement.h"

#include <condition_variable>

namespace carplay_experiment {

class SecondarySession final {
public:
    using Publish = void (*)(DisplayArray, void*) noexcept;

    explicit SecondarySession(bool enabled = false);
    ~SecondarySession();
    std::uint64_t arm(std::shared_ptr<DecoderLease> lease);
    bool ready(std::uint64_t generation) const;
    bool active(std::uint64_t generation) const;
    bool advertised(std::uint64_t generation) const;
    observed_abi::Status advertise(std::uint64_t generation, DisplayArray original,
        const SecondaryDisplayConfig& config, const DisplayAdvertisementApi& api,
        Publish publish, void* context);
    bool revoke(std::uint64_t generation);
    void close();

private:
    friend class SecondaryRuntime;
    bool bindRuntime(std::uint64_t generation);
    observed_abi::Status advertiseForGetter(std::uint64_t generation, DisplayArray original,
        const SecondaryDisplayConfig& config, const DisplayAdvertisementApi& api,
        Publish publish, void* context);
    observed_abi::Status prepareAndPublish(std::uint64_t generation, DisplayArray original,
        const SecondaryDisplayConfig& config, const DisplayAdvertisementApi& api,
        Publish publish, void* context, bool repeat);
    enum class Phase { Idle, Ready, Advertised, Revoking, Closed };
    bool readyLocked(std::uint64_t generation) const;
    bool activeLocked(std::uint64_t generation) const;

    const bool enabled_;
    mutable std::mutex mutex_;
    std::condition_variable changed_;
    std::shared_ptr<DecoderLease> lease_;
    std::uint64_t generation_ = 0;
    Phase phase_ = Phase::Idle;
    bool closing_ = false;
    bool runtimeBound_ = false;
};

}

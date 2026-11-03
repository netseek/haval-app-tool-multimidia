#pragma once

#include "hook_installation.h"

namespace carplay_experiment {

class HookPermit final {
public:
    using Clock = std::function<std::uint64_t()>;
    using Generation = std::function<std::uint64_t()>;
    HookPermit(std::uint32_t uid, std::uint64_t generation, std::uint64_t requestId,
        const std::array<std::uint8_t, 32>& token, std::uint64_t issuedMs, std::uint64_t expiresMs,
        Clock clock, Generation currentGeneration);
    ~HookPermit();
    bool consume(const HookAuthorization& authorization, std::uint32_t verifiedPeerUid) noexcept;
    bool validFor(std::uint64_t generation) noexcept;
    void revoke() noexcept;
private:
    const std::uint32_t uid_;
    const std::uint64_t generation_;
    const std::uint64_t requestId_;
    std::array<std::uint8_t, 32> token_;
    const std::uint64_t issuedMs_;
    const std::uint64_t expiresMs_;
    const Clock clock_;
    const Generation currentGeneration_;
    std::mutex mutex_;
    bool consumed_ = false;
    bool authorized_ = false;
    void erase() noexcept;
};

HookInstaller::Prepare authenticatedPreparation(std::shared_ptr<HookPermit> permit,
    std::function<std::optional<std::uint32_t>()> trustedPeer, HookInstaller::Prepare prepare);

}

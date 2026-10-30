#include "hook_permit.h"

#include <stdexcept>
#include <utility>

namespace carplay_experiment {

HookPermit::HookPermit(std::uint32_t uid, std::uint64_t generation, std::uint64_t requestId,
    const std::array<std::uint8_t, 32>& token, std::uint64_t issuedMs, std::uint64_t expiresMs,
    Clock clock, Generation currentGeneration)
    : uid_(uid), generation_(generation), requestId_(requestId), token_(token), issuedMs_(issuedMs),
      expiresMs_(expiresMs), clock_(std::move(clock)), currentGeneration_(std::move(currentGeneration)) {
    std::uint8_t present = 0;
    for (const auto value : token_) { present |= value; }
    if (!generation_ || !requestId_ || !present || !clock_ || !currentGeneration_ ||
        expiresMs_ <= issuedMs_ || expiresMs_ - issuedMs_ > 60000) {
        erase();
        throw std::invalid_argument("Nonempty session-bound permit with at most 60-second lifetime required");
    }
}

HookPermit::~HookPermit() { erase(); }
void HookPermit::erase() noexcept {
    volatile std::uint8_t* bytes = token_.data();
    for (std::size_t index = 0; index < token_.size(); ++index) { bytes[index] = 0; }
}

bool HookPermit::consume(const HookAuthorization& authorization, std::uint32_t verifiedPeerUid) noexcept {
    try {
        std::lock_guard<std::mutex> lock(mutex_);
        if (consumed_ || !authorization.enabled || verifiedPeerUid != uid_ || authorization.callerUid != uid_ ||
            authorization.requestId != requestId_) { return false; }
        const auto now = clock_();
        if (now < issuedMs_ || now >= expiresMs_ || currentGeneration_() != generation_) {
            consumed_ = true;
            erase();
            return false;
        }
        volatile std::uint8_t difference = 0;
        for (std::size_t index = 0; index < token_.size(); ++index) {
            difference = static_cast<std::uint8_t>(difference | (token_[index] ^ authorization.token[index]));
        }
        if (difference != 0) { return false; }
        consumed_ = true;
        authorized_ = true;
        erase();
        return true;
    } catch (...) { revoke(); return false; }
}

void HookPermit::revoke() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    consumed_ = true;
    authorized_ = false;
    erase();
}

bool HookPermit::validFor(std::uint64_t generation) noexcept {
    try {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!authorized_ || generation != generation_) { return false; }
        const auto now = clock_();
        if (now < issuedMs_ || now >= expiresMs_ || currentGeneration_() != generation_) {
            authorized_ = false;
            return false;
        }
        return true;
    } catch (...) { revoke(); return false; }
}

namespace {
class PermitTransaction final : public QuiescentHookTransaction {
public:
    PermitTransaction(std::shared_ptr<HookPermit> permit, std::unique_ptr<QuiescentHookTransaction> transaction)
        : permit_(std::move(permit)), transaction_(std::move(transaction)) {}
    LivePrincipal capturePrincipal() override {
        auto principal = transaction_->capturePrincipal();
        if (!permit_->validFor(principal.epoch)) { return {}; }
        generation_ = principal.epoch;
        return principal;
    }
    bool isCurrent(const LivePrincipal& principal) noexcept override {
        return principal.epoch == generation_ && permit_->validFor(generation_) && transaction_->isCurrent(principal);
    }
    bool publish(observed_abi::VideoCallbacks video, OriginalStartSession session, OriginalGetDisplays displays) noexcept override {
        return permit_->validFor(generation_) && transaction_->publish(video, session, displays);
    }
private:
    const std::shared_ptr<HookPermit> permit_;
    const std::unique_ptr<QuiescentHookTransaction> transaction_;
    std::uint64_t generation_ = 0;
};

class PermitPrepared final : public PreparedHooks {
public:
    PermitPrepared(std::shared_ptr<HookPermit> permit, std::shared_ptr<PreparedHooks> prepared)
        : permit_(std::move(permit)), prepared_(std::move(prepared)) {}
    std::unique_ptr<OemRuntimeAdapter> makeAdapter() override { return prepared_->makeAdapter(); }
    std::unique_ptr<QuiescentHookTransaction> tryQuiesce() override {
        auto transaction = prepared_->tryQuiesce();
        if (!transaction) { return {}; }
        return std::make_unique<PermitTransaction>(permit_, std::move(transaction));
    }
private:
    const std::shared_ptr<HookPermit> permit_;
    const std::shared_ptr<PreparedHooks> prepared_;
};
}

HookInstaller::Prepare authenticatedPreparation(std::shared_ptr<HookPermit> permit,
    std::function<std::optional<std::uint32_t>()> trustedPeer, HookInstaller::Prepare prepare) {
    if (!permit || !trustedPeer || !prepare) { throw std::invalid_argument("Trusted peer transport required"); }
    return [permit = std::move(permit), trustedPeer = std::move(trustedPeer), prepare = std::move(prepare)]
        (const HookAuthorization& authorization) -> std::shared_ptr<PreparedHooks> {
        if (!authorization.enabled) { return {}; }
        const auto peer = trustedPeer();
        if (!peer || !permit->consume(authorization, *peer)) { return {}; }
        auto prepared = prepare(authorization);
        return prepared ? std::make_shared<PermitPrepared>(permit, std::move(prepared)) : nullptr;
    };
}

}

#include "live_session_capture.h"

#include <limits>
#include <stdexcept>
#include <utility>

namespace carplay_experiment {

struct LiveSessionCapture::Scope {
    LiveSessionCapture* owner;
    Scope* previous;
    void* session;
    std::uint64_t epoch;
    Scope(LiveSessionCapture* capture, void* value, std::uint64_t generation)
        : owner(capture), previous(scope_), session(value), epoch(generation) { scope_ = this; }
    ~Scope() { scope_ = previous; }
};
thread_local LiveSessionCapture::Scope* LiveSessionCapture::scope_ = nullptr;

LiveSessionCapture::LiveSessionCapture(std::string principalUuid, UuidReader reader, RetainUuid retain)
    : principalUuid_(std::move(principalUuid)), reader_(std::move(reader)), retain_(std::move(retain)) {
    if (principalUuid_.empty() || principalUuid_.size() > 128 || !reader_ || !retain_) {
        throw std::invalid_argument("Explicit principal identity, bounded reader and native UUID owner required");
    }
}

observed_abi::Status LiveSessionCapture::session(void* session, observed_abi::String uuid,
    std::uint32_t type, const std::function<observed_abi::Status()>& invoke) {
    std::uint64_t epoch = 0;
    if (type == 110) {
        bool matches = false;
        try {
            const auto value = reader_(uuid);
            matches = session && value && *value == principalUuid_;
        } catch (...) {}
        LivePrincipal retired;
        LivePrincipal pending;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            retired = std::move(principal_);
            pending = std::move(pending_);
            principal_ = {};
            pending_ = {};
            session_ = nullptr;
            if (sessionEpoch_ == std::numeric_limits<std::uint64_t>::max()) { exhausted_ = true; }
            else { ++sessionEpoch_; }
            if (matches && !exhausted_) {
                session_ = session;
                epoch = sessionEpoch_;
            }
        }
    }
    Scope scope(this, session, epoch);
    try {
        const auto result = invoke();
        if (result != 0 && epoch) { invalidateEpoch(epoch); }
        return result;
    } catch (...) {
        if (epoch) { invalidateEpoch(epoch); }
        throw;
    }
}

void LiveSessionCapture::starting(observed_abi::Stream stream, std::uint32_t type,
    observed_abi::String uuid) noexcept {
    if (type != 110) { return; }
    const auto* scope = scope_;
    if (!scope || scope->owner != this || !scope->epoch) { invalidate(); return; }
    try {
        auto retained = uuid ? retain_(uuid) : std::shared_ptr<void>{};
        const auto value = retained && retained.get() == uuid ? reader_(uuid) : std::optional<std::string>{};
        if (!stream || !value || *value != principalUuid_) { invalidateEpoch(scope->epoch); return; }
        bool conflict = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (exhausted_ || sessionEpoch_ != scope->epoch || session_ != scope->session) { return; }
            conflict = pending_.stream != nullptr;
            if (!conflict) { pending_ = {stream, uuid, type, scope->epoch, std::move(retained)}; }
        }
        if (conflict) { invalidateEpoch(scope->epoch); }
    } catch (...) { invalidateEpoch(scope->epoch); }
}

void LiveSessionCapture::started(observed_abi::Status status, observed_abi::Stream stream,
    std::uint32_t type, observed_abi::String uuid) noexcept {
    const auto* scope = scope_;
    if (type != 110 || !scope || scope->owner != this || !scope->epoch) { return; }
    if (status != 0 || !stream || !uuid) { invalidateEpoch(scope->epoch); return; }
    try {
        LivePrincipal retired;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (exhausted_ || sessionEpoch_ != scope->epoch || session_ != scope->session) { return; }
            if (pending_.stream != stream || pending_.uuid != uuid || !pending_.retainedIdentity) { return; }
            retired = std::move(principal_);
            principal_ = {};
            if (revision_ == std::numeric_limits<std::uint64_t>::max()) {
                exhausted_ = true;
                session_ = nullptr;
                return;
            }
            principal_ = {stream, uuid, type, ++revision_, std::move(pending_.retainedIdentity)};
            pending_ = {};
        }
    } catch (...) { invalidateEpoch(scope->epoch); }
}

void LiveSessionCapture::invalidate() noexcept {
    LivePrincipal retired;
    LivePrincipal pending;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        retired = std::move(principal_);
        pending = std::move(pending_);
        principal_ = {};
        pending_ = {};
        session_ = nullptr;
        if (sessionEpoch_ == std::numeric_limits<std::uint64_t>::max()) { exhausted_ = true; }
        else { ++sessionEpoch_; }
    }
}

void LiveSessionCapture::invalidateEpoch(std::uint64_t epoch) noexcept {
    LivePrincipal retired;
    LivePrincipal pending;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (sessionEpoch_ != epoch) { return; }
        retired = std::move(principal_);
        pending = std::move(pending_);
        principal_ = {};
        pending_ = {};
        session_ = nullptr;
        if (sessionEpoch_ == std::numeric_limits<std::uint64_t>::max()) { exhausted_ = true; }
        else { ++sessionEpoch_; }
    }
}

void LiveSessionCapture::retiring(observed_abi::Stream stream) noexcept {
    LivePrincipal retired;
    LivePrincipal pending;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream || (principal_.stream != stream && pending_.stream != stream)) { return; }
        retired = std::move(principal_);
        pending = std::move(pending_);
        principal_ = {};
        pending_ = {};
        session_ = nullptr;
        if (sessionEpoch_ == std::numeric_limits<std::uint64_t>::max()) { exhausted_ = true; }
        else { ++sessionEpoch_; }
    }
}

LivePrincipal LiveSessionCapture::capture() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return principal_;
}

bool LiveSessionCapture::isCurrent(const LivePrincipal& principal) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return !exhausted_ && session_ && principal.stream && principal.epoch &&
        principal.stream == principal_.stream && principal.uuid == principal_.uuid &&
        principal.type == principal_.type && principal.epoch == principal_.epoch &&
        principal.retainedIdentity && principal.retainedIdentity == principal_.retainedIdentity;
}

LiveSessionCapture::RetainUuid makeCapturedUuidOwner(RetainCfObject retain, ReleaseCfObject release,
    std::shared_ptr<void> nativeOwners) {
    if (!retain || !release || !nativeOwners) { throw std::invalid_argument("Verified owned CoreUtils APIs required"); }
    return [retain, release, nativeOwners = std::move(nativeOwners)](observed_abi::String uuid) -> std::shared_ptr<void> {
        if (!uuid) { return {}; }
        const auto retained = retain(uuid);
        if (!retained) { return {}; }
        if (retained != uuid) { release(retained); return {}; }
        return {const_cast<void*>(retained), [release, nativeOwners](void* value) noexcept { release(value); }};
    };
}

}

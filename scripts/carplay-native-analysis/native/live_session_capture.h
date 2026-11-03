#pragma once

#include "coordinated_dispatch.h"

namespace carplay_experiment {

class LiveSessionCapture final : public DispatchObservation {
public:
    using RetainUuid = std::function<std::shared_ptr<void>(observed_abi::String)>;
    LiveSessionCapture(std::string principalUuid, UuidReader reader, RetainUuid retain);
    observed_abi::Status session(void* session, observed_abi::String uuid, std::uint32_t type,
        const std::function<observed_abi::Status()>& invoke) override;
    void starting(observed_abi::Stream stream, std::uint32_t type, observed_abi::String uuid) noexcept override;
    void started(observed_abi::Status status, observed_abi::Stream stream,
        std::uint32_t type, observed_abi::String uuid) noexcept override;
    void retiring(observed_abi::Stream stream) noexcept override;
    void invalidate() noexcept;
    LivePrincipal capture() const;
    bool isCurrent(const LivePrincipal& principal) const;

private:
    struct Scope;
    static thread_local Scope* scope_;
    const std::string principalUuid_;
    const UuidReader reader_;
    const RetainUuid retain_;
    mutable std::mutex mutex_;
    void* session_ = nullptr;
    std::uint64_t sessionEpoch_ = 0;
    std::uint64_t revision_ = 0;
    bool exhausted_ = false;
    LivePrincipal principal_;
    LivePrincipal pending_;
    void invalidateEpoch(std::uint64_t epoch) noexcept;
};

using RetainCfObject = const void* (*)(const void*);
using ReleaseCfObject = void (*)(const void*);
LiveSessionCapture::RetainUuid makeCapturedUuidOwner(RetainCfObject retain, ReleaseCfObject release,
    std::shared_ptr<void> nativeOwners);

}

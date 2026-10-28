#pragma once

#include "oem_runtime_adapter.h"

#include <array>
#include <mutex>

namespace carplay_experiment {

struct HookAuthorization {
    bool enabled = false;
    std::uint32_t callerUid = 0;
    std::uint64_t requestId = 0;
    std::array<std::uint8_t, 32> token{};
};

struct LivePrincipal {
    observed_abi::Stream stream = nullptr;
    observed_abi::String uuid = nullptr;
    std::uint32_t type = 0;
    std::uint64_t epoch = 0;
    std::shared_ptr<void> retainedIdentity;
};

class QuiescentHookTransaction {
public:
    virtual ~QuiescentHookTransaction() = default;
    virtual LivePrincipal capturePrincipal() = 0;
    virtual bool isCurrent(const LivePrincipal& principal) noexcept = 0;
    virtual bool publish(observed_abi::VideoCallbacks video, OriginalStartSession session,
        OriginalGetDisplays displays) noexcept = 0;
};

class PreparedHooks {
public:
    virtual ~PreparedHooks() = default;
    virtual std::unique_ptr<OemRuntimeAdapter> makeAdapter() = 0;
    virtual std::unique_ptr<QuiescentHookTransaction> tryQuiesce() = 0;
};

enum class HookInstallResult {
    disabled, busy, rejected, noQuiescence, invalidPrincipal, stalePrincipal,
    registrationUnavailable, publicationFailedPinned, installed, alreadyInstalled, fault
};

class HookInstaller final {
public:
    using Prepare = std::function<std::shared_ptr<PreparedHooks>(const HookAuthorization&)>;
    explicit HookInstaller(Prepare prepare);
    HookInstallResult install(const HookAuthorization& authorization) noexcept;

private:
    const Prepare prepare_;
    std::mutex installationMutex_;
    bool consumed_ = false;
    bool published_ = false;
};

}

#include "hook_installation.h"

#include <stdexcept>
#include <utility>

namespace carplay_experiment {
namespace {
struct InstalledLifetime {
    std::shared_ptr<PreparedHooks> prepared;
    std::shared_ptr<void> identity;
};
}

HookInstaller::HookInstaller(Prepare prepare) : prepare_(std::move(prepare)) {
    if (!prepare_) { throw std::invalid_argument("Verified hook preparation backend required"); }
}

HookInstallResult HookInstaller::install(const HookAuthorization& authorization) noexcept {
    if (!authorization.enabled) { return HookInstallResult::disabled; }
    std::unique_lock<std::mutex> lock;
    try {
        lock = std::unique_lock<std::mutex>(installationMutex_, std::try_to_lock);
        if (!lock.owns_lock()) { return HookInstallResult::busy; }
        if (consumed_) {
            return published_ ? HookInstallResult::alreadyInstalled : HookInstallResult::publicationFailedPinned;
        }
        const auto prepared = prepare_(authorization);
        if (!prepared) { return HookInstallResult::rejected; }
        auto adapter = prepared->makeAdapter();
        if (!adapter || !adapter->runtime().enabled()) { return HookInstallResult::rejected; }
        auto lifetime = std::make_shared<InstalledLifetime>();
        lifetime->prepared = prepared;
        auto transaction = prepared->tryQuiesce();
        if (!transaction) { return HookInstallResult::noQuiescence; }
        const auto principal = transaction->capturePrincipal();
        if (!principal.stream || !principal.uuid || principal.type != 110 || !principal.epoch ||
            !principal.retainedIdentity) { return HookInstallResult::invalidPrincipal; }
        if (!transaction->isCurrent(principal)) { return HookInstallResult::stalePrincipal; }
        if (adapter->runtime().adoptPrincipal(principal.stream, principal.type, principal.uuid) != 0) {
            return HookInstallResult::invalidPrincipal;
        }
        lifetime->identity = principal.retainedIdentity;
        adapter->installationLifetime_ = std::move(lifetime);
        if (!transaction->isCurrent(principal)) { return HookInstallResult::stalePrincipal; }
        auto* pinned = adapter.get();
        if (!pinRuntimeAdapterForProcess(std::move(adapter))) {
            return HookInstallResult::registrationUnavailable;
        }
        consumed_ = true;
        published_ = transaction->publish(runtimeVideoCallbacks(), impulse_carplay_runtime_session,
            impulse_carplay_runtime_displays);
        if (!published_) {
            pinned->runtime().close();
            return HookInstallResult::publicationFailedPinned;
        }
        return HookInstallResult::installed;
    } catch (...) {
        return lock.owns_lock() && consumed_ ? HookInstallResult::publicationFailedPinned : HookInstallResult::fault;
    }
}

}

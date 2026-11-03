#pragma once

#include "hook_permit.h"

namespace carplay_experiment {

class UnixHookEndpoint final {
public:
    UnixHookEndpoint(int connectedSocket, std::shared_ptr<HookPermit> permit, HookInstaller::Prepare prepare);
    std::optional<HookInstallResult> poll() noexcept;
private:
    struct Peer;
    const std::shared_ptr<Peer> peer_;
    HookInstaller installer_;
    std::mutex receiveMutex_;
};

}

#include "unix_hook_endpoint.h"

#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <utility>

namespace carplay_experiment {
namespace {
template<std::size_t Size>
void wipe(std::array<std::uint8_t, Size>& value) noexcept {
    volatile std::uint8_t* bytes = value.data();
    for (std::size_t index = 0; index < Size; ++index) { bytes[index] = 0; }
}
struct Packet {
    std::array<std::uint8_t, 56> bytes{};
    ~Packet() { wipe(bytes); }
};
struct Request {
    HookAuthorization authorization;
    ~Request() { wipe(authorization.token); }
};
std::uint64_t littleEndian(const Packet& packet, std::size_t offset, std::size_t width) noexcept {
    std::uint64_t result = 0;
    for (std::size_t index = 0; index < width; ++index) {
        result |= static_cast<std::uint64_t>(packet.bytes[offset + index]) << (index * 8);
    }
    return result;
}
}

struct UnixHookEndpoint::Peer {
    explicit Peer(int supplied) : descriptor(fcntl(supplied, F_DUPFD_CLOEXEC, 3)) {
        if (descriptor < 0) { throw std::invalid_argument("Owned connected local packet socket required"); }
        int type = 0;
        socklen_t typeSize = sizeof(type);
        sockaddr_un remote{};
        socklen_t remoteSize = sizeof(remote);
        const int passCredentials = 1;
        if (getsockopt(descriptor, SOL_SOCKET, SO_TYPE, &type, &typeSize) != 0 ||
            type != SOCK_SEQPACKET || getpeername(descriptor, reinterpret_cast<sockaddr*>(&remote), &remoteSize) != 0 ||
            remote.sun_family != AF_UNIX || !uid() ||
            setsockopt(descriptor, SOL_SOCKET, SO_PASSCRED, &passCredentials, sizeof(passCredentials)) != 0) {
            close(descriptor);
            throw std::invalid_argument("Connected AF_UNIX SOCK_SEQPACKET peer credentials required");
        }
    }
    ~Peer() { close(descriptor); }
    std::optional<std::uint32_t> uid() const noexcept {
        ucred credentials{};
        socklen_t size = sizeof(credentials);
        if (getsockopt(descriptor, SOL_SOCKET, SO_PEERCRED, &credentials, &size) != 0 ||
            size != sizeof(credentials) || credentials.pid <= 0) { return {}; }
        return static_cast<std::uint32_t>(credentials.uid);
    }
    bool authenticMessage(const ucred& sender) const noexcept {
        ucred peer{};
        socklen_t size = sizeof(peer);
        return getsockopt(descriptor, SOL_SOCKET, SO_PEERCRED, &peer, &size) == 0 && size == sizeof(peer) &&
            sender.pid > 0 && sender.pid == peer.pid && sender.uid == peer.uid && sender.gid == peer.gid;
    }
    const int descriptor;
};

UnixHookEndpoint::UnixHookEndpoint(int connectedSocket, std::shared_ptr<HookPermit> permit,
    HookInstaller::Prepare prepare)
    : peer_(std::make_shared<Peer>(connectedSocket)),
      installer_(authenticatedPreparation(std::move(permit), [peer = peer_] { return peer->uid(); }, std::move(prepare))) {}

std::optional<HookInstallResult> UnixHookEndpoint::poll() noexcept {
    try {
        std::unique_lock<std::mutex> lock(receiveMutex_, std::try_to_lock);
        if (!lock.owns_lock()) { return {}; }
        Packet packet;
        alignas(cmsghdr) std::array<char, CMSG_SPACE(sizeof(ucred)) + CMSG_SPACE(16 * sizeof(int))> control{};
        iovec payload{packet.bytes.data(), packet.bytes.size()};
        msghdr message{};
        message.msg_iov = &payload;
        message.msg_iovlen = 1;
        message.msg_control = control.data();
        message.msg_controllen = control.size();
        const auto received = recvmsg(peer_->descriptor, &message, MSG_DONTWAIT | MSG_TRUNC | MSG_CMSG_CLOEXEC);
        bool validCredentials = false;
        bool seenCredentials = false;
        bool unexpected = false;
        for (auto* header = CMSG_FIRSTHDR(&message); received >= 0 && header; header = CMSG_NXTHDR(&message, header)) {
            if (header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_CREDENTIALS &&
                header->cmsg_len == CMSG_LEN(sizeof(ucred)) && !seenCredentials) {
                seenCredentials = true;
                ucred sender{};
                std::memcpy(&sender, CMSG_DATA(header), sizeof(sender));
                validCredentials = peer_->authenticMessage(sender);
            } else {
                unexpected = true;
                if (header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS && header->cmsg_len >= CMSG_LEN(0)) {
                    const auto count = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
                    for (std::size_t index = 0; index < count; ++index) {
                        int descriptor = -1;
                        std::memcpy(&descriptor, CMSG_DATA(header) + index * sizeof(int), sizeof(descriptor));
                        if (descriptor >= 0) { close(descriptor); }
                    }
                }
            }
        }
        if (!validCredentials || unexpected || (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0 ||
            received != static_cast<ssize_t>(packet.bytes.size()) || littleEndian(packet, 0, 4) != 0x31484943 ||
            littleEndian(packet, 4, 4) != 1 || littleEndian(packet, 12, 4) != 0 || littleEndian(packet, 16, 8) == 0) {
            return {};
        }
        Request request;
        request.authorization.enabled = true;
        request.authorization.callerUid = static_cast<std::uint32_t>(littleEndian(packet, 8, 4));
        request.authorization.requestId = littleEndian(packet, 16, 8);
        std::copy(packet.bytes.begin() + 24, packet.bytes.end(), request.authorization.token.begin());
        return installer_.install(request.authorization);
    } catch (...) { return {}; }
}

}

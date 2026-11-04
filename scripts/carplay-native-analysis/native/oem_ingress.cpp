#include "oem_ingress.h"

#include <array>
#include <atomic>
#include <cstring>
#include <stdexcept>
#include <utility>

namespace carplay_experiment {

namespace {
struct CreationScope;
thread_local CreationScope* currentScope = nullptr;
std::atomic<OemIngress*> activeIngress{nullptr};
std::atomic<bool> processBridgeClaimed{false};
std::atomic<std::uint64_t> boundaryFaults{0};

struct CreationScope {
    OemIngress* owner;
    StreamIdentity identity;
    CreationScope* previous;
    std::size_t depth;

    CreationScope(OemIngress* ingress, StreamIdentity value)
        : owner(ingress), identity(std::move(value)), previous(currentScope),
          depth(previous ? previous->depth + 1 : 1) {
        if (depth > 16) {
            throw std::runtime_error("Nested session limit exceeded");
        }
        currentScope = this;
    }
    ~CreationScope() { currentScope = previous; }
    CreationScope(const CreationScope&) = delete;
    CreationScope& operator=(const CreationScope&) = delete;
};
}

UuidReader makeCapturedUuidReader(StringLength length, CopyUtf8String copy) {
    if (!length || !copy) {
        throw std::invalid_argument("Captured CFL string API required");
    }
    return [length, copy](observed_abi::String uuid) -> std::optional<std::string> {
        if (!uuid) {
            return std::nullopt;
        }
        const auto characters = length(uuid);
        if (characters < 1 || characters > 128) {
            return std::nullopt;
        }
        std::array<char, 129> buffer{};
        if (!copy(uuid, buffer.data(), static_cast<std::int32_t>(buffer.size()), 0x08000100)) {
            return std::nullopt;
        }
        const auto terminator = std::memchr(buffer.data(), 0, buffer.size());
        if (!terminator) {
            return std::nullopt;
        }
        const auto bytes = static_cast<const char*>(terminator) - buffer.data();
        if (bytes != characters) {
            return std::nullopt;
        }
        for (std::ptrdiff_t index = 0; index < bytes; ++index) {
            const auto character = buffer[static_cast<std::size_t>(index)];
            if (!((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
                (character >= '0' && character <= '9') || character == '-' || character == '_' ||
                character == '.' || character == ':')) {
                return std::nullopt;
            }
        }
        return std::string(buffer.data(), static_cast<std::size_t>(bytes));
    };
}

OemIngress::OemIngress(observed_abi::VideoCallbacks original, OriginalStartSession startSession,
    BridgeConfig config, VideoBridge::DecoderFactory factory, UuidReader uuidReader)
    : originalSession_(startSession), enabled_(config.enabled), reader_(std::move(uuidReader)),
      principal_(config.principal), secondary_(config.secondary),
      bridge_(original, std::move(config), std::move(factory)) {
    if (!originalSession_ || (enabled_ && !reader_)) {
        throw std::invalid_argument("Original session entry and enabled-mode UUID reader required");
    }
}

observed_abi::Status OemIngress::startSession(void* session, observed_abi::String uuid,
                                            std::uint32_t type, void* context) {
    if (!enabled_) {
        return originalSession_(session, uuid, type, context);
    }
    auto copied = reader_(uuid);
    if (!copied || copied->empty() || copied->size() > 128) {
        return VideoBridge::rejected;
    }
    if (!((type == principal_.type && *copied == principal_.uuid) ||
          (type == secondary_.type && *copied == secondary_.uuid))) {
        return VideoBridge::rejected;
    }
    CreationScope scope(this, {type, std::move(*copied)});
    return originalSession_(session, uuid, type, context);
}

observed_abi::Status OemIngress::initialize(observed_abi::Stream stream) {
    const auto* identity = currentScope && currentScope->owner == this ? &currentScope->identity : nullptr;
    return bridge_.initialize(stream, identity);
}

observed_abi::Status OemIngress::adoptPrincipal(observed_abi::Stream stream, std::uint32_t type,
                                              observed_abi::String uuid) {
    if (!enabled_ || type != principal_.type) { return VideoBridge::rejected; }
    const auto copied = reader_(uuid);
    if (!copied || *copied != principal_.uuid) { return VideoBridge::rejected; }
    return bridge_.adoptPrincipal(stream, {type, *copied});
}

observed_abi::Status OemIngress::start(observed_abi::Stream stream, std::uint32_t type,
                                      observed_abi::String uuid) {
    if (!enabled_) {
        return bridge_.start(stream, {type, {}}, uuid);
    }
    auto copied = reader_(uuid);
    return bridge_.start(stream, {type, copied ? std::move(*copied) : std::string{}}, uuid);
}

VideoBridge& OemIngress::video() { return bridge_; }

bool pinIngressForProcess(std::unique_ptr<OemIngress> ingress) {
    if (!ingress || !detail::claimProcessBridge()) { return false; }
    activeIngress.store(ingress.release());
    return true;
}

bool detail::claimProcessBridge() noexcept {
    bool expected = false;
    return processBridgeClaimed.compare_exchange_strong(expected, true);
}

static OemIngress* ingress() { return activeIngress.load(); }

static void boundaryFailure() noexcept {
    boundaryFaults.fetch_add(1);
    try {
        if (auto* adapter = ingress()) {
            adapter->video().shutdownSecondary();
        }
    } catch (...) {
    }
}

observed_abi::VideoCallbacks ingressCallbacks() {
    return {impulse_carplay_stream_initialize, impulse_carplay_stream_finalize,
        impulse_carplay_stream_dimensions, impulse_carplay_stream_start,
        impulse_carplay_stream_stop, impulse_carplay_stream_avcc, impulse_carplay_stream_data};
}

}

using namespace carplay_experiment;

extern "C" observed_abi::Status impulse_carplay_install_passthrough(
    const observed_abi::VideoCallbacks* original, OriginalStartSession session,
    StringLength length, CopyUtf8String copy) noexcept {
    try {
        if (!original) {
            return VideoBridge::rejected;
        }
        auto adapter = std::make_unique<OemIngress>(*original, session, BridgeConfig{},
            VideoBridge::DecoderFactory{}, makeCapturedUuidReader(length, copy));
        return pinIngressForProcess(std::move(adapter)) ? 0 : VideoBridge::rejected;
    } catch (...) {
        boundaryFaults.fetch_add(1);
        return VideoBridge::decoderFailure;
    }
}

extern "C" observed_abi::Status impulse_carplay_start_session(void* session,
    observed_abi::String uuid, std::uint32_t type, void* context) noexcept {
    try {
        auto* adapter = ingress();
        return adapter ? adapter->startSession(session, uuid, type, context) : VideoBridge::rejected;
    } catch (...) { boundaryFailure(); return VideoBridge::decoderFailure; }
}

extern "C" observed_abi::Status impulse_carplay_stream_initialize(observed_abi::Stream stream) noexcept {
    try {
        auto* adapter = ingress();
        return adapter ? adapter->initialize(stream) : VideoBridge::rejected;
    } catch (...) { boundaryFailure(); return VideoBridge::decoderFailure; }
}

extern "C" void impulse_carplay_stream_finalize(observed_abi::Stream stream) noexcept {
    try { if (auto* adapter = ingress()) { adapter->video().finalize(stream); } }
    catch (...) { boundaryFailure(); }
}

extern "C" void impulse_carplay_stream_dimensions(const void* stream, std::uint32_t width,
                                                  std::uint32_t height) noexcept {
    try {
        if (auto* adapter = ingress()) {
            adapter->video().setWidthHeight(static_cast<observed_abi::Stream>(const_cast<void*>(stream)), width, height);
        }
    } catch (...) { boundaryFailure(); }
}

extern "C" observed_abi::Status impulse_carplay_stream_start(observed_abi::Stream stream,
    std::uint32_t type, observed_abi::String uuid) noexcept {
    try {
        auto* adapter = ingress();
        return adapter ? adapter->start(stream, type, uuid) : VideoBridge::rejected;
    } catch (...) { boundaryFailure(); return VideoBridge::decoderFailure; }
}

extern "C" void impulse_carplay_stream_stop(observed_abi::Stream stream) noexcept {
    try { if (auto* adapter = ingress()) { adapter->video().stop(stream); } }
    catch (...) { boundaryFailure(); }
}

extern "C" observed_abi::Status impulse_carplay_stream_avcc(const void* stream,
    const std::uint8_t* bytes, std::size_t length) noexcept {
    try {
        auto* adapter = ingress();
        return adapter ? adapter->video().setAVCC(static_cast<observed_abi::Stream>(const_cast<void*>(stream)), bytes, length)
                       : VideoBridge::rejected;
    } catch (...) { boundaryFailure(); return VideoBridge::decoderFailure; }
}

extern "C" observed_abi::Status impulse_carplay_stream_data(observed_abi::Stream stream,
    const std::uint8_t* bytes, std::size_t length, std::uint64_t ticks,
    observed_abi::Dictionary options, observed_abi::Completion completion, void* context) noexcept {
    try {
        auto* adapter = ingress();
        return adapter ? adapter->video().processData(stream, bytes, length, ticks, options, completion, context)
                       : VideoBridge::rejected;
    } catch (...) { boundaryFailure(); return VideoBridge::decoderFailure; }
}

extern "C" void impulse_carplay_shutdown_secondary() noexcept {
    try { if (auto* adapter = ingress()) { adapter->video().shutdownSecondary(); } }
    catch (...) { boundaryFailure(); }
}

extern "C" std::uint64_t impulse_carplay_boundary_faults() noexcept { return boundaryFaults.load(); }

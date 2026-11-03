#include "secondary_runtime.h"

#include <stdexcept>
#include <utility>

namespace carplay_experiment {
namespace {
bool sameIdentity(const StreamIdentity& first, const StreamIdentity& second) {
    return first.type == second.type && first.uuid == second.uuid;
}

class GeometryDecoder final : public SecondaryDecoder {
public:
    GeometryDecoder(std::unique_ptr<SecondaryDecoder> decoder, std::uint32_t width, std::uint32_t height)
        : decoder_(std::move(decoder)), width_(width), height_(height) {}
    ~GeometryDecoder() override { stop(); }
    bool configure(std::uint32_t width, std::uint32_t height, const std::vector<std::uint8_t>& avcc) override {
        return decoder_ && width == width_ && height == height_ && decoder_->configure(width, height, avcc);
    }
    SubmitResult submit(const std::vector<std::uint8_t>& bytes, std::uint64_t ticks) override {
        return decoder_ ? decoder_->submit(bytes, ticks) : SubmitResult::Fatal;
    }
    void stop() noexcept override {
        if (decoder_) { decoder_->stop(); decoder_.reset(); }
    }
private:
    std::unique_ptr<SecondaryDecoder> decoder_;
    const std::uint32_t width_;
    const std::uint32_t height_;
};
}

SecondaryRuntime::SecondaryRuntime(observed_abi::VideoCallbacks original, OriginalStartSession originalSession,
    SecondaryRuntimeConfig config, VideoBridge::DecoderFactory factory, UuidReader uuidReader,
    std::shared_ptr<SecondarySession> session, std::uint64_t generation,
    DisplayAdvertisementApi displayApi, std::shared_ptr<void> lifetime)
    : config_(std::move(config)), displayApi_(std::move(displayApi)), lifetime_(std::move(lifetime)),
      session_(std::move(session)), generation_(generation) {
    auto bridge = config_.bridge;
    bridge.enabled = config_.enabled;
    if (config_.enabled) {
        if (!session_ || !session_->ready(generation_) || !factory || !uuidReader || !lifetime_ ||
            !config_.display.enabled || !displayApi_.copyMutable || !displayApi_.release ||
            !displayApi_.append || !displayApi_.readUuid ||
            !sameIdentity(bridge.principal, config_.display.principal) ||
            !sameIdentity(bridge.secondary, config_.display.secondary) ||
            bridge.principal.type != 110 || bridge.secondary.type != 111 ||
            !config_.display.widthPixels || config_.display.widthPixels > bridge.maximumWidth ||
            !config_.display.heightPixels || config_.display.heightPixels > bridge.maximumHeight) {
            throw std::invalid_argument("Matched identity, ready route, pinned lifetime and display API required");
        }
        factory = [session = session_, generation = generation_, create = std::move(factory),
                   width = config_.display.widthPixels, height = config_.display.heightPixels]
                  (const StreamIdentity& identity) -> std::unique_ptr<SecondaryDecoder> {
            if (!session->advertised(generation)) { return std::unique_ptr<SecondaryDecoder>{}; }
            auto decoder = create(identity);
            if (!decoder) { return nullptr; }
            return std::make_unique<GeometryDecoder>(std::move(decoder), width, height);
        };
    } else {
        factory = {};
    }
    ingress_ = std::make_unique<OemIngress>(original, originalSession, std::move(bridge),
        std::move(factory), std::move(uuidReader));
    if (config_.enabled && !session_->bindRuntime(generation_)) {
        throw std::invalid_argument("Session generation already bound or retired");
    }
}

SecondaryRuntime::~SecondaryRuntime() { close(); }
bool SecondaryRuntime::enabled() const noexcept { return config_.enabled; }

observed_abi::Status SecondaryRuntime::advertise(DisplayArray original,
    SecondarySession::Publish publish, void* context) {
    if (!config_.enabled || closed_.load()) { return VideoBridge::notReady; }
    return session_->advertise(generation_, original, config_.display, displayApi_, publish, context);
}

observed_abi::Status SecondaryRuntime::advertiseForGetter(DisplayArray original,
    SecondarySession::Publish publish, void* context) {
    if (!config_.enabled || closed_.load()) { return VideoBridge::notReady; }
    return session_->advertiseForGetter(generation_, original, config_.display, displayApi_, publish, context);
}

observed_abi::Status SecondaryRuntime::startSession(void* session, observed_abi::String uuid,
    std::uint32_t type, void* context) {
    if (config_.enabled && type == config_.bridge.secondary.type &&
        (closed_.load() || !session_->advertised(generation_))) { return VideoBridge::notReady; }
    return ingress_->startSession(session, uuid, type, context);
}

observed_abi::Status SecondaryRuntime::initialize(observed_abi::Stream stream) {
    return ingress_->initialize(stream);
}

observed_abi::Status SecondaryRuntime::adoptPrincipal(observed_abi::Stream stream,
    std::uint32_t type, observed_abi::String uuid) { return ingress_->adoptPrincipal(stream, type, uuid); }

void SecondaryRuntime::finalize(observed_abi::Stream stream) { ingress_->video().finalize(stream); }

observed_abi::Status SecondaryRuntime::dimensions(observed_abi::Stream stream,
    std::uint32_t width, std::uint32_t height) {
    return ingress_->video().setWidthHeight(stream, width, height);
}

observed_abi::Status SecondaryRuntime::start(observed_abi::Stream stream,
    std::uint32_t type, observed_abi::String uuid) { return ingress_->start(stream, type, uuid); }

void SecondaryRuntime::stop(observed_abi::Stream stream) { ingress_->video().stop(stream); }

observed_abi::Status SecondaryRuntime::avcc(observed_abi::Stream stream,
    const std::uint8_t* bytes, std::size_t length) { return ingress_->video().setAVCC(stream, bytes, length); }

observed_abi::Status SecondaryRuntime::data(observed_abi::Stream stream,
    const std::uint8_t* bytes, std::size_t length, std::uint64_t ticks,
    observed_abi::Dictionary options, observed_abi::Completion completion, void* context) {
    return ingress_->video().processData(stream, bytes, length, ticks, options, completion, context);
}

void SecondaryRuntime::close() {
    const bool alreadyClosed = closed_.exchange(true);
    if (config_.enabled && session_) { session_->revoke(generation_); }
    if (!alreadyClosed && ingress_) { ingress_->video().shutdownSecondary(); }
}

}

#include "coordinated_dispatch.h"

#include <condition_variable>
#include <stdexcept>
#include <thread>
#include <utility>

namespace carplay_experiment {
namespace {
bool complete(const DispatchTargets& targets) noexcept {
    const auto& video = targets.video;
    return video.initialize && video.finalize && video.setWidthHeight && video.start &&
        video.stop && video.setAVCC && video.processData && targets.session && targets.displays;
}
}

struct CoordinatedDispatch::State {
    State(DispatchTargets initial, std::shared_ptr<DispatchObservation> observer)
        : targets(initial), observation(std::move(observer)) {}
    std::mutex mutex;
    std::condition_variable resumed;
    DispatchTargets targets;
    const std::shared_ptr<DispatchObservation> observation;
    std::size_t active = 0;
    bool frozen = false;
    std::thread::id freezer;
};

class CoordinatedDispatch::Invocation final {
public:
    explicit Invocation(std::shared_ptr<State> state) : state_(std::move(state)) {
        std::unique_lock<std::mutex> lock(state_->mutex);
        if (state_->frozen && state_->freezer == std::this_thread::get_id()) {
            throw std::logic_error("OEM callback reentry from hook publication is forbidden");
        }
        state_->resumed.wait(lock, [this] { return !state_->frozen; });
        targets = state_->targets;
        observation = state_->observation;
        ++state_->active;
    }
    ~Invocation() {
        std::lock_guard<std::mutex> lock(state_->mutex);
        --state_->active;
    }
    DispatchTargets targets{};
    std::shared_ptr<DispatchObservation> observation;
private:
    const std::shared_ptr<State> state_;
};

CoordinatedDispatch::CoordinatedDispatch(DispatchTargets originals, std::shared_ptr<DispatchObservation> observation)
    : state_(std::make_shared<State>(originals, std::move(observation))) {
    if (!complete(originals)) { throw std::invalid_argument("All nine original dispatch targets required"); }
}

CoordinatedDispatch::Freeze::Freeze(std::shared_ptr<State> state) : state_(std::move(state)) {}

CoordinatedDispatch::Freeze::~Freeze() {
    if (!armed_) { return; }
    {
        std::lock_guard<std::mutex> lock(state_->mutex);
        state_->frozen = false;
        state_->freezer = {};
    }
    state_->resumed.notify_all();
}

bool CoordinatedDispatch::Freeze::replace(DispatchTargets targets) noexcept {
    if (!complete(targets)) { return false; }
    std::lock_guard<std::mutex> lock(state_->mutex);
    if (!armed_ || replaced_ || !state_->frozen || state_->active != 0 ||
        state_->freezer != std::this_thread::get_id()) {
        return false;
    }
    state_->targets = targets;
    replaced_ = true;
    return true;
}

std::unique_ptr<CoordinatedDispatch::Freeze> CoordinatedDispatch::tryFreeze() {
    auto freeze = std::unique_ptr<Freeze>(new Freeze(state_));
    std::lock_guard<std::mutex> lock(state_->mutex);
    if (state_->frozen || state_->active != 0) {
        return {};
    }
    state_->frozen = true;
    state_->freezer = std::this_thread::get_id();
    freeze->armed_ = true;
    return freeze;
}

observed_abi::Status CoordinatedDispatch::initialize(observed_abi::Stream stream) {
    Invocation call(state_); return call.targets.video.initialize(stream);
}
void CoordinatedDispatch::finalize(observed_abi::Stream stream) {
    Invocation call(state_);
    if (call.observation) { call.observation->retiring(stream); }
    call.targets.video.finalize(stream);
}
void CoordinatedDispatch::dimensions(const void* stream, std::uint32_t width, std::uint32_t height) {
    Invocation call(state_); call.targets.video.setWidthHeight(stream, width, height);
}
observed_abi::Status CoordinatedDispatch::start(observed_abi::Stream stream, std::uint32_t type,
    observed_abi::String uuid) {
    Invocation call(state_);
    if (call.observation) { call.observation->starting(stream, type, uuid); }
    const auto status = call.targets.video.start(stream, type, uuid);
    if (call.observation) { call.observation->started(status, stream, type, uuid); }
    return status;
}
void CoordinatedDispatch::stop(observed_abi::Stream stream) {
    Invocation call(state_);
    if (call.observation) { call.observation->retiring(stream); }
    call.targets.video.stop(stream);
}
observed_abi::Status CoordinatedDispatch::avcc(const void* stream, const std::uint8_t* bytes,
    std::size_t length) {
    Invocation call(state_); return call.targets.video.setAVCC(stream, bytes, length);
}
observed_abi::Status CoordinatedDispatch::data(observed_abi::Stream stream, const std::uint8_t* bytes,
    std::size_t length, std::uint64_t ticks, observed_abi::Dictionary options,
    observed_abi::Completion completion, void* context) {
    Invocation call(state_); return call.targets.video.processData(stream, bytes, length, ticks, options, completion, context);
}
observed_abi::Status CoordinatedDispatch::session(void* session, observed_abi::String uuid,
    std::uint32_t type, void* context) {
    Invocation call(state_);
    const auto invoke = [&] { return call.targets.session(session, uuid, type, context); };
    return call.observation ? call.observation->session(session, uuid, type, invoke) : invoke();
}
DisplayArray CoordinatedDispatch::displays(observed_abi::Status* error) {
    Invocation call(state_); return call.targets.displays(error);
}
void CoordinatedDispatch::sessionBoundary(const std::function<void()>& apply) {
    Invocation call(state_); apply();
}

namespace {
class DispatchTransaction final : public QuiescentHookTransaction {
public:
    DispatchTransaction(std::unique_ptr<CoordinatedDispatch::Freeze> freeze,
        DispatchPreparedHooks::Capture capture, DispatchPreparedHooks::Current current)
        : freeze_(std::move(freeze)), capture_(std::move(capture)), current_(std::move(current)) {}
    LivePrincipal capturePrincipal() override { return capture_(); }
    bool isCurrent(const LivePrincipal& principal) noexcept override {
        try { return current_(principal); } catch (...) { return false; }
    }
    bool publish(observed_abi::VideoCallbacks video, OriginalStartSession session,
        OriginalGetDisplays displays) noexcept override { return freeze_->replace({video, session, displays}); }
private:
    const std::unique_ptr<CoordinatedDispatch::Freeze> freeze_;
    const DispatchPreparedHooks::Capture capture_;
    const DispatchPreparedHooks::Current current_;
};
}

DispatchPreparedHooks::DispatchPreparedHooks(std::shared_ptr<CoordinatedDispatch> dispatch,
    AdapterFactory factory, Capture capture, Current current, std::shared_ptr<void> nativeOwners)
    : nativeOwners_(std::move(nativeOwners)), dispatch_(std::move(dispatch)), factory_(std::move(factory)),
      capture_(std::move(capture)), current_(std::move(current)) {
    if (!nativeOwners_ || !dispatch_ || !factory_ || !capture_ || !current_) {
        throw std::invalid_argument("Owned native targets, dispatch and live capture providers required");
    }
}

std::unique_ptr<OemRuntimeAdapter> DispatchPreparedHooks::makeAdapter() { return factory_(); }

std::unique_ptr<QuiescentHookTransaction> DispatchPreparedHooks::tryQuiesce() {
    auto freeze = dispatch_->tryFreeze();
    if (!freeze) { return {}; }
    return std::make_unique<DispatchTransaction>(std::move(freeze), capture_, current_);
}

}

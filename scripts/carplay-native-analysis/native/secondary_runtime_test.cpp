#include "secondary_runtime.h"

#include <cassert>
#include <future>
#include <iostream>
#include <stdexcept>

namespace carplay_experiment {
struct CFLArray { std::vector<int> displays; };
}

using namespace carplay_experiment;

namespace {
struct Counters {
    int sessions = 0;
    int initialize = 0;
    int frames = 0;
    int finalize = 0;
    int reads = 0;
    const std::uint8_t* bytes = nullptr;
    observed_abi::Dictionary options = nullptr;
    observed_abi::Completion completion = nullptr;
    void* context = nullptr;
} original;

struct Gate {
    std::mutex mutex;
    std::condition_variable changed;
    bool entered = false;
    bool released = false;
    void block() {
        std::unique_lock<std::mutex> lock(mutex);
        entered = true;
        changed.notify_all();
        changed.wait(lock, [this] { return released; });
    }
    void wait() {
        std::unique_lock<std::mutex> lock(mutex);
        assert(changed.wait_for(lock, std::chrono::seconds(2), [this] { return entered; }));
    }
    void release() {
        std::lock_guard<std::mutex> lock(mutex);
        released = true;
        changed.notify_all();
    }
};
struct DecoderState {
    int created = 0;
    int configured = 0;
    int frames = 0;
    int stopped = 0;
    Gate* submitGate = nullptr;
};
SecondaryRuntime* current = nullptr;
int mainKey = 0;
int secondaryKey = 0;
std::string mainUuid = "main";
std::string secondaryUuid = "secondary";
int liveArrays = 0;
std::uint32_t reportedWidth = 1280;

observed_abi::String string(std::string& value) { return reinterpret_cast<observed_abi::String>(&value); }
observed_abi::Stream stream(int& value) { return reinterpret_cast<observed_abi::Stream>(&value); }

const std::vector<std::uint8_t> configuration{1, 66, 0, 30, 0xff, 0xe1,
    0, 4, 0x67, 66, 0, 30, 1, 0, 2, 0x68, 0x88};
const std::vector<std::uint8_t> packet{0, 0, 0, 2, 0x65, 0x88};

class Backend final : public SecondaryDecoder {
public:
    explicit Backend(std::shared_ptr<DecoderState> state) : state_(std::move(state)) { ++state_->created; }
    bool configure(std::uint32_t width, std::uint32_t height,
        const std::vector<std::uint8_t>& bytes) override {
        assert(width == 1280 && height == 720 && bytes == configuration);
        ++state_->configured;
        return true;
    }
    SubmitResult submit(const std::vector<std::uint8_t>& bytes, std::uint64_t ticks) override {
        assert((bytes == std::vector<std::uint8_t>{0, 0, 0, 1, 0x65, 0x88}));
        assert(ticks == 1234);
        if (state_->submitGate) { state_->submitGate->block(); }
        ++state_->frames;
        return SubmitResult::Accepted;
    }
    void stop() noexcept override { ++state_->stopped; }
private:
    std::shared_ptr<DecoderState> state_;
};

observed_abi::VideoCallbacks originalCallbacks() {
    return {
        [](observed_abi::Stream) { ++original.initialize; return 0; },
        [](observed_abi::Stream) { ++original.finalize; },
        [](const void*, std::uint32_t, std::uint32_t) {},
        [](observed_abi::Stream, std::uint32_t, observed_abi::String) { return 0; },
        [](observed_abi::Stream) {},
        [](const void*, const std::uint8_t*, std::size_t) { return 0; },
        [](observed_abi::Stream, const std::uint8_t* bytes, std::size_t length,
            std::uint64_t ticks, observed_abi::Dictionary options, observed_abi::Completion completion, void* context) {
            assert(length == packet.size() && ticks == 1234);
            ++original.frames;
            original.bytes = bytes;
            original.options = options;
            original.completion = completion;
            original.context = context;
            return 37;
        }
    };
}

observed_abi::Status originalSession(void*, observed_abi::String uuid, std::uint32_t type, void*) {
    ++original.sessions;
    const auto target = type == 111 ? stream(secondaryKey) : stream(mainKey);
    auto status = current->initialize(target);
    if (status != 0) { return status; }
    status = current->dimensions(target, type == 111 ? reportedWidth : 1280, 720);
    if (status != 0) { return status; }
    status = current->avcc(target, configuration.data(), configuration.size());
    return status != 0 ? status : current->start(target, type, uuid);
}

UuidReader reader() {
    return [](observed_abi::String uuid) -> std::optional<std::string> {
        ++original.reads;
        if (!uuid) { return std::nullopt; }
        return *reinterpret_cast<std::string*>(uuid);
    };
}

DisplayAdvertisementApi displayApi() {
    return {
        [](DisplayArray array) { ++liveArrays; return new CFLArray(*array); },
        [](DisplayArray array) { --liveArrays; delete array; },
        [](DisplayArray* array, observed_abi::String, std::uint32_t, std::uint32_t,
            std::uint32_t, std::uint32_t, std::uint32_t, std::uint8_t*, std::uint8_t*,
            std::uint32_t, observed_abi::String) { (*array)->displays.push_back(111); return 0; },
        reader()
    };
}

SecondaryRuntimeConfig config() {
    SecondaryRuntimeConfig config;
    config.enabled = true;
    config.bridge.principal = {110, "main"};
    config.bridge.secondary = {111, "secondary"};
    config.display.enabled = true;
    config.display.principal = config.bridge.principal;
    config.display.secondary = config.bridge.secondary;
    config.display.secondaryUuid = string(secondaryUuid);
    config.display.framesPerSecond = 30;
    config.display.widthPixels = 1280;
    config.display.heightPixels = 720;
    config.display.widthMillimeters = 300;
    config.display.heightMillimeters = 100;
    return config;
}

struct Fixture {
    std::shared_ptr<DecoderState> state = std::make_shared<DecoderState>();
    std::shared_ptr<DecoderLease> lease = std::make_shared<DecoderLease>([this] {
        return std::make_unique<Backend>(state);
    });
    std::shared_ptr<SecondarySession> session = std::make_shared<SecondarySession>(true);
    std::uint64_t generation = session->arm(lease);
    std::shared_ptr<void> lifetime = std::make_shared<int>(1);

    VideoBridge::DecoderFactory factory() {
        return [retained = lease](const StreamIdentity&) { return retained->claim(); };
    }
    std::unique_ptr<SecondaryRuntime> runtime(SecondaryRuntimeConfig settings) {
        return std::make_unique<SecondaryRuntime>(originalCallbacks(), originalSession,
            std::move(settings), factory(), reader(), session, generation, displayApi(), lifetime);
    }
};

struct Published {
    DisplayArray value = nullptr;
    ~Published() { if (value) { displayApi().release(value); } }
};

void publish(DisplayArray array, void* context) noexcept { static_cast<Published*>(context)->value = array; }

observed_abi::Status send(SecondaryRuntime& runtime, observed_abi::Stream target,
    observed_abi::Dictionary options = nullptr, observed_abi::Completion completion = nullptr, void* context = nullptr) {
    return runtime.data(target, packet.data(), packet.size(), 1234, options, completion, context);
}

void defaultOffIsExactPassthrough() {
    original = {};
    auto settings = config();
    settings.enabled = false;
    SecondaryRuntime runtime(originalCallbacks(), originalSession, settings, {}, {});
    current = &runtime;
    assert(runtime.startSession(nullptr, nullptr, 111, nullptr) == 0);
    assert(original.sessions == 1 && original.initialize == 1 && original.reads == 0);
    auto options = reinterpret_cast<observed_abi::Dictionary>(&original);
    observed_abi::Completion completion = [](void*) {};
    assert(send(runtime, stream(secondaryKey), options, completion, &mainKey) == 37);
    assert(original.bytes == packet.data() && original.options == options &&
        original.completion == completion && original.context == &mainKey);
    runtime.close();
    assert(send(runtime, stream(secondaryKey)) == 37);
}

void completeSecondaryFlowLeavesPrincipalUntouched() {
    original = {};
    Fixture fixture;
    auto runtime = fixture.runtime(config());
    current = runtime.get();
    assert(runtime->startSession(nullptr, string(mainUuid), 110, nullptr) == 0);
    assert(runtime->startSession(nullptr, string(secondaryUuid), 111, nullptr) == VideoBridge::notReady);
    assert(original.sessions == 1 && original.initialize == 1 && fixture.state->created == 0);
    CFLArray originalArray{{110}};
    Published output;
    assert(runtime->advertise(&originalArray, publish, &output) == 0);
    assert((output.value->displays == std::vector<int>{110, 111}));
    assert((originalArray.displays == std::vector<int>{110}));
    assert(runtime->startSession(nullptr, string(secondaryUuid), 111, nullptr) == 0);
    assert(original.initialize == 1 && fixture.state->created == 1 && fixture.state->configured == 1);
    assert(send(*runtime, stream(secondaryKey)) == 0 && fixture.state->frames == 1);
    assert(send(*runtime, stream(mainKey)) == 37 && original.frames == 1);
    runtime->close();
    runtime->close();
    assert(fixture.state->stopped == 1);
    assert(send(*runtime, stream(secondaryKey)) != 0);
    assert(original.frames == 1);
    assert(send(*runtime, stream(mainKey)) == 37 && original.frames == 2);
    runtime->finalize(stream(mainKey));
    assert(runtime->startSession(nullptr, string(mainUuid), 110, nullptr) == 0);
    assert(original.finalize == 1 && original.initialize == 2);
    assert((packet == std::vector<std::uint8_t>{0, 0, 0, 2, 0x65, 0x88}));
}

void duplicateAndMismatchedRuntimeCannotTakeSession() {
    Fixture fixture;
    auto invalid = config();
    invalid.display.secondary.uuid = "different";
    try { fixture.runtime(invalid); assert(false); } catch (const std::invalid_argument&) {}
    assert(fixture.session->ready(fixture.generation));
    auto runtime = fixture.runtime(config());
    try { fixture.runtime(config()); assert(false); } catch (const std::invalid_argument&) {}
    current = runtime.get();
    CFLArray array{{110}};
    Published output;
    assert(runtime->advertise(&array, publish, &output) == 0);
    assert(output.value);
}

void oldRuntimeCannotRevokeNewGeneration() {
    Fixture fixture;
    auto runtime = fixture.runtime(config());
    runtime->close();
    auto newerLease = std::make_shared<DecoderLease>([] { return std::unique_ptr<SecondaryDecoder>{}; });
    const auto newer = fixture.session->arm(newerLease);
    assert(newer > fixture.generation && fixture.session->ready(newer));
    runtime->close();
    runtime.reset();
    assert(newerLease->active() && fixture.session->ready(newer));
    fixture.session->close();
}

void mismatchedVideoGeometryRetiresOnlySecondary() {
    original = {};
    Fixture fixture;
    auto runtime = fixture.runtime(config());
    current = runtime.get();
    CFLArray array{{110}};
    Published output;
    assert(runtime->advertise(&array, publish, &output) == 0);
    reportedWidth = 1920;
    assert(runtime->startSession(nullptr, string(secondaryUuid), 111, nullptr) != 0);
    reportedWidth = 1280;
    assert(fixture.state->configured == 0 && fixture.state->stopped == 1 && original.initialize == 0);
    assert(runtime->startSession(nullptr, string(mainUuid), 110, nullptr) == 0);
    assert(send(*runtime, stream(mainKey)) == 37 && original.frames == 1);
}

void principalProgressesWhileSecondaryCloseWaitsForFrame() {
    original = {};
    Fixture fixture;
    Gate gate;
    fixture.state->submitGate = &gate;
    auto runtime = fixture.runtime(config());
    current = runtime.get();
    CFLArray array{{110}};
    Published output;
    assert(runtime->advertise(&array, publish, &output) == 0);
    assert(runtime->startSession(nullptr, string(mainUuid), 110, nullptr) == 0);
    assert(runtime->startSession(nullptr, string(secondaryUuid), 111, nullptr) == 0);
    auto sending = std::async(std::launch::async, [&] { return send(*runtime, stream(secondaryKey)); });
    gate.wait();
    auto closing = std::async(std::launch::async, [&] { runtime->close(); });
    assert(closing.wait_for(std::chrono::milliseconds(20)) == std::future_status::timeout);
    assert(send(*runtime, stream(mainKey)) == 37 && original.frames == 1);
    gate.release();
    assert(sending.get() == 0);
    closing.get();
    assert(fixture.state->stopped == 1);
    assert(send(*runtime, stream(secondaryKey)) != 0 && original.frames == 1);
}
}

int main() {
    defaultOffIsExactPassthrough();
    completeSecondaryFlowLeavesPrincipalUntouched();
    duplicateAndMismatchedRuntimeCannotTakeSession();
    oldRuntimeCannotRevokeNewGeneration();
    mismatchedVideoGeometryRetiresOnlySecondary();
    principalProgressesWhileSecondaryCloseWaitsForFrame();
    assert(liveArrays == 0);
    std::cout << "Secondary runtime passthrough, advertisement/creation/frame isolation and generation checks passed\n";
}

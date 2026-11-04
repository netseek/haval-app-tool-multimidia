#include "secondary_session.h"

#include <cassert>
#include <chrono>
#include <future>
#include <iostream>

namespace carplay_experiment {
struct CFLArray { std::vector<int> displays; };
}

using namespace carplay_experiment;
using namespace std::chrono_literals;

namespace {
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
        assert(changed.wait_for(lock, 2s, [this] { return entered; }));
    }
    void release() {
        std::lock_guard<std::mutex> lock(mutex);
        released = true;
        changed.notify_all();
    }
};

struct DecoderState {
    std::atomic<int> stopped{0};
    Gate* stopGate = nullptr;
};

class Backend final : public SecondaryDecoder {
public:
    explicit Backend(std::shared_ptr<DecoderState> state) : state_(std::move(state)) {}
    bool configure(std::uint32_t, std::uint32_t, const std::vector<std::uint8_t>&) override { return false; }
    SubmitResult submit(const std::vector<std::uint8_t>&, std::uint64_t) override { return SubmitResult::Accepted; }
    void stop() noexcept override {
        if (state_->stopGate) { state_->stopGate->block(); }
        ++state_->stopped;
    }
private:
    std::shared_ptr<DecoderState> state_;
};

std::shared_ptr<DecoderLease> leaseFor(const std::shared_ptr<DecoderState>& state) {
    return std::make_shared<DecoderLease>([state] { return std::make_unique<Backend>(state); });
}

std::atomic<int> liveArrays{0};
std::atomic<Gate*> appendGate{nullptr};

DisplayArray copyArray(DisplayArray original) {
    auto copy = new CFLArray(*original);
    ++liveArrays;
    return copy;
}
void releaseArray(DisplayArray array) { delete array; --liveArrays; }
observed_abi::Status appendArray(DisplayArray* array, observed_abi::String,
    std::uint32_t, std::uint32_t, std::uint32_t, std::uint32_t, std::uint32_t,
    std::uint8_t*, std::uint8_t*, std::uint32_t, observed_abi::String) {
    if (auto gate = appendGate.load()) { gate->block(); }
    (*array)->displays.push_back(111);
    return 0;
}

struct Publication {
    DisplayArray array = nullptr;
    Gate* gate = nullptr;
    ~Publication() { if (array) { releaseArray(array); } }
};

void publish(DisplayArray array, void* context) noexcept {
    auto& output = *static_cast<Publication*>(context);
    assert(!output.array);
    if (output.gate) { output.gate->block(); }
    output.array = array;
}

SecondaryDisplayConfig configuration() {
    SecondaryDisplayConfig config;
    config.enabled = true;
    config.principal = {110, "main"};
    config.secondary = {111, "secondary"};
    config.secondaryUuid = reinterpret_cast<observed_abi::String>(&liveArrays);
    config.framesPerSecond = 30;
    config.widthPixels = 1280;
    config.heightPixels = 720;
    config.widthMillimeters = 300;
    config.heightMillimeters = 100;
    return config;
}

DisplayAdvertisementApi api() {
    return {copyArray, releaseArray, appendArray,
        [](observed_abi::String) { return std::optional<std::string>{"secondary"}; }};
}

void disabledAndSinglePublication() {
    auto state = std::make_shared<DecoderState>();
    auto lease = leaseFor(state);
    SecondarySession disabled;
    assert(disabled.arm(lease) == 0);
    CFLArray original{{110}};
    Publication output;
    assert(disabled.advertise(1, &original, configuration(), {}, publish, &output) != 0);
    assert(!output.array && liveArrays == 0 && lease->active());
    SecondarySession session(true);
    const auto generation = session.arm(lease);
    SecondarySession duplicate(true);
    assert(duplicate.arm(lease) == 0);
    auto client = lease->claim();
    assert(generation && session.ready(generation));
    assert(session.arm(leaseFor(std::make_shared<DecoderState>())) == 0);
    assert(session.advertise(generation, &original, configuration(), api(), publish, &output) == 0);
    assert(output.array && output.array != &original);
    assert((original.displays == std::vector<int>{110}));
    assert(session.active(generation) && !session.ready(generation));
    assert(session.advertise(generation, &original, configuration(), api(), publish, &output) != 0);
    assert(session.revoke(generation) && session.revoke(generation));
    assert(!session.active(generation) && state->stopped == 1);
    assert(client->submit({1}, 0) == SecondaryDecoder::SubmitResult::Fatal);
}

void revokeDuringPreparationRejectsStaleGeneration() {
    SecondarySession session(true);
    auto oldLease = leaseFor(std::make_shared<DecoderState>());
    const auto oldGeneration = session.arm(oldLease);
    CFLArray original{{110}};
    Publication output;
    Gate gate;
    appendGate.store(&gate);
    auto preparing = std::async(std::launch::async, [&] {
        return session.advertise(oldGeneration, &original, configuration(), api(), publish, &output);
    });
    gate.wait();
    assert(session.revoke(oldGeneration));
    assert(!oldLease->active());
    const auto newGeneration = session.arm(leaseFor(std::make_shared<DecoderState>()));
    assert(newGeneration > oldGeneration && session.ready(newGeneration));
    appendGate.store(nullptr);
    gate.release();
    assert(preparing.get() != 0 && !output.array);
    assert(!session.revoke(oldGeneration) && session.ready(newGeneration));
    assert(session.advertise(oldGeneration, &original, configuration(), api(), publish, &output) != 0);
}

void publicationSerializesEvenDirectLeaseRevoke() {
    auto state = std::make_shared<DecoderState>();
    auto lease = leaseFor(state);
    SecondarySession session(true);
    const auto generation = session.arm(lease);
    auto client = lease->claim();
    CFLArray original{{110}};
    Gate gate;
    Publication output;
    output.gate = &gate;
    auto publishing = std::async(std::launch::async, [&] {
        return session.advertise(generation, &original, configuration(), api(), publish, &output);
    });
    gate.wait();
    auto directRevoke = std::async(std::launch::async, [&] { lease->revoke(); });
    auto sessionRevoke = std::async(std::launch::async, [&] { return session.revoke(generation); });
    assert(directRevoke.wait_for(20ms) == std::future_status::timeout);
    assert(sessionRevoke.wait_for(20ms) == std::future_status::timeout);

    SecondarySession independent(true);
    const auto other = independent.arm(leaseFor(std::make_shared<DecoderState>()));
    Publication otherOutput;
    assert(independent.advertise(other, &original, configuration(), api(), publish, &otherOutput) == 0);
    gate.release();
    assert(publishing.get() == 0);
    directRevoke.get();
    assert(sessionRevoke.get());
    assert(state->stopped == 1 && !session.active(generation));
    assert(output.array && client->submit({1}, 0) == SecondaryDecoder::SubmitResult::Fatal);
}

void duplicateCloseWaitsForResourceRetirement() {
    Gate gate;
    auto state = std::make_shared<DecoderState>();
    state->stopGate = &gate;
    auto lease = leaseFor(state);
    SecondarySession session(true);
    const auto generation = session.arm(lease);
    auto client = lease->claim();
    auto closing = std::async(std::launch::async, [&] { session.close(); });
    gate.wait();
    auto repeated = std::async(std::launch::async, [&] { session.close(); });
    assert(repeated.wait_for(20ms) == std::future_status::timeout);
    assert(!session.ready(generation));
    assert(session.arm(leaseFor(std::make_shared<DecoderState>())) == 0);
    gate.release();
    closing.get();
    repeated.get();
    assert(state->stopped == 1);
    assert(!session.active(generation));
    assert(session.arm(leaseFor(std::make_shared<DecoderState>())) == 0);
}

void backendRetirementPreventsAdvertisement() {
    auto lease = leaseFor(std::make_shared<DecoderState>());
    SecondarySession session(true);
    const auto generation = session.arm(lease);
    auto client = lease->claim();
    assert(!client->configure(1, 1, {}));
    CFLArray original{{110}};
    Publication output;
    assert(session.advertise(generation, &original, configuration(), api(), publish, &output) != 0);
    assert(!session.ready(generation) && !output.array);
}

void closeDuringPreparationPreventsLatePublish() {
    SecondarySession session(true);
    const auto generation = session.arm(leaseFor(std::make_shared<DecoderState>()));
    CFLArray original{{110}};
    Publication output;
    Gate gate;
    appendGate.store(&gate);
    auto preparing = std::async(std::launch::async, [&] {
        return session.advertise(generation, &original, configuration(), api(), publish, &output);
    });
    gate.wait();
    session.close();
    assert(!session.active(generation));
    appendGate.store(nullptr);
    gate.release();
    assert(preparing.get() != 0 && !output.array);
}

void concurrentPreparationPublishesOnlyOnce() {
    SecondarySession session(true);
    const auto generation = session.arm(leaseFor(std::make_shared<DecoderState>()));
    CFLArray original{{110}};
    Publication output;
    Gate gate;
    appendGate.store(&gate);
    auto prepare = [&] {
        return session.advertise(generation, &original, configuration(), api(), publish, &output);
    };
    auto first = std::async(std::launch::async, prepare);
    gate.wait();
    auto second = std::async(std::launch::async, prepare);
    appendGate.store(nullptr);
    gate.release();
    const auto firstStatus = first.get();
    const auto secondStatus = second.get();
    assert((firstStatus == 0) != (secondStatus == 0));
    assert(output.array && session.active(generation));
}
}

int main() {
    disabledAndSinglePublication();
    revokeDuringPreparationRejectsStaleGeneration();
    publicationSerializesEvenDirectLeaseRevoke();
    duplicateCloseWaitsForResourceRetirement();
    backendRetirementPreventsAdvertisement();
    closeDuringPreparationPreventsLatePublish();
    concurrentPreparationPublishesOnlyOnce();
    assert(liveArrays == 0);
    std::cout << "Secondary session generation, publication/revoke serialization and isolation passed\n";
}

#include "decoder_lease.h"

#include <cassert>
#include <condition_variable>
#include <future>
#include <iostream>
#include <stdexcept>

using namespace carplay_experiment;

struct State {
    int stopped = 0;
    int destroyed = 0;
    int submitted = 0;
    bool fail = false;
    bool retryable = false;
    bool throws = false;
    std::mutex mutex;
    std::condition_variable changed;
    bool entered = false;
    bool released = false;
    bool block = false;
};

class FakeDecoder final : public SecondaryDecoder {
public:
    explicit FakeDecoder(std::shared_ptr<State> state) : state_(std::move(state)) {}
    ~FakeDecoder() override { ++state_->destroyed; }
    bool configure(std::uint32_t, std::uint32_t, const std::vector<std::uint8_t>&) override {
        if (state_->throws) { throw std::runtime_error("synthetic configure failure"); }
        return !state_->fail;
    }
    SubmitResult submit(const std::vector<std::uint8_t>&, std::uint64_t) override {
        std::unique_lock<std::mutex> lock(state_->mutex);
        ++state_->submitted;
        state_->entered = true;
        state_->changed.notify_all();
        if (state_->block) { state_->changed.wait(lock, [this] { return state_->released; }); }
        if (state_->throws) { throw std::runtime_error("synthetic submit failure"); }
        if (state_->fail) return SubmitResult::Fatal;
        if (state_->retryable) return SubmitResult::Retryable;
        return SubmitResult::Accepted;
    }
    void stop() noexcept override { ++state_->stopped; }
private:
    std::shared_ptr<State> state_;
};

std::shared_ptr<DecoderLease> makeLease(const std::shared_ptr<State>& state) {
    return std::make_shared<DecoderLease>([state] { return std::make_unique<FakeDecoder>(state); });
}

int main() {
    auto state = std::make_shared<State>();
    auto lease = makeLease(state);
    assert(lease->active());
    auto client = lease->claim();
    assert(lease->active());
    assert(client && !lease->claim());
    assert(client->configure(640, 480, {}));
    assert(client->submit({1}, 1) == SecondaryDecoder::SubmitResult::Accepted);
    lease->revoke();
    assert(!lease->active());
    lease->revoke();
    assert(client->submit({1}, 2) == SecondaryDecoder::SubmitResult::Fatal && !client->configure(640, 480, {}));
    client.reset();
    assert(state->stopped == 1 && state->destroyed == 1);

    for (bool throwing : {false, true}) {
        auto failed = std::make_shared<State>();
        failed->fail = true;
        failed->throws = throwing;
        auto failingLease = makeLease(failed);
        auto failingClient = failingLease->claim();
        assert(!failingClient->configure(1, 1, {}));
        assert(failed->stopped == 1 && failed->destroyed == 1);
        assert(!failingLease->claim());
        auto submitState = std::make_shared<State>();
        auto submitLease = makeLease(submitState);
        auto submitClient = submitLease->claim();
        submitState->fail = true;
        submitState->throws = throwing;
        assert(submitClient->submit({1}, 1) == SecondaryDecoder::SubmitResult::Fatal);
        assert(submitState->stopped == 1 && submitState->destroyed == 1);
    }

    auto retryState = std::make_shared<State>();
    retryState->retryable = true;
    auto retryLease = makeLease(retryState);
    auto retryClient = retryLease->claim();
    assert(retryClient->submit({1}, 1) == SecondaryDecoder::SubmitResult::Retryable);
    assert(retryLease->active() && retryState->stopped == 0 && retryState->destroyed == 0);
    retryState->retryable = false;
    assert(retryClient->submit({1}, 2) == SecondaryDecoder::SubmitResult::Accepted);
    retryClient.reset();
    assert(retryState->stopped == 1 && retryState->destroyed == 1);

    auto empty = std::make_shared<DecoderLease>([] { return std::unique_ptr<SecondaryDecoder>{}; });
    assert(!empty->claim() && !empty->claim());
    assert(!empty->active());
    auto exceptional = std::make_shared<DecoderLease>([]() -> std::unique_ptr<SecondaryDecoder> {
        throw std::runtime_error("synthetic factory failure");
    });
    try { exceptional->claim(); assert(false); } catch (const std::runtime_error&) {}
    assert(!exceptional->claim());
    assert(!exceptional->active());
    auto early = makeLease(std::make_shared<State>());
    early->revoke();
    assert(!early->claim());

    auto independentState = std::make_shared<State>();
    auto independent = makeLease(independentState);
    auto independentClient = independent->claim();
    auto blockedState = std::make_shared<State>();
    blockedState->block = true;
    auto blocked = makeLease(blockedState);
    auto blockedClient = blocked->claim();
    auto sending = std::async(std::launch::async, [&] { return blockedClient->submit({1}, 1); });
    {
        std::unique_lock<std::mutex> lock(blockedState->mutex);
        blockedState->changed.wait(lock, [&] { return blockedState->entered; });
    }
    std::promise<void> revoking;
    auto stopped = std::async(std::launch::async, [&] { revoking.set_value(); blocked->revoke(); });
    revoking.get_future().wait();
    assert(stopped.wait_for(std::chrono::milliseconds(20)) == std::future_status::timeout);
    assert(independentClient->submit({1}, 1) == SecondaryDecoder::SubmitResult::Accepted);
    {
        std::lock_guard<std::mutex> lock(blockedState->mutex);
        blockedState->released = true;
        blockedState->changed.notify_all();
    }
    assert(sending.get() == SecondaryDecoder::SubmitResult::Accepted);
    stopped.get();
    assert(blockedClient->submit({1}, 2) == SecondaryDecoder::SubmitResult::Fatal);
    assert(blockedState->stopped == 1 && independentState->stopped == 0);
    independentClient.reset();
    assert(independentState->stopped == 1);
    std::cout << "Decoder lease single claim, revocation, failure and concurrent isolation passed\n";
}

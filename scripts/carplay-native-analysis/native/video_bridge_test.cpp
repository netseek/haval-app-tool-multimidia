#include "video_bridge.h"

#include <condition_variable>
#include <cstdlib>
#include <future>
#include <iostream>
#include <stdexcept>
#include <thread>

using namespace carplay_experiment;

struct OriginalState {
    int initialize = 0;
    int finalize = 0;
    int dimensions = 0;
    int configuration = 0;
    int start = 0;
    int stop = 0;
    int frames = 0;
    const std::uint8_t* bytes = nullptr;
    std::size_t length = 0;
    std::uint64_t ticks = 0;
    observed_abi::Dictionary options = nullptr;
    observed_abi::Completion completion = nullptr;
    void* context = nullptr;
} original;

static void require(bool value) {
    if (!value) {
        std::cerr << "Bridge regression failed\n";
        std::exit(1);
    }
}

static observed_abi::VideoCallbacks callbacks() {
    return {
        [](observed_abi::Stream) { ++original.initialize; return 17; },
        [](observed_abi::Stream) { ++original.finalize; },
        [](const void*, std::uint32_t, std::uint32_t) { ++original.dimensions; },
        [](observed_abi::Stream, std::uint32_t, observed_abi::String) { ++original.start; return 19; },
        [](observed_abi::Stream) { ++original.stop; },
        [](const void*, const std::uint8_t*, std::size_t) { ++original.configuration; return 23; },
        [](observed_abi::Stream, const std::uint8_t* bytes, std::size_t length,
           std::uint64_t ticks, observed_abi::Dictionary options,
           observed_abi::Completion completion, void* context) {
            ++original.frames;
            original.bytes = bytes;
            original.length = length;
            original.ticks = ticks;
            original.options = options;
            original.completion = completion;
            original.context = context;
            return 29;
        }
    };
}

struct DecoderState {
    int creates = 0;
    int configures = 0;
    int frames = 0;
    int stops = 0;
    int destroys = 0;
    bool configureResult = true;
    SecondaryDecoder::SubmitResult submitResult = SecondaryDecoder::SubmitResult::Accepted;
    bool throwOnSubmit = false;
    bool holdSubmit = false;
    bool enteredSubmit = false;
    std::mutex mutex;
    std::condition_variable condition;
    std::vector<std::uint8_t> lastFrame;
    std::uint64_t ticks = 0;
};

class TestDecoder final : public SecondaryDecoder {
public:
    explicit TestDecoder(DecoderState& state) : state_(state) { ++state_.creates; }
    ~TestDecoder() override { ++state_.destroys; }
    bool configure(std::uint32_t, std::uint32_t, const std::vector<std::uint8_t>&) override {
        ++state_.configures;
        return state_.configureResult;
    }
    SubmitResult submit(const std::vector<std::uint8_t>& bytes, std::uint64_t ticks) override {
        if (state_.throwOnSubmit) {
            throw std::runtime_error("synthetic decoder failure");
        }
        std::unique_lock<std::mutex> lock(state_.mutex);
        state_.enteredSubmit = true;
        state_.condition.notify_all();
        state_.condition.wait(lock, [&] { return !state_.holdSubmit; });
        ++state_.frames;
        state_.lastFrame = bytes;
        state_.ticks = ticks;
        return state_.submitResult;
    }
    void stop() noexcept override { ++state_.stops; }
private:
    DecoderState& state_;
};

static BridgeConfig enabledConfig() {
    BridgeConfig config;
    config.enabled = true;
    config.principal = {71, "synthetic-main"};
    config.secondary = {93, "synthetic-secondary"};
    return config;
}

static VideoBridge::DecoderFactory factory(DecoderState& state) {
    return [&state](const StreamIdentity&) { return std::make_unique<TestDecoder>(state); };
}

static const std::vector<std::uint8_t> configuration{1, 66, 0, 30, 0xff, 0xe1,
    0, 4, 0x67, 66, 0, 30, 1, 0, 2, 0x68, 0x88};
static const std::vector<std::uint8_t> packet{0, 0, 0, 2, 0x65, 0x88};

static void ready(VideoBridge& bridge, VideoBridge::Stream stream, const BridgeConfig& config) {
    require(bridge.initialize(stream, &config.secondary) == 0);
    require(bridge.setWidthHeight(stream, 160, 96) == 0);
    require(bridge.setAVCC(stream, configuration.data(), configuration.size()) == 0);
    require(bridge.start(stream, config.secondary, nullptr) == 0);
}

static void defaultOff(VideoBridge::Stream stream) {
    original = {};
    DecoderState decoder;
    {
        VideoBridge bridge(callbacks(), {}, factory(decoder));
        require(bridge.initialize(stream, nullptr) == 17);
        require(bridge.setWidthHeight(stream, 1, 1) == 0);
        require(bridge.setAVCC(stream, packet.data(), packet.size()) == 23);
        require(bridge.start(stream, {99, "unchanged"}, nullptr) == 19);
        auto completion = +[](void*) {};
        auto options = reinterpret_cast<observed_abi::Dictionary>(stream);
        require(bridge.processData(stream, packet.data(), packet.size(), 123, options, completion, stream) == 29);
        require(original.bytes == packet.data() && original.length == packet.size());
        require(original.ticks == 123 && original.options == options);
        require(original.completion == completion && original.context == stream);
        bridge.stop(stream);
        bridge.finalize(stream);
    }
    require(original.initialize == 1 && original.start == 1 && original.stop == 1);
    require(original.finalize == 1 && original.configuration == 1 && original.frames == 1);
    require(decoder.creates == 0 && decoder.stops == 0);
}

int main() {
    int mainKey = 0;
    int secondaryKey = 0;
    auto mainStream = reinterpret_cast<VideoBridge::Stream>(&mainKey);
    auto secondaryStream = reinterpret_cast<VideoBridge::Stream>(&secondaryKey);
    defaultOff(mainStream);
    auto config = enabledConfig();
    {
        original = {};
        DecoderState decoder;
        VideoBridge bridge(callbacks(), config, factory(decoder));
        require(bridge.initialize(secondaryStream, nullptr) == VideoBridge::rejected);
        StreamIdentity unknown{71, "unrecognized"};
        require(bridge.initialize(secondaryStream, &unknown) == VideoBridge::rejected);
        require(original.initialize == 0 && decoder.creates == 0);
        require(bridge.initialize(mainStream, &config.principal) == 17);
        require(bridge.start(mainStream, config.secondary, nullptr) == VideoBridge::rejected);
        require(bridge.start(mainStream, config.principal, nullptr) == 19);
        ready(bridge, secondaryStream, config);
        require(bridge.initialize(secondaryStream, &config.secondary) == VideoBridge::rejected);
        require(bridge.processData(secondaryStream, packet.data(), packet.size(), 123, nullptr, nullptr, nullptr) == 0);
        require(decoder.frames == 1 && decoder.lastFrame == std::vector<std::uint8_t>({0, 0, 0, 1, 0x65, 0x88}));
        require(decoder.ticks == 123 && original.frames == 0 && original.initialize == 1);
        bridge.shutdownSecondary();
        bridge.shutdownSecondary();
        require(decoder.stops == 1 && decoder.destroys == 1 && original.stop == 0);
        require(bridge.processData(secondaryStream, packet.data(), packet.size(), 0, nullptr, nullptr, nullptr) == VideoBridge::rejected);
        require(bridge.processData(mainStream, packet.data(), packet.size(), 0, nullptr, nullptr, nullptr) == 29);
        bridge.finalize(secondaryStream);
        require(bridge.processData(secondaryStream, packet.data(), packet.size(), 0, nullptr, nullptr, nullptr) == VideoBridge::rejected);
        require(original.finalize == 0 && original.frames == 1);
        bridge.stop(mainStream);
        bridge.finalize(mainStream);
        require(original.stop == 1 && original.finalize == 1);
    }
    for (int failure = 0; failure < 5; ++failure) {
        original = {};
        DecoderState decoder;
        VideoBridge bridge(callbacks(), config, factory(decoder));
        ready(bridge, secondaryStream, config);
        if (failure == 0) {
            decoder.submitResult = SecondaryDecoder::SubmitResult::Fatal;
            require(bridge.processData(secondaryStream, packet.data(), packet.size(), 0, nullptr, nullptr, nullptr) == VideoBridge::decoderFailure);
        } else if (failure == 1) {
            require(bridge.processData(secondaryStream, packet.data(), 1, 0, nullptr, nullptr, nullptr) == VideoBridge::invalidInput);
        } else if (failure == 2) {
            require(bridge.processData(secondaryStream, packet.data(), packet.size(), 0, nullptr, +[](void*) {}, nullptr) == VideoBridge::unsupportedCompletion);
        } else if (failure == 3) {
            require(bridge.setWidthHeight(secondaryStream, 192, 112) == VideoBridge::invalidInput);
        } else {
            decoder.throwOnSubmit = true;
            require(bridge.processData(secondaryStream, packet.data(), packet.size(), 0, nullptr, nullptr, nullptr) == VideoBridge::decoderFailure);
        }
        bridge.finalize(secondaryStream);
        require(decoder.stops == 1 && decoder.destroys == 1);
        require(original.initialize == 0 && original.frames == 0 && original.stop == 0);
    }
    {
        DecoderState decoder;
        VideoBridge bridge(callbacks(), config, factory(decoder));
        ready(bridge, secondaryStream, config);
        decoder.submitResult = SecondaryDecoder::SubmitResult::Retryable;
        require(bridge.processData(secondaryStream, packet.data(), packet.size(), 0, nullptr, nullptr, nullptr) == 0);
        require(decoder.stops == 0 && decoder.destroys == 0);
        decoder.submitResult = SecondaryDecoder::SubmitResult::Accepted;
        require(bridge.processData(secondaryStream, packet.data(), packet.size(), 1, nullptr, nullptr, nullptr) == 0);
        bridge.finalize(secondaryStream);
        require(decoder.frames == 2 && decoder.stops == 1 && decoder.destroys == 1);
    }
    {
        DecoderState decoder;
        decoder.configureResult = false;
        VideoBridge bridge(callbacks(), config, factory(decoder));
        require(bridge.initialize(secondaryStream, &config.secondary) == 0);
        require(bridge.start(secondaryStream, config.secondary, nullptr) == 0);
        require(bridge.setWidthHeight(secondaryStream, 160, 96) == 0);
        require(bridge.setAVCC(secondaryStream, configuration.data(), configuration.size()) == VideoBridge::decoderFailure);
        require(decoder.stops == 1);
    }
    {
        DecoderState decoder;
        decoder.holdSubmit = true;
        VideoBridge bridge(callbacks(), config, factory(decoder));
        ready(bridge, secondaryStream, config);
        auto submit = std::async(std::launch::async, [&] {
            return bridge.processData(secondaryStream, packet.data(), packet.size(), 0, nullptr, nullptr, nullptr);
        });
        {
            std::unique_lock<std::mutex> lock(decoder.mutex);
            require(decoder.condition.wait_for(lock, std::chrono::seconds(2), [&] { return decoder.enteredSubmit; }));
        }
        auto shutdown = std::async(std::launch::async, [&] { bridge.shutdownSecondary(); });
        require(shutdown.wait_for(std::chrono::milliseconds(20)) == std::future_status::timeout);
        {
            std::lock_guard<std::mutex> lock(decoder.mutex);
            decoder.holdSubmit = false;
            decoder.condition.notify_all();
        }
        require(submit.get() == 0);
        shutdown.get();
        require(decoder.stops == 1 && decoder.destroys == 1);
    }
    {
        original = {};
        DecoderState decoder;
        VideoBridge bridge(callbacks(), config, factory(decoder));
        require(bridge.initialize(secondaryStream, &config.secondary) == 0);
        require(bridge.processData(secondaryStream, packet.data(), packet.size(), 0, nullptr, nullptr, nullptr) == VideoBridge::notReady);
        require(bridge.initialize(mainStream, &config.secondary) == VideoBridge::rejected);
        const std::vector<std::uint8_t> invalidConfiguration{1, 66, 0, 30, 0xff, 0xe1, 0};
        require(bridge.setAVCC(secondaryStream, invalidConfiguration.data(), invalidConfiguration.size()) == VideoBridge::invalidInput);
        require(decoder.stops == 1 && original.initialize == 0);
    }
    {
        original = {};
        DecoderState decoder;
        VideoBridge bridge(callbacks(), config, [](const StreamIdentity&) -> std::unique_ptr<SecondaryDecoder> {
            throw std::runtime_error("synthetic factory failure");
        });
        require(bridge.initialize(secondaryStream, &config.secondary) == VideoBridge::decoderFailure);
        require(bridge.processData(secondaryStream, packet.data(), packet.size(), 0, nullptr, nullptr, nullptr) == VideoBridge::rejected);
        require(original.initialize == 0 && decoder.creates == 0);
    }
    {
        DecoderState decoder;
        auto bounded = config;
        bounded.maximumPacketBytes = 5;
        VideoBridge bridge(callbacks(), bounded, factory(decoder));
        ready(bridge, secondaryStream, bounded);
        require(bridge.processData(secondaryStream, packet.data(), packet.size(), 0, nullptr, nullptr, nullptr) == VideoBridge::invalidInput);
        require(decoder.stops == 1 && decoder.frames == 0);
    }
    std::cout << "Native bridge routing, ownership, failure and concurrent shutdown checks passed\n";
}

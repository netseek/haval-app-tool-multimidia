#include "oem_ingress.h"

#include <cstdlib>
#include <cstring>
#include <future>
#include <iostream>
#include <stdexcept>

using namespace carplay_experiment;

struct FakeString { std::string value; } mainUuid{"synthetic-main"}, secondaryUuid{"synthetic-secondary"};
static int mainKey = 0;
static int secondaryKey = 0;
static int unknownKey = 0;
static int originalInitializes = 0;
static int originalFrames = 0;
static int originalSessions = 0;
static int decoderCreates = 0;
static int decoderStops = 0;
static int decoderFrames = 0;
static int stringReads = 0;
static bool nested = false;
static bool foreignThread = false;
static bool throwSession = false;

static void require(bool condition) {
    if (!condition) { std::cerr << "Ingress regression failed\n"; std::exit(1); }
}
static observed_abi::String string(FakeString& value) { return reinterpret_cast<observed_abi::String>(&value); }
static observed_abi::Stream stream(int& value) { return reinterpret_cast<observed_abi::Stream>(&value); }
static std::int32_t length(observed_abi::String value) {
    ++stringReads;
    return static_cast<std::int32_t>(reinterpret_cast<FakeString*>(value)->value.size());
}
static std::uint8_t copy(observed_abi::String value, char* output, std::int32_t maximum, std::uint32_t encoding) {
    require(encoding == 0x08000100);
    const auto& bytes = reinterpret_cast<FakeString*>(value)->value;
    if (bytes.size() + 1 > static_cast<std::size_t>(maximum)) { return 0; }
    std::memcpy(output, bytes.c_str(), bytes.size() + 1);
    return 1;
}

class Decoder final : public SecondaryDecoder {
public:
    bool configure(std::uint32_t, std::uint32_t, const std::vector<std::uint8_t>&) override { return true; }
    SubmitResult submit(const std::vector<std::uint8_t>&, std::uint64_t) override {
        ++decoderFrames;
        return SubmitResult::Accepted;
    }
    void stop() noexcept override { ++decoderStops; }
};

static observed_abi::VideoCallbacks originals() {
    return {
        [](observed_abi::Stream) { ++originalInitializes; return 0; },
        [](observed_abi::Stream) {},
        [](const void*, std::uint32_t, std::uint32_t) {},
        [](observed_abi::Stream, std::uint32_t, observed_abi::String) { return 0; },
        [](observed_abi::Stream) {},
        [](const void*, const std::uint8_t*, std::size_t) { return 0; },
        [](observed_abi::Stream, const std::uint8_t*, std::size_t, std::uint64_t,
           observed_abi::Dictionary, observed_abi::Completion, void*) { ++originalFrames; return 31; }
    };
}

static observed_abi::Status originalSession(void* session, observed_abi::String uuid,
                                           std::uint32_t type, void* context) {
    ++originalSessions;
    require(session == &mainKey && context == &unknownKey);
    if (throwSession) { throw std::runtime_error("synthetic original exception"); }
    if (nested && type == 93) {
        require(impulse_carplay_start_session(session, string(mainUuid), 71, context) == 0);
    }
    if (foreignThread) {
        auto result = std::async(std::launch::async, [] { return impulse_carplay_stream_initialize(stream(unknownKey)); });
        require(result.get() == VideoBridge::rejected);
    }
    auto target = type == 93 ? stream(secondaryKey) : stream(mainKey);
    const auto status = impulse_carplay_stream_initialize(target);
    return status == 0 ? impulse_carplay_stream_start(target, type, uuid) : status;
}

int main(int count, char** arguments) {
    require(count == 2);
    const bool enabled = std::string(arguments[1]) == "enabled";
    require(impulse_carplay_stream_initialize(stream(mainKey)) == VideoBridge::rejected);
    auto callbacks = originals();
    auto reader = makeCapturedUuidReader(length, copy);
    FakeString embedded{std::string("synthetic-main\0extra", 20)};
    FakeString oversized{std::string(129, 'a')};
    FakeString invalid{"map/name"};
    require(!reader(string(embedded)) && !reader(string(oversized)) && !reader(string(invalid)));
    require(!reader(nullptr));
    require(reader(string(mainUuid)) == std::optional<std::string>("synthetic-main"));
    stringReads = 0;
    if (!enabled) {
        require(impulse_carplay_install_passthrough(&callbacks, originalSession, length, copy) == 0);
        require(impulse_carplay_install_passthrough(&callbacks, originalSession, length, copy) == VideoBridge::rejected);
        require(impulse_carplay_start_session(&mainKey, nullptr, 71, &unknownKey) == 0);
        require(stringReads == 0 && originalInitializes == 1);
        require(impulse_carplay_stream_data(stream(mainKey), nullptr, 0, 1, nullptr, nullptr, nullptr) == 31);
        impulse_carplay_stream_finalize(stream(mainKey));
    } else {
        BridgeConfig config;
        config.enabled = true;
        config.principal = {71, mainUuid.value};
        config.secondary = {93, secondaryUuid.value};
        auto adapter = std::make_unique<OemIngress>(callbacks, originalSession, config,
            [](const StreamIdentity& identity) {
                require(identity.uuid == secondaryUuid.value && identity.type == 93);
                ++decoderCreates;
                return std::make_unique<Decoder>();
            }, reader);
        require(pinIngressForProcess(std::move(adapter)));
        nested = true;
        foreignThread = true;
        require(impulse_carplay_start_session(&mainKey, string(secondaryUuid), 93, &unknownKey) == 0);
        require(originalInitializes == 1 && decoderCreates == 1 && originalSessions == 2);
        require(impulse_carplay_stream_initialize(stream(unknownKey)) == VideoBridge::rejected);
        const std::vector<std::uint8_t> avcc{1, 66, 0, 30, 0xff, 0xe1, 0, 4, 0x67, 66, 0, 30, 1, 0, 2, 0x68, 0x88};
        impulse_carplay_stream_dimensions(stream(secondaryKey), 160, 96);
        require(impulse_carplay_stream_avcc(stream(secondaryKey), avcc.data(), avcc.size()) == 0);
        const std::uint8_t packet[]{0, 0, 0, 1, 0x65};
        require(impulse_carplay_stream_data(stream(secondaryKey), packet, sizeof(packet), 2000, nullptr, nullptr, nullptr) == 0);
        require(decoderFrames == 1 && originalFrames == 0);
        impulse_carplay_stream_finalize(stream(secondaryKey));
        impulse_carplay_stream_finalize(stream(mainKey));
        require(decoderStops == 1);
        const auto before = originalSessions;
        require(impulse_carplay_start_session(&mainKey, string(embedded), 71, &unknownKey) == VideoBridge::rejected);
        require(originalSessions == before);
        foreignThread = false;
        nested = false;
    }
    throwSession = true;
    require(impulse_carplay_start_session(&mainKey, string(mainUuid), 71, &unknownKey) == VideoBridge::decoderFailure);
    require(impulse_carplay_boundary_faults() == 1);
    throwSession = false;
    if (enabled) {
        require(impulse_carplay_stream_initialize(stream(unknownKey)) == VideoBridge::rejected);
    }
    impulse_carplay_shutdown_secondary();
    std::cout << "OEM ingress " << (enabled ? "enabled" : "passthrough")
              << ": ABI boundary, thread scope, nested identity and exception checks passed\n";
}

#pragma once

#include "oem_ingress.h"

namespace carplay_experiment {

struct CFLArray;
using DisplayArray = CFLArray*;
using AddAlternateDisplay = observed_abi::Status (*)(
    DisplayArray*, observed_abi::String, std::uint32_t, std::uint32_t, std::uint32_t,
    std::uint32_t, std::uint32_t, std::uint8_t*, std::uint8_t*, std::uint32_t,
    observed_abi::String);

struct DisplayAdvertisementApi {
    DisplayArray (*copyMutable)(DisplayArray) = nullptr;
    void (*release)(DisplayArray) = nullptr;
    AddAlternateDisplay append = nullptr;
    UuidReader readUuid;
};

struct SecondaryDisplayConfig {
    bool enabled = false;
    StreamIdentity principal{0, {}};
    StreamIdentity secondary{0, {}};
    observed_abi::String secondaryUuid = nullptr;
    std::uint32_t framesPerSecond = 0;
    std::uint32_t widthPixels = 0;
    std::uint32_t heightPixels = 0;
    std::uint32_t widthMillimeters = 0;
    std::uint32_t heightMillimeters = 0;
};

class PreparedDisplays final {
public:
    PreparedDisplays(DisplayArray original, observed_abi::Status status = 0) noexcept;
    PreparedDisplays(DisplayArray value, void (*release)(DisplayArray)) noexcept;
    ~PreparedDisplays();
    PreparedDisplays(const PreparedDisplays&) = delete;
    PreparedDisplays& operator=(const PreparedDisplays&) = delete;
    PreparedDisplays(PreparedDisplays&& other) noexcept;
    PreparedDisplays& operator=(PreparedDisplays&&) = delete;
    DisplayArray get() const noexcept;
    observed_abi::Status status() const noexcept;
    bool hasSecondary() const noexcept;
    DisplayArray takePrepared() noexcept;

private:
    DisplayArray value_;
    void (*release_)(DisplayArray) = nullptr;
    observed_abi::Status status_;
};

PreparedDisplays prepareSecondaryDisplay(DisplayArray original, const SecondaryDisplayConfig& config,
    const DisplayAdvertisementApi& api, const std::function<bool()>& routeReady);

}

#include "secondary_advertisement.h"

#include <cassert>
#include <iostream>
#include <stdexcept>

namespace carplay_experiment {
struct CFLArray { std::vector<int> displays; };
}

using namespace carplay_experiment;

namespace {
int copies = 0;
int releases = 0;
int appends = 0;
bool aliasCopy = false;
bool nullCopy = false;
bool throwAppend = false;
bool replaceArray = false;
observed_abi::Status appendStatus = 0;
DisplayArray originalPointer = nullptr;

DisplayArray copyArray(DisplayArray array) {
    ++copies;
    if (aliasCopy) { return array; }
    if (nullCopy) { return nullptr; }
    return new CFLArray(*array);
}

void releaseArray(DisplayArray array) {
    assert(array != originalPointer);
    ++releases;
    delete array;
}

observed_abi::Status appendArray(DisplayArray* array, observed_abi::String uuid,
    std::uint32_t fps, std::uint32_t width, std::uint32_t height,
    std::uint32_t physicalWidth, std::uint32_t physicalHeight, std::uint8_t* night,
    std::uint8_t* limited, std::uint32_t limitedTypes, observed_abi::String url) {
    ++appends;
    assert(array && *array != originalPointer && uuid);
    assert(fps == 30 && width == 1280 && height == 720);
    assert(physicalWidth == 300 && physicalHeight == 100);
    assert(!night && !limited && limitedTypes == 0 && !url);
    (*array)->displays.push_back(111);
    if (throwAppend) { throw std::runtime_error("synthetic append failure"); }
    if (replaceArray) { *array = new CFLArray{{999}}; }
    return appendStatus;
}
}

int main() {
    CFLArray original{{110, 77}};
    originalPointer = &original;
    SecondaryDisplayConfig config;
    config.principal = {110, "main"};
    config.secondary = {111, "secondary"};
    config.secondaryUuid = reinterpret_cast<observed_abi::String>(&config);
    config.framesPerSecond = 30;
    config.widthPixels = 1280;
    config.heightPixels = 720;
    config.widthMillimeters = 300;
    config.heightMillimeters = 100;
    DisplayAdvertisementApi api{copyArray, releaseArray, appendArray,
        [](observed_abi::String) { return std::optional<std::string>{"secondary"}; }};

    auto disabled = prepareSecondaryDisplay(&original, config, {}, {});
    assert(disabled.get() == &original && disabled.status() == 0 && !disabled.hasSecondary());
    assert(!disabled.takePrepared() && copies == 0 && appends == 0);
    config.enabled = true;
    {
        auto prepared = prepareSecondaryDisplay(&original, config, api, [] { return true; });
        assert(prepared.hasSecondary());
        assert((prepared.get()->displays == std::vector<int>{110, 77, 111}));
        assert((original.displays == std::vector<int>{110, 77}));
        auto moved = std::move(prepared);
        assert(!prepared.hasSecondary() && moved.hasSecondary());
        auto owned = moved.takePrepared();
        assert(owned && !moved.hasSecondary());
        releaseArray(owned);
    }
    const auto successfulCopies = copies;
    assert(prepareSecondaryDisplay(&original, config, api, [] { return false; }).status() != 0);
    auto mismatched = api;
    mismatched.readUuid = [](observed_abi::String) { return std::optional<std::string>{"wrong"}; };
    assert(prepareSecondaryDisplay(&original, config, mismatched, [] { return true; }).status() != 0);
    auto invalid = config;
    invalid.secondary.uuid = "main";
    assert(prepareSecondaryDisplay(&original, invalid, api, [] { return true; }).status() != 0);
    invalid = config;
    invalid.secondary.type = 110;
    assert(prepareSecondaryDisplay(&original, invalid, api, [] { return true; }).status() != 0);
    invalid = config;
    invalid.widthMillimeters = 0;
    assert(prepareSecondaryDisplay(&original, invalid, api, [] { return true; }).status() != 0);
    assert(copies == successfulCopies);
    aliasCopy = true;
    assert(!prepareSecondaryDisplay(&original, config, api, [] { return true; }).hasSecondary());
    aliasCopy = false;
    nullCopy = true;
    assert(!prepareSecondaryDisplay(&original, config, api, [] { return true; }).hasSecondary());
    nullCopy = false;
    const auto releasedBeforeFailure = releases;
    appendStatus = -123;
    assert(prepareSecondaryDisplay(&original, config, api, [] { return true; }).status() == -123);
    appendStatus = 0;
    throwAppend = true;
    assert(!prepareSecondaryDisplay(&original, config, api, [] { return true; }).hasSecondary());
    throwAppend = false;
    replaceArray = true;
    assert(!prepareSecondaryDisplay(&original, config, api, [] { return true; }).hasSecondary());
    replaceArray = false;
    int checks = 0;
    assert(!prepareSecondaryDisplay(&original, config, api, [&] { return ++checks == 1; }).hasSecondary());
    assert(releases == releasedBeforeFailure + 5);
    assert((original.displays == std::vector<int>{110, 77}));
    std::cout << "Secondary advertisement default-off, ownership, rollback and readiness checks passed\n";
}

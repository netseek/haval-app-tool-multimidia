#pragma once

#include <cstddef>
#include <cstdint>
#include <type_traits>

namespace carplay_experiment::observed_abi {

struct ScreenStreamPrivate;
struct CFLString;
struct CFLDictionary;

using Stream = ScreenStreamPrivate*;
using String = CFLString*;
using Dictionary = CFLDictionary*;
using Status = std::int32_t;
using StreamType = std::uint32_t;
using Completion = void (*)(void*);

struct VideoCallbacks {
    Status (*initialize)(Stream);
    void (*finalize)(Stream);
    void (*setWidthHeight)(const void*, std::uint32_t, std::uint32_t);
    Status (*start)(Stream, StreamType, String);
    void (*stop)(Stream);
    Status (*setAVCC)(const void*, const std::uint8_t*, std::size_t);
    Status (*processData)(Stream, const std::uint8_t*, std::size_t,
                          std::uint64_t, Dictionary, Completion, void*);
};

static_assert(sizeof(void*) == 8);
static_assert(sizeof(std::size_t) == 8);
static_assert(sizeof(Status) == 4);
static_assert(sizeof(StreamType) == 4);
static_assert(std::is_standard_layout_v<VideoCallbacks>);
static_assert(sizeof(VideoCallbacks) == 56);
static_assert(offsetof(VideoCallbacks, initialize) == 0);
static_assert(offsetof(VideoCallbacks, finalize) == 8);
static_assert(offsetof(VideoCallbacks, setWidthHeight) == 16);
static_assert(offsetof(VideoCallbacks, start) == 24);
static_assert(offsetof(VideoCallbacks, stop) == 32);
static_assert(offsetof(VideoCallbacks, setAVCC) == 40);
static_assert(offsetof(VideoCallbacks, processData) == 48);

}

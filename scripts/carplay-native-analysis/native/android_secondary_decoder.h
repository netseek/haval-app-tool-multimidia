#pragma once

#include "video_bridge.h"

#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <functional>
#include <optional>
#include <thread>

namespace carplay_experiment {

class AndroidSecondaryDecoder final : public SecondaryDecoder {
public:
    using TickConverter = std::function<std::optional<std::int64_t>(std::uint64_t)>;

    AndroidSecondaryDecoder(ANativeWindow* independentWindow, TickConverter tickConverter);
    ~AndroidSecondaryDecoder() override;
    bool configure(std::uint32_t width, std::uint32_t height,
                   const std::vector<std::uint8_t>& avcc) override;
    SubmitResult submit(const std::vector<std::uint8_t>& annexB, std::uint64_t ticks) override;
    void stop() noexcept override;

private:
    void drain() noexcept;

    ANativeWindow* window_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    const TickConverter tickConverter_;
    std::thread outputThread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> failed_{false};
    bool started_ = false;
    bool hasTimestamp_ = false;
    std::int64_t lastTimestamp_ = 0;
    std::uint32_t width_ = 0;
    std::uint32_t height_ = 0;
};

}

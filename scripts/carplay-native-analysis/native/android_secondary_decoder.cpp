#include "android_secondary_decoder.h"
#include "avc_configuration.h"

#include <media/NdkMediaFormat.h>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <utility>

namespace carplay_experiment {

AndroidSecondaryDecoder::AndroidSecondaryDecoder(ANativeWindow* independentWindow,
                                               TickConverter tickConverter)
    : window_(independentWindow), tickConverter_(std::move(tickConverter)) {
    if (!window_ || !tickConverter_) {
        throw std::invalid_argument("Independent window and explicit tick conversion required");
    }
    ANativeWindow_acquire(window_);
}

AndroidSecondaryDecoder::~AndroidSecondaryDecoder() {
    stop();
}

bool AndroidSecondaryDecoder::configure(std::uint32_t width, std::uint32_t height,
                                        const std::vector<std::uint8_t>& avcc) {
    if (!window_ || codec_ || !width || !height ||
        width > static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max()) ||
        height > static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max())) {
        return false;
    }
    const auto configuration = parseAvcConfiguration(avcc, 64 * 1024);
    if (!configuration.valid) {
        return false;
    }
    codec_ = AMediaCodec_createDecoderByType("video/avc");
    if (!codec_) {
        return false;
    }
    std::unique_ptr<AMediaFormat, decltype(&AMediaFormat_delete)> format(
        AMediaFormat_new(), AMediaFormat_delete);
    if (!format) {
        stop();
        return false;
    }
    AMediaFormat_setString(format.get(), AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format.get(), AMEDIAFORMAT_KEY_WIDTH, static_cast<std::int32_t>(width));
    AMediaFormat_setInt32(format.get(), AMEDIAFORMAT_KEY_HEIGHT, static_cast<std::int32_t>(height));
    AMediaFormat_setBuffer(format.get(), "csd-0", configuration.sequenceParameters.data(),
                          configuration.sequenceParameters.size());
    AMediaFormat_setBuffer(format.get(), "csd-1", configuration.pictureParameters.data(),
                          configuration.pictureParameters.size());
    if (AMediaCodec_configure(codec_, format.get(), window_, nullptr, 0) != AMEDIA_OK ||
        AMediaCodec_start(codec_) != AMEDIA_OK) {
        stop();
        return false;
    }
    started_ = true;
    width_ = width;
    height_ = height;
    running_.store(true);
    try {
        outputThread_ = std::thread([this] { drain(); });
    } catch (...) {
        stop();
        return false;
    }
    return true;
}

SecondaryDecoder::SubmitResult AndroidSecondaryDecoder::submit(
    const std::vector<std::uint8_t>& annexB, std::uint64_t ticks) {
    if (!running_.load() || failed_.load() || !codec_ || annexB.empty()) {
        return SubmitResult::Fatal;
    }
    const auto timestamp = tickConverter_(ticks);
    if (!timestamp || *timestamp < 0 || (hasTimestamp_ && *timestamp < lastTimestamp_)) {
        return SubmitResult::Fatal;
    }
    const auto index = AMediaCodec_dequeueInputBuffer(codec_, 2000);
    if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
        return SubmitResult::Retryable;
    }
    if (index < 0) {
        return SubmitResult::Fatal;
    }
    std::size_t capacity = 0;
    auto* buffer = AMediaCodec_getInputBuffer(codec_, static_cast<std::size_t>(index), &capacity);
    if (!buffer || capacity < annexB.size()) {
        return SubmitResult::Fatal;
    }
    std::memcpy(buffer, annexB.data(), annexB.size());
    if (AMediaCodec_queueInputBuffer(codec_, static_cast<std::size_t>(index), 0,
                                    annexB.size(), static_cast<std::uint64_t>(*timestamp), 0) != AMEDIA_OK) {
        return SubmitResult::Fatal;
    }
    hasTimestamp_ = true;
    lastTimestamp_ = *timestamp;
    return failed_.load() ? SubmitResult::Fatal : SubmitResult::Accepted;
}

void AndroidSecondaryDecoder::drain() noexcept {
    while (running_.load()) {
        AMediaCodecBufferInfo info{};
        const auto index = AMediaCodec_dequeueOutputBuffer(codec_, &info, 10000);
        if (index >= 0) {
            if (AMediaCodec_releaseOutputBuffer(codec_, static_cast<std::size_t>(index), true) != AMEDIA_OK) {
                failed_.store(true);
                running_.store(false);
            }
        } else if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            std::unique_ptr<AMediaFormat, decltype(&AMediaFormat_delete)> format(
                AMediaCodec_getOutputFormat(codec_), AMediaFormat_delete);
            std::int32_t width = 0;
            std::int32_t height = 0;
            if (!format || !AMediaFormat_getInt32(format.get(), AMEDIAFORMAT_KEY_WIDTH, &width) ||
                !AMediaFormat_getInt32(format.get(), AMEDIAFORMAT_KEY_HEIGHT, &height) ||
                width != static_cast<std::int32_t>(width_) || height != static_cast<std::int32_t>(height_)) {
                failed_.store(true);
                running_.store(false);
            }
        } else if (index != AMEDIACODEC_INFO_TRY_AGAIN_LATER &&
                   index != AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            failed_.store(true);
            running_.store(false);
        }
    }
}

void AndroidSecondaryDecoder::stop() noexcept {
    running_.store(false);
    if (outputThread_.joinable()) {
        outputThread_.join();
    }
    if (codec_) {
        if (started_) {
            AMediaCodec_stop(codec_);
        }
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    started_ = false;
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

}

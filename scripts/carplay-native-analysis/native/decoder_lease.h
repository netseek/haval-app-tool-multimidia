#pragma once

#include "video_bridge.h"

namespace carplay_experiment {

class DecoderLease final : public std::enable_shared_from_this<DecoderLease> {
public:
    using Factory = std::function<std::unique_ptr<SecondaryDecoder>()>;
    explicit DecoderLease(Factory factory);
    ~DecoderLease();
    std::unique_ptr<SecondaryDecoder> claim();
    void revoke() noexcept;
    bool active() const;
    bool withActive(const std::function<void()>& operation);

private:
    friend class SecondarySession;
    class Client;
    bool bindSession();
    void retireLocked() noexcept;
    mutable std::mutex mutex_;
    Factory factory_;
    std::unique_ptr<SecondaryDecoder> decoder_;
    bool claimed_ = false;
    bool revoked_ = false;
    bool sessionBound_ = false;
};

}

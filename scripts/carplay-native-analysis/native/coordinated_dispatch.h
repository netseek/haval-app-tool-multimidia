#pragma once

#include "hook_installation.h"

namespace carplay_experiment {

struct DispatchTargets {
    observed_abi::VideoCallbacks video;
    OriginalStartSession session = nullptr;
    OriginalGetDisplays displays = nullptr;
};

class DispatchObservation {
public:
    virtual ~DispatchObservation() = default;
    virtual observed_abi::Status session(void* session, observed_abi::String uuid, std::uint32_t type,
        const std::function<observed_abi::Status()>& invoke) = 0;
    virtual void starting(observed_abi::Stream stream, std::uint32_t type, observed_abi::String uuid) noexcept = 0;
    virtual void started(observed_abi::Status status, observed_abi::Stream stream,
        std::uint32_t type, observed_abi::String uuid) noexcept = 0;
    virtual void retiring(observed_abi::Stream stream) noexcept = 0;
};

class CoordinatedDispatch final {
    struct State;
    class Invocation;
public:
    class Freeze final {
    public:
        ~Freeze();
        Freeze(const Freeze&) = delete;
        Freeze& operator=(const Freeze&) = delete;
        bool replace(DispatchTargets targets) noexcept;
    private:
        friend class CoordinatedDispatch;
        explicit Freeze(std::shared_ptr<State> state);
        const std::shared_ptr<State> state_;
        bool armed_ = false;
        bool replaced_ = false;
    };

    explicit CoordinatedDispatch(DispatchTargets originals, std::shared_ptr<DispatchObservation> observation = {});
    std::unique_ptr<Freeze> tryFreeze();
    observed_abi::Status initialize(observed_abi::Stream stream);
    void finalize(observed_abi::Stream stream);
    void dimensions(const void* stream, std::uint32_t width, std::uint32_t height);
    observed_abi::Status start(observed_abi::Stream stream, std::uint32_t type, observed_abi::String uuid);
    void stop(observed_abi::Stream stream);
    observed_abi::Status avcc(const void* stream, const std::uint8_t* bytes, std::size_t length);
    observed_abi::Status data(observed_abi::Stream stream, const std::uint8_t* bytes, std::size_t length,
        std::uint64_t ticks, observed_abi::Dictionary options, observed_abi::Completion completion, void* context);
    observed_abi::Status session(void* session, observed_abi::String uuid, std::uint32_t type, void* context);
    DisplayArray displays(observed_abi::Status* error);
    void sessionBoundary(const std::function<void()>& apply);

private:
    const std::shared_ptr<State> state_;
};

class DispatchPreparedHooks final : public PreparedHooks {
public:
    using AdapterFactory = std::function<std::unique_ptr<OemRuntimeAdapter>()>;
    using Capture = std::function<LivePrincipal()>;
    using Current = std::function<bool(const LivePrincipal&)>;
    DispatchPreparedHooks(std::shared_ptr<CoordinatedDispatch> dispatch, AdapterFactory factory,
        Capture capture, Current current, std::shared_ptr<void> nativeOwners);
    std::unique_ptr<OemRuntimeAdapter> makeAdapter() override;
    std::unique_ptr<QuiescentHookTransaction> tryQuiesce() override;

private:
    const std::shared_ptr<void> nativeOwners_;
    const std::shared_ptr<CoordinatedDispatch> dispatch_;
    const AdapterFactory factory_;
    const Capture capture_;
    const Current current_;
};

}

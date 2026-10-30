#include "android_surface_transport.h"
#include "decoder_lease.h"
#include "live_surface_activation.h"

#include <android/native_window_jni.h>
#include <jni.h>
#include <limits>
#include <unordered_map>
#include <utility>

namespace carplay_experiment {
namespace {
using Window = std::shared_ptr<ANativeWindow>;
struct SurfaceRecord {
    Window window;
    SurfaceSessionBinding binding;
};
std::mutex registryMutex;
std::unordered_map<std::int64_t, SurfaceRecord> registry;
std::int64_t nextHandle = 1;

std::int64_t importSurface(JNIEnv* environment, jobject surface) {
    if (!surface) { return 0; }
    Window window(ANativeWindow_fromSurface(environment, surface), [](ANativeWindow* value) {
        if (value) { ANativeWindow_release(value); }
    });
    if (!window || environment->ExceptionCheck()) { return 0; }
    std::lock_guard<std::mutex> lock(registryMutex);
    if (registry.size() >= 8 || nextHandle == std::numeric_limits<std::int64_t>::max()) { return 0; }
    const auto handle = nextHandle++;
    registry.emplace(handle, SurfaceRecord{std::move(window), {}});
    return handle;
}

void revokeSurface(std::int64_t handle) {
    SurfaceRecord retired;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        const auto iterator = registry.find(handle);
        if (iterator == registry.end()) { return; }
        retired = std::move(iterator->second);
        registry.erase(iterator);
    }
    if (retired.binding.session) { retired.binding.session->close(); }
}

bool matchesWindowGeometry(std::int64_t handle, std::uint32_t width, std::uint32_t height) {
    Window window;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        const auto iterator = registry.find(handle);
        if (iterator == registry.end()) { return false; }
        window = iterator->second.window;
    }
    const auto actualWidth = ANativeWindow_getWidth(window.get());
    const auto actualHeight = ANativeWindow_getHeight(window.get());
    return actualWidth > 0 && actualHeight > 0 &&
        static_cast<std::uint32_t>(actualWidth) == width && static_cast<std::uint32_t>(actualHeight) == height;
}
}

VideoBridge::DecoderFactory surfaceDecoderFactory(
    std::int64_t handle, AndroidSecondaryDecoder::TickConverter converter) {
    if (!converter) { return {}; }
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto iterator = registry.find(handle);
    if (iterator == registry.end() || iterator->second.binding.session) { return {}; }
    const auto window = iterator->second.window;
    auto lease = std::make_shared<DecoderLease>([window, converter = std::move(converter)] {
        return std::make_unique<AndroidSecondaryDecoder>(window.get(), converter);
    });
    auto session = std::make_shared<SecondarySession>(true);
    const auto generation = session->arm(lease);
    if (!generation) { return {}; }
    VideoBridge::DecoderFactory factory = [lease](const StreamIdentity&) { return lease->claim(); };
    iterator->second.binding = {std::move(session), generation};
    return factory;
}

bool surfaceRouteReady(std::int64_t handle) {
    const auto binding = surfaceSessionBinding(handle);
    return binding.session && binding.session->active(binding.generation);
}

std::optional<std::pair<std::uint32_t, std::uint32_t>> surfaceGeometry(std::int64_t handle) {
    Window window;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        const auto iterator = registry.find(handle);
        if (iterator == registry.end()) { return std::nullopt; }
        window = iterator->second.window;
    }
    const auto width = ANativeWindow_getWidth(window.get());
    const auto height = ANativeWindow_getHeight(window.get());
    if (width <= 0 || height <= 0 || width > 4096 || height > 2160) { return std::nullopt; }
    return std::make_pair(static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height));
}

SurfaceSessionBinding surfaceSessionBinding(std::int64_t handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto iterator = registry.find(handle);
    return iterator == registry.end() ? SurfaceSessionBinding{} : iterator->second.binding;
}

std::unique_ptr<SecondaryRuntime> runtimeForSurface(std::int64_t handle,
    AndroidSecondaryDecoder::TickConverter converter, observed_abi::VideoCallbacks original,
    OriginalStartSession originalSession, SecondaryRuntimeConfig config, UuidReader uuidReader,
    DisplayAdvertisementApi displayApi, std::shared_ptr<void> lifetime) {
    if (!config.enabled) {
        return std::make_unique<SecondaryRuntime>(original, originalSession, std::move(config),
            VideoBridge::DecoderFactory{}, std::move(uuidReader));
    }
    if (!lifetime || !matchesWindowGeometry(handle, config.display.widthPixels, config.display.heightPixels)) {
        return nullptr;
    }
    auto factory = surfaceDecoderFactory(handle, std::move(converter));
    if (!factory) { return nullptr; }
    const auto binding = surfaceSessionBinding(handle);
    if (!binding.session) { return nullptr; }
    try {
        return std::make_unique<SecondaryRuntime>(original, originalSession, std::move(config),
            std::move(factory), std::move(uuidReader), binding.session, binding.generation,
            std::move(displayApi), std::move(lifetime));
    } catch (...) {
        binding.session->revoke(binding.generation);
        throw;
    }
}

}

extern "C" JNIEXPORT jlong JNICALL
Java_br_com_redesurftank_havalshisuku_experimental_CarPlaySurfaceEndpoint_nativeImportSurface(
    JNIEnv* environment, jclass, jobject surface) noexcept {
    try { return carplay_experiment::importSurface(environment, surface); }
    catch (...) { return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_br_com_redesurftank_havalshisuku_experimental_CarPlaySurfaceEndpoint_nativeRevokeSurface(
    JNIEnv*, jclass, jlong handle) noexcept {
    try { carplay_experiment::revokeSurface(handle); }
    catch (...) {
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_br_com_redesurftank_havalshisuku_experimental_CarPlaySurfaceEndpoint_nativeActivateSurface(
    JNIEnv* environment, jclass, jlong handle, jint authorizedUid, jlong requestId,
    jbyteArray token, jint framesPerSecond, jint widthMillimeters, jint heightMillimeters) noexcept {
    std::array<std::uint8_t, 32> copied{};
    try {
        if (!token || environment->GetArrayLength(token) != static_cast<jsize>(copied.size()) ||
            handle <= 0 || authorizedUid < 0 || requestId <= 0 || framesPerSecond <= 0 ||
            widthMillimeters <= 0 || heightMillimeters <= 0) {
            return static_cast<jint>(carplay_experiment::HookInstallResult::rejected);
        }
        environment->GetByteArrayRegion(token, 0, static_cast<jsize>(copied.size()),
            reinterpret_cast<jbyte*>(copied.data()));
        if (environment->ExceptionCheck()) {
            environment->ExceptionClear();
            return static_cast<jint>(carplay_experiment::HookInstallResult::rejected);
        }
        const auto result = carplay_experiment::activateLiveSurface(handle,
            static_cast<std::uint32_t>(authorizedUid), static_cast<std::uint64_t>(requestId), copied,
            static_cast<std::uint32_t>(framesPerSecond),
            static_cast<std::uint32_t>(widthMillimeters),
            static_cast<std::uint32_t>(heightMillimeters));
        volatile auto* bytes = copied.data();
        for (std::size_t index = 0; index < copied.size(); ++index) { bytes[index] = 0; }
        return static_cast<jint>(result);
    } catch (...) {
        volatile auto* bytes = copied.data();
        for (std::size_t index = 0; index < copied.size(); ++index) { bytes[index] = 0; }
        return static_cast<jint>(carplay_experiment::HookInstallResult::fault);
    }
}

extern "C" JNIEXPORT jint JNICALL impulse_carplay_register_surface_endpoint(
    JNIEnv* environment, jclass endpointClass) noexcept {
    if (!environment || !endpointClass) { return JNI_ERR; }
    JNINativeMethod methods[] = {
        {const_cast<char*>("nativeImportSurface"), const_cast<char*>("(Landroid/view/Surface;)J"),
            reinterpret_cast<void*>(
                Java_br_com_redesurftank_havalshisuku_experimental_CarPlaySurfaceEndpoint_nativeImportSurface)},
        {const_cast<char*>("nativeRevokeSurface"), const_cast<char*>("(J)V"),
            reinterpret_cast<void*>(
                Java_br_com_redesurftank_havalshisuku_experimental_CarPlaySurfaceEndpoint_nativeRevokeSurface)},
        {const_cast<char*>("nativeActivateSurface"), const_cast<char*>("(JIJ[BIII)I"),
            reinterpret_cast<void*>(
                Java_br_com_redesurftank_havalshisuku_experimental_CarPlaySurfaceEndpoint_nativeActivateSurface)}
    };
    return environment->RegisterNatives(endpointClass, methods,
        static_cast<jint>(sizeof(methods) / sizeof(methods[0])));
}

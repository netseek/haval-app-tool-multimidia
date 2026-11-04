#pragma once

#include "oem_entry_bridge.h"

namespace carplay_experiment {
void* pinnedInitialModuleSymbol(const char* module, const char* name) noexcept;
}

extern "C" std::uint32_t impulse_carplay_initial_bootstrap_abi() noexcept;
extern "C" std::uint32_t impulse_carplay_initial_bootstrap_state() noexcept;
extern "C" carplay_experiment::observed_abi::Status impulse_carplay_prepare_initial_entries() noexcept;
extern "C" carplay_experiment::observed_abi::Status impulse_carplay_prepare_initial_entries_for_oem_namespace(
    const void* attestedOemCaller) noexcept;
extern "C" carplay_experiment::observed_abi::Status impulse_carplay_release_initial_preparation() noexcept;

extern "C" carplay_experiment::observed_abi::Status impulse_carplay_bootstrap_initial_entries(
    const carplay_experiment::observed_abi::VideoCallbacks* original,
    carplay_experiment::OriginalStartSession session, carplay_experiment::OriginalGetDisplays displays,
    carplay_experiment::StringLength length, carplay_experiment::CopyUtf8String copy,
    carplay_experiment::RetainCfObject retain, carplay_experiment::ReleaseCfObject release,
    const char* principalUuid, std::size_t uuidLength) noexcept;

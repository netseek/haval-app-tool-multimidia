# CarPlay display getter ownership — 2026-10-23

## Scope and conclusion

Read-only REA/Ghidra analysis of the exact privately captured ARM64 libraries
supports treating `_getScreenDisplays(OSStatus*)` as returning a newly owned
array. The existing adapter's successful replacement path releases that original
once and transfers its independently owned replacement to the unchanged caller.
Failure must return the original pointer and status unchanged.

This resolves the static caller-contract question for these artifacts only.
It does not attest loaded modules, install hooks, authenticate a bootstrap,
validate live pointer lifetime, or establish an iPhone secondary video stream.
No Android build, production version, OTA or vehicle operation was performed.

## Artifact and address coordinates

All addresses below are ELF virtual addresses. Ghidra analysis addresses are
ELF addresses plus `0x100000`; never use analysis addresses as runtime offsets.

| Artifact | SHA-256 | Function / ELF address |
| --- | --- | --- |
| libcarplaystack.so | 8d4c1fe001d3e8bf1299438a96ca07e12470bc60d75d0d52fe89fe5d18f85931 | `_getScreenDisplays`, `0x158c4` |
| same | same | `_AirPlayHandleServerCopyProperty`, `0x1466c` |
| libAirPlay_ts.so | 818cf91d3bac8f807328c58189db8cbcaa3e604e7263031d1b2257eebf2ff477 | `AirPlayReceiverServerPlatformCopyProperty`, `0x1c638` |
| same | same | `AirPlayCopyServerInfo`, `0x1d2dc` |

## Observations

1. `_getScreenDisplays` initializes its local array to null and passes its address
   to the main-display builder (with or without view areas). It returns the array
   without releasing it. The shared display builder creates a mutable array when
   the supplied pointer is null, appends the display dictionary and releases its
   temporary dictionary. Only the main display is added by this getter.
2. The exact-address reference query returned three references: Ghidra `0x10c220`,
   `0x10d438`, `0x1148a4`. Only the last was followed here as an observed direct
   call; the two others have not been classified as callers.
3. At ELF `0x148a4`, `_AirPlayHandleServerCopyProperty` calls the getter for the
   `displays` property. It stores the returned pointer, checks status, then calls
   `CFRetain` on success at `0x148bc`. On failure it changes the delegate status
   to `-0x1a3a`. The getter hook must not reproduce this outer retain or error
   translation: those instructions remain in the original caller.
4. `AirPlayReceiverServerPlatformCopyProperty` forwards ordinary properties to
   the server delegate. On nonzero status with a nonnull return, it releases the
   value and returns null. Successful delegate values pass through unchanged.
5. `AirPlayCopyServerInfo` requests `displays` through that platform function,
   inserts a nonnull result into its response dictionary and calls `CFRelease`
   on the temporary result. The property was correlated from the artifact's
   inline CFL constant at ELF `0x10b39`; the stack uses `0xbba3` for `displays`.

## Ownership implication and limits

The getter's creation reference is distinct from the extra retain in the outer
delegate. The adapter may release the original getter result once when replacing
it; the unchanged delegate subsequently retains the replacement just as it did
the original. No extra retain belongs in the getter adapter.

The observed creation plus delegate retain plus response temporary release does
not establish balanced ownership over the entire server/session lifetime. It
suggests an extra retained reference in this OEM path. Do not add a compensating
release or claim an OEM leak is fixed: other lifetime paths were not exhaustively
traced and no reference counts were observed at runtime.

The proof applies to interception of `_getScreenDisplays`, not replacing the
five-argument server delegate or platform property function with the adapter's
one-argument getter. Those have different ABI and ownership boundaries.
The mutable-copy and release APIs must resolve from the verified live CoreUtils
module; static names and on-disk hashes alone do not prove loaded targets.

## Retained private evidence

Raw binaries, full Evidence JSON and snapshots remain in the private capture
directory `test-artifacts/carplay-apks/20261022-205614-telnet` on the coordinator;
none are committed with this document.

| Evidence file | REA Evidence ID |
| --- | --- |
| ghidra-getScreenDisplays-corrected.json | ev_961f5fbdf3e102bec74e374cb85993ffa706435329bc71ba7cb6ff2adc467e1f |
| ghidra-screen-dictionary.json | ev_53936f6a01c799a4e24b8123cf587c64f8d104ca82ff17a51c58c290cb1621c1 |
| ghidra-getter-xrefs.json | ev_d2257889a7f1cf7bc21c9de157028c0a4c99ce5e59f25a0bc8597a7ccc4caaca |
| ghidra-server-copy-property.json | ev_ff6ef6100fff70a3b372d788e5ea8e2c795302997434f4425e76f6c9f4127523 |
| ghidra-platform-copy-property.json | ev_46a8ec05bf0a34e0fdad1f933836cf4e3223eb8f9a654a87a7a751173666c5a0 |
| ghidra-copy-server-info.json | ev_28bc849895abf96f63dab8fb934bf0f93dd5f5a489a98b488c2f070a8f84fbb8 |

REA 6.7.0 / Ghidra 12.1.4, immutable imported artifact, no native target execution.
Reference lists are bounded provider observations. Pseudocode contains unresolved
DWARF locals; the getter call/status/retain sequence was corroborated in assembly.

## Next implementation boundary

Implement and test installation coordination separately from the decoder: attest
loaded modules and original targets, authenticate the app request, serialize
hook publication against all relevant callbacks, and establish live session/epoch
ownership. A warm installation requires fresh principal capture and adoption
before publication, with UUID ownership pinned; observer hashes are insufficient.
If that proof cannot be obtained, refuse warm activation rather than guessing.
Keep native packaging excluded and app activation absent until that gate passes.

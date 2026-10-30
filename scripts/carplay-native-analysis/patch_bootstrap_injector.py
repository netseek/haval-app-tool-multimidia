#!/usr/bin/env python3
"""Mini-only bootstrap asset transformation; --audit is read-only on any host.

The original Frida resource stays intact. Only the profiled tail call from its
CLI initializer is omitted in the separate bootstrap asset. This is usable only
behind the runtime's existing-permissive guard and policy-preservation probe.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct

PROFILE = Path(__file__).resolve().parent / "testdata/frida-injector-17.5.2-permissive.json"
ORIGINAL_SHA = "bdfa97cdd84ca678c5e5eea9eb6625c9528acff32b4f9413f1e5c58a3d15222e"
PATCHED_SHA = "32f7c702ea36c9ad1d09d2dc9e21de5f4994d98f855c4e51db01bcc6d0fd496a"
PROFILE_ID = "frida-17.5.2-android-arm64-init-tailcall-ret-v1"
POLICY_MODE = "permissive-existing-policy-v1"


def audit(data: bytes, profile: dict) -> dict:
    if (profile.get("schema") != 1 or profile.get("id") != PROFILE_ID or
            profile.get("policyMode") != POLICY_MODE or profile.get("upstreamVersion") != "17.5.2" or
            profile.get("originalSha256") != ORIGINAL_SHA or profile.get("patchedSha256") != PATCHED_SHA):
        raise ValueError("Unrecognized bootstrap injector profile")
    if len(data) != profile["originalSize"] or hashlib.sha256(data).hexdigest() != ORIGINAL_SHA:
        raise ValueError("Original injector differs from the reviewed immutable artifact")
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", data, 16) != (3, 183):
        raise ValueError("Expected an ELF64 little-endian AArch64 ET_DYN")
    offset = profile["patchFileOffset"]
    address = int(profile["patchAddress"], 16)
    target = int(profile["tailTargetAddress"], 16)
    old = bytes.fromhex(profile["originalInstructionHex"])
    new = bytes.fromhex(profile["replacementInstructionHex"])
    if offset != 0x449F98 or address != 0x44AF98 or target != 0x2DD23DC or offset % 4:
        raise ValueError("Unexpected patch location")
    if old != bytes.fromhex("111da614") or new != bytes.fromhex("c0035fd6") or data[offset:offset + 4] != old:
        raise ValueError("Unexpected AArch64 instruction replacement")
    caller = bytes.fromhex(profile["callerHex"])
    caller_offset = profile["callerFileOffset"]
    if caller_offset != offset - 20 or len(caller) != 24 or data[caller_offset:offset + 4] != caller:
        raise ValueError("CLI initializer/frame-restoration preimage mismatch")
    # Verify the original instruction is an unconditional tail B to the policy
    # routine, not BL: RET is correct only after the caller restored x29/x30.
    word = int.from_bytes(old, "little")
    immediate = word & 0x3FFFFFF
    if immediate & (1 << 25):
        immediate -= 1 << 26
    if word & 0xFC000000 != 0x14000000 or address + immediate * 4 != target:
        raise ValueError("Expected the reviewed policy tail call")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size != 56 or count > 128 or phoff + size * count > len(data):
        raise ValueError("Malformed program-header bounds")
    executable_mapping = False
    for index in range(count):
        kind, flags, file_offset, va, _, file_size, _, _ = struct.unpack_from("<IIQQQQQQ", data, phoff + index * size)
        if kind == 1 and flags & 1 and file_offset <= offset and offset + 4 <= file_offset + file_size:
            executable_mapping = va + offset - file_offset == address
    if not executable_mapping:
        raise ValueError("Patch is not at the reviewed executable address")
    digest = hashlib.sha256()
    digest.update(memoryview(data)[:offset])
    digest.update(new)
    digest.update(memoryview(data)[offset + 4:])
    if digest.hexdigest() != PATCHED_SHA:
        raise ValueError("Derived injector digest differs from the profile")
    return {"injectorOriginalSha256": ORIGINAL_SHA, "injectorSha256": PATCHED_SHA,
            "injectorSize": len(data), "policyMode": POLICY_MODE, "patchProfile": PROFILE_ID}


def build(source: Path, destination: Path) -> dict:
    """Called only by the authorized Mini's immutable generated-assets task."""
    profile = json.loads(PROFILE.read_text())
    data = source.read_bytes()
    metadata = audit(data, profile)
    if destination.exists() or destination.is_symlink():
        raise ValueError("Use a new generated injector destination")
    offset = profile["patchFileOffset"]
    with destination.open("xb") as output:
        output.write(memoryview(data)[:offset])
        output.write(bytes.fromhex(profile["replacementInstructionHex"]))
        output.write(memoryview(data)[offset + 4:])
    if hashlib.sha256(destination.read_bytes()).hexdigest() != PATCHED_SHA:
        raise ValueError("Generated injector differs from its derivation")
    return metadata


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--audit", action="store_true", help="Read/verify only; never emit Android bytes")
    parser.add_argument("--output", type=Path, help="Generated asset; authorized Mini builds only")
    args = parser.parse_args()
    if args.audit == (args.output is not None):
        parser.error("Choose exactly one of --audit or --output")
    result = audit(args.source.read_bytes(), json.loads(PROFILE.read_text())) if args.audit else build(args.source, args.output)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

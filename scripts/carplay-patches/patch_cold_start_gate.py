#!/usr/bin/env python3
"""Add an opt-in, bounded cold-hook gate to a decoded TsCarPlayService APK.

The gate is inert unless persist.haval.carplay.experimental_cold_hook contains a
32-character lowercase hexadecimal nonce.  When enabled, CarPlayService waits
for at most ten seconds for a root-owned release file that includes both its PID
and that nonce.  This creates a deterministic attach window before init() loads
the OEM CarPlay native stack, without changing the normal startup path.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path


SERVICE_RELATIVE_PATH = Path("smali/com/ts/carplay/CarPlayService.smali")
GATE_RELATIVE_PATH = Path("smali/com/ts/carplay/experimental/ImpulseColdStartGate.smali")
SENTINEL = "# IMPULSE_CARPLAY_COLD_START_GATE_V1"
GATE_CALL = "invoke-static {}, Lcom/ts/carplay/experimental/ImpulseColdStartGate;->awaitController()V"

SERVICE_ANCHOR = """    invoke-super {p0}, Landroid/app/Service;->onCreate()V

    .line 118
    iput-object p0, p0, Lcom/ts/carplay/CarPlayService;->mContext:Landroid/content/Context;
"""

SERVICE_PATCH = """    invoke-super {p0}, Landroid/app/Service;->onCreate()V

    # IMPULSE_CARPLAY_COLD_START_GATE_V1
    # Default-off bounded attach window. The helper never loads native code.
    invoke-static {}, Lcom/ts/carplay/experimental/ImpulseColdStartGate;->awaitController()V

    .line 118
    iput-object p0, p0, Lcom/ts/carplay/CarPlayService;->mContext:Landroid/content/Context;
"""

GATE_SMALI = r""".class public final Lcom/ts/carplay/experimental/ImpulseColdStartGate;
.super Ljava/lang/Object;
.source "ImpulseColdStartGate.java"


# static fields
.field private static final CONTROL_DIRECTORY:Ljava/lang/String; = "/data/local/tmp/impulse-carplay/control"

.field private static final PROPERTY:Ljava/lang/String; = "persist.haval.carplay.experimental_cold_hook"

.field private static final TAG:Ljava/lang/String; = "ImpulseColdStartGate"

.field private static final TIMEOUT_MS:J = 0x2710L


# direct methods
.method private constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static awaitController()V
    .locals 10

    const-string v0, "persist.haval.carplay.experimental_cold_hook"

    const-string v1, ""

    invoke-static {v0, v1}, Landroid/os/SystemProperties;->get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    const-string v1, "[0-9a-f]{32}"

    invoke-virtual {v0, v1}, Ljava/lang/String;->matches(Ljava/lang/String;)Z

    move-result v1

    if-nez v1, :enabled

    return-void

    :enabled
    invoke-static {}, Landroid/os/Process;->myPid()I

    move-result v1

    new-instance v2, Ljava/lang/StringBuilder;

    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V

    const-string v3, "/data/local/tmp/impulse-carplay/control/release."

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string v3, "."

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v2

    new-instance v3, Ljava/io/File;

    invoke-direct {v3, v2}, Ljava/io/File;-><init>(Ljava/lang/String;)V

    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v4

    const-wide/16 v6, 0x2710

    add-long/2addr v4, v6

    const-string v2, "ImpulseColdStartGate"

    const-string v6, "Cold CarPlay controller window armed"

    invoke-static {v2, v6}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    :poll
    invoke-virtual {v3}, Ljava/io/File;->isFile()Z

    move-result v2

    if-eqz v2, :check_cancel

    const-string v0, "ImpulseColdStartGate"

    const-string v1, "Cold CarPlay controller released startup"

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    return-void

    :check_cancel
    const-string v2, "persist.haval.carplay.experimental_cold_hook"

    const-string v6, ""

    invoke-static {v2, v6}, Landroid/os/SystemProperties;->get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v0, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v2

    if-nez v2, :check_timeout

    const-string v0, "ImpulseColdStartGate"

    const-string v1, "Cold CarPlay controller window cancelled"

    invoke-static {v0, v1}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    return-void

    :check_timeout
    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v6

    cmp-long v2, v6, v4

    if-ltz v2, :sleep

    const-string v0, "ImpulseColdStartGate"

    const-string v1, "Cold CarPlay controller timed out; continuing stock startup"

    invoke-static {v0, v1}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I

    return-void

    :sleep
    const-wide/16 v6, 0x19

    invoke-static {v6, v7}, Landroid/os/SystemClock;->sleep(J)V

    goto :poll
.end method
"""


def normalize_smali(source: str) -> list[str]:
    lines = [
        line.strip()
        for line in source.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    labels = {}
    for line in lines:
        match = re.fullmatch(r":([A-Za-z0-9_]+)", line)
        if match:
            labels[match.group(1)] = f"label_{len(labels)}"

    def replace_label(match):
        name = match.group(1)
        return ":" + labels.get(name, name)

    return [re.sub(r":([A-Za-z0-9_]+)", replace_label, line) for line in lines]


def verify_decoded_apk(decoded_directory: Path) -> None:
    service_path = decoded_directory / SERVICE_RELATIVE_PATH
    gate_path = decoded_directory / GATE_RELATIVE_PATH
    service = service_path.read_text(encoding="utf-8")
    if service.count(GATE_CALL) != 1:
        raise RuntimeError("expected exact cold-start gate call once")
    on_create_start = service.index(".method public onCreate()V")
    on_create_end = service.index(".end method", on_create_start)
    on_create = service[on_create_start:on_create_end]
    super_offset = on_create.index("invoke-super {p0}, Landroid/app/Service;->onCreate()V")
    gate_offset = on_create.index(GATE_CALL)
    context_offset = on_create.index(
        "iput-object p0, p0, Lcom/ts/carplay/CarPlayService;->mContext:Landroid/content/Context;"
    )
    if not super_offset < gate_offset < context_offset:
        raise RuntimeError("cold-start gate call is outside the reviewed startup boundary")
    if not gate_path.is_file():
        raise RuntimeError("cold-start gate helper is missing")
    if normalize_smali(gate_path.read_text(encoding="utf-8")) != normalize_smali(GATE_SMALI):
        raise RuntimeError("cold-start gate helper changed")


def patch_decoded_apk(decoded_directory: Path) -> bool:
    service_path = decoded_directory / SERVICE_RELATIVE_PATH
    gate_path = decoded_directory / GATE_RELATIVE_PATH
    service = service_path.read_text(encoding="utf-8")

    if SENTINEL in service or GATE_CALL in service:
        verify_decoded_apk(decoded_directory)
        return False

    if service.count(SERVICE_ANCHOR) != 1:
        raise RuntimeError("expected exact CarPlayService.onCreate anchor once")

    service_path.write_text(service.replace(SERVICE_ANCHOR, SERVICE_PATCH), encoding="utf-8")
    gate_path.parent.mkdir(parents=True, exist_ok=True)
    gate_path.write_text(GATE_SMALI, encoding="utf-8")
    verify_decoded_apk(decoded_directory)
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("decoded_directory", type=Path)
    args = parser.parse_args()
    changed = patch_decoded_apk(args.decoded_directory)
    print("patched" if changed else "already-patched")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

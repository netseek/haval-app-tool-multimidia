import argparse
import json
from pathlib import Path

from probe_profile import PROFILES, generate as generate_modules


ENTRY_PROFILES = {name: (digest, dict(targets)) for name, (digest, targets) in PROFILES.items()}
ENTRY_PROFILES['libcarplaystack.so'][1].update({
    'dimensions': '_ZL33CarPlayScreenStreamSetWidthHeightPKvjj',
    'stop': '_ZL23CarPlayScreenStreamStopP19ScreenStreamPrivate',
    'avcc': '_ZL26CarPlayScreenStreamSetAVCCPKvPKhm',
    'displays': '_ZL18_getScreenDisplaysPi',
})
ENTRY_PROFILES['libCoreUtils_ts.so'][1].update({'retain': 'CFRetain', 'release': 'CFRelease'})


def generate(directory):
    profile = generate_modules(directory, ENTRY_PROFILES)
    profile.update(version=2, kind='oem-entry-binding-v1')
    return profile


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Offline exact OEM entry binding profile; no hook installation')
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    print(json.dumps(generate(args.directory), indent=2))

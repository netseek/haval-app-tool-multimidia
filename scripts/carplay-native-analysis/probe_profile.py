import argparse
import hashlib
import json
from pathlib import Path

from elftools.elf.elffile import ELFFile


PROFILES = {
    'libAirPlay_ts.so': ('818cf91d3bac8f807328c58189db8cbcaa3e604e7263031d1b2257eebf2ff477', {
        'session': 'AirPlayReceiverSessionScreen_StartSession',
    }),
    'libcarplaystack.so': ('8d4c1fe001d3e8bf1299438a96ca07e12470bc60d75d0d52fe89fe5d18f85931', {
        'initialize': '_ZL29CarPlayScreenStreamInitializeP19ScreenStreamPrivate',
        'start': '_ZL24CarPlayScreenStreamStartP19ScreenStreamPrivatejP9CFLString',
        'data': '_ZL30CarPlayScreenStreamProcessDataP19ScreenStreamPrivatePKhmmP13CFLDictionaryPFvPvES5_',
        'finalize': '_ZL27CarPlayScreenStreamFinalizeP19ScreenStreamPrivate',
    }),
    'libCoreUtils_ts.so': ('8466bd2e24e3f8eb0217d33686d03b54806993074c3c33d3a1e5608fdd90fed0', {
        'getLength': 'CFStringGetLength',
        'copyString': 'CFStringGetCString',
    }),
}


def generate(directory, profiles=PROFILES):
    modules = []
    for name, (expected, targets) in profiles.items():
        path = Path(directory) / ('vendor__app__TsCarPlayService__lib__arm64__' + name)
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            if digest != expected:
                raise ValueError('Unsupported input hash: ' + name)
            stream.seek(0)
            elf = ELFFile(stream)
            loads = [segment for segment in elf.iter_segments() if segment['p_type'] == 'PT_LOAD']
            if elf['e_machine'] != 'EM_AARCH64' or min(segment['p_vaddr'] for segment in loads) != 0:
                raise ValueError('Unsupported load image: ' + name)
            note = elf.get_section_by_name('.note.gnu.build-id')
            if note is None or note['sh_size'] > 128:
                raise ValueError('Missing bounded build identity: ' + name)
            symbols = elf.get_section_by_name('.symtab')
            functions = {}
            for label, symbol_name in targets.items():
                matches = symbols.get_symbol_by_name(symbol_name) or []
                if len(matches) != 1 or matches[0]['st_size'] < 16:
                    raise ValueError('Unsupported symbol: ' + symbol_name)
                symbol = matches[0]
                section = elf.get_section(symbol['st_shndx'])
                offset = symbol['st_value'] - section['sh_addr']
                functions[label] = {'offset': symbol['st_value'],
                                    'signature': section.data()[offset:offset + 16].hex()}
            modules.append({'name': name, 'path': '/vendor/app/TsCarPlayService/lib/arm64/' + name,
                            'sha256': digest, 'note': {'offset': note['sh_addr'], 'hex': note.data().hex()},
                            'functions': functions})
    return {'version': 1, 'architecture': 'arm64', 'modules': modules}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Generate an offline pinned observer profile; no device access')
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    print(json.dumps(generate(args.directory), indent=2))

import argparse
import hashlib
import io
import json
from pathlib import Path

from elftools.elf.elffile import ELFFile

from entry_profile import ENTRY_PROFILES, generate as entry_profile


def generate(directory):
    profile = entry_profile(directory)
    profile.update(version=3, kind='oem-cold-interception-v1')
    for module in profile['modules']:
        path = Path(directory) / ('vendor__app__TsCarPlayService__lib__arm64__' + module['name'])
        payload = path.read_bytes()
        if len(payload) > 64 * 1024 * 1024 or hashlib.sha256(payload).hexdigest() != module['sha256']:
            raise ValueError('Changed or oversized captured module')
        elf = ELFFile(io.BytesIO(payload))
        dynamic = elf.get_section_by_name('.dynamic')
        if dynamic:
            for tag in dynamic.iter_tags():
                if tag.entry.d_tag == 'DT_TEXTREL' or (tag.entry.d_tag == 'DT_FLAGS' and tag.entry.d_val & 4):
                    raise ValueError('Text relocations unsupported')
        executable = []
        for segment in elf.iter_segments():
            if segment['p_type'] == 'PT_LOAD' and segment['p_flags'] & 1:
                if segment['p_flags'] != 5 or not segment['p_filesz']:
                    raise ValueError('Readable nonwritable executable segment required')
                executable.append({'offset': segment['p_vaddr'], 'size': segment['p_filesz'],
                                   'sha256': hashlib.sha256(segment.data()).hexdigest()})
        if not executable:
            raise ValueError('Executable image missing')
        module.update(fileSize=len(payload), executable=executable)
        symbols = elf.get_section_by_name('.symtab')
        for label, target in module['functions'].items():
            symbol = symbols.get_symbol_by_name(ENTRY_PROFILES[module['name']][1][label])[0]
            size = symbol['st_size']
            if size < 16 or size > 65536:
                raise ValueError('Unsupported function body size')
            section = elf.get_section(symbol['st_shndx'])
            offset = symbol['st_value'] - section['sh_addr']
            body = section.data()[offset:offset + size]
            if len(body) != size:
                raise ValueError('Truncated function')
            target.update(size=size, sha256=hashlib.sha256(body).hexdigest())
    return profile


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Offline full-code attestation profile; no device access')
    parser.add_argument('directory', type=Path)
    print(json.dumps(generate(parser.parse_args().directory), indent=2))

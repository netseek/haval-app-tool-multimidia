import argparse
import hashlib
import json
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from elftools.elf.elffile import ELFFile


def branch_target(instruction):
    if instruction.mnemonic not in ('bl', 'b'):
        return None
    if not instruction.op_str.startswith('#'):
        return None
    return int(instruction.op_str[1:], 0)


def plt_symbols(elf, decoder):
    relocations = elf.get_section_by_name('.rela.plt')
    section = elf.get_section_by_name('.plt')
    if relocations is None or section is None:
        raise ValueError('Expected AArch64 PLT and relocations')
    dynamic = elf.get_section(relocations['sh_link'])
    symbols = {}
    for index, relocation in enumerate(relocations.iter_relocations()):
        offset = 32 + index * 16
        address = section['sh_addr'] + offset
        instructions = list(decoder.disasm(section.data()[offset:offset + 16], address))
        if len(instructions) != 4:
            raise ValueError('Incomplete PLT entry')
        if instructions[0].mnemonic != 'adrp' or instructions[1].mnemonic != 'ldr':
            raise ValueError('Unsupported PLT layout')
        destination = instructions[0].operands[1].imm + instructions[1].operands[1].mem.disp
        if destination != relocation['r_offset']:
            raise ValueError('PLT mapping does not match relocation')
        symbols[address] = dynamic.get_symbol(relocation['r_info_sym']).name
    return symbols


def video_callbacks(elf, table, destinations):
    matches = table.get_symbol_by_name('gVideoCallback') or []
    if not matches:
        return None
    if len(matches) != 1 or matches[0]['st_size'] != 56:
        raise ValueError('Unexpected video callback table layout')
    symbol = matches[0]
    relocations = elf.get_section_by_name('.rela.dyn')
    if relocations is None:
        raise ValueError('Callback relocations unavailable')
    start = symbol['st_value']
    slots = {}
    for relocation in relocations.iter_relocations():
        offset = relocation['r_offset'] - start
        if 0 <= offset < 56:
            if offset % 8 or relocation['r_info_type'] != 1027 or offset in slots:
                raise ValueError('Unsupported callback relocation')
            target = relocation['r_addend']
            slots[offset] = {'offset': offset, 'target': hex(target), 'symbol': destinations.get(target)}
    if len(slots) != 7 or any(slot['symbol'] is None for slot in slots.values()):
        raise ValueError('Incomplete callback symbol mapping')
    return [slots[offset] for offset in sorted(slots)]


def literal_references(elf, instructions):
    results = []
    section = elf.get_section_by_name('.rodata')
    if section is None:
        return results
    for previous, current in zip(instructions, instructions[1:]):
        if previous.mnemonic != 'adrp' or current.mnemonic != 'add':
            continue
        if len(current.operands) != 3 or previous.operands[0].reg != current.operands[1].reg:
            continue
        if current.operands[2].type != 2:
            continue
        address = previous.operands[1].imm + (current.operands[2].imm << current.operands[2].shift.value)
        offset = address - section['sh_addr']
        if not 0 <= offset < section['sh_size']:
            continue
        data = section.data()[offset:offset + 256]
        if b'\0' not in data:
            continue
        value = data.split(b'\0', 1)[0]
        if value and all(32 <= byte < 127 for byte in value):
            results.append({'at': hex(current.address), 'address': hex(address), 'value': value.decode('ascii')})
    return results


def analyze(path, expected_sha256, requested):
    with Path(path).open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != expected_sha256:
            raise ValueError('Artifact hash differs from approved input')
        stream.seek(0)
        elf = ELFFile(stream)
        if elf.elfclass != 64 or not elf.little_endian or elf['e_machine'] != 'EM_AARCH64':
            raise ValueError('Only ELF64 little-endian AArch64 is supported')
        decoder = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        decoder.detail = True
        destinations = plt_symbols(elf, decoder)
        table = elf.get_section_by_name('.symtab')
        if table is None:
            raise ValueError('Full symbol table required; stripped input is unsupported')
        functions = {}
        for symbol in table.iter_symbols():
            if symbol['st_info']['type'] != 'STT_FUNC' or not symbol['st_size']:
                continue
            address = symbol['st_value']
            destinations[address] = symbol.name
            if symbol.name in requested:
                if symbol.name in functions:
                    raise ValueError('Ambiguous function name: ' + symbol.name)
                functions[symbol.name] = symbol
        missing = set(requested) - functions.keys()
        if missing:
            raise ValueError('Missing functions: ' + ', '.join(sorted(missing)))
        results = []
        for name in requested:
            symbol = functions[name]
            section = elf.get_section(symbol['st_shndx'])
            address, size = symbol['st_value'], symbol['st_size']
            offset = address - section['sh_addr']
            code = section.data()[offset:offset + size]
            instructions = list(decoder.disasm(code, address))
            if sum(instruction.size for instruction in instructions) != size:
                raise ValueError('Incomplete function disassembly: ' + name)
            calls = []
            indirect = []
            for instruction in instructions:
                target = branch_target(instruction)
                if target is not None and (instruction.mnemonic == 'bl' or not address <= target < address + size):
                    calls.append({'at': hex(instruction.address), 'target': hex(target), 'symbol': destinations.get(target)})
                if instruction.mnemonic in ('blr', 'br'):
                    indirect.append(hex(instruction.address))
            results.append({'name': name, 'address': hex(address), 'size': size,
                            'direct_transfers': calls, 'indirect_transfers': indirect,
                            'adjacent_literal_references': literal_references(elf, instructions),
                            'assembly': [{'at': hex(instruction.address), 'opcode': instruction.mnemonic,
                                          'operands': instruction.op_str} for instruction in instructions]})
        return {'sha256': digest, 'architecture': 'AArch64', 'static_only': True,
                'limitations': ['Direct transfers only; indirect targets unresolved.',
                                'No runtime, negotiation, stream or display capability is proven.'],
                'functions': results, 'video_callback_table': video_callbacks(elf, table, destinations)}


def main():
    parser = argparse.ArgumentParser(description='Read-only, hash-pinned CarPlay AArch64 analysis')
    parser.add_argument('artifact', type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--function', action='append', required=True)
    args = parser.parse_args()
    try:
        result = analyze(args.artifact, args.sha256, args.function)
    except (ValueError, OSError) as error:
        parser.exit(1, str(error) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()

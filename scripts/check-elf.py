#!/usr/bin/env python3
"""Check a built EasyTier JNI library.

Verifies the JNI export is present and that every PT_LOAD segment is aligned
to 16 KB (0x4000), which Android 15+ requires for 16 KB page devices.

Uses only the standard library so the check does not depend on which toolchain
binaries an NDK install happens to ship.

  check-elf.py [--summary] LIB.so [...]
"""
import struct
import sys

EXPORT = "Java_com_easytier_jni_EasyTierJNI_runNetworkInstance"
REQUIRED_ALIGN = 0x4000
PT_LOAD = 1
SHT_DYNSYM = 11
SHN_UNDEF = 0
ELF64_EHDR = struct.Struct("<16sHHIQQQIHHHHHH")
ELF64_PHDR = struct.Struct("<IIQQQQQQ")
ELF64_SHDR = struct.Struct("<IIQQQQIIQQ")
ELF64_SYM = struct.Struct("<IBBHQQ")


class Problem(Exception):
    pass


def unpack(fmt, data, offset):
    try:
        return fmt.unpack_from(data, offset)
    except struct.error as exc:
        raise Problem(f"truncated or malformed ELF: {exc}") from exc


def read_elf(path):
    try:
        with open(path, "rb") as fh:
            data = fh.read()
    except OSError as exc:
        raise Problem(f"cannot read file: {exc.strerror}") from exc
    if data[:4] != b"\x7fELF":
        raise Problem("not an ELF file")
    if data[4] != 2:
        raise Problem("only 64-bit ELF libraries are supported")
    if data[5] != 1:
        raise Problem("only little-endian ELF libraries are supported")
    fields = unpack(ELF64_EHDR, data, 0)
    phoff, shoff = fields[5], fields[6]
    phentsize, phnum = fields[9], fields[10]
    shentsize, shnum = fields[11], fields[12]
    return data, phoff, phentsize, phnum, shoff, shentsize, shnum


def load_alignments(data, phoff, phentsize, phnum):
    if phoff == 0 or phnum == 0:
        raise Problem("no program headers")
    aligns = []
    for i in range(phnum):
        seg = unpack(ELF64_PHDR, data, phoff + i * phentsize)
        if seg[0] == PT_LOAD:
            aligns.append(seg[7])
    if not aligns:
        raise Problem("no PT_LOAD segments")
    return aligns


def exported_symbols(data, shoff, shentsize, shnum):
    """Return the set of defined dynamic symbol names, or None if stripped."""
    if shoff == 0 or shnum == 0:
        return None
    sections = [unpack(ELF64_SHDR, data, shoff + i * shentsize) for i in range(shnum)]
    for sh in sections:
        if sh[1] != SHT_DYNSYM:
            continue
        dynsym_off, dynsym_size, link, entsize = sh[4], sh[5], sh[6], sh[9]
        if link >= len(sections):
            raise Problem("dynamic symbol table has no string table")
        strtab_off, strtab_size = sections[link][4], sections[link][5]
        strings = data[strtab_off:strtab_off + strtab_size]
        entsize = entsize or ELF64_SYM.size
        names = set()
        for off in range(dynsym_off, dynsym_off + dynsym_size, entsize):
            st_name, _, _, st_shndx, _, _ = unpack(ELF64_SYM, data, off)
            if st_name == 0 or st_shndx == SHN_UNDEF:
                continue
            end = strings.find(b"\x00", st_name)
            names.add(strings[st_name:end].decode("ascii", "replace"))
        return names
    raise Problem("no dynamic symbol table")


def check(path, summary):
    data, phoff, phentsize, phnum, shoff, shentsize, shnum = read_elf(path)
    aligns = load_alignments(data, phoff, phentsize, phnum)
    # Any alignment that is a multiple of 16 KB is fine; a linker may pick a
    # larger value (e.g. 0x10000) on a future NDK.
    bad = [hex(a) for a in aligns if a < REQUIRED_ALIGN or a % REQUIRED_ALIGN]
    symbols = exported_symbols(data, shoff, shentsize, shnum)
    if symbols is None:
        # Section headers stripped: fall back to the dynamic string table.
        has_export = EXPORT.encode() in data
        note = "export found in raw strings (section headers stripped)"
    else:
        has_export = EXPORT in symbols
        note = "export present"
    if not has_export:
        raise Problem(f"missing JNI export {EXPORT}")
    if bad:
        raise Problem(f"PT_LOAD alignment must be {hex(REQUIRED_ALIGN)}, got {', '.join(bad)}")
    if summary:
        print(f"{path}: {len(data)} bytes, LOAD align {hex(REQUIRED_ALIGN)} x{len(aligns)}, {note}")
    return True


def main(argv):
    summary = "--summary" in argv
    paths = [a for a in argv[1:] if not a.startswith("--")]
    if not paths:
        sys.stderr.write(__doc__)
        return 2
    failed = False
    for path in paths:
        try:
            check(path, summary)
        except Problem as exc:
            message = f"{path}: {exc}" if summary else str(exc)
            print(f"::error::{message}" if not sys.stdout.isatty() else f"error: {message}")
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

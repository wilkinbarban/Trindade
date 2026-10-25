#!/usr/bin/env python3
"""Extract Delphi form resources (DFM) from an Aero32 client executable.

Aero32 ships as a Delphi VCL application: every form is stored as an RT_RCDATA
resource. This tool dumps the ordered string runs of each resource, which is
enough to recover the declared SQL (TADQuery.SQL.Strings), field definitions and
component names, and optionally builds a table -> forms index.

Usage:
    python3 scripts/aero32/dfm_extract.py --exe /path/aerosml.exe --out /tmp/aero32/res
    python3 scripts/aero32/dfm_extract.py --exe ... --out ... --index

Requires: pefile (pip install pefile)
Analysis is read-only: the executable is never executed nor modified.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

try:
    import pefile
except ImportError:  # pragma: no cover - dependency guard
    sys.exit('missing dependency: pip install pefile')

RT_RCDATA = 10
SQL_VERBS = re.compile(
    r'(?is)\b(insert\s+into|update|delete\s+from|from|join)\s+([A-Za-z_][\w$]*)'
)
NOISE = {'SELECT', 'SET', 'WHERE', 'DUAL', 'THE', 'VALUES'}


def resource_name(entry) -> str:
    """Resource names are ANSI in Borland resources; fall back to UTF-16."""
    raw = entry.name.string if entry.name else None
    if raw is None:
        return f'id_{entry.struct.Id}'
    text = raw.decode('latin-1', 'ignore').rstrip('\x00')
    if len(text) > 2 and all(32 <= ord(c) < 127 for c in text):
        return text
    return raw.decode('utf-16-le', 'ignore').rstrip('\x00')


def iter_rcdata(pe: 'pefile.PE'):
    for directory in pe.DIRECTORY_ENTRY_RESOURCE.entries:
        if directory.struct.Id != RT_RCDATA:
            continue
        for entry in directory.directory.entries:
            data = entry.directory.entries[0].data.struct
            yield resource_name(entry), pe.get_data(data.OffsetToData, data.Size)


def extract(exe: pathlib.Path, out: pathlib.Path) -> dict[str, tuple[set, set]]:
    pe = pefile.PE(str(exe), fast_load=True)
    pe.parse_data_directories(
        directories=[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_RESOURCE']]
    )
    out.mkdir(parents=True, exist_ok=True)
    index: dict[str, tuple[set, set]] = {}
    count = 0
    for name, data in iter_rcdata(pe):
        runs = re.findall(rb'[\x20-\x7e\xa0-\xff]{3,}', data)
        text = '\n'.join(run.decode('cp1252', 'replace') for run in runs)
        (out / f'{name}.txt').write_text(text, encoding='utf-8')
        written, read = set(), set()
        for match in SQL_VERBS.finditer(text):
            verb, table = match.group(1).lower(), match.group(2).upper()
            if table in NOISE:
                continue
            is_write = 'insert' in verb or verb == 'update' or 'delete' in verb
            (written if is_write else read).add(table)
        index[name] = (written, read)
        count += 1
    print(f'{count} resources -> {out}')
    return index


def print_index(index: dict[str, tuple[set, set]], table: str) -> None:
    writers = sorted(n for n, (w, _) in index.items() if table in w)
    readers = sorted(n for n, (_, r) in index.items() if table in r)
    print(f'{table}: {len(writers)} writer(s), {len(readers)} reader(s)')
    if writers:
        print('  writes:', ', '.join(writers[:20]))
    if readers:
        print('  reads :', ', '.join(readers[:20]))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exe', required=True, type=pathlib.Path,
                        help='unpacked aerosml.exe (run `upx -d` first)')
    parser.add_argument('--out', required=True, type=pathlib.Path,
                        help='directory for the per-resource text dumps')
    parser.add_argument('--index', action='store_true',
                        help='print the table -> forms index for the stock tables')
    args = parser.parse_args()

    index = extract(args.exe, args.out)
    if args.index:
        for table in ('SALDOESTOQUE', 'CUSTOMEDIO', 'ENTRADA', 'ENTRADADET',
                      'OPERACOES', 'KEYCONT', 'OPR00000', 'PROM0000', 'PROF0000'):
            print_index(index, table)


if __name__ == '__main__':
    main()

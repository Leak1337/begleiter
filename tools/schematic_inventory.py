"""Read-only NBT inventory and content fingerprints for bundled schematics (stdlib only)."""
import collections
import gzip
import hashlib
import json
from pathlib import Path
import struct
import sys


class Nbt:
    def __init__(self, raw):
        self.raw, self.at = raw, 0

    def take(self, n):
        if n < 0 or self.at + n > len(self.raw):
            raise ValueError("Truncated NBT")
        out = self.raw[self.at:self.at + n]
        self.at += n
        return out

    def number(self, fmt):
        return struct.unpack('>' + fmt, self.take(struct.calcsize(fmt)))[0]

    def string(self):
        return self.take(self.number('H')).decode('utf-8', errors='replace')

    def payload(self, kind):
        if 1 <= kind <= 6:
            return self.number({1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}[kind])
        if kind == 7:
            return list(self.take(self.number('i')))
        if kind == 8:
            return self.string()
        if kind == 9:
            subtype, length = self.number('B'), self.number('i')
            return [self.payload(subtype) for _ in range(length)]
        if kind == 10:
            out = {}
            while (subtype := self.number('B')):
                name = self.string()
                out[name] = self.payload(subtype)
            return out
        if kind in (11, 12):
            length = self.number('i')
            return [self.number('i' if kind == 11 else 'q') for _ in range(length)]
        raise ValueError(f"Unknown NBT type {kind}")

    def root(self):
        kind = self.number('B')
        self.string()
        return self.payload(kind)


def read(path):
    raw = path.read_bytes()
    return Nbt(gzip.decompress(raw) if raw[:2] == b'\x1f\x8b' else raw).root()


def inspect(path):
    raw = path.read_bytes()
    root = read(path)
    row = dict(name=path.name, bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
    # Ignore only top-level author/name/time metadata; retain all actual region data and settings.
    content = {k: v for k, v in root.items() if k not in ('Metadata', 'MetadataVersion', 'Author')}
    row['content_sha256'] = hashlib.sha256(json.dumps(content, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    row['version'] = root.get('MinecraftDataVersion', root.get('DataVersion'))
    entities, block_entities, states = [], [], collections.Counter()
    bounds = []
    regions = root.get('Regions', {})
    for region in regions.values():
        size = [abs(region['Size'][axis]) for axis in 'xyz']
        bounds.append(dict(size=size, origin=region.get('Position')))
        palette, packed = region.get('BlockStatePalette', []), region.get('BlockStates', [])
        bits = max(2, (max(1, len(palette) - 1)).bit_length())
        mask = (1 << bits) - 1
        counts = collections.Counter()
        unsigned = [v & ((1 << 64) - 1) for v in packed]
        for index in range(size[0] * size[1] * size[2]):
            word, shift = divmod(index * bits, 64)
            if word >= len(unsigned):
                raise ValueError('Incomplete block-state array')
            value = unsigned[word] >> shift
            if shift + bits > 64:
                value |= unsigned[word + 1] << (64 - shift)
            slot = value & mask
            if slot >= len(palette):
                raise ValueError('Invalid palette index')
            counts[slot] += 1
        for slot, count in counts.items():
            states[palette[slot].get('Name', '?')] += count
        entities.extend(region.get('Entities', []))
        block_entities.extend(region.get('TileEntities', []))
    if not regions:
        entities = root.get('Entities', root.get('Schematic', {}).get('Entities', []))
        block_entities = root.get('TileEntities', root.get('BlockEntities', []))
        if 'Schematic' in root:
            block_entities = root['Schematic'].get('Blocks', {}).get('BlockEntities', [])
        row['dimensions'] = [root.get(a) for a in ('Width', 'Height', 'Length')]
        row['palette'] = root.get('Palette', root.get('Schematic', {}).get('Blocks', {}).get('Palette', {}))
    row['regions'] = bounds
    row['blocks'] = dict(states.most_common())
    row['entities'] = dict(collections.Counter(e.get('id', e.get('Id', e.get('Data', {}).get('id', '?'))) for e in entities))
    row['entity_details'] = entities
    row['block_entity_count'] = len(block_entities)
    row['block_entity_types'] = dict(collections.Counter(e.get('id', e.get('Id', '?')) for e in block_entities))
    row['texts'] = [e for e in block_entities if any(k in e for k in ('Text1', 'front_text', 'back_text'))]
    row['inventories'] = [e for e in block_entities if e.get('Items')]
    return row


if __name__ == '__main__':
    folder, output = map(Path, sys.argv[1:3])
    rows = []
    for path in sorted(folder.iterdir()):
        if path.suffix.lower() not in ('.litematic', '.schem', '.schematic', '.nbt'):
            continue
        try:
            row = inspect(path)
        except Exception as error:
            row = dict(name=path.name, error=str(error))
        rows.append(row)
        print(json.dumps({k: v for k, v in row.items() if k in ('name', 'regions', 'entities', 'block_entity_count', 'error')}, ensure_ascii=True))
    output.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')

#!/usr/bin/env python3
"""Convert the production Mother Willow voxel CSV into a Sponge Schematic v2."""
import csv
import gzip
import struct
import sys
from pathlib import Path


def nbt_name(value):
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def tag(tag_type, name, payload):
    return bytes((tag_type,)) + nbt_name(name) + payload


def int_tag(name, value):
    return tag(3, name, struct.pack(">i", value))


def short_tag(name, value):
    return tag(2, name, struct.pack(">h", value))


def string_tag(name, value):
    encoded = value.encode("utf-8")
    return tag(8, name, struct.pack(">H", len(encoded)) + encoded)


def byte_array_tag(name, values):
    return tag(7, name, struct.pack(">i", len(values)) + values)


def int_array_tag(name, values):
    return tag(11, name, struct.pack(">i", len(values))
               + b"".join(struct.pack(">i", item) for item in values))


def list_tag(name, element_type, count):
    return tag(9, name, bytes((element_type,)) + struct.pack(">i", count))


def compound_tag(name, children):
    return tag(10, name, b"".join(children) + b"\x00")


def varint(value):
    encoded = bytearray()
    while value & ~0x7F:
        encoded.append((value & 0x7F) | 0x80)
        value >>= 7
    encoded.append(value)
    return encoded


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: export_mother_willow_schematic.py input.csv output.schem")
    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    voxels = {}
    minimum = [10**9, 10**9, 10**9]
    maximum = [-(10**9), -(10**9), -(10**9)]

    with source.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            position = (int(row["x"]), int(row["y"]), int(row["z"]))
            voxels[position] = row["state"]
            for axis in range(3):
                minimum[axis] = min(minimum[axis], position[axis])
                maximum[axis] = max(maximum[axis], position[axis])

    width = maximum[0] - minimum[0] + 1
    height = maximum[1] - minimum[1] + 1
    length = maximum[2] - minimum[2] + 1
    if max(width, height, length) > 32767:
        raise SystemExit(f"schematic dimensions exceed signed-short limit: {width}x{height}x{length}")

    states = ["minecraft:air"] + sorted(set(voxels.values()))
    palette = {state: index for index, state in enumerate(states)}
    block_data = bytearray()
    for y in range(minimum[1], maximum[1] + 1):
        for z in range(minimum[2], maximum[2] + 1):
            for x in range(minimum[0], maximum[0] + 1):
                block_data.extend(varint(palette.get(voxels.get((x, y, z), "minecraft:air"), 0)))

    schematic = compound_tag("Schematic", [
        int_tag("Version", 2),
        int_tag("DataVersion", 0),
        short_tag("Width", width),
        short_tag("Height", height),
        short_tag("Length", length),
        int_array_tag("Offset", [0, 0, 0]),
        int_tag("PaletteMax", len(palette)),
        compound_tag("Palette", [int_tag(state, index) for state, index in palette.items()]),
        byte_array_tag("BlockData", bytes(block_data)),
        list_tag("BlockEntities", 10, 0),
        list_tag("Entities", 10, 0),
        compound_tag("Metadata", [
            string_tag("Name", "Mother Willow R14.43 production voxel preview"),
            string_tag("Author", "Metropolis deterministic geometry QA")
        ])
    ])

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(gzip.compress(schematic, mtime=0))

    decoded = gzip.decompress(output.read_bytes())
    if not decoded.startswith(b"\x0a\x00\x09Schematic"):
        raise SystemExit("Sponge schematic root NBT validation failed")
    if len(block_data) == 0 or len(voxels) == 0:
        raise SystemExit("Sponge schematic contains no production block writes")
    print(f"Mother Willow schematic PASS: {width}x{height}x{length}, "
          f"{len(voxels)} emitted blocks, {len(palette)} palette states")


if __name__ == "__main__":
    main()

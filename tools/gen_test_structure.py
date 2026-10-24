"""Genera estructuras vacías para los GameTests: un piso de piedra en y=0 y aire arriba.

    python tools/gen_test_structure.py empty_big 17 18 17

La deja en src/main/resources/data/warbandcraft/structures/<nombre>.nbt (como empty.nbt, pero del tamaño pedido:
los edificios con estilo ocupan hasta 13×13 y 15 de alto).
"""
import gzip
import os
import struct
import sys

DATA_VERSION = 3465  # 1.20.1


def name(n):
    b = n.encode()
    return struct.pack('>H', len(b)) + b


def tag(t, n, payload):
    return struct.pack('>b', t) + name(n) + payload


def int_list(values):
    return struct.pack('>bi', 3, len(values)) + b''.join(struct.pack('>i', v) for v in values)


def compound(items):
    return b''.join(items) + b'\x00'


def main():
    nm, sx, sy, sz = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
    palette = compound([tag(8, 'Name', name('minecraft:stone'))])
    blocks = []
    for x in range(sx):
        for z in range(sz):
            blocks.append(compound([tag(9, 'pos', int_list([x, 0, z])), tag(3, 'state', struct.pack('>i', 0))]))
    root = compound([
        tag(3, 'DataVersion', struct.pack('>i', DATA_VERSION)),
        tag(9, 'size', int_list([sx, sy, sz])),
        tag(9, 'palette', struct.pack('>bi', 10, 1) + palette),
        tag(9, 'blocks', struct.pack('>bi', 10, len(blocks)) + b''.join(blocks)),
        tag(9, 'entities', struct.pack('>bi', 10, 0)),
    ])
    data = struct.pack('>b', 10) + name('') + root
    out = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'data', 'warbandcraft', 'structures', nm + '.nbt')
    with gzip.open(out, 'wb') as f:
        f.write(data)
    print(os.path.normpath(out))


if __name__ == '__main__':
    main()

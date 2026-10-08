#!/usr/bin/env python3
# Copyright 2026 Kite Browser authors（Kite の tools/ai-bridge/kite_qr.py から写した。作者は同じ）。
"""依存なしの小さな QR コード作成（バイトモード・誤り訂正 M・型番 1〜15）。

中継の接続情報を wave に読ませるためだけのもの。端末に半角ブロックで描く（render_terminal）か、
PNG にする（to_png）。作り方は ISO/IEC 18004 の手順どおり（Project Nayuki の解説に沿った実装）。
"""

import struct
import zlib

# 誤り訂正 M の、型番ごとの（ブロックあたりの訂正語数, [(ブロック数, ブロックあたりのデータ語数), ...]）
_ECC_M = {
    1: (10, [(1, 16)]),
    2: (16, [(1, 28)]),
    3: (26, [(1, 44)]),
    4: (18, [(2, 32)]),
    5: (24, [(2, 43)]),
    6: (16, [(4, 27)]),
    7: (18, [(4, 31)]),
    8: (22, [(2, 38), (2, 39)]),
    9: (22, [(3, 36), (2, 37)]),
    10: (26, [(4, 43), (1, 44)]),
    11: (30, [(1, 50), (4, 51)]),
    12: (22, [(6, 36), (2, 37)]),
    13: (22, [(8, 37), (1, 38)]),
    14: (24, [(4, 40), (5, 41)]),
    15: (24, [(5, 41), (5, 42)]),
}
_ALIGN = {
    1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34], 7: [6, 22, 38],
    8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50], 11: [6, 30, 54], 12: [6, 32, 58],
    13: [6, 34, 62], 14: [6, 26, 46, 66], 15: [6, 26, 48, 70],
}

# ---- GF(256) と Reed-Solomon ----

_EXP = [0] * 512
_LOG = [0] * 256
_x = 1
for _i in range(255):
    _EXP[_i] = _x
    _LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    _EXP[_i] = _EXP[_i - 255]


def _mul(a, b):
    if a == 0 or b == 0:
        return 0
    return _EXP[_LOG[a] + _LOG[b]]


def _rs_generator(degree):
    g = [1]
    for i in range(degree):
        # g *= (x - α^i)
        ng = [0] * (len(g) + 1)
        for j, c in enumerate(g):
            ng[j] ^= c
            ng[j + 1] ^= _mul(c, _EXP[i])
        g = ng
    return g


def _rs_remainder(data, degree):
    gen = _rs_generator(degree)
    rem = list(data) + [0] * degree
    for i in range(len(data)):
        coef = rem[i]
        if coef:
            for j in range(1, len(gen)):
                rem[i + j] ^= _mul(gen[j], coef)
    return rem[len(data):]


# ---- 符号化 ----


def _data_capacity(version):
    _, blocks = _ECC_M[version]
    return sum(n * d for n, d in blocks)


def _choose_version(nbytes):
    for v in range(1, 16):
        count_bits = 8 if v <= 9 else 16
        if 4 + count_bits + nbytes * 8 <= _data_capacity(v) * 8:
            return v
    raise ValueError("too long for this small QR encoder (%d bytes)" % nbytes)


def _encode_data(payload, version):
    bits = []

    def put(value, n):
        for i in range(n - 1, -1, -1):
            bits.append((value >> i) & 1)

    put(0b0100, 4)  # バイトモード
    put(len(payload), 8 if version <= 9 else 16)
    for b in payload:
        put(b, 8)
    cap = _data_capacity(version) * 8
    put(0, min(4, cap - len(bits)))
    while len(bits) % 8:
        bits.append(0)
    data = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = [0xEC, 0x11]
    i = 0
    while len(data) < cap // 8:
        data.append(pad[i % 2])
        i += 1
    return data


def _interleave(data, version):
    ec_len, groups = _ECC_M[version]
    blocks = []
    pos = 0
    for n, d in groups:
        for _ in range(n):
            blk = data[pos:pos + d]
            pos += d
            blocks.append((blk, _rs_remainder(blk, ec_len)))
    out = []
    for i in range(max(len(b[0]) for b in blocks)):
        for blk, _ in blocks:
            if i < len(blk):
                out.append(blk[i])
    for i in range(ec_len):
        for _, ec in blocks:
            out.append(ec[i])
    return out


# ---- 配置 ----


class _Matrix:
    def __init__(self, version):
        self.version = version
        self.size = 17 + 4 * version
        self.mod = [[False] * self.size for _ in range(self.size)]
        self.fn = [[False] * self.size for _ in range(self.size)]

    def set_fn(self, x, y, dark):
        self.mod[y][x] = dark
        self.fn[y][x] = True

    def draw_function_patterns(self):
        s = self.size
        for i in range(s):
            self.set_fn(6, i, i % 2 == 0)
            self.set_fn(i, 6, i % 2 == 0)
        for cx, cy in ((3, 3), (s - 4, 3), (3, s - 4)):
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    x, y = cx + dx, cy + dy
                    if 0 <= x < s and 0 <= y < s:
                        d = max(abs(dx), abs(dy))
                        self.set_fn(x, y, d not in (2, 4))
        pos = _ALIGN[self.version]
        n = len(pos)
        for i in range(n):
            for j in range(n):
                if (i == 0 and j == 0) or (i == 0 and j == n - 1) or (i == n - 1 and j == 0):
                    continue
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        self.set_fn(pos[i] + dx, pos[j] + dy, max(abs(dx), abs(dy)) != 1)
        self.draw_format(0)  # 場所を取っておく（あとで上書き）
        self.draw_version()

    def draw_format(self, mask):
        data = (0b00 << 3) | mask  # 誤り訂正 M = 00
        rem = data
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = ((data << 10) | rem) ^ 0x5412
        s = self.size

        def bit(i):
            return (bits >> i) & 1 == 1

        for i in range(6):
            self.set_fn(8, i, bit(i))
        self.set_fn(8, 7, bit(6))
        self.set_fn(8, 8, bit(7))
        self.set_fn(7, 8, bit(8))
        for i in range(9, 15):
            self.set_fn(14 - i, 8, bit(i))
        for i in range(8):
            self.set_fn(s - 1 - i, 8, bit(i))
        for i in range(8, 15):
            self.set_fn(8, s - 15 + i, bit(i))
        self.set_fn(8, s - 8, True)

    def draw_version(self):
        if self.version < 7:
            return
        rem = self.version
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        bits = (self.version << 12) | rem
        for i in range(18):
            dark = (bits >> i) & 1 == 1
            a = self.size - 11 + i % 3
            b = i // 3
            self.set_fn(a, b, dark)
            self.set_fn(b, a, dark)

    def draw_codewords(self, codewords):
        s = self.size
        total = len(codewords) * 8
        i = 0
        right = s - 1
        while right >= 1:
            if right == 6:
                right = 5
            for vert in range(s):
                for j in range(2):
                    x = right - j
                    upward = ((right + 1) & 2) == 0
                    y = s - 1 - vert if upward else vert
                    if not self.fn[y][x] and i < total:
                        self.mod[y][x] = (codewords[i >> 3] >> (7 - (i & 7))) & 1 == 1
                        i += 1
            right -= 2

    def apply_mask(self, mask):
        for y in range(self.size):
            for x in range(self.size):
                if self.fn[y][x]:
                    continue
                if mask == 0:
                    inv = (x + y) % 2 == 0
                elif mask == 1:
                    inv = y % 2 == 0
                elif mask == 2:
                    inv = x % 3 == 0
                elif mask == 3:
                    inv = (x + y) % 3 == 0
                elif mask == 4:
                    inv = (x // 3 + y // 2) % 2 == 0
                elif mask == 5:
                    inv = x * y % 2 + x * y % 3 == 0
                elif mask == 6:
                    inv = (x * y % 2 + x * y % 3) % 2 == 0
                else:
                    inv = ((x + y) % 2 + x * y % 3) % 2 == 0
                if inv:
                    self.mod[y][x] = not self.mod[y][x]

    def penalty(self):
        s = self.size
        m = self.mod
        score = 0
        lines = [m[y] for y in range(s)] + [[m[y][x] for y in range(s)] for x in range(s)]
        for line in lines:
            run = 1
            for i in range(1, s):
                if line[i] == line[i - 1]:
                    run += 1
                else:
                    if run >= 5:
                        score += 3 + run - 5
                    run = 1
            if run >= 5:
                score += 3 + run - 5
            text = "0000" + "".join("1" if v else "0" for v in line) + "0000"
            for pat in ("10111010000", "00001011101"):
                start = 0
                while True:
                    k = text.find(pat, start)
                    if k < 0:
                        break
                    score += 40
                    start = k + 1
        for y in range(s - 1):
            for x in range(s - 1):
                c = m[y][x]
                if c == m[y][x + 1] == m[y + 1][x] == m[y + 1][x + 1]:
                    score += 3
        dark = sum(v for row in m for v in row)
        total = s * s
        k = (abs(dark * 20 - total * 10) + total - 1) // total - 1
        score += max(0, k) * 10
        return score


def encode(text):
    """text（文字列）の QR を、真偽の 2 次元配列（True が黒）で返す。"""
    payload = text.encode("utf-8")
    version = _choose_version(len(payload))
    codewords = _interleave(_encode_data(payload, version), version)
    best = None
    for mask in range(8):
        mat = _Matrix(version)
        mat.draw_function_patterns()
        mat.draw_codewords(codewords)
        mat.apply_mask(mask)
        mat.draw_format(mask)
        p = mat.penalty()
        if best is None or p < best[0]:
            best = (p, mat)
    return best[1].mod


def render_terminal(modules, quiet=2):
    """端末に描く（1 文字で縦 2 マス）。背景の色に左右されないよう、24 ビット色で白黒を指定する。"""
    size = len(modules)
    n = size + quiet * 2

    def dark(x, y):
        x -= quiet
        y -= quiet
        return 0 <= x < size and 0 <= y < size and modules[y][x]

    black = "0;0;0"
    white = "255;255;255"
    out = []
    for y in range(0, n, 2):
        line = []
        for x in range(n):
            top = dark(x, y)
            bottom = dark(x, y + 1) if y + 1 < n else False
            line.append(
                "\x1b[38;2;%sm\x1b[48;2;%sm▀" % (black if top else white, black if bottom else white)
            )
        out.append("".join(line) + "\x1b[0m")
    return "\n".join(out)


def to_png(modules, scale=8, quiet=4):
    size = len(modules)
    n = (size + quiet * 2) * scale
    rows = []
    for py in range(n):
        y = py // scale - quiet
        row = bytearray([0])
        for px in range(n):
            x = px // scale - quiet
            d = 0 <= x < size and 0 <= y < size and modules[y][x]
            row.append(0 if d else 255)
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 0, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


if __name__ == "__main__":
    import sys

    print(render_terminal(encode(sys.argv[1] if len(sys.argv) > 1 else "hello")))

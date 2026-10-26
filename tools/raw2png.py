#!/usr/bin/env python3
"""Convert an Android `screencap` raw dump to PNG (stdlib only).

Usage: python3 -I tools/raw2png.py in.raw out.png
"""
import struct
import sys
import zlib


def chunk(tag, body):
    crc = zlib.crc32(tag + body) & 0xffffffff
    return struct.pack('>I', len(body)) + tag + body + struct.pack('>I', crc)


def main():
    if len(sys.argv) < 3:
        sys.exit('usage: raw2png.py in.raw out.png')
    with open(sys.argv[1], 'rb') as f:
        data = f.read()
    width, height, pixel_format = struct.unpack('<III', data[:12])
    # 1 = RGBA_8888, 2 = RGBX_8888; both are 4 bytes per pixel.
    assert pixel_format in (1, 2), 'unsupported pixel format %d' % pixel_format
    stride = width * 4
    pixels = data[12:12 + stride * height]
    assert len(pixels) == stride * height, 'truncated pixel data'
    rows = b''.join(b'\x00' + pixels[y * stride:(y + 1) * stride]
                    for y in range(height))
    header = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    with open(sys.argv[2], 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header)
                + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))


if __name__ == '__main__':
    main()

import sys,struct,zlib
d=open(sys.argv[1],'rb').read(); w,h,f=struct.unpack('<III',d[:12]); px=d[12:12+w*h*4]
rows=b''.join(b'\x00'+px[y*w*4:(y+1)*w*4] for y in range(h))
def ch(t,b): return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
open(sys.argv[2],'wb').write(b'\x89PNG\r\n\x1a\n'+ch(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+ch(b'IDAT',zlib.compress(rows))+ch(b'IEND',b''))

import struct
class Elf:
 def __init__(self,p):
  self.d=open(p,'rb').read();h=struct.unpack_from('<16sHHIIIIIHHHHHH',self.d);self.sh=[]
  for i in range(h[12]):self.sh.append(struct.unpack_from('<IIIIIIIIII',self.d,h[6]+i*h[11]))
  self.names=self.section(h[13]);self.sym={};self.symbols=[]
  for s in self.sh:
   if s[1]==11:
    names=self.section(s[6])
    for off in range(s[4],s[4]+s[5],s[9]):
     name,val,size,info,other,idx=struct.unpack_from('<IIIBBH',self.d,off);name=names[name:].split(b'\0')[0].decode();self.symbols.append((name,val,size,idx));self.sym[val&~1]=name
 def section(self,i):s=self.sh[i];return self.d[s[4]:s[4]+s[5]]
 def read(self,a,n):
  for s in self.sh:
   if s[1]!=8 and s[3]<=a and a+n<=s[3]+s[5]:return self.d[s[4]+a-s[3]:s[4]+a-s[3]+n]
  raise ValueError(hex(a))
 def word(self,a):return struct.unpack('<I',self.read(a,4))[0]
 def string(self,a):
  try:return self.read(a,200).split(b'\0')[0].decode('utf8','replace')
  except:return '?'
 def relocations(self):
  out={}
  for i,s in enumerate(self.sh):
   if s[1]!=9 and not self.names[s[0]:].split(b'\0')[0] in [b'.rel.dyn',b'.rel.plt']:continue
   d=self.section(i)
   if d[:4]==b'APS2':
    pos=4
    def sleb():
     nonlocal pos
     val=0;shift=0
     while True:
      b=d[pos];pos+=1;val|=(b&127)<<shift;shift+=7
      if not b&128:break
     return val-(1<<shift) if b&64 else val
    count=sleb();off=sleb();done=0
    while done<count:
     size=sleb();flags=sleb();delta=sleb() if flags&2 else None;info=sleb() if flags&1 else None
     assert not flags&8
     for _ in range(size):
      off+=delta if flags&2 else sleb();inf=info if flags&1 else sleb();out[off]=inf;done+=1
   else:
    for j in range(0,len(d),8):off,info=struct.unpack_from('<II',d,j);out[off]=info
  return out

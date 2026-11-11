"""Append two signature-protected components without rebuilding SFA resources.

The original string indices/resource references and all existing nodes remain
unchanged. Works only with ordinary style-free manifest string pools; fails
closed for an unexpected binary structure.
"""
import struct

U32=0xffffffff
ANDROID="http://schemas.android.com/apk/res/android"
PERMISSION="io.nekohasekai.sfa.permission.RICE_CONTROL"

def patch(data):
    kind,header,total=struct.unpack_from('<HHI',data)
    if (kind,header,total)!=(3,8,len(data)):raise ValueError('Unexpected AXML header')
    chunks=[];pos=8
    while pos<len(data):
        typ,hs,size=struct.unpack_from('<HHI',data,pos)
        if size<hs or pos+size>len(data):raise ValueError('Invalid AXML chunk')
        chunks.append((typ,data[pos:pos+size]));pos+=size
    pool=next(b for t,b in chunks if t==1)
    n,styles,flags,start,style_start=struct.unpack_from('<IIIII',pool,8)
    if styles or style_start:raise ValueError('Styled AXML pool unsupported')
    utf8=bool(flags&0x100)
    def length(blob,pos,byte):
        if byte:
            a=blob[pos];pos+=1
            if a&0x80:a=((a&127)<<8)|blob[pos];pos+=1
        else:
            a=struct.unpack_from('<H',blob,pos)[0];pos+=2
            if a&0x8000:a=((a&0x7fff)<<16)|struct.unpack_from('<H',blob,pos)[0];pos+=2
        return a,pos
    strings=[]
    for i in range(n):
        at=start+struct.unpack_from('<I',pool,28+i*4)[0]
        a,at=length(pool,at,utf8)
        if utf8:
            count,at=length(pool,at,True);s=pool[at:at+count].decode('utf-8')
        else:s=pool[at:at+a*2].decode('utf-16le')
        strings.append(s)
    resource_blob=next((b for t,b in chunks if t==0x180),None)
    ids=list(struct.unpack('<'+'I'*((len(resource_blob)-8)//4),resource_blob[8:])) if resource_blob else []
    ids.extend([0]*(len(strings)-len(ids)))
    def sid(text):
        try:return strings.index(text)
        except ValueError:strings.append(text);ids.append(0);return len(strings)-1
    def aid(text,res):
        if res in ids:return ids.index(res)
        strings.append(text);ids.append(res);return len(strings)-1
    ns=sid(ANDROID);app_label=sid('Focal VPN')
    attrs={
        'name':aid('name',0x01010003),'permission':aid('permission',0x01010006),
        'protectionLevel':aid('protectionLevel',0x01010009),
        'exported':aid('exported',0x01010010),'authorities':aid('authorities',0x01010018),
        'excludeFromRecents':aid('excludeFromRecents',0x01010017),
        'launchMode':aid('launchMode',0x0101001d)}
    def element(tag,values):
        tagid=sid(tag);payload=[]
        # Android's styled-attribute reader expects resource IDs in ascending
        # order, even when aapt2's XML dumper accepts an unsorted chunk.
        for key,(typ,value) in sorted(values.items(),key=lambda item:ids[attrs[item[0]]]):
            actual=sid(value) if typ==3 else value
            payload.append(struct.pack('<IIIHBBI',ns,attrs[key],actual if typ==3 else U32,8,0,typ,actual))
        count=len(payload)
        start_chunk=struct.pack('<HHIII',0x102,16,36+20*count,1,U32)+struct.pack('<IIHHHHHH',U32,tagid,20,20,count,0,0,0)+b''.join(payload)
        end_chunk=struct.pack('<HHIIIII',0x103,16,24,1,U32,U32,tagid)
        return start_chunk+end_chunk
    permission=element('permission',{'name':(3,PERMISSION),'protectionLevel':(0x10,2)})
    activity=element('activity',{'name':(3,'dev.projector.singbox.RiceControlActivity'),
        'exported':(0x12,U32),'permission':(3,PERMISSION),'excludeFromRecents':(0x12,U32),'launchMode':(0x10,2)})
    provider=element('provider',{'name':(3,'dev.projector.singbox.RiceStatusProvider'),
        'authorities':(3,'io.nekohasekai.sfa.rice'),'exported':(0x12,U32),'permission':(3,PERMISSION)})
    def encode_len(value,byte):
        if byte:
            if value>=0x8000:raise ValueError('Oversized UTF8 manifest string')
            return bytes([value]) if value<128 else bytes([(value>>8)|128,value&255])
        return struct.pack('<H',value) if value<0x8000 else struct.pack('<HH',(value>>16)|0x8000,value&0xffff)
    offsets=[];encoded=b''
    for text in strings:
        offsets.append(len(encoded));u16=len(text.encode('utf-16le'))//2
        if utf8:
            b=text.encode('utf-8');encoded+=encode_len(u16,True)+encode_len(len(b),True)+b+b'\0'
        else:encoded+=encode_len(u16,False)+text.encode('utf-16le')+b'\0\0'
    encoded+=b'\0'*((-len(encoded))%4)
    pool=struct.pack('<HHIIIIII',1,28,28+4*len(strings)+len(encoded),len(strings),0,flags&~1,28+4*len(strings),0)+struct.pack('<'+'I'*len(offsets),*offsets)+encoded
    resources=struct.pack('<HHI',0x180,8,8+4*len(ids))+struct.pack('<'+'I'*len(ids),*ids)
    output=[];inserted=0
    for typ,b in chunks:
        if typ==1:output.extend([pool,resources]);continue
        if typ==0x180:continue
        if typ in (0x102,0x103):
            tag=strings[struct.unpack_from('<I',b,20)[0]]
            if typ==0x102 and tag=='application':
                # Identify this local derivative separately from the upstream app.
                node=bytearray(b);count=struct.unpack_from('<H',node,28)[0]
                for i in range(count):
                    at=36+20*i;name_index=struct.unpack_from('<I',node,at+4)[0]
                    if ids[name_index]==0x01010001:
                        struct.pack_into('<IHBBI',node,at+8,app_label,8,0,3,app_label)
                b=bytes(node);output.append(permission);inserted+=1
            if typ==0x103 and tag=='application':output.extend([activity,provider]);inserted+=1
        output.append(b)
    if inserted!=2:raise ValueError('Application boundaries not unique')
    result=b''.join(output)
    return struct.pack('<HHI',3,8,8+len(result))+result

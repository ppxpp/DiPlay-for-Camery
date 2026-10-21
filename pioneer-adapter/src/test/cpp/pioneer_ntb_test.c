#include "pioneer_ntb.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

struct expected { struct pn_frame *frames; unsigned count, seen; };
static int receive(void *opaque, const unsigned char *data, size_t size) {
    struct expected *e=opaque;
    assert(e->seen<e->count);assert(size==e->frames[e->seen].size);
    assert(memcmp(data,e->frames[e->seen].data,size)==0);e->seen++;return 0;
}
static int ignore(void *opaque,const unsigned char *data,size_t size) { (void)opaque;(void)data;(void)size;return 0; }

int main(void) {
    unsigned char data[16][1600], out[16384], second[16384]; struct pn_frame frames[16];
    for(unsigned i=0;i<16;i++)for(unsigned j=0;j<1600;j++)data[i][j]=(unsigned char)(i*31+j);
    for(unsigned count=1;count<=16;count++) {
        for(unsigned i=0;i<count;i++) { frames[i].data=data[i];frames[i].size=60+i*10; }
        size_t length=pn_build(out,sizeof(out),frames,count,65535);assert(length);
        assert(pn_u16(out+6)==65535);assert(pn_u16(out+16)==8+4*(count+1));
        struct pn_stream stream;pn_stream_init(&stream,512);
        struct expected expected={frames,count,0};
        assert(pn_feed(&stream,out,length,receive,&expected)==(int)count);assert(expected.seen==count);
        assert(stream.used==0&&stream.errors==0&&stream.copied_bytes==0);
        const size_t chunks[]={1,2,3,7,64,511,512,1024,32768};
        for(unsigned c=0;c<sizeof(chunks)/sizeof(chunks[0]);c++) {
            pn_stream_init(&stream,512);expected.seen=0;
            for(size_t offset=0;offset<length;) {
                size_t n=length-offset;if(n>chunks[c])n=chunks[c];
                assert(pn_feed(&stream,out+offset,n,receive,&expected)>=0);offset+=n;
            }
            assert(expected.seen==count&&stream.used==0&&stream.errors==0);
        }
    }
    /* A fixed three-frame wire fixture: NTH12, NDP24, four-byte alignment. */
    for(unsigned i=0;i<3;i++){frames[i].data=data[i];frames[i].size=60+i;}
    size_t size=pn_build(out,sizeof(out),frames,3,0x1234);
    assert(size==222&&pn_u32(out)==PN_NTH&&pn_u16(out+8)==222);
    assert(pn_u16(out+20)==36&&pn_u16(out+24)==96&&pn_u16(out+28)==160);
    assert(pn_u32(out+32)==0&&out[157]==0&&out[158]==0&&out[159]==0);

    /* Exact USB packet boundaries, including a pad arriving in a separate read. */
    frames[0].size=484;size=pn_build(out,sizeof(out),frames,1,0);
    assert(size==513&&pn_u16(out+8)==512&&out[512]==0);
    struct pn_stream stream;pn_stream_init(&stream,512);struct expected expected={frames,1,0};
    assert(pn_feed(&stream,out,512,receive,&expected)==1);
    assert(pn_feed(&stream,out+512,1,receive,&expected)==0&&stream.used==0);
    assert(pn_feed(&stream,out,size,receive,&(struct expected){frames,1,0})==1);
    frames[0].size=100;size=pn_build(out,sizeof(out),frames,1,0);assert(size==128);out[size++]=0;
    pn_stream_init(&stream,64);expected.seen=0;assert(pn_feed(&stream,out,size,receive,&expected)==1&&stream.used==0);

    /* Corruption may resynchronize; malformed/cyclic tables deliver no partial frames. */
    frames[0].size=100;size=pn_build(out,sizeof(out),frames,1,0);memcpy(second,out,size);
    pn_w16(out+18,12);pn_stream_init(&stream,512);expected.seen=0;
    assert(pn_feed(&stream,out,size,receive,&expected)==0&&expected.seen==0&&stream.errors==1);
    unsigned char joined[32768];memset(joined,0xa5,19);memcpy(joined+19,second,size);
    pn_stream_init(&stream,512);expected.seen=0;
    assert(pn_feed(&stream,joined,19+size,receive,&expected)==1&&stream.errors==1&&stream.used==0);
    memcpy(out,second,size);pn_w16(out+22,65535);pn_stream_init(&stream,512);expected.seen=0;
    assert(pn_feed(&stream,out,size,receive,&expected)==0&&expected.seen==0&&stream.errors==1);

    /* Bounds: frame limit, output capacity and random fragmented input under sanitizers. */
    assert(pn_build(out,20,frames,1,0)==0);assert(pn_build(out,sizeof(out),frames,0,0)==0);
    frames[0].size=16384;assert(pn_build(out,sizeof(out),frames,1,0)==0);frames[0].size=100;
    pn_stream_init(&stream,512);uint32_t random=7;
    for(unsigned i=0;i<10000;i++) {
        size_t length=1+(i%1024);for(size_t j=0;j<length;j++){random=random*1664525u+1013904223u;joined[j]=(unsigned char)(random>>24);}
        assert(pn_feed(&stream,joined,length,ignore,NULL)>=0);assert(stream.used<=PN_MAX_BLOCK);
    }
    puts("native NTB codec: batch 1..16, fragments, padding, resync, invalid tables and 10000 fuzz chunks passed");
    return 0;
}

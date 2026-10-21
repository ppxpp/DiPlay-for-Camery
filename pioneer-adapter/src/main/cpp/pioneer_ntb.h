#ifndef PIONEER_NTB_H
#define PIONEER_NTB_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>

#define PN_NTH 0x484d434eu
#define PN_NDP 0x304d434eu
#define PN_MAX_BLOCK 65535u
#define PN_OUT_LIMIT 16383u
#define PN_MAX_BATCH 16u
struct pn_frame { const unsigned char *data; size_t size; };
static uint16_t pn_u16(const unsigned char *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t pn_u32(const unsigned char *p) { return (uint32_t)pn_u16(p) | ((uint32_t)pn_u16(p+2) << 16); }
static void pn_w16(unsigned char *p, unsigned n) { p[0]=(unsigned char)n; p[1]=(unsigned char)(n>>8); }
static void pn_w32(unsigned char *p, uint32_t n) { pn_w16(p,n); pn_w16(p+2,n>>16); }

/* Identical wire layout to Ntb16Codec.buildBatch, including short-packet padding. */
static size_t pn_build(unsigned char *out, size_t capacity, const struct pn_frame *frames, unsigned count, uint16_t sequence) {
    if (!out || !frames || count < 1 || count > PN_MAX_BATCH) return 0;
    size_t offsets[PN_MAX_BATCH], ndp=8+4*(count+1), length=12+ndp;
    for (unsigned i=0;i<count;i++) {
        if (!frames[i].data || frames[i].size==0 || frames[i].size>PN_OUT_LIMIT) return 0;
        length=(length+3)&~(size_t)3; offsets[i]=length; length+=frames[i].size;
        if (length>PN_OUT_LIMIT) return 0;
    }
    size_t transfer=length+(length%512==0);
    if (transfer>capacity) return 0;
    /* Only metadata/alignment/pad need zeroes. Payload is overwritten exactly once. */
    size_t written=12+ndp;
    memset(out,0,written); pn_w32(out,PN_NTH); pn_w16(out+4,12); pn_w16(out+6,sequence);
    pn_w16(out+8,(unsigned)length); pn_w16(out+10,12); pn_w32(out+12,PN_NDP); pn_w16(out+16,(unsigned)ndp);
    for (unsigned i=0;i<count;i++) {
        pn_w16(out+20+4*i,(unsigned)offsets[i]); pn_w16(out+22+4*i,(unsigned)frames[i].size);
        memset(out+written,0,offsets[i]-written);
        memcpy(out+offsets[i],frames[i].data,frames[i].size);
        written=offsets[i]+frames[i].size;
    }
    if(transfer>written)memset(out+written,0,transfer-written);
    return transfer;
}

typedef int (*pn_receive)(void *, const unsigned char *, size_t);
/* Validate the complete table first: malformed/cyclic tables must not partially deliver a block. */
static int pn_parse(const unsigned char *block, size_t length, pn_receive receive, void *context) {
    if (length<28 || pn_u32(block)!=PN_NTH || pn_u16(block+4)!=12 || pn_u16(block+8)!=length) return -1;
    for (int pass=0;pass<2;pass++) {
        size_t ndp=pn_u16(block+10), hops=0; int delivered=0;
        if (!ndp) return -1;
        while (ndp) {
            if (++hops>length/4 || ndp>length-12 || (pn_u32(block+ndp)&0x00ffffffu)!=(PN_NDP&0x00ffffffu)) return -1;
            size_t table=pn_u16(block+ndp+4);
            if (table<12 || table%4 || table>length-ndp) return -1;
            int terminated=0;
            for (size_t entry=ndp+8;entry+4<=ndp+table;entry+=4) {
                size_t offset=pn_u16(block+entry), size=pn_u16(block+entry+2);
                if (!offset && !size) { terminated=1; break; }
                if (!offset || !size || offset<12 || offset>length || size>length-offset) return -1;
                if (pass && receive(context,block+offset,size)<0) return -2;
                delivered++;
            }
            if (!terminated) return -1;
            ndp=pn_u16(block+ndp+6);
        }
        if (pass) return delivered;
    }
    return -1;
}

struct pn_stream {
    unsigned char bytes[PN_MAX_BLOCK];
    size_t used;
    unsigned packet_size, padding, damaged;
    uint64_t errors, copied_bytes;
};
static void pn_stream_init(struct pn_stream *s, unsigned packet_size) {
    memset(s,0,sizeof(*s)); s->packet_size=packet_size ? packet_size : 512;
}
/* Synchronous consumer: input USB memory is never retained after this call. */
static int pn_feed(struct pn_stream *s, const unsigned char *data, size_t length, pn_receive receive, void *context) {
    int frames=0;
    while (length || s->used>=12) {
        if (!s->used && s->padding && length) { s->padding=0; if (*data==0) { data++;length--;continue; } }
        /* Whole USB blocks stay in their original DirectByteBuffer. */
        if (!s->used && length>=12 && pn_u32(data)==PN_NTH && pn_u16(data+4)==12) {
            size_t size=pn_u16(data+8);
            if (size>=28 && size<=length) {
                int result=pn_parse(data,size,receive,context);
                if (result==-2) return -2;
                if (result<0) s->errors++; else frames+=result;
                s->damaged=0; s->padding=(size%s->packet_size==0);data+=size;length-=size;continue;
            }
        }
        if (length) {
            size_t n=sizeof(s->bytes)-s->used; if (n>length)n=length;
            if (!n) return -1;
            memcpy(s->bytes+s->used,data,n);s->copied_bytes+=n;s->used+=n;data+=n;length-=n;
        }
        if (s->used<12) break;
        if (pn_u32(s->bytes)!=PN_NTH || pn_u16(s->bytes+4)!=12 || pn_u16(s->bytes+8)<28) {
            if (!s->damaged) { s->errors++;s->damaged=1; }
            size_t skip=1;
            while (skip+4<=s->used && pn_u32(s->bytes+skip)!=PN_NTH) skip++;
            memmove(s->bytes,s->bytes+skip,s->used-skip);s->used-=skip;
            continue;
        }
        size_t size=pn_u16(s->bytes+8);
        if (s->used<size) { if (!length) break; continue; }
        int result=pn_parse(s->bytes,size,receive,context);
        if (result==-2) return -2;
        if (result<0) s->errors++; else frames+=result;
        s->damaged=0; s->padding=(size%s->packet_size==0);
        memmove(s->bytes,s->bytes+size,s->used-size);s->used-=size;
        if (s->padding && s->used) {
            s->padding=0;
            if (!s->bytes[0]) { memmove(s->bytes,s->bytes+1,--s->used); }
        }
    }
    return frames;
}
#endif

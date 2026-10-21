/* Host test: compile with JNI include directories and -pthread. No JVM needed. */
#include <assert.h>
#include <stdio.h>
#include <sys/socket.h>
#include "../../main/cpp/pioneer_vpn.c"
static int count_reply(void *ctx,const unsigned char *frame,size_t len) {
    int *count=ctx;(*count)++;assert(len==86);assert(frame[54]==136);assert(checksum(frame+14,32)==65535);assert(frame[78]==2);return 0;
}
static int count_tx(void *ctx,const unsigned char *frame,size_t len) {
    (*(int *)ctx)++;assert(len>=54);assert(frame[12]==0x86 && frame[13]==0xdd);
    assert(40u+be16(frame+18)==len-14);return 0;
}
int main(void) {
    int pair[2];assert(socketpair(AF_UNIX,SOCK_DGRAM,0,pair)==0);
    int socket_bytes=65536;assert(setsockopt(pair[1],SOL_SOCKET,SO_SNDBUF,&socket_bytes,sizeof(socket_bytes))==0);assert(setsockopt(pair[0],SOL_SOCKET,SO_RCVBUF,&socket_bytes,sizeof(socket_bytes))==0);
    struct vpn v={0};v.fd=pair[0];assert(pthread_mutex_init(&v.peer_lock,NULL)==0);
    unsigned char mac[6]={2,3,4,5,6,7};memcpy(v.mac,mac,6);v.address[0]=0xfd;v.address[15]=1;
    unsigned char reply[16384],frame[86]={0};struct input in={.v=&v,.reply=reply,.capacity=sizeof(reply)};
    memcpy(frame+6,mac,6);frame[12]=0x86;frame[13]=0xdd;unsigned char *ip=frame+14;
    ip[0]=0x60;ip[5]=8;ip[6]=17;ip[8]=0xfd;ip[23]=2;
    assert(accept_frame(&in,frame,62)==0);unsigned char received[100];assert(read(pair[1],received,sizeof(received))==48);assert(memcmp(received,ip,48)==0);assert(v.has_peer);
    memset(ip,0,72);ip[0]=0x60;ip[5]=24;ip[6]=58;ip[7]=255;ip[8]=0xfd;ip[23]=2;ip[40]=135;memcpy(ip+48,v.address,16);fix_checksum(ip);
    assert(accept_frame(&in,frame,78)==0);assert(finish_replies(&in)==0);assert(in.reply_size==114);int count=0;assert(pn_parse(reply,114,count_reply,&count)==1);assert(count==1);assert(memcmp(reply+28,mac,6)==0);
    /* DAD uses all-nodes multicast and clears solicited flag. */
    in.reply_count=0;memset(ip+8,0,16);fix_checksum(ip);assert(accept_frame(&in,frame,78)==0);assert(finish_replies(&in)==0);assert(reply[28]==0x33 && reply[33]==1);assert(reply[28+14+44]==0x20);
    /* Eight distinct peers get separate replies; ninth NS is forwarded, never dropped. */
    in.reply_count=0;in.reply_size=0;
    for(unsigned i=0;i<9;i++){
        frame[11]=(unsigned char)(20+i);ip[8]=0xfd;ip[23]=(unsigned char)(40+i);fix_checksum(ip);
        assert(accept_frame(&in,frame,78)==0);
    }
    assert(in.reply_count==8);assert(finish_replies(&in)==0);
    assert(read(pair[1],received,sizeof(received))==64);assert(received[23]==48);
    count=0;assert(pn_parse(reply,in.reply_size,count_reply,&count)==8);assert(count==8);
    for(unsigned i=0;i<8;i++){
        unsigned offset=pn_u16(reply+20+4*i);
        assert(reply[offset+5]==20+i);assert(reply[offset+14+39]==40+i);
    }
    /* Android NA option insertion and replacement preserve valid checksum. */
    memset(ip,0,72);ip[0]=0x60;ip[5]=24;ip[6]=58;ip[40]=136;
    assert(add_na_mac(ip,64,mac)==72);assert(be16(ip+4)==32);assert(checksum(ip,32)==65535);
    unsigned char replacement[6]={8,9,10,11,12,13};assert(add_na_mac(ip,72,replacement)==72);assert(memcmp(ip+66,replacement,6)==0);assert(checksum(ip,32)==65535);
    /* Fragmented USB input delivers the IPv6 payload without Java copies. */
    ip[40]=128;fix_checksum(ip);struct pn_frame f={frame,86};unsigned char ntb[200];size_t size=pn_build(ntb,sizeof(ntb),&f,1,0);pn_stream_init(&v.stream,512);in.reply_size=0;in.reply_count=0;
    assert(pn_feed(&v.stream,ntb,19,accept_frame,&in)==0);assert(pn_feed(&v.stream,ntb+19,size-19,accept_frame,&in)==1);assert(read(pair[1],received,sizeof(received))==72);assert(in.reply_size==0);
    /* TX batches up to eight already-ready packets, leaves the ninth queued, no wait. */
    assert(fcntl(pair[0],F_SETFL,O_NONBLOCK)==0);
    for(unsigned i=0;i<9;i++){ip[23]=(unsigned char)i;assert(write(pair[1],ip,72)==72);}
    unsigned char out[16384];int total=output_batch(&v,out,sizeof(out),0,8);
    assert(total>0);assert(pn_u16(out+16)==44);count=0;assert(pn_parse(out,pn_u16(out+8),count_tx,&count)==8);assert(count==8);
    for(unsigned i=0;i<8;i++){unsigned offset=pn_u16(out+20+4*i);assert(offset%4==0);assert(pn_u16(out+22+4*i)==86);assert(out[offset+14+23]==i);}
    assert(pn_u16(out+52)==0 && pn_u16(out+54)==0);
    total=output_batch(&v,out,sizeof(out),0,8);assert(total>0);assert(pn_u16(out+16)==16);assert(out[pn_u16(out+20)+14+23]==8);
    assert(output_batch(&v,out,sizeof(out),0,8)==0);
    unsigned char packet[1501]={0};packet[0]=0x60;put16(packet+4,1460);packet[6]=17;
    for(int i=0;i<8;i++)assert(write(pair[1],packet,1500)==1500);
    total=output_batch(&v,out,sizeof(out),0,8);assert(total>0 && total<16384);count=0;assert(pn_parse(out,pn_u16(out+8),count_tx,&count)==8);assert(count==8);
    put16(packet+4,402);assert(write(pair[1],packet,442)==442);
    total=output_batch(&v,out,sizeof(out),0,8);assert(total==513);assert(pn_u16(out+8)==512 && out[512]==0);count=0;assert(pn_parse(out,512,count_tx,&count)==1);
    /* A malformed second packet fails the batch, never exposes its valid prefix. */
    assert(write(pair[1],packet,442)==442);packet[0]=0x40;assert(write(pair[1],packet,442)==442);
    assert(output_batch(&v,out,sizeof(out),0,8)==-1);
    close(pair[0]);close(pair[1]);pthread_mutex_destroy(&v.peer_lock);puts("pioneer_vpn_test: passed");return 0;
}

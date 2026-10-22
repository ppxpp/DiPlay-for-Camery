#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "pioneer_ntb.h"
#include "pioneer_vpn_urb.h"

#define VPN_METRIC(name) uint64_t name __attribute__((aligned(8)))

/* One RX caller and one TX caller; join both before destroy. */
struct vpn {
    int fd;
    unsigned char mac[6], address[16], peer[6];
    int has_peer;
    pthread_mutex_t peer_lock;
    pthread_mutex_t usb_write_lock;
    pthread_mutex_t usb_context_lock;
    volatile int running;
    struct vpn_linux_urb_context *usb_context;
    struct pn_stream stream;
    uint16_t usb_sequence;
    uint64_t error_window;
    unsigned window_errors;
    VPN_METRIC(rx_chunks);
    VPN_METRIC(rx_direct_blocks);
    VPN_METRIC(rx_stream_copies);
    VPN_METRIC(rx_copied_bytes);
    VPN_METRIC(rx_frames);
    VPN_METRIC(rx_tun_writes);
    VPN_METRIC(rx_ndp_replies);
    VPN_METRIC(rx_parse_errors);
    VPN_METRIC(tx_polls);
    VPN_METRIC(tx_packets);
    VPN_METRIC(tx_batches);
    VPN_METRIC(tx_batch_full);
    uint64_t tx_batch_hist[17] __attribute__((aligned(8)));
    VPN_METRIC(tx_tun_reads);
    VPN_METRIC(tx_tun_ready_drains);
    VPN_METRIC(tx_build_ns);
    VPN_METRIC(rx_process_ns);
    VPN_METRIC(rx_bytes);
    VPN_METRIC(tx_built_bytes);
    _Atomic unsigned max_batch_frames;
};
struct input {
    struct vpn *v; unsigned char *reply; size_t capacity, reply_size; int error;
    unsigned reply_count;
    unsigned char replies[8][86];
};
static int finish_replies(struct input *in) {
    if(!in->reply_count)return 0;
    struct pn_frame frames[8];
    for(unsigned i=0;i<in->reply_count;i++){frames[i].data=in->replies[i];frames[i].size=86;}
    in->reply_size=pn_build(in->reply,in->capacity,frames,in->reply_count,0);
    if(!in->reply_size){in->error=EMSGSIZE;return -1;}
    return 0;
}
static unsigned be16(const unsigned char *p) { return (unsigned)p[0]*256+p[1]; }
static void put16(unsigned char *p,unsigned n) { p[0]=(unsigned char)(n>>8);p[1]=(unsigned char)n; }
static uint64_t now_ms(void) { struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000; }
static uint64_t now_ns(void) { struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000000000+t.tv_nsec; }
static void metric_add(uint64_t *value,uint64_t amount){__atomic_fetch_add(value,amount,__ATOMIC_RELAXED);}
static void fail(JNIEnv *e,const char *s) { jclass c=(*e)->FindClass(e,"java/io/IOException");if(c)(*e)->ThrowNew(e,c,s); }
static struct vpn *handle(jlong h) { return (struct vpn *)(uintptr_t)h; }
static unsigned checksum(const unsigned char *ip,unsigned n) {
    unsigned sum=n+58;
    for(unsigned i=8;i<40;i+=2)sum+=be16(ip+i);
    for(unsigned i=0;i<n;i+=2)sum+=(unsigned)ip[40+i]*256+(i+1<n?ip[41+i]:0);
    while(sum>>16)sum=(sum&65535)+(sum>>16);
    return sum;
}
static void fix_checksum(unsigned char *ip) {ip[42]=ip[43]=0;put16(ip+42,(~checksum(ip,be16(ip+4)))&65535);}
static int neighbor_reply(struct input *in,const unsigned char *f,size_t n) {
    struct vpn *v=in->v;const unsigned char *ip=f+14;
    if(n<78 || ip[6]!=58 || ip[7]!=255 || ip[40]!=135 || ip[41]!=0)return 0;
    unsigned len=be16(ip+4);
    if(len<24 || 40u+len>n-14 || checksum(ip,len)!=65535 || memcmp(ip+48,v->address,16) || ip[8]==255)return 0;
    /* Forward excess solicitations to TUN so its normal NA path can answer. */
    if(in->reply_count==8)return 0;
    unsigned char *frame=in->replies[in->reply_count++],*na=frame+14;
    memset(frame,0,86);
    int dad=1;for(int i=8;i<24;i++)if(ip[i])dad=0;
    if(dad){frame[0]=frame[1]=0x33;frame[5]=1;na[24]=255;na[25]=2;na[39]=1;}
    else{memcpy(frame,f+6,6);memcpy(na+24,ip+8,16);}
    memcpy(frame+6,v->mac,6);frame[12]=0x86;frame[13]=0xdd;
    na[0]=0x60;na[5]=32;na[6]=58;na[7]=255;memcpy(na+8,v->address,16);
    na[40]=136;na[44]=dad?0x20:0x60;memcpy(na+48,v->address,16);
    na[64]=2;na[65]=1;memcpy(na+66,v->mac,6);fix_checksum(na);
    return 1;
}
static int accept_frame(void *context,const unsigned char *f,size_t n) {
    struct input *in=context;struct vpn *v=in->v;
    if(n<54 || f[12]!=0x86 || f[13]!=0xdd || (f[14]>>4)!=6)return 0;
    size_t len=40+be16(f+18);if(len>n-14)return 0;
    if(!__atomic_load_n(&v->has_peer,__ATOMIC_ACQUIRE)){
        pthread_mutex_lock(&v->peer_lock);
        if(!v->has_peer){memcpy(v->peer,f+6,6);__atomic_store_n(&v->has_peer,1,__ATOMIC_RELEASE);}
        pthread_mutex_unlock(&v->peer_lock);
    }
    int reply=neighbor_reply(in,f,n);
    if(reply<0)return -1;
    if(reply){metric_add(&v->rx_ndp_replies,1);return 0;}
    uint64_t deadline=now_ms()+100;
    for(;;){
        ssize_t wrote=write(v->fd,f+14,len);
        if(wrote==(ssize_t)len){metric_add(&v->rx_tun_writes,1);return 0;}
        if(wrote>=0){in->error=EIO;return -1;}
        if(errno!=EINTR && errno!=EAGAIN && errno!=EWOULDBLOCK){in->error=errno;return -1;}
        uint64_t now=now_ms();if(now>=deadline){in->error=ETIMEDOUT;return -1;}
        struct pollfd p={v->fd,POLLOUT,0};int result=poll(&p,1,(int)(deadline-now));
        if(result<0 && errno==EINTR)continue;
        if(result<=0 || (p.revents&(POLLERR|POLLHUP|POLLNVAL))){in->error=result<0?errno:EIO;return -1;}
    }
}
JNIEXPORT jlong JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerVpnPacketNative_create(JNIEnv *e,jobject o,jint fd,jbyteArray mac,jbyteArray address) {
    (void)o;if(!mac || !address || (*e)->GetArrayLength(e,mac)!=6 || (*e)->GetArrayLength(e,address)!=16){fail(e,"Invalid VPN address or MAC");return 0;}
    struct vpn *v=calloc(1,sizeof(*v));if(!v){fail(e,"VPN allocation failed");return 0;}
    v->fd=fcntl(fd,F_DUPFD_CLOEXEC,0);if(v->fd<0){free(v);fail(e,"Cannot duplicate TUN fd");return 0;}
    int flags=fcntl(v->fd,F_GETFL);if(flags<0 || fcntl(v->fd,F_SETFL,flags|O_NONBLOCK)<0 || pthread_mutex_init(&v->peer_lock,NULL)!=0 || pthread_mutex_init(&v->usb_write_lock,NULL)!=0 || pthread_mutex_init(&v->usb_context_lock,NULL)!=0){close(v->fd);free(v);fail(e,"Cannot initialize VPN fd");return 0;}
    (*e)->GetByteArrayRegion(e,mac,0,6,(jbyte *)v->mac);(*e)->GetByteArrayRegion(e,address,0,16,(jbyte *)v->address);
    pn_stream_init(&v->stream,512);v->max_batch_frames=1;v->error_window=now_ms();v->running=1;return (jlong)(uintptr_t)v;
}
static int process_input(struct vpn *v,const unsigned char *data,size_t length,
                         unsigned char *out,size_t ocap,jlong result[3]) {
    uint64_t process_start=now_ns();
    result[0]=result[1]=result[2]=0;
    struct input in={.v=v,.reply=out,.capacity=ocap};uint64_t previous=v->stream.errors;
    uint64_t copied_previous=v->stream.copied_bytes;
    int count=pn_feed(&v->stream,data,length,accept_frame,&in);
    metric_add(&v->rx_chunks,1);
    if(v->stream.copied_bytes>copied_previous){metric_add(&v->rx_stream_copies,1);metric_add(&v->rx_copied_bytes,v->stream.copied_bytes-copied_previous);}
    else metric_add(&v->rx_direct_blocks,1);
    if(count>0)metric_add(&v->rx_frames,(uint64_t)count);
    if(v->stream.errors>previous)metric_add(&v->rx_parse_errors,v->stream.errors-previous);
    if(count>=0 && finish_replies(&in)<0)count=-1;
    metric_add(&v->rx_process_ns,now_ns()-process_start);
    metric_add(&v->rx_bytes,length);
    if(count<0)return in.error? -in.error : -EIO;
    if(v->stream.errors!=previous){uint64_t now=now_ms();if(now-v->error_window>10000){v->error_window=now;v->window_errors=0;}v->window_errors+=(unsigned)(v->stream.errors-previous);if(v->window_errors>=16)return -3;}
    result[0]=(jlong)in.reply_size;result[1]=in.reply_count;result[2]=in.reply_count*86;return count;
}
static size_t add_na_mac(unsigned char *ip,size_t len,const unsigned char *mac) {
    if(len<64 || ip[6]!=58 || ip[40]!=136)return len;
    unsigned payload=be16(ip+4);if(payload<24 || 40u+payload>len)return len;
    size_t end=40+payload,offset=64;
    while(offset+2<=end){unsigned size=ip[offset+1]*8;if(!size || offset+size>end)break;if(ip[offset]==2 && size>=8){memcpy(ip+offset+2,mac,6);fix_checksum(ip);return len;}offset+=size;}
    ip[end]=2;ip[end+1]=1;memcpy(ip+end+2,mac,6);put16(ip+4,payload+8);fix_checksum(ip);return end+8;
}
/* Drain only packets already ready: no batching timer and no extra payload copy.
 * Header reserves eight datagram entries; a short final batch may keep unused space. */
static int output_batch(struct vpn *v,unsigned char *out,size_t capacity,int timeout,unsigned max_frames) {
    metric_add(&v->tx_polls,1);
    if(timeout>100)timeout=100;
    unsigned char peer[6];int known=__atomic_load_n(&v->has_peer,__ATOMIC_ACQUIRE);
    if(known){pthread_mutex_lock(&v->peer_lock);memcpy(peer,v->peer,6);pthread_mutex_unlock(&v->peer_lock);}
    if(!known){poll(NULL,0,timeout);return 0;}
    struct pollfd p={v->fd,POLLIN,0};int ready=poll(&p,1,timeout);
    if(ready<0 && errno==EINTR)return 0;
    if(ready<0 || (p.revents&(POLLERR|POLLHUP|POLLNVAL)))return -1;
    if(!ready)return 0;
    uint64_t build_start=now_ns();
    size_t block=(12+8+4*(max_frames+1)+3)&~(size_t)3;unsigned count=0;
    memset(out,0,block);
    while(count<max_frames) {
        size_t offset=(block+3)&~(size_t)3;
        /* TUN MTU is 1500. Read one extra byte to detect oversized datagrams. */
        if(offset+14+1501+8>capacity-1)break;
        unsigned char *frame=out+offset,*ip=frame+14;
        ssize_t n=read(v->fd,ip,1501);
        if(n<0 && (errno==EAGAIN || errno==EWOULDBLOCK))break;
        if(n<0 && errno==EINTR){if(count)break;return 0;}
        if(n<40 || n>1500 || (ip[0]>>4)!=6 || 40u+be16(ip+4)!=(size_t)n)return -1;
        metric_add(&v->tx_tun_reads,1);
        if(count)metric_add(&v->tx_tun_ready_drains,1);
        size_t len=add_na_mac(ip,(size_t)n,v->mac),frame_len=14+len;
        if(ip[24]==255){frame[0]=frame[1]=0x33;memcpy(frame+2,ip+36,4);}else memcpy(frame,peer,6);
        memcpy(frame+6,v->mac,6);frame[12]=0x86;frame[13]=0xdd;
        pn_w16(out+20+count*4,(unsigned)offset);pn_w16(out+22+count*4,(unsigned)frame_len);
        if(offset>block)memset(out+block,0,offset-block);
        block=offset+frame_len;count++;

    }
    if(!count)return 0;
    pn_w32(out,PN_NTH);pn_w16(out+4,12);pn_w16(out+8,(unsigned)block);pn_w16(out+10,12);
    pn_w32(out+12,PN_NDP);pn_w16(out+16,12+4*count);
    if(block%512==0)out[block++]=0;
    metric_add(&v->tx_batches,1);
    metric_add(&v->tx_packets,count);
    if(count==max_frames)metric_add(&v->tx_batch_full,1);
    if(count<=16)metric_add(&v->tx_batch_hist[count],1);
    metric_add(&v->tx_build_ns,now_ns()-build_start);
    metric_add(&v->tx_built_bytes,block);
    return (int)block;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerVpnPacketNative_snapshot(JNIEnv *e,jobject o,jlong h,jlongArray values) {
    (void)o;struct vpn *v=handle(h);
    if(!v||!values||(*e)->GetArrayLength(e,values)<36){fail(e,"Invalid VPN metric buffer");return -1;}
    unsigned long urb[13]={0};
#if defined(__linux__)
    vpn_linux_urb_snapshot(v->usb_context,urb);
#endif
    jlong result[36]={
        (jlong)__atomic_load_n(&v->rx_chunks,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->rx_direct_blocks,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->rx_stream_copies,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->rx_copied_bytes,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->rx_frames,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->rx_tun_writes,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->rx_ndp_replies,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->rx_parse_errors,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_polls,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_packets,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_batches,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_batch_full,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_batch_hist[1],__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_batch_hist[4],__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_batch_hist[8],__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_batch_hist[16],__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_tun_reads,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_tun_ready_drains,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_build_ns,__ATOMIC_RELAXED),
        (jlong)urb[0],(jlong)urb[1],(jlong)urb[2],
        (jlong)urb[3],(jlong)urb[4],(jlong)urb[5],
        (jlong)urb[6],(jlong)urb[7],(jlong)urb[8],
        (jlong)urb[9],(jlong)urb[10],(jlong)urb[11],
        (jlong)v->max_batch_frames,(jlong)urb[12],
        (jlong)__atomic_load_n(&v->rx_process_ns,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->rx_bytes,__ATOMIC_RELAXED),
        (jlong)__atomic_load_n(&v->tx_built_bytes,__ATOMIC_RELAXED)
    };
    (*e)->SetLongArrayRegion(e,values,0,36,result);return 0;
}
#if defined(__linux__)
struct native_rx_context {
    struct vpn *v;
    struct vpn_linux_urb_context *usb;
};
struct native_tx_context {
    struct vpn *v;
};
static int native_rx_chunk(void *opaque,const unsigned char *data,size_t length) {
    struct native_rx_context *context=opaque;
    unsigned char reply[16384];jlong metadata[3]={0};
    int count=process_input(context->v,data,length,reply,sizeof(reply),metadata);
    if(count<0)return count;
    if(metadata[0]>0)return vpn_linux_urb_sync_write(
        context->usb,reply,(size_t)metadata[0]);
    return count;
}
static int native_tx_build(void *opaque,unsigned char *buffer,size_t capacity,
                           unsigned max_frames,int *frame_count) {
    struct native_tx_context *context=opaque;
    int result=output_batch(context->v,buffer,capacity,
        100,context->v->max_batch_frames);
    if(result>0)*frame_count=(int)((pn_u16(buffer+16)-12)/4);
    else *frame_count=0;
    return result;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerVpnPacketNative_prepareUsbContext(
    JNIEnv *e,jobject o,jlong h,jint fd,jint in_endpoint,jint out_endpoint) {
    (void)o;struct vpn *v=handle(h);
    if(!v||fd<0||!(in_endpoint&0x80)||(out_endpoint&0x80)){
        fail(e,"Invalid VPN USB context arguments");return -1;
    }
    pthread_mutex_lock(&v->usb_context_lock);
    if(!v->usb_context)v->usb_context=vpn_linux_urb_create(
        fd,in_endpoint,out_endpoint,&v->usb_write_lock,&v->usb_sequence);
    int result=v->usb_context?0:-ENOMEM;
    pthread_mutex_unlock(&v->usb_context_lock);
    return result;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerVpnPacketNative_runUsbReceiver(
    JNIEnv *e,jobject o,jlong h,jint fd,jint in_endpoint,jint out_endpoint,jint slots) {
    (void)o;struct vpn *v=handle(h);
    if(!v||fd<0||!(in_endpoint&0x80)||(out_endpoint&0x80)||slots<2||slots>4){
        fail(e,"Invalid VPN USB receiver arguments");return -1;
    }
    pthread_mutex_lock(&v->usb_context_lock);
    if(!v->usb_context)v->usb_context=vpn_linux_urb_create(
        fd,in_endpoint,out_endpoint,&v->usb_write_lock,&v->usb_sequence);
    struct vpn_linux_urb_context *usb=v->usb_context;
    pthread_mutex_unlock(&v->usb_context_lock);
    if(!usb){fail(e,"Could not create VPN USBFS receiver");return -ENOMEM;}
    struct native_rx_context context={.v=v,.usb=usb};
    int result=vpn_linux_urb_reader(usb,(unsigned)slots,&v->running,native_rx_chunk,&context);
    if(result<0)fail(e,"VPN USBFS receiver failed");return result;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerVpnPacketNative_runUsbSender(
    JNIEnv *e,jobject o,jlong h,jint fd,jint out_endpoint,jint pipeline_depth) {
    (void)o;struct vpn *v=handle(h);
    if(!v||fd<0||(out_endpoint&0x80)||pipeline_depth!=2){
        fail(e,"Invalid VPN USB sender arguments");return -1;
    }
    pthread_mutex_lock(&v->usb_context_lock);
    if(!v->usb_context)v->usb_context=vpn_linux_urb_create(
        fd,0,out_endpoint,&v->usb_write_lock,&v->usb_sequence);
    struct vpn_linux_urb_context *usb=v->usb_context;
    pthread_mutex_unlock(&v->usb_context_lock);
    if(!usb){fail(e,"Could not create VPN USBFS sender");return -ENOMEM;}
    struct native_tx_context context={.v=v};
    int result=vpn_linux_urb_writer(usb,(unsigned)pipeline_depth,
        8,&v->running,native_tx_build,&context);
    if(result<0)fail(e,"VPN USBFS sender failed");return result;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerVpnPacketNative_enableBatching(
    JNIEnv *e,jobject o,jlong h) {
    (void)o;struct vpn *v=handle(h);
    if(!v){fail(e,"VPN session already closed");return -1;}
    v->max_batch_frames=8;
    return 0;
}
JNIEXPORT void JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerVpnPacketNative_stop(
    JNIEnv *e,jobject o,jlong h) {
    (void)e;(void)o;struct vpn *v=handle(h);if(v)__atomic_store_n(&v->running,0,__ATOMIC_RELEASE);
}
#endif
JNIEXPORT void JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerVpnPacketNative_close(JNIEnv *e,jobject o,jlong h) {
    (void)e;(void)o;struct vpn *v=handle(h);if(v){__atomic_store_n(&v->running,0,__ATOMIC_RELEASE);
#if defined(__linux__)
        vpn_linux_urb_destroy(v->usb_context);
#endif
        close(v->fd);pthread_mutex_destroy(&v->usb_context_lock);pthread_mutex_destroy(&v->usb_write_lock);pthread_mutex_destroy(&v->peer_lock);free(v);}
}

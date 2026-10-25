/* Host regression against the actual raw TCP bridge, using real local sockets and lwIP. */
#include <assert.h>
#include "../../main/cpp/pioneer_lwip.c"

static void make_connection(struct connection *c,int *peer){
 int sockets[2];assert(socketpair(AF_UNIX,SOCK_STREAM,0,sockets)==0);
 memset(c,0,sizeof(*c));c->fd=sockets[0];*peer=sockets[1];socket_nonblock(c->fd);socket_nonblock(*peer);
 c->rx=malloc(RX_CAP);assert(c->rx);
 c->pcb=tcp_new_ip_type(IPADDR_TYPE_V6);assert(c->pcb);
 c->pcb->state=ESTABLISHED;c->pcb->local_port=9911;c->pcb->remote_port=50000;
 TCP_REG_ACTIVE(c->pcb);c->progress_ns=mono_ns();
}
static void remove_connection(struct connection *c,int peer){
 if(c->pcb){tcp_arg(c->pcb,NULL);tcp_err(c->pcb,NULL);tcp_abort(c->pcb);}
 forget_connection(c);close(peer);
 while(tcp_active_pcbs){tcp_arg(tcp_active_pcbs,NULL);tcp_err(tcp_active_pcbs,NULL);tcp_abort(tcp_active_pcbs);}
}
static void receive_payload(struct connection *c,const unsigned char *bytes,unsigned size){
 struct pbuf *p=pbuf_alloc(PBUF_RAW,(u16_t)size,PBUF_POOL);assert(p);
 assert(pbuf_take(p,bytes,(u16_t)size)==ERR_OK);
 assert(on_tcp_recv(c,c->pcb,p,ERR_OK)==ERR_OK);
}
int main(void){
 lwip_init();diagnostics_enabled=0;
 struct connection c;int peer;
 make_connection(&c,&peer);
 unsigned char payload[6000],received[6000];for(unsigned i=0;i<sizeof(payload);i++)payload[i]=(unsigned char)(i*31);
 c.rx_head=RX_CAP-100;
 receive_payload(&c,payload,sizeof(payload));assert(c.rx_size==sizeof(payload));
 pump_tcp(&c,POLLOUT);assert(c.rx_head==0&&c.rx_size==sizeof(payload)-100);
 pump_tcp(&c,POLLOUT);assert(c.rx_size==0);
 assert(recv(peer,received,sizeof(received),0)==sizeof(received));assert(!memcmp(payload,received,sizeof(payload)));
 // No readiness: do not consume an available local stream byte.
 assert(send(peer,payload,20,0)==20);pump_tcp(&c,0);assert(c.tx_total==0);
 remove_connection(&c,peer);

 // A write-half-closed local requester must still receive the remote response.
 make_connection(&c,&peer);assert(shutdown(peer,SHUT_WR)==0);
 pump_tcp(&c,POLLIN);assert(c.local_eof&&c.local_fin&&c.pcb&&c.peer_eof==0);
 receive_payload(&c,payload,100);pump_tcp(&c,POLLOUT);
 assert(recv(peer,received,100,0)==100);assert(!memcmp(payload,received,100));
 assert(on_tcp_recv(&c,c.pcb,NULL,ERR_OK)==ERR_OK);pump_tcp(&c,0);assert(!c.pcb&&c.fd==-1);
 remove_connection(&c,peer);

 // Passive close must not send FIN while response bytes still await local delivery.
 make_connection(&c,&peer);c.pcb->state=CLOSE_WAIT;receive_payload(&c,payload,100);
 assert(on_tcp_recv(&c,c.pcb,NULL,ERR_OK)==ERR_OK);assert(shutdown(peer,SHUT_WR)==0);
 pump_tcp(&c,POLLIN);assert(c.local_eof&&!c.local_fin&&c.rx_size==100&&c.pcb->state==CLOSE_WAIT);
 pump_tcp(&c,POLLOUT);assert(!c.pcb&&c.fd==-1);
 assert(recv(peer,received,100,0)==100);assert(!memcmp(payload,received,100));
 remove_connection(&c,peer);

 // A peer FIN only shuts down the local write half once; its read half remains usable.
 make_connection(&c,&peer);assert(on_tcp_recv(&c,c.pcb,NULL,ERR_OK)==ERR_OK);
 pump_tcp(&c,0);assert(c.peer_eof==2&&c.pcb);pump_tcp(&c,0);assert(c.peer_eof==2&&c.pcb);
 assert(recv(peer,received,1,0)==0);assert(send(peer,payload,20,0)==20);
 pump_tcp(&c,POLLIN);assert(c.tx_total==20&&c.pcb);
 remove_connection(&c,peer);

 // Recycled frame storage is bounded and reused without changing wire contents.
 optimized_io=1;struct frame *f=allocate_frame(100);assert(f&&f->capacity==1600);
 release_frame(f);assert(free_count==1);struct frame *g=allocate_frame(1500);assert(g==f&&free_count==0);release_frame(g);
 while(free_frames){f=free_frames;free_frames=f->next;free(f);}free_count=0;
 puts("native lwIP stream: ring wrap, readiness, bidirectional half-close and bounded frame reuse passed");return 0;
}

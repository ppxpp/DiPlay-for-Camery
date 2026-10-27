/* Standalone ARM32 client: default linker namespace, same app UID, no injection.
 * Reuse the verified callback ABI/queue from the JNI adapter. */
#include "pioneer_blueforce.c"
#include <sys/socket.h>
#include <sys/un.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <stdlib.h>
#include <signal.h>
#include <errno.h>
static int rxfd = -1;
static int all(int fd, void *p, size_t n, int output) {
    char *b=p;
    while(n) { ssize_t r=output?send(fd,b,n,MSG_NOSIGNAL):read(fd,b,n);
        if(r<0 && errno==EINTR)continue;
        if(r<=0)return -1;
        b+=r;n-=(size_t)r;
    } return 0;
}
static int integer(int fd,int value) { uint32_t n=htonl((uint32_t)value);return all(fd,&n,4,1); }
static int number(int fd,int *value) { uint32_t n;if(all(fd,&n,4,0))return -1;*value=(int)ntohl(n);return 0; }
static int text_reply(int fd,const char *s) {size_t n=strlen(s);return integer(fd,(int)n)||all(fd,(void*)s,n,1);}
static const char *initialize(char *message,size_t capacity) {
    if(sizeof(void*)!=4)return "ARM32 required";
    library=dlopen("/system/lib/libblueforce.so",RTLD_NOW|RTLD_LOCAL);
    if(!library)return dlerror();
    void *binder=dlopen("/system/lib/libbinder.so",RTLD_NOW|RTLD_LOCAL);
    void (*self)(void**)=binder?dlsym(binder,"_ZN7android12ProcessState4selfEv"):NULL;
    void (*pool)(void*)=binder?dlsym(binder,"_ZN7android12ProcessState15startThreadPoolEv"):NULL;
    if(!self||!pool)return "Binder threadpool symbols missing";
    void *state=NULL;self(&state);if(!state)return "ProcessState missing";pool(state);
    void *(*factory)(void)=dlsym(library,"_ZN7android17CBlueforceFactory11getInstanceEv");
    int (*create)(void*,void**)=dlsym(library,"_ZN7android17CBlueforceFactory12createManageERPNS_16IBlueforceManageE");
    if(!factory||!create)return "factory symbols missing";
    int result=create(factory(),&manage);
    if(!result&&manage) {vtable=*(void***)manage;result=((int(*)(void*,int))vtable[2])(manage,-1);}
    if(result||!manage) {snprintf(message,capacity,"host init=%d",result);return message;}
    active=1;result=((int(*)(void*,void*))vtable[4])(manage,&callback);
    if(result) {active=0;snprintf(message,capacity,"host register=%d",result);return message;}
    registered=1;return "OK";
}
static int peer(int server,uid_t uid,char role) {
    int fd=accept(server,NULL,NULL);if(fd<0)return -1;
    struct ucred cred; socklen_t length=sizeof(cred);char received=0;
    struct timeval timeout={10,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&cred,&length)||cred.uid!=uid||all(fd,&received,1,0)||received!=role) {
        close(fd);return -1;
    }
    return fd;
}
static void *pump(void *unused) {
    (void)unused;unsigned char bytes[4096];
    while(1) {
        pthread_mutex_lock(&lock);
        while(active&&!used&&!overflow)pthread_cond_wait(&changed,&lock);
        if(!active||overflow) {pthread_mutex_unlock(&lock);break;}
        size_t n=used<sizeof(bytes)?used:sizeof(bytes);
        for(size_t i=0;i<n;i++)bytes[i]=queue[(head+i)%CAPACITY];
        head=(head+n)%CAPACITY;used-=n;pthread_mutex_unlock(&lock);
        if(all(rxfd,bytes,n,1))break;
    }
    shutdown(rxfd,SHUT_RDWR);return NULL;
}
int main(int argc,char **argv) {
    signal(SIGPIPE,SIG_IGN);alarm(180);
    char message[512];
    if(argc==2&&!strcmp(argv[1],"--selftest")) {
        const char *status=initialize(message,sizeof(message));printf("%s\n",status);
        if(registered)((int(*)(void*,void*))vtable[5])(manage,&callback);
        return strcmp(status,"OK")?1:0;
    }
    if(argc!=3||strlen(argv[1])>90)return 2;
    uid_t expected=(uid_t)strtoul(argv[2],NULL,10);
    if(getuid()!=expected)return 3;
    int server=socket(AF_UNIX,SOCK_STREAM,0);struct sockaddr_un address;
    memset(&address,0,sizeof(address));address.sun_family=AF_UNIX;memcpy(address.sun_path+1,argv[1],strlen(argv[1]));
    if(server<0||bind(server,(struct sockaddr*)&address,(socklen_t)(offsetof(struct sockaddr_un,sun_path)+1+strlen(argv[1])))||listen(server,2))return 4;
    int control=peer(server,expected,'C');if(control<0)return 5;
    const char *status=initialize(message,sizeof(message));text_reply(control,status);
    if(strcmp(status,"OK"))return 6;
    rxfd=peer(server,expected,'R');if(rxfd<0)return 7;
    close(server);
    struct timeval timeout={150,0};setsockopt(control,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    pthread_t sender;pthread_create(&sender,NULL,pump,NULL);
    char op;int value;
    while(!all(control,&op,1,0)) {
        if(op=='W') {
            unsigned char data[4096];if(number(control,&value)||value<1||value>4096||all(control,data,(size_t)value,0))break;
            pthread_mutex_lock(&lock);unsigned long before=tx_events;pthread_mutex_unlock(&lock);
            int result=((int(*)(void*,int,const unsigned char*,int))vtable[0x21c/4])(manage,0,data,value);
            if(!result) {
                struct timespec until;clock_gettime(CLOCK_REALTIME,&until);until.tv_sec+=3;
                pthread_mutex_lock(&lock);
                while(active&&tx_events==before) {if(pthread_cond_timedwait(&changed,&lock,&until))break;}
                result=tx_events==before?-10001:tx_result;
                pthread_mutex_unlock(&lock);
            }
            if(integer(control,result)||result)break;
        } else if(op=='A') {
            void *utils=dlopen("/system/lib/libutils.so",RTLD_NOW|RTLD_LOCAL);
            void (*ctor)(void*)=utils?dlsym(utils,"_ZN7android7String8C1Ev"):NULL;
            void (*dtor)(void*)=utils?dlsym(utils,"_ZN7android7String8D1Ev"):NULL;
            struct {const char *text;} str;
            if(!ctor||!dtor) {text_reply(control,"");continue;}
            ctor(&str);int result=((int(*)(void*,int,void*))vtable[0x230/4])(manage,0,&str);
            text_reply(control,(!result&&str.text)?str.text:"");dtor(&str);dlclose(utils);
        } else if(op=='S') {
            pthread_mutex_lock(&lock);
            snprintf(message,sizeof(message),"host uid=%d events=%d last=%d rx=%lu txCompleted=%lu txResult=%d queued=%zu overflow=%d",getuid(),event_count,last_event,rx_bytes,tx_events,tx_result,used,overflow);
            pthread_mutex_unlock(&lock);if(text_reply(control,message))break;
        } else if(op=='X') {integer(control,0);break;} else break;
    }
    pthread_mutex_lock(&lock);active=0;pthread_cond_broadcast(&changed);pthread_mutex_unlock(&lock);
    shutdown(rxfd,SHUT_RDWR);pthread_join(sender,NULL);
    if(registered)((int(*)(void*,void*))vtable[5])(manage,&callback);
    close(rxfd);close(control);return 0;
}

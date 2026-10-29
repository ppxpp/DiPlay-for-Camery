/* Same-UID Opus worker: use the verified ROM codec outside the app linker namespace. */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <arpa/inet.h>
static int transfer(int fd,void *buffer,size_t size,int output){char*p=buffer;while(size){ssize_t n=output?write(fd,p,size):read(fd,p,size);if(n<0&&errno==EINTR)continue;if(n<=0)return -1;p+=n;size-=n;}return 0;}
static int number(int n){uint32_t value=htonl((uint32_t)n);return transfer(1,&value,4,1);}
int main(int argc,char**argv){
 signal(SIGPIPE,SIG_IGN);
 if(argc!=2||sizeof(void*)!=4)return 2;
 int bitrate=atoi(argv[1]);if(bitrate<6000||bitrate>128000)return 3;
 void*lib=dlopen("/system/lib/libopus.so",RTLD_NOW|RTLD_LOCAL);if(!lib){number(-1);return 4;}
 void*(*create)(int,int,int,int*)=dlsym(lib,"opus_encoder_create");
 int(*encode)(void*,const int16_t*,int,unsigned char*,int)=dlsym(lib,"opus_encode");
 int(*ctl)(void*,int,...)=dlsym(lib,"opus_encoder_ctl");
 void(*destroy)(void*)=dlsym(lib,"opus_encoder_destroy");
 if(!create||!encode||!ctl||!destroy){number(-2);return 5;}
 int error=0;void*codec=create(48000,1,2048,&error);
 if(!codec||error){number(-3);return 6;}
 if(ctl(codec,4002,bitrate)||ctl(codec,4010,5)||ctl(codec,4024,3001)){destroy(codec);number(-4);return 7;}
 if(number(0)){destroy(codec);return 8;}
 int16_t pcm[960];unsigned char output[1275];
 while(!transfer(0,pcm,sizeof(pcm),0)){
   int n=encode(codec,pcm,960,output,sizeof(output));
   if(number(n)||n<0||transfer(1,output,(size_t)n,1))break;
 }
 destroy(codec);dlclose(lib);return 0;
}

/* ARM32 structural diagnostic. Invoked by DiPlay's existing root dispatcher.
 * Audit uses /proc reads only. Repair calls the daemon's ORIGINAL registration API
 * with its ORIGINAL callback object. No injected library, ROM patch or new BT stack.
 * A remote call is allowed only on the idle main Binder thread. Once its context
 * has changed, an incomplete/faulting call kills that service rather than restoring
 * a PC into possibly held vendor locks. init owns service restart (not this tool).
 */
#define _GNU_SOURCE
#include <asm/ptrace.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <dirent.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <errno.h>
#include <stdarg.h>
#include <limits.h>
#ifndef __WALL
#define __WALL 0x40000000
#endif
static int memfd=-1, resultfd=-1, attached=0, changed=0;
static pid_t target=-1;
static unsigned long long target_start;
static uint32_t daemon_base, bluep_base;
static char cancellation[PATH_MAX];
static char details[16384];
static size_t used;
static uint64_t millis(void) {struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000;}
static void add(const char *fmt,...) {if(used>=sizeof(details)-1)return;va_list a;va_start(a,fmt);int n=vsnprintf(details+used,sizeof(details)-used,fmt,a);va_end(a);if(n>0)used+=(size_t)n<sizeof(details)-used?(size_t)n:sizeof(details)-used-1;}
static unsigned long long start_time(void);
static int finish(const char *status,int code) {
    if(attached) {
        if(changed) {if(start_time()==target_start){kill(target,SIGKILL);add("service_killed_after_incomplete_remote_call=1\n");}}
        else ptrace(PTRACE_DETACH,target,NULL,NULL);
    }
    if(resultfd>=0) {dprintf(resultfd,"status=%s\n%scomplete=1\n",status,details);fsync(resultfd);close(resultfd);}
    if(memfd>=0)close(memfd);
    return code;
}
static int read_at(uint32_t a,void *out,size_t n) {return a>=4096 && pread64(memfd,out,n,a)==(ssize_t)n;}
static int word(uint32_t a,uint32_t *v) {return read_at(a,v,4);}
static uint32_t must_word(uint32_t a) {uint32_t v=0;if(!word(a,&v)){add("unreadable_address=%08x\n",a);}return v;}
static int anchor(uint32_t a,const unsigned char *expected,size_t n) {unsigned char b[32];return n<=sizeof(b)&&read_at(a,b,n)&&memcmp(b,expected,n)==0;}
static int find_process(void) {
    DIR *d=opendir("/proc");if(!d)return 0;struct dirent *e;int count=0;
    while((e=readdir(d))) {char *end;long id=strtol(e->d_name,&end,10);if(*end||id<=1||id>INT_MAX)continue;
        char p[128],exe[PATH_MAX];snprintf(p,sizeof(p),"/proc/%ld/exe",id);ssize_t n=readlink(p,exe,sizeof(exe)-1);
        if(n<0)continue;exe[n]=0;if(!strcmp(exe,"/system/bin/blueforcemanage")){target=(pid_t)id;count++;}
    }closedir(d);return count==1;
}
static unsigned long long start_time(void) {
    char path[128],line[2048];snprintf(path,sizeof(path),"/proc/%d/stat",target);FILE *f=fopen(path,"r");
    if(!f)return 0;if(!fgets(line,sizeof(line),f)){fclose(f);return 0;}fclose(f);
    char *p=strrchr(line,')');if(!p)return 0;p+=2;char *save=NULL,*part=strtok_r(p," ",&save);
    for(int i=0;i<19&&part;i++)part=strtok_r(NULL," ",&save);
    return part?strtoull(part,NULL,10):0;
}
static int maps(void) {
    char p[128],line[1024];snprintf(p,sizeof(p),"/proc/%d/maps",target);FILE *f=fopen(p,"r");if(!f)return 0;
    while(fgets(line,sizeof(line),f)) {unsigned long lo,hi,off;char perms[5],path[512]="";
        int n=sscanf(line,"%lx-%lx %4s %lx %*s %*s %511s",&lo,&hi,perms,&off,path);
        if(n!=5||off!=0||perms[0]!='r')continue;
        if(!strcmp(path,"/system/bin/blueforcemanage"))daemon_base=(uint32_t)lo;
        if(!strcmp(path,"/system/lib/libbluep.so"))bluep_base=(uint32_t)lo-0x2000; /* libbluep first PT_LOAD p_vaddr=0x2000 */
    }fclose(f);return daemon_base&&bluep_base;
}
static int string_at(uint32_t a,char out[129]) {
    unsigned char b[12];if(!read_at(a,b,sizeof(b)))return 0;uint32_t len,p;
    if(b[0]&1) {memcpy(&len,b+4,4);memcpy(&p,b+8,4);}else {len=b[0]>>1;p=a+1;}
    if(len>128|| (len&&!read_at(p,out,len)))return 0;out[len]=0;return 1;
}
struct port {uint32_t object;int cache,status;unsigned char connected,id;char address[129],uuid[129];};
struct snapshot {uint32_t power,callback,general,core,list;int registered,count;struct port ports[9];};
static int snapshot(struct snapshot *s,int print) {
    memset(s,0,sizeof(*s));s->power=must_word(daemon_base+0x27cdf4);if(!s->power)return 0;
    s->callback=s->power+0x58;
    if(print)add("power=%08x\nserial_callback=%08x\nserial_vtable=%08x\n",s->power,s->callback,must_word(s->callback));
    if(must_word(s->callback)!=daemon_base+0x228174)return 0;
    uint32_t module=must_word(bluep_base+0xbf9fc);if(!module)return 0;
    s->general=must_word(module+8);uint32_t iface=must_word(s->general+0x14);s->core=must_word(iface+4);
    if(!s->general||!iface||!s->core)return 0;
    if(print)add("general=%08x\niface=%08x\ncore=%08x\n",s->general,iface,s->core);
    uint32_t vt=must_word(s->general);if(must_word(vt+0x2c)!=bluep_base+0x66f95)return 0;
    if(print)add("general_vtable=%08x\nregister_function=%08x\n",vt,must_word(vt+0x2c));
    s->list=must_word(s->core+0x34);if(!s->list)return 0;
    uint32_t node=must_word(s->list+8),seen[64],callbacks[64];
    while(node) {if(s->count>=64)return 0;for(int i=0;i<s->count;i++)if(seen[i]==node)return 0;
        seen[s->count]=node;uint32_t cb;if(!word(node,&cb)||!word(node+4,&node))return 0;
        if(print)add("callback%d=%08x,member=%x\n",s->count,cb,cb-s->power);
        callbacks[s->count++]=cb;if(cb==s->callback)s->registered=1;
    }
    const unsigned offsets[]={0x24,0x2c,0x34,0x3c,0x44,0x50,0x60};
    for(size_t k=0;k<sizeof(offsets)/sizeof(offsets[0]);k++) {int found=0;for(int i=0;i<s->count;i++)if(callbacks[i]==s->power+offsets[k])found=1;if(!found){add("other_callback_missing=%x\n",offsets[k]);return 0;}}
    uint32_t spp=must_word(daemon_base+0x22b160);if(!spp||must_word(spp)!=daemon_base+0x225ba0)return 0;
    if(print)add("pid=%d\ndaemon_base=%08x\nbluep_base=%08x\ncallback_count=%d\nserial_callback_registered=%d\n",target,daemon_base,bluep_base,s->count,s->registered);
    for(int i=0;i<9;i++) {struct port *p=&s->ports[i];p->object=must_word(spp+4+i*8);p->cache=(int)must_word(spp+8+i*8);
        if(p->object) {uint32_t dev;if(!read_at(p->object+0x1c,&p->status,4)||!read_at(p->object+0x20,&p->connected,1)||!read_at(p->object+0x34,&p->id,1)||!word(p->object+0x18,&dev)||!dev||!string_at(dev+0x5c,p->address)||!string_at(p->object+0x24,p->uuid))return 0;}
        if(print)add("port%d=%d,%d,%u,%s,%s,%u\n",i,p->cache,p->status,p->connected,p->address,p->uuid,p->id);
    }return 1;
}
static int wait_stop(uint64_t deadline,int *status) {
    while(millis()<deadline) {pid_t n=waitpid(target,status,__WALL|WNOHANG);if(n==target)return 1;if(n<0&&errno!=EINTR)return 0;usleep(10000);}return 0;
}
static int idle_binder_thread(struct pt_regs *r) {
    /* ARM EABI ioctl(BINDER_WRITE_READ), not a vendor method/futex/allocator. */
    uint32_t command=(uint32_t)r->ARM_r1;
    return (uint32_t)r->ARM_r7==54 && (command==0xc0186201 || command==0xc0306201);
}
static int remote_call(uint32_t function,uint32_t arg0,uint32_t arg1,uint32_t arg2,int *retval) {
    if(!target_start||start_time()!=target_start){add("daemon_identity_changed=1\n");return 0;}
    if(access(cancellation,F_OK)==0){add("cancelled_before_remote_call=1\n");return 0;}
    char path[128],wchan[128]="";snprintf(path,sizeof(path),"/proc/%d/wchan",target);
    uint64_t idle_until=millis()+1000;
    do {
        FILE *f=fopen(path,"r");wchan[0]=0;
        if(f){if(!fgets(wchan,sizeof(wchan),f))wchan[0]=0;fclose(f);}
        if(strstr(wchan,"binder"))break;
        if(access(cancellation,F_OK)==0)return 0;
        usleep(10000);
    } while(millis()<idle_until);
    if(!strstr(wchan,"binder")){add("main_thread_not_idle_binder=1\n");return 0;}
    if(ptrace(PTRACE_ATTACH,target,NULL,NULL)){add("attach_errno=%d\n",errno);return 0;}attached=1;
    int st;if(!wait_stop(millis()+2000,&st)||!WIFSTOPPED(st))return 0;
    if(start_time()!=target_start){add("daemon_identity_changed_after_attach=1\n");return 0;}
    struct pt_regs saved,call,returned;unsigned char vfp[ARM_VFPREGS_SIZE+16] __attribute__((aligned(8)));
    if(ptrace(PTRACE_GETREGS,target,NULL,&saved)||!idle_binder_thread(&saved)||ptrace(PTRACE_GETVFPREGS,target,NULL,vfp)){add("stopped_thread_not_idle_or_regs_unavailable=1\n");return 0;}
    /* Scratch stack is below all live ARM frames, with a saved 32KiB window. */
    uint32_t top=((uint32_t)saved.ARM_sp-512)&~7u,low=top-32768;unsigned char *stack=malloc(32768);
    if(!stack||!read_at(low,stack,32768)){free(stack);return 0;}
    call=saved;call.ARM_r0=arg0;call.ARM_r1=arg1;call.ARM_r2=arg2;call.ARM_lr=0;call.ARM_sp=top;
    call.ARM_pc=function&~1u;call.ARM_cpsr=(saved.ARM_cpsr&~(PSR_T_BIT|PSR_IT_MASK))|((function&1)?PSR_T_BIT:0);call.ARM_ORIG_r0=-1;
    if(ptrace(PTRACE_SETREGS,target,NULL,&call)){free(stack);return 0;}changed=1;
    if(ptrace(PTRACE_CONT,target,NULL,NULL)){free(stack);return 0;}
    uint64_t until=millis()+3000;int complete=0;
    while(wait_stop(until,&st)&&WIFSTOPPED(st)) {
        int sig=WSTOPSIG(st);if(ptrace(PTRACE_GETREGS,target,NULL,&returned))break;
        if(sig==SIGSEGV&&(uint32_t)returned.ARM_pc==0){complete=1;break;}
        /* Do not resume a crashing vendor call or fabricate completion. */
        if(sig==SIGSEGV||sig==SIGBUS||sig==SIGILL||sig==SIGABRT)break;
        if(ptrace(PTRACE_CONT,target,NULL,(void*)(intptr_t)sig))break;
    }
    if(!complete){add("remote_call_incomplete_service_restart_required=1\n");free(stack);return 0;}
    if(pwrite64(memfd,stack,32768,low)!=32768||ptrace(PTRACE_SETVFPREGS,target,NULL,vfp)||ptrace(PTRACE_SETREGS,target,NULL,&saved)){free(stack);return 0;}
    free(stack);changed=0;
    if(ptrace(PTRACE_DETACH,target,NULL,NULL))return 0;attached=0;*retval=(int)returned.ARM_r0;return 1;
}
int main(int argc,char **argv) {
    umask(077);
    if(argc!=4||sizeof(void*)!=4||geteuid()!=0)return 2;
    int repair=!strcmp(argv[1],"--repair"),clear=!strcmp(argv[1],"--clear-idle");
    if(!repair&&!clear&&strcmp(argv[1],"--audit"))return 2;
    char *end;unsigned long uid=strtoul(argv[2],&end,10);if(*end||uid<10000||uid>19999)return 2;
    resultfd=open(argv[3],O_WRONLY|O_NOFOLLOW|O_CLOEXEC);struct stat out;
    if(resultfd<0||fstat(resultfd,&out)||!S_ISREG(out.st_mode)||out.st_uid!=uid||out.st_nlink!=1||(out.st_mode&077)!=0)return 2;
    if(snprintf(cancellation,sizeof(cancellation),"%s.cancel",argv[3])>=(int)sizeof(cancellation))return finish("OUTPUT_ERROR",3);
    if(ftruncate(resultfd,0))return finish("OUTPUT_ERROR",3);
    if(access(cancellation,F_OK)==0)return finish("CANCELLED",0);
    if(!find_process()||!maps())return finish("DAEMON_NOT_FOUND",4);
    add("mapped_daemon_bias=%08x\nmapped_bluep_bias=%08x\n",daemon_base,bluep_base);
    target_start=start_time();if(!target_start)return finish("DAEMON_IDENTITY_UNAVAILABLE",4);
    char p[128];snprintf(p,sizeof(p),"/proc/%d/mem",target);memfd=open(p,(repair||clear?O_RDWR:O_RDONLY)|O_CLOEXEC);
    if(memfd<0){add("mem_errno=%d\n",errno);return finish("PROC_ACCESS_DENIED",5);}
    const unsigned char init[]={0x28,0x68,0x04,0xf1,0x50,0x01,0xc2,0x6a,0x28,0x46,0x90,0x47,0x28,0x68,0x04,0xf1};
    const unsigned char reg[]={0x70,0xb5,0x84,0xb0,0x05,0x46,0x0e,0x48,0x01,0xae,0x0c,0x46,0x78,0x44,0x29,0x1d};
    if(!anchor(daemon_base+0x1dfd86,init,sizeof(init))||!anchor(bluep_base+0x66f94,reg,sizeof(reg)))return finish("RUNTIME_SIGNATURE_MISMATCH",6);
    struct snapshot before,after;if(!snapshot(&before,1))return finish("LAYOUT_OR_CALLBACK_LIST_MISMATCH",7);
    if(!repair&&!clear)return finish("AUDIT_OK",0);
    usleep(100000);
    if(!snapshot(&after,0)||before.power!=after.power||before.general!=after.general||before.registered!=after.registered||memcmp(before.ports,after.ports,sizeof(before.ports)))return finish("STATE_CHANGED_RETRY_AUDIT",8);
    if(repair) {
        if(before.registered)return finish("ALREADY_REGISTERED",0);
        int ignored;if(!remote_call(bluep_base+0x66f95,before.general,before.callback,0,&ignored))return finish(changed?"SERVICE_RESTART_REQUIRED":"REPAIR_NOT_EXECUTED",9);
        if(!snapshot(&after,0)||!after.registered)return finish("REGISTRATION_NOT_CONFIRMED",10);
        add("serial_callback_registered_after=1\n");return finish("REGISTERED",0);
    }
    /* Only discard stale 2/3 caches when EVERY actual serial object is stably idle.
     * This is not a connected-state override and cannot count as iAP2 success. */
    if(!before.registered)return finish("REGISTER_CALLBACK_FIRST",11);
    for(int i=0;i<9;i++)if(before.ports[i].connected||before.ports[i].status)return finish("LOWER_PORT_BUSY_NO_CACHE_CLEAR",12);
    uint32_t spp=must_word(daemon_base+0x22b160);
    for(int i=0;i<9;i++)if(before.ports[i].cache==2||before.ports[i].cache==3) {
        if(!before.ports[i].object)return finish("INVALID_STALE_PORT",13);
        if(!snapshot(&after,0)||memcmp(before.ports,after.ports,sizeof(before.ports)))return finish("STATE_CHANGED_NO_CACHE_CLEAR",13);
        int ignored;if(!remote_call(daemon_base+0x18f249,spp,before.ports[i].object,0,&ignored))return finish(changed?"SERVICE_RESTART_REQUIRED":"CACHE_CLEAR_NOT_EXECUTED",14);
        if(!snapshot(&after,0)||after.ports[i].cache!=0||after.ports[i].connected||after.ports[i].status)return finish("CACHE_CLEAR_NOT_CONFIRMED",15);
        add("cleared_idle_stale_port=%d\n",i);before=after;
    }
    return finish("BASELINE_IDLE",0);
}

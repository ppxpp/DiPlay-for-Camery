/* One-shot ARM32 syscall barrier, not a code/ROM patch. The original Wi-Fi
 * thread is paused AFTER frequency 2 was sent and BEFORE SELECT_NETWORK.
 * A separate supplicant client sets frequency 5, then the thread is detached.
 * No tracee registers, instructions or memory are modified. */
#define _GNU_SOURCE
#include <asm/ptrace.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "pioneer_ap_gate_protocol.h"
#ifndef __WALL
#define __WALL 0x40000000
#endif
#ifndef PTRACE_SEIZE
#define PTRACE_SEIZE 0x4206
#define PTRACE_INTERRUPT 0x4207
#endif

static volatile sig_atomic_t interrupted;
static pid_t tid = -1;
static int pending_signal;
static int attached, stopped, result_fd = -1, memory_fd = -1;
static uint64_t start_ms;
static unsigned syscall_stops, send_stops, sendto_stops, write_stops, sendmsg_stops, frequency_stops, select_stops, last_nr;
static char frequency_command[128], control_reply[64];
static int control_step, control_error, control_path;
static const char *cancel_path;
static sigset_t stop_signals;
static unsigned wait_signals, wait_timeouts;
static uint64_t wait_millis;

static uint64_t millis(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000 + (uint64_t)t.tv_nsec / 1000000;
}
static void on_signal(int sig) { (void)sig; interrupted = 1; }
static int cancelled(void) {
    return interrupted || (cancel_path && access(cancel_path, F_OK) == 0);
}
static void report(const char *state) {
    if (result_fd < 0) return;
    char text[768];
    int count = snprintf(text, sizeof(text), "state=%s\ntid=%d\nelapsedMs=%llu\nsyscallStops=%u send=%u sendto=%u write=%u sendmsg=%u frequency=%u select=%u lastNr=%u\nfrequencyCommand=%s\ncontrolPath=%d controlStep=%d controlErrno=%d controlReply=%s\nwaitSignals=%u waitTimeouts=%u waitMillis=%llu\n",
                         state, tid, (unsigned long long)(millis() - start_ms),
                         syscall_stops,send_stops,sendto_stops,write_stops,sendmsg_stops,frequency_stops,select_stops,last_nr,frequency_command,control_path,control_step,control_error,control_reply,wait_signals,wait_timeouts,(unsigned long long)wait_millis);
    if (count >= (int)sizeof(text)) count=(int)sizeof(text)-1;
    if (count > 0) {
        if (ftruncate(result_fd, 0) == 0) {
            pwrite(result_fd, text, (size_t)count, 0);
            fsync(result_fd);
        }
    }
}
static int wait_stop(uint64_t deadline, int *status, int honour_cancel) {
    while (millis() < deadline) {
        pid_t result = waitpid(tid, status, __WALL | WNOHANG);
        if (result == tid) {
            stopped = WIFSTOPPED(*status);
            if (WIFEXITED(*status) || WIFSIGNALED(*status)) attached = 0;
            return stopped;
        }
        if (result < 0 && errno != EINTR) return 0;
        if (honour_cancel && cancelled()) return 0;
        // Wake on ptrace SIGCHLD; retain a bounded poll for cancellation/deadlines.
        struct timespec pause = {0, 10000000};
        uint64_t wait_start=millis();
        int wake=sigtimedwait(&stop_signals, NULL, &pause);
        wait_millis+=millis()-wait_start;
        if(wake==SIGCHLD) wait_signals++;
        else if(wake<0 && errno==EAGAIN) wait_timeouts++;
    }
    return 0;
}
static int detach(void) {
    if (!attached) return 1;
    if (!stopped) {
        if (ptrace(PTRACE_INTERRUPT, tid, 0, 0) < 0 && errno == ESRCH) {
            attached = 0;
            return 1;
        }
        int status;
        if (!wait_stop(millis() + 2000, &status, 0)) return 0;
    }
    if (!attached || ptrace(PTRACE_DETACH, tid, 0, (void *)(uintptr_t)pending_signal) == 0 || errno == ESRCH) {
        attached = 0;
        stopped = 0;
        return 1;
    }
    return 0;
}
static int finish(const char *state, int result) {
    /* No EXITKILL option: even tracer death must not kill system_server. */
    if (!detach()) { state = "DETACH_UNCONFIRMED"; result = 1; }
    report(state);
    if (memory_fd >= 0) close(memory_fd);
    if (result_fd >= 0) close(result_fd);
    return result;
}
static int read_file(const char *path, char *out, size_t capacity) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, out, capacity - 1);
    close(fd);
    if (n <= 0) return 0;
    out[n] = 0;
    return 1;
}
static pid_t wifi_thread(void) {
    DIR *processes = opendir("/proc");
    if (!processes) return -1;
    struct dirent *entry;
    pid_t found = -1;
    int count = 0;
    while ((entry = readdir(processes))) {
        char *end;
        long id = strtol(entry->d_name, &end, 10);
        if (*end || id <= 1 || id > INT_MAX) continue;
        char path[PATH_MAX], text[128];
        snprintf(path, sizeof(path), "/proc/%ld", id);
        struct stat info;
        if (stat(path, &info) || info.st_uid != 1000) continue;
        snprintf(path, sizeof(path), "/proc/%ld/cmdline", id);
        if (!read_file(path, text, sizeof(text)) || strcmp(text, "system_server")) continue;
        snprintf(path, sizeof(path), "/proc/%ld/task", id);
        DIR *threads = opendir(path);
        if (!threads) continue;
        struct dirent *thread;
        while ((thread = readdir(threads))) {
            long thread_id = strtol(thread->d_name, &end, 10);
            if (*end || thread_id <= 1 || thread_id > INT_MAX) continue;
            snprintf(path, sizeof(path), "/proc/%ld/task/%ld/comm", id, thread_id);
            if (!read_file(path, text, sizeof(text))) continue;
            text[strcspn(text, "\r\n")] = 0;
            if (!strcmp(text, "WifiStateMachin") || !strcmp(text, "WifiStateMachine")) {
                found = (pid_t)thread_id;
                count++;
            }
        }
        closedir(threads);
    }
    closedir(processes);
    return count == 1 ? found : -1;
}
static void observe_syscall(const struct pt_regs *regs) {
    syscall_stops++; last_nr=(unsigned)regs->uregs[7];
    if(last_nr==289) send_stops++;
    if(last_nr==290) sendto_stops++;
    if(last_nr==4) write_stops++;
    if(last_nr==296) sendmsg_stops++;
    if(last_nr!=289 && last_nr!=290 && last_nr!=4) return;
    size_t length=regs->uregs[2];
    if(length<1 || length>=sizeof(frequency_command)) return;
    char command[128];
    if(pread64(memory_fd,command,length,(off64_t)pioneer_ap_gate_address(regs->uregs[1]))!=(ssize_t)length) return;
    command[length]=0;
    // Diagnostics only log this fixed non-secret command family, never SSID/PSK.
    if(!strncmp(command,"IFNAME=bcm1 SET_NETWORK 0 frequency ",strlen("IFNAME=bcm1 SET_NETWORK 0 frequency ")) || !strncmp(command,"SET_NETWORK 0 frequency ",strlen("SET_NETWORK 0 frequency "))) {
        frequency_stops++; memcpy(frequency_command,command,length+1);
    }
    if(strstr(command,"SELECT_NETWORK 0")) select_stops++;
}
static int is_frequency_send(const struct pt_regs *regs) {
    /* ARM EABI sendto; the verified wpa_ctrl implementation uses send(). */
    if ((regs->uregs[7] != 289 && regs->uregs[7] != 290) || regs->uregs[2] != sizeof(PIONEER_AP_FREQUENCY_2) - 1) return 0;
    char command[sizeof(PIONEER_AP_FREQUENCY_2)];
    ssize_t n = pread64(memory_fd, command, sizeof(command) - 1, (off64_t)pioneer_ap_gate_address(regs->uregs[1]));
    return n == (ssize_t)sizeof(command) - 1 && pioneer_ap_gate_matches(command, (size_t)n);
}
static int control_command(int fd, const char *command, const char *expected) {
    if (cancelled()) return 0;
    size_t size = strlen(command);
    control_step=!strcmp(expected,"OK") ? 10 : 20;
    if (send(fd, command, size, MSG_NOSIGNAL) != (ssize_t)size) { control_error=errno;return 0; }
    control_step++;
    char reply[128];
    ssize_t n = recv(fd, reply, sizeof(reply) - 1, 0);
    if (n <= 0) { control_error=errno;return 0; }
    reply[n] = 0;
    reply[strcspn(reply, "\r\n")] = 0;
    snprintf(control_reply,sizeof(control_reply),"%.63s",reply);
    control_step++;
    return !strcmp(expected,"5") ? pioneer_ap_gate_is_five_ghz(reply) : !strcmp(reply, expected);
}
static int set_band(void) {
    const char *paths[] = {"/data/misc/wifi/sockets/wpa_wlan0_cmd", "/data/misc/wifi/sockets/wpa_wlan0",
                           "@wpa_wlan0_cmd", "@wpa_wlan0", "/dev/socket/wpa_wlan0_cmd"};
    for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
        int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (fd < 0) return 0;
        struct timeval timeout = {1, 0};
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        struct sockaddr_un local, remote;
        memset(&local, 0, sizeof(local)); memset(&remote, 0, sizeof(remote));
        local.sun_family = AF_UNIX; remote.sun_family = AF_UNIX;
        int length = snprintf(local.sun_path + 1, sizeof(local.sun_path) - 1, "diplay-ap-gate-%d", getpid());
        control_path=(int)i;control_step=1;
        socklen_t remote_size;
        if(paths[i][0]=='@') {
            size_t name_size=strlen(paths[i]+1);
            memcpy(remote.sun_path+1,paths[i]+1,name_size);
            remote_size=(socklen_t)(offsetof(struct sockaddr_un,sun_path)+1+name_size);
        } else {
            memcpy(remote.sun_path,paths[i],strlen(paths[i])+1);
            remote_size=(socklen_t)(offsetof(struct sockaddr_un,sun_path)+strlen(paths[i])+1);
        }
        int connected = bind(fd, (void *)&local, (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + length)) == 0 &&
                        connect(fd, (void *)&remote, remote_size) == 0;
        if(!connected) control_error=errno;
        if(connected) control_error=0;
        int ok = connected && control_command(fd, PIONEER_AP_FREQUENCY_5, "OK") &&
                 control_command(fd, PIONEER_AP_GET_FREQUENCY, "5");
        close(fd);
        if (ok) return 1;
        if (connected || cancelled()) return 0;
    }
    return 0;
}
int main(int argc, char **argv) {
    start_ms = millis();
    if (argc != 4 || getuid() != 0 || sizeof(void *) != 4) return 2;
    char *end;
    long owner = strtol(argv[3], &end, 10);
    if (*end || owner < 10000 || owner > INT_MAX) return 2;
    cancel_path = argv[2];
    result_fd = open(argv[1], O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (result_fd < 0) return 2;
    if (fchown(result_fd, (uid_t)owner, (gid_t)owner)) return finish("OUTPUT_OWNER_FAILED", 1);
    struct sigaction action;
    memset(&action, 0, sizeof(action)); action.sa_handler = on_signal;
    sigaction(SIGTERM, &action, NULL); sigaction(SIGINT, &action, NULL); sigaction(SIGALRM, &action, NULL);
    sigemptyset(&stop_signals);
    sigaddset(&stop_signals,SIGCHLD);
    if(sigprocmask(SIG_BLOCK,&stop_signals,NULL)) return finish("SIGNAL_MASK_FAILED",1);
    alarm(20);
    if (cancelled()) return finish("CANCELLED", 1);
    tid = wifi_thread();
    if (tid <= 1) return finish("THREAD_NOT_VERIFIED", 1);
    if (ptrace(PTRACE_SEIZE, tid, 0, (void *)(uintptr_t)PTRACE_O_TRACESYSGOOD)) return finish("SEIZE_UNAVAILABLE", 1);
    attached = 1;
    if (ptrace(PTRACE_INTERRUPT, tid, 0, 0)) return finish("INTERRUPT_FAILED", 1);
    int status;
    if (!wait_stop(millis() + 2000, &status, 1)) return finish("STOP_UNCONFIRMED", 1);
    char memory[128]; snprintf(memory, sizeof(memory), "/proc/%d/mem", tid);
    memory_fd = open(memory, O_RDONLY | O_CLOEXEC);
    if (memory_fd < 0) return finish("MEMORY_UNAVAILABLE", 1);
    if (ptrace(PTRACE_SYSCALL, tid, 0, 0)) return finish("RESUME_FAILED", 1);
    stopped = 0; report("ARMED");
    struct pioneer_ap_gate_barrier gate = {0};
    uint64_t deadline = millis() + 15000;
    while (!cancelled() && millis() < deadline) {
        if (!wait_stop(deadline, &status, 1)) break;
        int signal = WSTOPSIG(status);
        if (signal == (SIGTRAP | 0x80)) {
            struct pt_regs regs;
            if (ptrace(PTRACE_GETREGS, tid, 0, &regs)) return finish("REGISTERS_UNAVAILABLE", 1);
            observe_syscall(&regs);
            int matched = is_frequency_send(&regs);
            if (pioneer_ap_gate_advance(&gate, 1, matched)) {
                /* Initial stop could be entry or exit. If still the same sendto,
                 * require its completed byte count before issuing the override. */
                if ((regs.uregs[7] == 289 || regs.uregs[7] == 290) && regs.uregs[0] != sizeof(PIONEER_AP_FREQUENCY_2) - 1)
                    return finish("SEND_UNCONFIRMED", 1);
                int applied = set_band();
                return finish(applied ? "APPLIED" : "CONTROL_REJECTED", applied ? 0 : 1);
            }
            signal = 0;
        } else if (gate.observed) {
            pending_signal = signal;
            return finish("SIGNAL_DURING_BARRIER", 1);
        } else if (signal == SIGTRAP || signal == SIGSTOP) {
            return finish("UNEXPECTED_STOP", 1);
        }
        if (ptrace(PTRACE_SYSCALL, tid, 0, (void *)(uintptr_t)signal)) return finish("TRACE_FAILED", 1);
        stopped = 0;
    }
    return finish(cancelled() ? "CANCELLED" : "NO_MATCH", 1);
}

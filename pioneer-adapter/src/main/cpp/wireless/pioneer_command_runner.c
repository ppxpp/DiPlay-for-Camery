/* Own the entire fixed-command process group until every child is dead.
 * Do not release the Java helper's operation lock while iptables can still mutate rules. */
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <fcntl.h>
#ifdef __linux__
#include <sys/prctl.h>
#endif
#include <signal.h>
#include <time.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>

static volatile sig_atomic_t stopping;
static void stop(int signal_number) { (void)signal_number; stopping = 1; }

static long long millis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now)) _exit(126);
    return (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}
int main(int argc, char **argv) {
    if (argc != 7) return 126;
    char *end = NULL;
    long timeout = strtol(argv[1], &end, 10);
    if (!end || *end || timeout < 1 || timeout > 180000) return 126;
    pid_t parent = (pid_t)strtol(argv[6], &end, 10);
    if (!end || *end || parent <= 1) return 126;
    struct sigaction handler;
    memset(&handler, 0, sizeof(handler));
    handler.sa_handler = stop;
    sigemptyset(&handler.sa_mask);
    if (sigaction(SIGTERM, &handler, NULL) || sigaction(SIGHUP, &handler, NULL)) return 126;
#ifdef __linux__
    if (prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0)) return 126;
    /* Adopt shell grandchildren so their death is confirmed before returning. */
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0)) return 126;
#endif
    /* Recovery probes this POSIX lock after taking the Java operation lock. A dead
     * Java parent cannot let cleanup overtake its still-live command descendants. */
    int guard = open(argv[5], O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (guard < 0) return 126;
    struct stat info;
    if (fstat(guard, &info) || !S_ISREG(info.st_mode) || info.st_nlink != 1) return 126;
    struct flock lock;
    memset(&lock, 0, sizeof(lock));
    lock.l_type = F_WRLCK; lock.l_whence = SEEK_SET;
    while (fcntl(guard, F_SETLKW, &lock)) {
        if (errno != EINTR) return 126;
        if (stopping || getppid() != parent) return 125;
    }
    if (stopping || getppid() != parent) return 125;
    pid_t child = fork();
    if (child < 0) return 126;
    if (!child) {
        if (setpgid(0, 0)) _exit(126);
        execl(argv[3], argv[3], "-c", argv[4], (char *)NULL);
        _exit(126);
    }
    /* Both sides establish the same group; EACCES means exec already completed. */
    if (setpgid(child, child) && errno != EACCES && errno != ESRCH) {
        kill(child, SIGKILL); waitpid(child, NULL, 0); return 126;
    }
    long long deadline = millis() + timeout;
    int result = 0;
    for (;;) {
        siginfo_t status;
        memset(&status, 0, sizeof(status));
        int done = waitid(P_PID, (id_t)child, &status, WEXITED | WNOHANG | WNOWAIT);
        if (!done && status.si_pid == child) {
            result = status.si_code == CLD_EXITED ? status.si_status : 128 + status.si_status;
            break;
        }
        if (done < 0 && errno != EINTR) { result = 126; break; }
        if (stopping || getppid() != parent) { result = 125; break; }
        struct stat marker;
        if (strcmp(argv[2], "-") && !stat(argv[2], &marker)) { result = 125; break; }
        if (millis() >= deadline) { result = 124; break; }
        struct timespec pause = {0, 10000000};
        nanosleep(&pause, NULL);
    }
    /* Kill the group even after a normal shell exit: no detached background command survives. */
    kill(-child, SIGKILL);
    while (waitpid(-1, NULL, 0) >= 0 || errno == EINTR) { }
    if (result == 124) fprintf(stderr, "[COMMAND_TIMEOUT]\n");
    if (result == 125) fprintf(stderr, "[COMMAND_CANCELLED]\n");
    return result;
}

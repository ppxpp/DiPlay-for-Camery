/* The injector must not outlive its validated Java root owner. */
#include <sys/prctl.h>
#include <unistd.h>
#include <signal.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    if (argc < 4 || getuid() != 0) return 126;
    char *end = NULL;
    long owner = strtol(argv[1], &end, 10);
    if (!end || *end || owner <= 1) return 126;
    if (prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0) || getppid() != owner) return 125;
    execv(argv[2], &argv[2]);
    return 126;
}

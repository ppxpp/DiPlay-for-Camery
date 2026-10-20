#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/*
 * Minimal ARMv7 helper for the Camry kernel. Toybox finit_module returns ETXTBSY
 * on this firmware, while its stdin fallback reads from an already-advanced fd.
 * This helper reads one regular module file and calls init_module with those bytes.
 */
int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "usage: %s MODULE [PARAMS]\n", argv[0]);
        return 2;
    }
    int fd = open(argv[1], O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        fprintf(stderr, "open failed errno=%d (%s)\n", errno, strerror(errno));
        return 1;
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 || st.st_size > 1024 * 1024) {
        fprintf(stderr, "invalid module file errno=%d\n", errno);
        close(fd);
        return 1;
    }
    size_t size = (size_t)st.st_size;
    unsigned char *buffer = malloc(size);
    if (buffer == NULL) {
        close(fd);
        return 1;
    }
    size_t done = 0;
    while (done < size) {
        ssize_t n = read(fd, buffer + done, size - done);
        if (n <= 0) {
            fprintf(stderr, "read failed errno=%d\n", errno);
            free(buffer);
            close(fd);
            return 1;
        }
        done += (size_t)n;
    }
    close(fd);
    long result = syscall(__NR_init_module, buffer, size, argc == 3 ? argv[2] : "");
    int saved = errno;
    free(buffer);
    if (result != 0) {
        fprintf(stderr, "init_module failed errno=%d (%s)\n", saved, strerror(saved));
        return 1;
    }
    return 0;
}

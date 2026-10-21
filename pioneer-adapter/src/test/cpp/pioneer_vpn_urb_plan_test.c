#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../../main/cpp/pioneer_vpn_urb.h"

struct fake_io {
    unsigned queued[16];
    unsigned head, tail;
    unsigned submitted, discarded;
};

static int submit(void *opaque, unsigned slot, unsigned char *buffer, size_t length) {
    struct fake_io *io = opaque;
    assert(buffer && length == VPN_URB_BUFFER_BYTES);
    assert(io->tail < sizeof(io->queued) / sizeof(io->queued[0]));
    io->queued[io->tail++] = slot;
    io->submitted++;
    return 0;
}

static int reap(void *opaque, unsigned *slot, size_t *length, int timeout_ms) {
    struct fake_io *io = opaque;
    (void)timeout_ms;
    if (io->head == io->tail) return -11;
    *slot = io->queued[io->head++];
    *length = 86;
    return 0;
}

static int discard(void *opaque, unsigned slot) {
    struct fake_io *io = opaque;
    (void)slot;
    io->discarded++;
    return 0;
}

struct reader_context {
    int chunks;
    volatile int *running;
};

static int chunk(void *opaque, const unsigned char *data, size_t length) {
    struct reader_context *context = opaque;
    assert(data && length == 86);
    context->chunks++;
    if (context->chunks == 3) *context->running = 0;
    return 0;
}

struct writer_context {
    volatile int *running;
    unsigned builds;
};

static int build(void *opaque, unsigned char *buffer, size_t capacity,
                 unsigned max_frames, int *frames) {
    struct writer_context *context = opaque;
    assert(buffer && capacity >= 512 && max_frames == 8);
    memset(buffer, context->builds, 512);
    context->builds++;
    *frames = 1;
    if (context->builds == 4) *context->running = 0;
    return 512;
}

int main(void) {
    volatile int running = 1;
    struct fake_io rx = {0};
    struct vpn_urb_io rx_ops = {.context=&rx,.submit=submit,.reap=reap,.discard=discard};
    struct vpn_urb_runner reader;
    vpn_urb_init(&reader, rx_ops, 2, &running);
    struct reader_context read_context = {.chunks=0,.running=&running};
    assert(vpn_urb_reader_run(&reader, chunk, &read_context) == 0);
    assert(read_context.chunks == 3);
    assert(rx.discarded == 1);

    running = 1;
    struct fake_io tx = {0};
    struct vpn_urb_io tx_ops = {.context=&tx,.submit=submit,.reap=reap,.discard=discard};
    struct vpn_urb_runner writer;
    vpn_urb_init(&writer, tx_ops, 2, &running);
    struct writer_context write_context = {.running=&running,.builds=0};
    assert(vpn_urb_writer_run(&writer, 2, 8, build, &write_context) == 0);
    assert(write_context.builds == 4);
    assert(tx.submitted == 3);
    assert(tx.discarded == 1);
    puts("pioneer_vpn_urb_plan_test: passed");
    return 0;
}

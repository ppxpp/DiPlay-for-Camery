#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include "../../main/cpp/pioneer_vpn_urb.h"

struct fake_io {
    unsigned queued[16];
    unsigned head, tail;
    unsigned submitted, discarded;
    int cancel_status;
    int completion_error;
    unsigned delayed_cancel_reaps;
    size_t expected_length;
};

static int submit(void *opaque, unsigned slot, unsigned char *buffer, size_t length) {
    struct fake_io *io = opaque;
    assert(buffer && length == (io->expected_length ? io->expected_length : VPN_URB_BUFFER_BYTES));
    assert(io->tail < sizeof(io->queued) / sizeof(io->queued[0]));
    io->queued[io->tail++] = slot;
    io->submitted++;
    return 0;
}

static int reap(void *opaque, unsigned *slot, size_t *length, int timeout_ms) {
    struct fake_io *io = opaque;
    (void)timeout_ms;
    if (io->discarded && io->delayed_cancel_reaps) { io->delayed_cancel_reaps--; return -EAGAIN; }
    if (io->head == io->tail) return -EAGAIN;
    *slot = io->queued[io->head++];
    *length = 86;
    if (!io->discarded && io->completion_error) {
        int error = io->completion_error; io->completion_error = 0; return error;
    }
    return io->discarded ? io->cancel_status : 0;
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
    struct fake_io tx = {.expected_length=512};
    struct vpn_urb_io tx_ops = {.context=&tx,.submit=submit,.reap=reap,.discard=discard};
    struct vpn_urb_runner writer;
    vpn_urb_init(&writer, tx_ops, 2, &running);
    struct writer_context write_context = {.running=&running,.builds=0};
    assert(vpn_urb_writer_run(&writer, 2, 8, build, &write_context) == 0);
    assert(write_context.builds == 4);
    assert(tx.submitted == 3);
    assert(tx.discarded == 1);
    running = 0;
    struct fake_io cancelled = {.cancel_status=-ECONNRESET};
    struct vpn_urb_io cancel_ops = {.context=&cancelled,.submit=submit,.reap=reap,.discard=discard};
    vpn_urb_init(&reader, cancel_ops, 4, &running);
    assert(vpn_urb_reader_run(&reader, chunk, &read_context) == 0);
    assert(cancelled.head == cancelled.tail);
    assert(reader.in_flight == 0);
    // More cancellation timeouts than the old fixed retry budget must still
    // drain every kernel-owned buffer before the runner can leave its scope.
    struct fake_io delayed = {.cancel_status=-ECONNRESET, .delayed_cancel_reaps=12};
    struct vpn_urb_io delayed_ops = {.context=&delayed,.submit=submit,.reap=reap,.discard=discard};
    vpn_urb_init(&reader, delayed_ops, 4, &running);
    assert(vpn_urb_reader_run(&reader, chunk, &read_context) == 0);
    assert(delayed.head == delayed.tail);
    assert(reader.in_flight == 0);
    // A failed completion has already left the kernel queue. It must not be
    // counted as outstanding during subsequent cancellation of the other URBs.
    running = 1;
    struct fake_io failed_rx = {.completion_error=-EIO};
    struct vpn_urb_io failed_rx_ops = {.context=&failed_rx,.submit=submit,.reap=reap,.discard=discard};
    vpn_urb_init(&reader, failed_rx_ops, 4, &running);
    assert(vpn_urb_reader_run(&reader, chunk, &read_context) == -EIO);
    assert(reader.in_flight == 0 && failed_rx.head == failed_rx.tail);
    for (unsigned depth = 1; depth <= 2; depth++) {
        struct fake_io failed_tx = {.completion_error=-EIO, .expected_length=512};
        struct vpn_urb_io failed_tx_ops = {.context=&failed_tx,.submit=submit,.reap=reap,.discard=discard};
        struct writer_context failed_write = {.running=&running,.builds=0};
        vpn_urb_init(&writer, failed_tx_ops, 2, &running);
        assert(vpn_urb_writer_run(&writer, depth, 8, build, &failed_write) == -EIO);
        assert(writer.in_flight == 0 && failed_tx.head == failed_tx.tail);
    }
    puts("pioneer_vpn_urb_plan_test: passed");
    return 0;
}

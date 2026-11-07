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
    int submit_error;
    unsigned submit_error_at;
    unsigned failures, discards_at_failure;
    const char *failure_stage;
    int failure_result;
    unsigned failure_slot;
};

static int submit(void *opaque, unsigned slot, unsigned char *buffer, size_t length) {
    struct fake_io *io = opaque;
    if (io->submit_error && io->submitted == io->submit_error_at) return io->submit_error;
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

static void failed(void *opaque, const char *stage, int result, unsigned slot) {
    struct fake_io *io = opaque;
    if (result >= 0) return;
    io->failures++;
    io->discards_at_failure = io->discarded;
    io->failure_stage = stage;
    io->failure_result = result;
    io->failure_slot = slot;
}

static int failed_chunk(void *opaque, const unsigned char *data, size_t length) {
    (void)opaque; (void)data; (void)length;
    return -EPROTO;
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
    struct vpn_urb_io rx_ops = {.context=&rx,.submit=submit,.reap=reap,.discard=discard,.before_cleanup=failed};
    struct vpn_urb_runner reader;
    vpn_urb_init(&reader, rx_ops, 2, &running);
    struct reader_context read_context = {.chunks=0,.running=&running};
    assert(vpn_urb_reader_run(&reader, chunk, &read_context) == 0);
    assert(read_context.chunks == 3);
    assert(rx.discarded == 1);
    assert(rx.failures == 0);

    running = 1;
    struct fake_io tx = {.expected_length=512};
    struct vpn_urb_io tx_ops = {.context=&tx,.submit=submit,.reap=reap,.discard=discard,.before_cleanup=failed};
    struct vpn_urb_runner writer;
    vpn_urb_init(&writer, tx_ops, 2, &running);
    struct writer_context write_context = {.running=&running,.builds=0};
    assert(vpn_urb_writer_run(&writer, 2, 8, build, &write_context) == 0);
    assert(write_context.builds == 4);
    assert(tx.submitted == 3);
    assert(tx.discarded == 1);
    running = 0;
    struct fake_io cancelled = {.cancel_status=-ECONNRESET};
    struct vpn_urb_io cancel_ops = {.context=&cancelled,.submit=submit,.reap=reap,.discard=discard,.before_cleanup=failed};
    vpn_urb_init(&reader, cancel_ops, 4, &running);
    assert(vpn_urb_reader_run(&reader, chunk, &read_context) == 0);
    assert(cancelled.head == cancelled.tail);
    assert(cancelled.failures == 0);
    assert(reader.in_flight == 0);
    // More cancellation timeouts than the old fixed retry budget must still
    // drain every kernel-owned buffer before the runner can leave its scope.
    struct fake_io delayed = {.cancel_status=-ECONNRESET, .delayed_cancel_reaps=12};
    struct vpn_urb_io delayed_ops = {.context=&delayed,.submit=submit,.reap=reap,.discard=discard,.before_cleanup=failed};
    vpn_urb_init(&reader, delayed_ops, 4, &running);
    assert(vpn_urb_reader_run(&reader, chunk, &read_context) == 0);
    assert(delayed.head == delayed.tail);
    assert(reader.in_flight == 0);
    // A failed completion has already left the kernel queue. It must not be
    // counted as outstanding during subsequent cancellation of the other URBs.
    running = 1;
    struct fake_io failed_rx = {.completion_error=-EIO};
    struct vpn_urb_io failed_rx_ops = {.context=&failed_rx,.submit=submit,.reap=reap,.discard=discard,.before_cleanup=failed};
    vpn_urb_init(&reader, failed_rx_ops, 4, &running);
    assert(vpn_urb_reader_run(&reader, chunk, &read_context) == -EIO);
    assert(reader.in_flight == 0 && failed_rx.head == failed_rx.tail);
    assert(failed_rx.failures == 1 && failed_rx.discards_at_failure == 0);
    assert(strcmp(failed_rx.failure_stage, "reap") == 0);
    assert(failed_rx.failure_result == -EIO && failed_rx.failure_slot == 0);
    for (unsigned depth = 1; depth <= 2; depth++) {
        struct fake_io failed_tx = {.completion_error=-EIO, .expected_length=512};
        struct vpn_urb_io failed_tx_ops = {.context=&failed_tx,.submit=submit,.reap=reap,.discard=discard,.before_cleanup=failed};
        struct writer_context failed_write = {.running=&running,.builds=0};
        vpn_urb_init(&writer, failed_tx_ops, 2, &running);
        assert(vpn_urb_writer_run(&writer, depth, 8, build, &failed_write) == -EIO);
        assert(writer.in_flight == 0 && failed_tx.head == failed_tx.tail);
        assert(failed_tx.failures == 1 && failed_tx.discards_at_failure == 0);
        assert(strcmp(failed_tx.failure_stage, "reap") == 0 && failed_tx.failure_result == -EIO);
    }
    struct fake_io failed_submit = {.submit_error=-ENODEV, .submit_error_at=1, .cancel_status=-ECONNRESET};
    struct vpn_urb_io submit_ops = {.context=&failed_submit,.submit=submit,.reap=reap,.discard=discard,.before_cleanup=failed};
    vpn_urb_init(&reader, submit_ops, 4, &running);
    assert(vpn_urb_reader_run(&reader, chunk, &read_context) == -ENODEV);
    assert(strcmp(failed_submit.failure_stage, "submit") == 0);
    assert(failed_submit.failure_result == -ENODEV && failed_submit.failure_slot == 1);
    assert(failed_submit.discards_at_failure == 0 && failed_submit.discarded == 1);
    assert(reader.in_flight == 0);

    struct fake_io failed_process = {.cancel_status=-ECONNRESET};
    struct vpn_urb_io process_ops = {.context=&failed_process,.submit=submit,.reap=reap,.discard=discard,.before_cleanup=failed};
    vpn_urb_init(&reader, process_ops, 4, &running);
    assert(vpn_urb_reader_run(&reader, failed_chunk, NULL) == -EPROTO);
    assert(strcmp(failed_process.failure_stage, "process-input") == 0);
    assert(failed_process.failure_result == -EPROTO && failed_process.discards_at_failure == 0);
    assert(reader.in_flight == 0);
    puts("pioneer_vpn_urb_plan_test: passed");
    return 0;
}

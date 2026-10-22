#include "pioneer_vpn_urb.h"

#include <errno.h>
#include <string.h>
#include <time.h>

void vpn_urb_init(struct vpn_urb_runner *runner, struct vpn_urb_io io,
                  unsigned slot_count, volatile int *running) {
    memset(runner, 0, sizeof(*runner));
    runner->io = io;
    runner->slot_count = slot_count < 2 ? 2 : slot_count > VPN_URB_MAX_SLOTS
        ? VPN_URB_MAX_SLOTS : slot_count;
    runner->running = running;
}

static int submit_slot(struct vpn_urb_runner *runner, unsigned slot, size_t length) {
    struct vpn_urb_slot *item = &runner->slots[slot];
    if (item->state != VPN_URB_FREE) return -EBUSY;
    item->state = VPN_URB_SUBMITTED;
    int result = runner->io.submit(runner->io.context, slot,
                                   item->buffer, length);
    if (result < 0) item->state = VPN_URB_FREE;
    else runner->in_flight++;
    return result;
}

/* A failed URB is still a completed URB: account for its released buffer
 * before propagating its status, or shutdown would wait for it a second time. */
static int reap_slot(struct vpn_urb_runner *runner, unsigned *slot, size_t *length) {
    *slot = runner->slot_count;
    int result = runner->io.reap(runner->io.context, slot, length, VPN_URB_TIMEOUT_MS);
    if (*slot < runner->slot_count && runner->slots[*slot].state == VPN_URB_SUBMITTED) {
        runner->slots[*slot].state = VPN_URB_FREE;
        if (runner->in_flight) runner->in_flight--;
    } else if (result >= 0) return -EIO;
    return result;
}

static void discard_and_reap(struct vpn_urb_runner *runner) {
    for (unsigned i = 0; i < runner->slot_count; i++) {
        if (runner->slots[i].state == VPN_URB_SUBMITTED)
            (void)runner->io.discard(runner->io.context, i);
    }
    // The kernel may still reference every submitted buffer. A timeout is not a
    // completed cancellation: never let this stack-owned runner disappear early.
    // If the device cannot quiesce, close() retains its context/FD and reports it.
    while (runner->in_flight) {
        unsigned slot = runner->slot_count;
        size_t length = 0;
        unsigned before = runner->in_flight;
        int result = reap_slot(runner, &slot, &length);
        if (runner->in_flight == before && result != -EAGAIN && result != -EINTR) {
            // An unexpected result must not turn outstanding kernel requests into
            // freed buffers, or spin a CPU while shutdown is waiting for them.
            struct timespec delay = {0, 10000000};
            while (nanosleep(&delay, &delay) < 0 && errno == EINTR) { }
        }
    }
}

static int reader_run(struct vpn_urb_runner *runner, vpn_urb_chunk on_chunk,
                       void *context) {
    for (unsigned i = 0; i < runner->slot_count; i++) {
        int result = submit_slot(runner, i, VPN_URB_BUFFER_BYTES);
        if (result < 0) {
            return result;
        }
    }
    while (*runner->running) {
        unsigned slot = runner->slot_count;
        size_t length = 0;
        int result = reap_slot(runner, &slot, &length);
        if (result == -EAGAIN || result == -EINTR) continue;
        if (result < 0) return result;
        runner->slots[slot].state = VPN_URB_CONSUMING;
        if (length > VPN_URB_BUFFER_BYTES) return -EMSGSIZE;
        result = on_chunk(context, runner->slots[slot].buffer, length);
        if (result < 0) return result;
        runner->slots[slot].state = VPN_URB_FREE;
        if (*runner->running) {
            result = submit_slot(runner, slot, VPN_URB_BUFFER_BYTES);
            if (result < 0) return result;
        }
    }
    return 0;
}

static int writer_run(struct vpn_urb_runner *runner, unsigned pipeline_depth,
                       unsigned max_frames, vpn_urb_build build, void *context) {
    unsigned depth = pipeline_depth ? pipeline_depth : 1;
    if (depth > runner->slot_count) depth = runner->slot_count;
    while (*runner->running) {
        unsigned slot = runner->slot_count;
        if (depth == 1) {
            slot = 0;
            runner->slots[slot].state = VPN_URB_FREE;
        } else {
            for (unsigned i = 0; i < depth; i++) {
                if (runner->slots[i].state == VPN_URB_FREE) {
                    slot = i;
                    break;
                }
            }
            while (slot == runner->slot_count && *runner->running) {
                size_t completed_length = 0;
                int result = reap_slot(runner, &slot, &completed_length);
                if (result == -EAGAIN || result == -EINTR) continue;
                if (result < 0) return result;
                if (slot >= depth) return -EIO;
            }
            if (slot == runner->slot_count) break;
        }
        int frames = 0;
        int length = build(context, runner->slots[slot].buffer,
                           sizeof(runner->slots[slot].buffer), max_frames, &frames);
        if (length < 0) return length;
        if (!length || !frames) continue;
        if (!*runner->running) break;
        int result = submit_slot(runner, slot, (size_t)length);
        if (result < 0) {
            return result;
        }
        if (depth == 1) {
            size_t completed_length = 0;
            do {
                result = reap_slot(runner, &slot, &completed_length);
            } while (result == -EINTR && *runner->running);
            if (result < 0) {
                if (!*runner->running) {
                            return 0;
                }
                return result;
            }
        }
    }
    return 0;
}

int vpn_urb_reader_run(struct vpn_urb_runner *runner, vpn_urb_chunk on_chunk, void *context) {
    int result = reader_run(runner, on_chunk, context);
    discard_and_reap(runner);
    return result;
}
int vpn_urb_writer_run(struct vpn_urb_runner *runner, unsigned pipeline_depth,
                       unsigned max_frames, vpn_urb_build build, void *context) {
    int result = writer_run(runner, pipeline_depth, max_frames, build, context);
    discard_and_reap(runner);
    return result;
}

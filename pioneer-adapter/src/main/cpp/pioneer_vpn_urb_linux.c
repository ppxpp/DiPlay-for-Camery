#include "pioneer_vpn_urb.h"

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

enum vpn_urb_direction { VPN_URB_RX, VPN_URB_TX };

struct vpn_linux_completion {
    uint64_t order;
    int ready;
    int status;
    size_t length;
};

struct vpn_linux_urb_context;
struct vpn_linux_target {
    struct vpn_linux_urb_context *parent;
    enum vpn_urb_direction direction;
};

struct vpn_linux_urb_context {
    int fd;
    int in_endpoint;
    int out_endpoint;
    pthread_mutex_t *write_lock;
    uint16_t *sequence;
    pthread_mutex_t completion_lock;
    uint64_t completion_order;
    uint64_t rx_submission_order[VPN_URB_MAX_SLOTS];
    uint64_t rx_next_submission;
    uint64_t rx_last_completed_submission;
    int rx_has_completion;
    _Atomic unsigned long rx_completion_regressions;
    struct usbdevfs_urb rx_urbs[VPN_URB_MAX_SLOTS];
    struct usbdevfs_urb tx_urbs[2];
    struct vpn_linux_completion rx_completed[VPN_URB_MAX_SLOTS];
    struct vpn_linux_completion tx_completed[2];
    unsigned rx_capacity;
    unsigned tx_capacity;
    _Atomic int rx_submitted[VPN_URB_MAX_SLOTS];
    _Atomic int tx_submitted[2];
    _Atomic unsigned long rx_submits;
    _Atomic unsigned long rx_reaps;
    _Atomic unsigned long rx_errors;
    _Atomic unsigned long tx_submits;
    _Atomic unsigned long tx_reaps;
    _Atomic unsigned long tx_errors;
    _Atomic unsigned long poll_calls[2];
    _Atomic unsigned long poll_ready[2];
    _Atomic unsigned long poll_timeouts[2];
    struct vpn_linux_target rx_target;
    struct vpn_linux_target tx_target;
};

static uint64_t monotonic_ns(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return (uint64_t)value.tv_sec * 1000000000u + (uint64_t)value.tv_nsec;
}

// Caller holds completion_lock across REAPURBNDELAY and publication: another
// direction must not publish a later kernel completion ahead of this one.
static int store_completion(struct vpn_linux_urb_context *context,
                            struct usbdevfs_urb *completed) {
    unsigned index = (unsigned)(uintptr_t)completed->usercontext;
    enum vpn_urb_direction direction =
        completed->endpoint & 0x80 ? VPN_URB_RX : VPN_URB_TX;
    struct vpn_linux_completion *item;
    _Atomic int *submitted;
    unsigned capacity;
    if (direction == VPN_URB_RX) {
        capacity = context->rx_capacity;
        item = context->rx_completed;
        submitted = context->rx_submitted;
    } else {
        capacity = context->tx_capacity;
        item = context->tx_completed;
        submitted = context->tx_submitted;
    }
    if (index >= capacity || !atomic_exchange_explicit(
            &submitted[index], 0, memory_order_acq_rel)) return -EIO;
    if (direction == VPN_URB_RX) {
        uint64_t order = context->rx_submission_order[index];
        if (context->rx_has_completion && order < context->rx_last_completed_submission)
            atomic_fetch_add_explicit(&context->rx_completion_regressions, 1, memory_order_relaxed);
        if (!context->rx_has_completion || order > context->rx_last_completed_submission)
            context->rx_last_completed_submission = order;
        context->rx_has_completion = 1;
    }
    item[index].order = context->completion_order++;
    item[index].ready = 1;
    item[index].status = completed->status;
    item[index].length = completed->actual_length > 0
        ? (size_t)completed->actual_length : 0;
    if (direction == VPN_URB_RX) {
        atomic_fetch_add_explicit(&context->rx_reaps, 1, memory_order_relaxed);
        if (completed->status < 0)
            atomic_fetch_add_explicit(&context->rx_errors, 1, memory_order_relaxed);
    } else {
        atomic_fetch_add_explicit(&context->tx_reaps, 1, memory_order_relaxed);
        if (completed->status < 0)
            atomic_fetch_add_explicit(&context->tx_errors, 1, memory_order_relaxed);
    }
    return 0;
}

static int reap_for(struct vpn_linux_target *target, unsigned *slot,
                    size_t *length, int timeout_ms) {
    struct vpn_linux_urb_context *context = target->parent;
    struct vpn_linux_completion *items = target->direction == VPN_URB_RX
        ? context->rx_completed : context->tx_completed;
    unsigned capacity = target->direction == VPN_URB_RX
        ? context->rx_capacity : context->tx_capacity;
    uint64_t deadline = monotonic_ns() + (uint64_t)timeout_ms * 1000000u;
    for (;;) {
        pthread_mutex_lock(&context->completion_lock);
        // Slot indices wrap on resubmission; they do not describe stream order.
        // Deliver the oldest kernel completion, including completions reaped by TX.
        unsigned oldest = capacity;
        for (unsigned i = 0; i < capacity; i++) {
            if (items[i].ready && (oldest == capacity || items[i].order < items[oldest].order)) oldest = i;
        }
        if (target->direction == VPN_URB_RX) {
            // A USB byte stream follows submission order, even if the host
            // controller reports callbacks in a different order. Wait for the
            // earliest outstanding read before delivering any later buffer.
            unsigned expected = capacity;
            for (unsigned i = 0; i < capacity; i++) {
                if ((items[i].ready || atomic_load_explicit(&context->rx_submitted[i], memory_order_acquire)) &&
                    (expected == capacity || context->rx_submission_order[i] < context->rx_submission_order[expected])) expected = i;
            }
            oldest = expected < capacity && items[expected].ready ? expected : capacity;
        }
        if (oldest < capacity) {
            int status = items[oldest].status;
            *slot = oldest;
            *length = items[oldest].length;
            items[oldest].ready = 0;
            pthread_mutex_unlock(&context->completion_lock);
            return status < 0 ? (status == -ECONNRESET ? -ECONNRESET : -EIO) : 0;
        }
        uint64_t now = monotonic_ns();
        if (now >= deadline) {
            pthread_mutex_unlock(&context->completion_lock);
            return -EAGAIN;
        }
        // The ioctl is nonblocking. Keep its result and publication atomic with
        // respect to the other reaper, then release before any poll/wait.
        struct usbdevfs_urb *completed = NULL;
        int result = ioctl(context->fd, USBDEVFS_REAPURBNDELAY, &completed);
        if (result == 0 && completed) {
            result = store_completion(context, completed);
            pthread_mutex_unlock(&context->completion_lock);
            if (result < 0) return result;
            continue;
        }
        int saved_errno = errno;
        pthread_mutex_unlock(&context->completion_lock);
        if (result < 0 && saved_errno != EAGAIN && saved_errno != EINTR) return -saved_errno;
        // usbdev_poll reports completed URBs as POLLOUT/POLLWRNORM (Linux 4.9
        // drivers/usb/core/devio.c), regardless of USB transfer direction.
        struct pollfd descriptor = {.fd = context->fd, .events = POLLOUT};
        now = monotonic_ns();
        if (now >= deadline) return -EAGAIN;
        int wait_ms = (int)((deadline - now + 999999u) / 1000000u);
        if (wait_ms > 5) wait_ms = 5;
        if (wait_ms > 0) {
            unsigned direction = target->direction == VPN_URB_RX ? 0u : 1u;
            atomic_fetch_add_explicit(&context->poll_calls[direction],1,memory_order_relaxed);
            int ready = poll(&descriptor,1,wait_ms);
            if (ready == 0) atomic_fetch_add_explicit(&context->poll_timeouts[direction],1,memory_order_relaxed);
            else if (ready > 0 && (descriptor.revents & POLLOUT))
                atomic_fetch_add_explicit(&context->poll_ready[direction],1,memory_order_relaxed);
        }
    }
}

static int submit_target(struct vpn_linux_target *target, unsigned slot,
                         unsigned char *buffer, size_t length) {
    struct vpn_linux_urb_context *context = target->parent;
    struct usbdevfs_urb *urb;
    _Atomic int *submitted;
    int endpoint;
    if (target->direction == VPN_URB_RX) {
        if (slot >= context->rx_capacity) return -EINVAL;
        urb = &context->rx_urbs[slot];
        submitted = &context->rx_submitted[slot];
        endpoint = context->in_endpoint;
    } else {
        if (slot >= context->tx_capacity) return -EINVAL;
        urb = &context->tx_urbs[slot];
        submitted = &context->tx_submitted[slot];
        endpoint = context->out_endpoint;
    }
    memset(urb, 0, sizeof(*urb));
    urb->type = USBDEVFS_URB_TYPE_BULK;
    urb->endpoint = (unsigned char)endpoint;
    urb->usercontext = (void *)(uintptr_t)slot;
    urb->buffer = buffer;
    urb->buffer_length = (int)length;
    // Publish submission before ioctl so another thread cannot reap it first.
    pthread_mutex_lock(&context->completion_lock);
    if (target->direction == VPN_URB_RX)
        context->rx_submission_order[slot] = context->rx_next_submission++;
    atomic_store_explicit(submitted, 1, memory_order_release);
    pthread_mutex_unlock(&context->completion_lock);
    if (target->direction == VPN_URB_TX && context->write_lock)
        pthread_mutex_lock(context->write_lock);
    if (target->direction == VPN_URB_TX && context->sequence) {
        buffer[6] = (unsigned char)*context->sequence;
        buffer[7] = (unsigned char)(*context->sequence >> 8);
        *context->sequence = (uint16_t)(*context->sequence + 1);
    }
    int result = ioctl(context->fd, USBDEVFS_SUBMITURB, urb);
    int error = result < 0 ? errno : 0;
    if (target->direction == VPN_URB_TX && context->write_lock)
        pthread_mutex_unlock(context->write_lock);
    if (result < 0) {
        atomic_store_explicit(submitted, 0, memory_order_release);
        if (target->direction == VPN_URB_RX)
            atomic_fetch_add_explicit(&context->rx_errors, 1, memory_order_relaxed);
        else
            atomic_fetch_add_explicit(&context->tx_errors, 1, memory_order_relaxed);
        return -error;
    }
    if (target->direction == VPN_URB_RX)
        atomic_fetch_add_explicit(&context->rx_submits, 1, memory_order_relaxed);
    else
        atomic_fetch_add_explicit(&context->tx_submits, 1, memory_order_relaxed);
    return 0;
}

static int discard_target(struct vpn_linux_target *target, unsigned slot) {
    struct vpn_linux_urb_context *context = target->parent;
    struct usbdevfs_urb *urb;
    _Atomic int *submitted;
    if (target->direction == VPN_URB_RX) {
        if (slot >= context->rx_capacity) return -EINVAL;
        urb = &context->rx_urbs[slot];
        submitted = &context->rx_submitted[slot];
    } else {
        if (slot >= context->tx_capacity) return -EINVAL;
        urb = &context->tx_urbs[slot];
        submitted = &context->tx_submitted[slot];
    }
    if (!atomic_load_explicit(submitted, memory_order_acquire)) return 0;
    int result = ioctl(context->fd, USBDEVFS_DISCARDURB, urb);
    return result < 0 && errno != ENOENT ? -errno : 0;
}

static int submit_callback(void *opaque, unsigned slot,
                           unsigned char *buffer, size_t length) {
    return submit_target((struct vpn_linux_target *)opaque, slot, buffer, length);
}

static int reap_callback(void *opaque, unsigned *slot, size_t *length, int timeout_ms) {
    return reap_for((struct vpn_linux_target *)opaque, slot, length, timeout_ms);
}

static int discard_callback(void *opaque, unsigned slot) {
    return discard_target((struct vpn_linux_target *)opaque, slot);
}

struct vpn_linux_urb_context *vpn_linux_urb_create(
    int fd, int in_endpoint, int out_endpoint, void *write_lock,
    uint16_t *sequence) {
    struct vpn_linux_urb_context *context = calloc(1, sizeof(*context));
    if (!context) return NULL;
    context->fd = fd;
    context->in_endpoint = in_endpoint;
    context->out_endpoint = out_endpoint;
    context->write_lock = (pthread_mutex_t *)write_lock;
    context->sequence = sequence;
    context->rx_target = (struct vpn_linux_target){.parent = context, .direction = VPN_URB_RX};
    context->tx_target = (struct vpn_linux_target){.parent = context, .direction = VPN_URB_TX};
    if (pthread_mutex_init(&context->completion_lock, NULL) != 0) {
        free(context);
        return NULL;
    }
    return context;
}

void vpn_linux_urb_destroy(struct vpn_linux_urb_context *context) {
    if (!context) return;
    pthread_mutex_destroy(&context->completion_lock);
    free(context);
}

int vpn_linux_urb_reader(struct vpn_linux_urb_context *context,
                         unsigned slots, volatile int *running,
                         vpn_urb_chunk callback, void *callback_context) {
    struct vpn_urb_runner runner;
    context->rx_capacity = slots < 2 ? 2 : slots > VPN_URB_MAX_SLOTS ? VPN_URB_MAX_SLOTS : slots;
    struct vpn_urb_io io = {
        .context = &context->rx_target,
        .submit = submit_callback,
        .reap = reap_callback,
        .discard = discard_callback,
    };
    vpn_urb_init(&runner, io, context->rx_capacity, running);
    return vpn_urb_reader_run(&runner, callback, callback_context);
}

int vpn_linux_urb_writer(struct vpn_linux_urb_context *context,
                         unsigned pipeline_depth, unsigned max_frames,
                         volatile int *running, vpn_urb_build build,
                         void *callback_context) {
    struct vpn_urb_runner runner;
    context->tx_capacity = pipeline_depth == 2 ? 2 : 1;
    struct vpn_urb_io io = {
        .context = &context->tx_target,
        .submit = submit_callback,
        .reap = reap_callback,
        .discard = discard_callback,
    };
    vpn_urb_init(&runner, io, context->tx_capacity, running);
    return vpn_urb_writer_run(&runner, pipeline_depth, max_frames, build, callback_context);
}

int vpn_linux_urb_sync_write(struct vpn_linux_urb_context *context,
                             const unsigned char *data, size_t length) {
    if (!context || !data || !length || length > VPN_URB_BUFFER_BYTES) return -EINVAL;
    if (context->write_lock) pthread_mutex_lock(context->write_lock);
    if (context->sequence) {
        unsigned char *writable = (unsigned char *)data;
        writable[6] = (unsigned char)*context->sequence;
        writable[7] = (unsigned char)(*context->sequence >> 8);
        *context->sequence = (uint16_t)(*context->sequence + 1);
    }
    struct usbdevfs_bulktransfer transfer = {
        .ep = (unsigned)context->out_endpoint,
        .len = (unsigned)length,
        .timeout = 2000,
        .data = (void *)data,
    };
    int result = ioctl(context->fd, USBDEVFS_BULK, &transfer);
    int error = result < 0 ? errno : 0;
    if (context->write_lock) pthread_mutex_unlock(context->write_lock);
    atomic_fetch_add_explicit(&context->tx_submits, 1, memory_order_relaxed);
    atomic_fetch_add_explicit(&context->tx_reaps, 1, memory_order_relaxed);
    if (result < 0 || result != (int)length)
        atomic_fetch_add_explicit(&context->tx_errors, 1, memory_order_relaxed);
    if (result < 0) return -error;
    return result == (int)length ? 0 : -EIO;
}

void vpn_linux_urb_snapshot(struct vpn_linux_urb_context *context,
                            unsigned long values[13]) {
    if (!values) return;
    if (!context) {
        memset(values, 0, sizeof(unsigned long) * 13);
        return;
    }
    values[12] = atomic_load_explicit(&context->rx_completion_regressions, memory_order_relaxed);
    values[0] = atomic_load_explicit(&context->rx_submits, memory_order_relaxed);
    values[1] = atomic_load_explicit(&context->rx_reaps, memory_order_relaxed);
    values[2] = atomic_load_explicit(&context->rx_errors, memory_order_relaxed);
    values[3] = atomic_load_explicit(&context->tx_submits, memory_order_relaxed);
    values[4] = atomic_load_explicit(&context->tx_reaps, memory_order_relaxed);
    values[5] = atomic_load_explicit(&context->tx_errors, memory_order_relaxed);
    for (unsigned i=0; i<2; i++) {
        values[6+i*3] = atomic_load_explicit(&context->poll_calls[i],memory_order_relaxed);
        values[7+i*3] = atomic_load_explicit(&context->poll_ready[i],memory_order_relaxed);
        values[8+i*3] = atomic_load_explicit(&context->poll_timeouts[i],memory_order_relaxed);
    }
}

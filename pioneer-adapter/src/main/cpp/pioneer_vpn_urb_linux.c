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
    pthread_cond_t completion_ready;
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
    struct vpn_linux_target rx_target;
    struct vpn_linux_target tx_target;
};

static uint64_t monotonic_ns(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return (uint64_t)value.tv_sec * 1000000000u + (uint64_t)value.tv_nsec;
}

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
    pthread_mutex_lock(&context->completion_lock);
    item[index].ready = 1;
    item[index].status = completed->status;
    item[index].length = completed->actual_length > 0
        ? (size_t)completed->actual_length : 0;
    pthread_cond_broadcast(&context->completion_ready);
    pthread_mutex_unlock(&context->completion_lock);
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
        for (unsigned i = 0; i < capacity; i++) {
            if (!items[i].ready) continue;
            int status = items[i].status;
            *slot = i;
            *length = items[i].length;
            items[i].ready = 0;
            pthread_mutex_unlock(&context->completion_lock);
            return status < 0 ? (status == -ECONNRESET ? -ECONNRESET : -EIO) : 0;
        }
        uint64_t now = monotonic_ns();
        if (now >= deadline) {
            pthread_mutex_unlock(&context->completion_lock);
            return -EAGAIN;
        }
        pthread_mutex_unlock(&context->completion_lock);

        struct usbdevfs_urb *completed = NULL;
        int result = ioctl(context->fd, USBDEVFS_REAPURBNDELAY, &completed);
        if (result == 0 && completed) {
            result = store_completion(context, completed);
            if (result < 0) return result;
            continue;
        }
        if (result < 0 && errno != EAGAIN && errno != EINTR) return -errno;
        struct pollfd descriptor = {.fd = context->fd, .events = POLLIN};
        int wait_ms = (int)((deadline - monotonic_ns() + 999999u) / 1000000u);
        if (wait_ms > 5) wait_ms = 5;
        if (wait_ms > 0) (void)poll(&descriptor, 1, wait_ms);
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
    atomic_store_explicit(submitted, 1, memory_order_release);
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
    if (pthread_mutex_init(&context->completion_lock, NULL) != 0 ||
        pthread_cond_init(&context->completion_ready, NULL) != 0) {
        free(context);
        return NULL;
    }
    return context;
}

void vpn_linux_urb_destroy(struct vpn_linux_urb_context *context) {
    if (!context) return;
    pthread_cond_destroy(&context->completion_ready);
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
                            unsigned long values[6]) {
    if (!values) return;
    if (!context) {
        memset(values, 0, sizeof(unsigned long) * 6);
        return;
    }
    values[0] = atomic_load_explicit(&context->rx_submits, memory_order_relaxed);
    values[1] = atomic_load_explicit(&context->rx_reaps, memory_order_relaxed);
    values[2] = atomic_load_explicit(&context->rx_errors, memory_order_relaxed);
    values[3] = atomic_load_explicit(&context->tx_submits, memory_order_relaxed);
    values[4] = atomic_load_explicit(&context->tx_reaps, memory_order_relaxed);
    values[5] = atomic_load_explicit(&context->tx_errors, memory_order_relaxed);
}

int vpn_linux_usb_capabilities(int fd, uint64_t *capabilities) {
    if (!capabilities) return -EINVAL;
    *capabilities = 0;
    unsigned kernel = 0;
#ifdef USBDEVFS_GET_CAPABILITIES
    if (fd >= 0) {
        int result = ioctl(fd, USBDEVFS_GET_CAPABILITIES, &kernel);
        if (result < 0 && errno != ENOTTY && errno != EINVAL) return -errno;
    }
#endif
    *capabilities = kernel;
    if (kernel & USBDEVFS_CAP_BULK_CONTINUATION) *capabilities |= 1ULL << 32;
    if (kernel & USBDEVFS_CAP_NO_PACKET_SIZE_LIM) *capabilities |= 1ULL << 33;
#if defined(USBDEVFS_MMAP) || defined(USBDEVFS_DMABUF)
    *capabilities |= 1ULL << 63;
#endif
    return 0;
}

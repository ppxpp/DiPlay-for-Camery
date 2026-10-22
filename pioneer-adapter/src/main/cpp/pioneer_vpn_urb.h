#ifndef PIONEER_VPN_URB_H
#define PIONEER_VPN_URB_H

#include <stddef.h>
#include <stdint.h>

#define VPN_URB_MAX_SLOTS 4
#define VPN_URB_BUFFER_BYTES (32 * 1024)
#define VPN_URB_TIMEOUT_MS 50

typedef int (*vpn_urb_submit)(void *context, unsigned slot,
                              unsigned char *buffer, size_t length);
typedef int (*vpn_urb_reap)(void *context, unsigned *slot, size_t *length, int timeout_ms);
typedef int (*vpn_urb_discard)(void *context, unsigned slot);
typedef int (*vpn_urb_chunk)(void *context, const unsigned char *data, size_t length);
typedef int (*vpn_urb_build)(void *context, unsigned char *buffer, size_t capacity,
                             unsigned max_frames, int *frame_count);

struct vpn_urb_io {
    void *context;
    vpn_urb_submit submit;
    vpn_urb_reap reap;
    vpn_urb_discard discard;
};

enum vpn_urb_slot_state {
    VPN_URB_FREE = 0,
    VPN_URB_SUBMITTED = 1,
    VPN_URB_CONSUMING = 2
};

struct vpn_urb_slot {
    unsigned char buffer[VPN_URB_BUFFER_BYTES];
    enum vpn_urb_slot_state state;
};

struct vpn_urb_runner {
    struct vpn_urb_io io;
    struct vpn_urb_slot slots[VPN_URB_MAX_SLOTS];
    unsigned slot_count;
    unsigned in_flight;
    volatile int *running;
};

void vpn_urb_init(struct vpn_urb_runner *runner, struct vpn_urb_io io,
                  unsigned slot_count, volatile int *running);
int vpn_urb_reader_run(struct vpn_urb_runner *runner, vpn_urb_chunk on_chunk,
                       void *context);
int vpn_urb_writer_run(struct vpn_urb_runner *runner, unsigned pipeline_depth,
                       unsigned max_frames, vpn_urb_build build, void *context);

struct vpn_linux_urb_context;
#if defined(__linux__)
struct vpn_linux_urb_context *vpn_linux_urb_create(
    int fd, int in_endpoint, int out_endpoint, void *write_lock,
    uint16_t *sequence);
void vpn_linux_urb_destroy(struct vpn_linux_urb_context *context);
int vpn_linux_urb_reader(struct vpn_linux_urb_context *context,
                         unsigned slots, volatile int *running,
                         vpn_urb_chunk callback, void *callback_context);
int vpn_linux_urb_writer(struct vpn_linux_urb_context *context,
                         unsigned pipeline_depth, unsigned max_frames,
                         volatile int *running, vpn_urb_build build,
                         void *callback_context);
int vpn_linux_urb_sync_write(struct vpn_linux_urb_context *context,
                             const unsigned char *data, size_t length);
void vpn_linux_urb_snapshot(struct vpn_linux_urb_context *context,
                            unsigned long values[13]);
#endif

#endif

/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#define _GNU_SOURCE /* program_invocation_short_name, strndup */
#include <time.h>
#include <stdio.h>
#ifdef HAVE_PTHREADS
#include <pthread.h>
#endif
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <stdlib.h>
#include <stdarg.h>
#include <sys/types.h>
#include <sys/stat.h>

#include <log/logger.h>
#include <log/logd.h>
#include <log/log.h>

#define LOG_BUF_SIZE	1024

#if FAKE_LOG_DEVICE
// This will be defined when building for the host.
#define log_open(pathname, flags) fakeLogOpen(pathname, flags)
#define log_writev(filedes, vector, count) fakeLogWritev(filedes, vector, count)
#define log_close(filedes) fakeLogClose(filedes)
#else
#define log_open(pathname, flags) open(pathname, (flags) | O_CLOEXEC)
#define log_writev(filedes, vector, count) writev(filedes, vector, count)
#define log_close(filedes) close(filedes)
#endif

static int __write_to_log_init(log_id_t, struct iovec *vec, size_t nr);
static int (*write_to_log)(log_id_t, struct iovec *vec, size_t nr) = __write_to_log_init;
#ifdef HAVE_PTHREADS
static pthread_mutex_t log_init_lock = PTHREAD_MUTEX_INITIALIZER;
#endif

static int log_fds[(int)LOG_ID_MAX] = { -1, -1, -1, -1 };

/*
 * This is used by the C++ code to decide if it should write logs through
 * the C code.  Basically, if /dev/log/... is available, we're running in
 * the simulator rather than a desktop tool and want to use the device.
 */
static enum {
    kLogUninitialized, kLogNotAvailable, kLogAvailable
} g_log_status = kLogUninitialized;
int __android_log_dev_available(void)
{
    if (g_log_status == kLogUninitialized) {
        if (access("/dev/"LOGGER_LOG_MAIN, W_OK) == 0)
            g_log_status = kLogAvailable;
        else
            g_log_status = kLogNotAvailable;
    }

    return (g_log_status == kLogAvailable);
}

static int __write_to_log_null(log_id_t log_fd, struct iovec *vec, size_t nr)
{
    return -1;
}

static int __write_to_log_kernel(log_id_t log_id, struct iovec *vec, size_t nr)
{
    ssize_t ret;
    int log_fd;

    if (/*(int)log_id >= 0 &&*/ (int)log_id < (int)LOG_ID_MAX) {
        log_fd = log_fds[(int)log_id];
    } else {
        return EBADF;
    }

    do {
        ret = log_writev(log_fd, vec, nr);
    } while (ret < 0 && errno == EINTR);

    return ret;
}

static int __write_to_log_init(log_id_t log_id, struct iovec *vec, size_t nr)
{
#ifdef HAVE_PTHREADS
    pthread_mutex_lock(&log_init_lock);
#endif

    if (write_to_log == __write_to_log_init) {
        log_fds[LOG_ID_MAIN] = log_open("/dev/"LOGGER_LOG_MAIN, O_WRONLY);
        log_fds[LOG_ID_RADIO] = log_open("/dev/"LOGGER_LOG_RADIO, O_WRONLY);
        log_fds[LOG_ID_EVENTS] = log_open("/dev/"LOGGER_LOG_EVENTS, O_WRONLY);
        log_fds[LOG_ID_SYSTEM] = log_open("/dev/"LOGGER_LOG_SYSTEM, O_WRONLY);

        write_to_log = __write_to_log_kernel;

        if (log_fds[LOG_ID_MAIN] < 0 || log_fds[LOG_ID_RADIO] < 0 ||
                log_fds[LOG_ID_EVENTS] < 0) {
            log_close(log_fds[LOG_ID_MAIN]);
            log_close(log_fds[LOG_ID_RADIO]);
            log_close(log_fds[LOG_ID_EVENTS]);
            log_fds[LOG_ID_MAIN] = -1;
            log_fds[LOG_ID_RADIO] = -1;
            log_fds[LOG_ID_EVENTS] = -1;
            write_to_log = __write_to_log_null;
        }

        if (log_fds[LOG_ID_SYSTEM] < 0) {
            log_fds[LOG_ID_SYSTEM] = log_fds[LOG_ID_MAIN];
        }
    }

#ifdef HAVE_PTHREADS
    pthread_mutex_unlock(&log_init_lock);
#endif

    return write_to_log(log_id, vec, nr);
}

int __android_log_write(int prio, const char *tag, const char *msg)
{
    return __android_log_buf_write(LOG_ID_MAIN, prio, tag, msg);
}

/* --- the API level 30 logging API (AOSP system/logging/liblog/logger_write.cpp) --- */

#define LOG_ID_DEFAULT 0x7FFFFFFF
#define LOG_ID_CRASH 4

static char *default_tag;
static int32_t minimum_log_priority = ANDROID_LOG_DEFAULT;

void __android_log_set_default_tag(const char *tag)
{
    char *old = default_tag;
    default_tag = strndup(tag, LOGGER_ENTRY_MAX_PAYLOAD);
    free(old);
}

static const char *get_default_tag(void)
{
    return default_tag ? default_tag : program_invocation_short_name;
}

int32_t __android_log_set_minimum_priority(int32_t priority)
{
    return __atomic_exchange_n(&minimum_log_priority, priority, __ATOMIC_RELAXED);
}

int32_t __android_log_get_minimum_priority(void)
{
    return __atomic_load_n(&minimum_log_priority, __ATOMIC_RELAXED);
}

/* the logd logger: the message goes to the log device as before */
void __android_log_logd_logger(const struct __android_log_message *log_message)
{
    int buffer_id = log_message->buffer_id == LOG_ID_DEFAULT ? LOG_ID_MAIN : log_message->buffer_id;
    struct iovec vec[3];

    vec[0].iov_base   = (unsigned char *) &log_message->priority;
    vec[0].iov_len    = 1;
    vec[1].iov_base   = (void *) log_message->tag;
    vec[1].iov_len    = strlen(log_message->tag) + 1;
    vec[2].iov_base   = (void *) log_message->message;
    vec[2].iov_len    = strlen(log_message->message) + 1;

    write_to_log(buffer_id, vec, 3);
}

/* the stderr logger, with the line format of AOSP liblog's filestream_logger() */
void __android_log_stderr_logger(const struct __android_log_message *log_message)
{
    static const char log_characters[] = "XXVDIWEF";
    struct timespec ts;
    struct tm now;
    char timestamp[sizeof("mm-DD HH::MM::SS.mmm")];

    clock_gettime(CLOCK_REALTIME, &ts);
    localtime_r(&ts.tv_sec, &now);
    size_t n = strftime(timestamp, sizeof(timestamp), "%m-%d %H:%M:%S", &now);
    snprintf(timestamp + n, sizeof(timestamp) - n, ".%03ld", ts.tv_nsec / (1000 * 1000));
    int32_t priority = log_message->priority > ANDROID_LOG_SILENT ? ANDROID_LOG_FATAL : log_message->priority;
    const char *tag = log_message->tag ? log_message->tag : " nullptr";

    if (log_message->file != NULL)
        fprintf(stderr, "%s %5d %5d %c %-8s: %s:%u %s\n", timestamp, getpid(), gettid(),
                log_characters[priority], tag, log_message->file, log_message->line, log_message->message);
    else
        fprintf(stderr, "%s %5d %5d %c %-8s: %s\n", timestamp, getpid(), gettid(),
                log_characters[priority], tag, log_message->message);
}

static __android_logger_function logger_function = __android_log_logd_logger;

void __android_log_set_logger(__android_logger_function logger)
{
    logger_function = logger;
}

void __android_log_default_aborter(const char *abort_message)
{
    (void) abort_message;
    abort();
}

static __android_aborter_function aborter_function = __android_log_default_aborter;

void __android_log_set_aborter(__android_aborter_function aborter)
{
    aborter_function = aborter;
}

void __android_log_call_aborter(const char *abort_message)
{
    aborter_function(abort_message);
}

void __android_log_write_log_message(struct __android_log_message *log_message)
{
    int saved_errno = errno;

    if (log_message->buffer_id != LOG_ID_DEFAULT && log_message->buffer_id != LOG_ID_MAIN &&
        log_message->buffer_id != LOG_ID_SYSTEM && log_message->buffer_id != LOG_ID_RADIO &&
        log_message->buffer_id != LOG_ID_CRASH) {
        return;
    }

    if (log_message->tag == NULL)
        log_message->tag = get_default_tag();

    logger_function(log_message);
    errno = saved_errno;
}

int __android_log_buf_write(int bufID, int prio, const char *tag, const char *msg)
{
    char tmp_tag[32];

    if (!__android_log_is_loggable(prio, tag, ANDROID_LOG_VERBOSE))
        return -EPERM;

    /* XXX: This needs to go! */
    if (tag && (bufID != LOG_ID_RADIO) &&
         (!strcmp(tag, "HTC_RIL") ||
        !strncmp(tag, "RIL", 3) || /* Any log tag with "RIL" as the prefix */
        !strncmp(tag, "IMS", 3) || /* Any log tag with "IMS" as the prefix */
        !strcmp(tag, "AT") ||
        !strcmp(tag, "GSM") ||
        !strcmp(tag, "STK") ||
        !strcmp(tag, "CDMA") ||
        !strcmp(tag, "PHONE") ||
        !strcmp(tag, "SMS"))) {
            bufID = LOG_ID_RADIO;
            // Inform third party apps/ril/radio.. to use Rlog or RLOG
            snprintf(tmp_tag, sizeof(tmp_tag), "use-Rlog/RLOG-%s", tag);
            tag = tmp_tag;
    }

    struct __android_log_message log_message = {
        sizeof(struct __android_log_message), bufID, prio, tag, NULL, 0, msg};
    __android_log_write_log_message(&log_message);
    return 1;
}

int __android_log_vprint(int prio, const char *tag, const char *fmt, va_list ap)
{
    char buf[LOG_BUF_SIZE];

    /* As in AOSP liblog (logger_write.cpp): skip formatting for messages
     * that are dropped anyway. */
    if (!__android_log_is_loggable(prio, tag, ANDROID_LOG_VERBOSE))
        return -EPERM;

    vsnprintf(buf, LOG_BUF_SIZE, fmt, ap);

    return __android_log_write(prio, tag, buf);
}

int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
    va_list ap;
    char buf[LOG_BUF_SIZE];

    /* As in AOSP liblog (logger_write.cpp): skip formatting for messages
     * that are dropped anyway. */
    if (!__android_log_is_loggable(prio, tag, ANDROID_LOG_VERBOSE))
        return -EPERM;

    va_start(ap, fmt);
    vsnprintf(buf, LOG_BUF_SIZE, fmt, ap);
    va_end(ap);

    return __android_log_write(prio, tag, buf);
}

int __android_log_buf_print(int bufID, int prio, const char *tag, const char *fmt, ...)
{
    va_list ap;
    char buf[LOG_BUF_SIZE];

    /* As in AOSP liblog (logger_write.cpp): skip formatting for messages
     * that are dropped anyway. */
    if (!__android_log_is_loggable(prio, tag, ANDROID_LOG_VERBOSE))
        return -EPERM;

    va_start(ap, fmt);
    vsnprintf(buf, LOG_BUF_SIZE, fmt, ap);
    va_end(ap);

    return __android_log_buf_write(bufID, prio, tag, buf);
}

void __android_log_assert(const char *cond, const char *tag,
			  const char *fmt, ...)
{
    char buf[LOG_BUF_SIZE];

    if (fmt) {
        va_list ap;
        va_start(ap, fmt);
        vsnprintf(buf, LOG_BUF_SIZE, fmt, ap);
        va_end(ap);
    } else {
        /* Msg not provided, log condition.  N.B. Do not use cond directly as
         * format string as it could contain spurious '%' syntax (e.g.
         * "%d" in "blocks%devs == 0").
         */
        if (cond)
            snprintf(buf, LOG_BUF_SIZE, "Assertion failed: %s", cond);
        else
            strcpy(buf, "Unspecified assertion failed");
    }

    __android_log_write(ANDROID_LOG_FATAL, tag, buf);

    __builtin_trap(); /* trap so we have a chance to debug the situation */
}

int __android_log_bwrite(int32_t tag, const void *payload, size_t len)
{
    struct iovec vec[2];

    vec[0].iov_base = &tag;
    vec[0].iov_len = sizeof(tag);
    vec[1].iov_base = (void*)payload;
    vec[1].iov_len = len;

    return write_to_log(LOG_ID_EVENTS, vec, 2);
}

/*
 * Like __android_log_bwrite, but takes the type as well.  Doesn't work
 * for the general case where we're generating lists of stuff, but very
 * handy if we just want to dump an integer into the log.
 */
int __android_log_btwrite(int32_t tag, char type, const void *payload,
    size_t len)
{
    struct iovec vec[3];

    vec[0].iov_base = &tag;
    vec[0].iov_len = sizeof(tag);
    vec[1].iov_base = &type;
    vec[1].iov_len = sizeof(type);
    vec[2].iov_base = (void*)payload;
    vec[2].iov_len = len;

    return write_to_log(LOG_ID_EVENTS, vec, 3);
}

/* AOSP log_event_write.cpp: an event list (subTag, uid, data) in the events buffer */
#define MAX_SUBTAG_LEN 32

int __android_log_error_write(int tag, const char *subTag, int32_t uid, const char *data, uint32_t dataLen)
{
    if (!subTag || (!data && dataLen))
        return -EINVAL;

    size_t sub_len = strnlen(subTag, MAX_SUBTAG_LEN);
    size_t len = 2 + (1 + 4 + sub_len) + (1 + 4) + (1 + 4 + dataLen);
    char *buf = malloc(len);
    if (!buf)
        return -ENOMEM;

    char *p = buf;
    int32_t n;
    *p++ = EVENT_TYPE_LIST;
    *p++ = 3;
    *p++ = EVENT_TYPE_STRING;
    n = sub_len; memcpy(p, &n, 4); p += 4;
    memcpy(p, subTag, sub_len); p += sub_len;
    *p++ = EVENT_TYPE_INT;
    memcpy(p, &uid, 4); p += 4;
    *p++ = EVENT_TYPE_STRING;
    n = dataLen; memcpy(p, &n, 4); p += 4;
    if (dataLen)
        memcpy(p, data, dataLen);

    int ret = __android_log_bwrite(tag, buf, len);
    free(buf);
    return ret;
}

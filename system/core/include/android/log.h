/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef _ANDROID_LOG_H
#define _ANDROID_LOG_H

/******************************************************************
 *
 * IMPORTANT NOTICE:
 *
 *   This file is part of Android's set of stable system headers
 *   exposed by the Android NDK (Native Development Kit) since
 *   platform release 1.5
 *
 *   Third-party source AND binary code relies on the definitions
 *   here to be FROZEN ON ALL UPCOMING PLATFORM RELEASES.
 *
 *   - DO NOT MODIFY ENUMS (EXCEPT IF YOU ADD NEW 32-BIT VALUES)
 *   - DO NOT MODIFY CONSTANTS OR FUNCTIONAL MACROS
 *   - DO NOT CHANGE THE SIGNATURE OF FUNCTIONS IN ANY WAY
 *   - DO NOT CHANGE THE LAYOUT OR SIZE OF STRUCTURES
 */

/*
 * Support routines to send messages to the Android in-kernel log buffer,
 * which can later be accessed through the 'logcat' utility.
 *
 * Each log message must have
 *   - a priority
 *   - a log tag
 *   - some text
 *
 * The tag normally corresponds to the component that emits the log message,
 * and should be reasonably small.
 *
 * Log message text may be truncated to less than an implementation-specific
 * limit (e.g. 1023 characters max).
 *
 * Note that a newline character ("\n") will be appended automatically to your
 * log message, if not already there. It is not possible to send several messages
 * and have them appear on a single line in logcat.
 *
 * PLEASE USE LOGS WITH MODERATION:
 *
 *  - Sending log messages eats CPU and slow down your application and the
 *    system.
 *
 *  - The circular log buffer is pretty small (<64KB), sending many messages
 *    might push off other important log messages from the rest of the system.
 *
 *  - In release builds, only send log messages to account for exceptional
 *    conditions.
 *
 * NOTE: These functions MUST be implemented by /system/lib/liblog.so
 */

#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Android log priority values, in ascending priority order.
 */
typedef enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,    /* only for SetMinPriority() */
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,     /* only for SetMinPriority(); must be last */
} android_LogPriority;

/*
 * Send a simple string to the log.
 */
int __android_log_write(int prio, const char *tag, const char *text);

/*
 * Check whether a message at the given priority for the given tag should be logged.
 */
int __android_log_is_loggable(int prio, const char *tag, int default_prio);
int __android_log_is_loggable_len(int prio, const char *tag, size_t len, int default_prio);

/*
 * Send a formatted string to the log, used like printf(fmt,...)
 */
int __android_log_print(int prio, const char *tag,  const char *fmt, ...)
#if defined(__GNUC__)
    __attribute__ ((format(printf, 3, 4)))
#endif
    ;

/*
 * A variant of __android_log_print() that takes a va_list to list
 * additional parameters.
 */
int __android_log_vprint(int prio, const char *tag,
                         const char *fmt, va_list ap);

/*
 * Log an assertion failure and SIGTRAP the process to have a chance
 * to inspect it, if a debugger is attached. This uses the FATAL priority.
 */
void __android_log_assert(const char *cond, const char *tag,
			  const char *fmt, ...)    
#if defined(__GNUC__)
    __attribute__ ((noreturn))
    __attribute__ ((format(printf, 3, 4)))
#endif
    ;

/*
 * The logging API of API level 30 (AOSP system/logging/liblog/include/android/log.h): log messages
 * as a struct, a replaceable logger and aborter, a default tag and a process-wide minimum priority.
 */
struct __android_log_message {
    size_t struct_size;   /* sizeof(struct __android_log_message) */
    int32_t buffer_id;    /* log_id_t, or LOG_ID_DEFAULT */
    int32_t priority;     /* android_LogPriority */
    const char *tag;
    const char *file;
    uint32_t line;
    const char *message;
};

typedef void (*__android_logger_function)(const struct __android_log_message *log_message);
typedef void (*__android_aborter_function)(const char *abort_message);

void __android_log_write_log_message(struct __android_log_message *log_message);
void __android_log_set_logger(__android_logger_function logger);
void __android_log_logd_logger(const struct __android_log_message *log_message);
void __android_log_stderr_logger(const struct __android_log_message *log_message);
void __android_log_set_aborter(__android_aborter_function aborter);
void __android_log_call_aborter(const char *abort_message);
void __android_log_default_aborter(const char *abort_message)
#if defined(__GNUC__)
    __attribute__ ((noreturn))
#endif
    ;
int32_t __android_log_set_minimum_priority(int32_t priority);
int32_t __android_log_get_minimum_priority(void);
void __android_log_set_default_tag(const char *tag);

#ifdef __cplusplus
}
#endif

#endif /* _ANDROID_LOG_H */

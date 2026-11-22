/*
 * Copyright (C) 2008 The Android Open Source Project
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

/*
 * The user-space ashmem API on a kernel without ashmem, as AOSP 16's libcutils (ashmem-dev.cpp) does with memfd:
 * a memfd sealed against size changes, the protection mask as F_SEAL_FUTURE_WRITE. Apps check these fds the same
 * way (Chromium's base/android/linker/ashmem.cc reads the seals for the protection flags).
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#include <cutils/ashmem.h>
#include <utils/Compat.h>

#ifndef __unused
#define __unused __attribute__((__unused__))
#endif

static bool is_memfd_fd(int fd)
{
    char fd_path[32], result[16];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    ssize_t len = readlink(fd_path, result, sizeof(result));
    return len >= 7 && !strncmp(result, "/memfd:", 7);
}

int ashmem_valid(int fd)
{
    return is_memfd_fd(fd);
}

int ashmem_create_region(const char *name, size_t size)
{
    if (name == NULL)
        name = "none";
    int fd = syscall(__NR_memfd_create, name, MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd == -1)
        return -1;
    /* forbid size changes to match ashmem behaviour */
    if (ftruncate(fd, size) == -1 || fcntl(fd, F_ADD_SEALS, F_SEAL_GROW | F_SEAL_SHRINK) == -1) {
        close(fd);
        return -1;
    }
    return fd;
}

int ashmem_set_prot_region(int fd, int prot)
{
    int seals = fcntl(fd, F_GET_SEALS);
    if (seals == -1)
        return -1;
    if (prot & PROT_WRITE) {
        /* read-write again after read-only is refused, with ashmem's error code */
        if (seals & F_SEAL_FUTURE_WRITE) {
            errno = EINVAL;
            return -1;
        }
        return 0;
    }
    /* only read-only for any future file operations */
    return fcntl(fd, F_ADD_SEALS, F_SEAL_FUTURE_WRITE) == -1 ? -1 : 0;
}

/* pinning is deprecated since Android Q, a memfd is never purged */
int ashmem_pin_region(int fd __unused, size_t offset __unused, size_t len __unused)
{
    return 0;
}

int ashmem_unpin_region(int fd __unused, size_t offset __unused, size_t len __unused)
{
    return 0;
}

int ashmem_get_size_region(int fd)
{
    struct stat buf;
    if (!is_memfd_fd(fd)) {
        errno = ENOTTY;
        return -1;
    }
    if (fstat(fd, &buf) == -1)
        return -1;
    return buf.st_size;
}

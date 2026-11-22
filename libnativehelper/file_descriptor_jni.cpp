/*
 * Copyright (C) 2020 The Android Open Source Project
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

// The NDK's java.io.FileDescriptor API (android/file_descriptor_jni.h, API level 31), as AOSP's
// libnativehelper/file_descriptor_jni.c implements it.

#define LOG_TAG "file_descriptor_jni"

#include <jni.h>

#include <log/log.h>
#include "JniConstants.h"

static void EnsureArgumentIsFileDescriptor(JNIEnv* env, jobject instance) {
    LOG_ALWAYS_FATAL_IF(instance == nullptr, "FileDescriptor is NULL");
    JniConstants::init(env);
    LOG_ALWAYS_FATAL_IF(!env->IsInstanceOf(instance, JniConstants::fileDescriptorClass),
                         "Argument is not a FileDescriptor");
}

extern "C" {

JNIEXPORT jobject AFileDescriptor_create(JNIEnv* env) {
    JniConstants::init(env);
    static jmethodID ctor = env->GetMethodID(JniConstants::fileDescriptorClass, "<init>", "()V");
    return env->NewObject(JniConstants::fileDescriptorClass, ctor);
}

JNIEXPORT int AFileDescriptor_getFd(JNIEnv* env, jobject fileDescriptor) {
    EnsureArgumentIsFileDescriptor(env, fileDescriptor);
    static jfieldID descriptor = env->GetFieldID(JniConstants::fileDescriptorClass, "descriptor", "I");
    return env->GetIntField(fileDescriptor, descriptor);
}

JNIEXPORT void AFileDescriptor_setFd(JNIEnv* env, jobject fileDescriptor, int fd) {
    EnsureArgumentIsFileDescriptor(env, fileDescriptor);
    static jmethodID setInt = env->GetMethodID(JniConstants::fileDescriptorClass, "setInt$", "(I)V");
    env->CallVoidMethod(fileDescriptor, setInt, fd);
}

}  // extern "C"

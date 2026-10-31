LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := twclock
LOCAL_SRC_FILES := twclock.c
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

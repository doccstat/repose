#include <jni.h>
#include <sys/timerfd.h>
#include <unistd.h>
#include <stdint.h>
#include <errno.h>
#include <stdio.h>

static void failed(JNIEnv *env) {
    int code = errno;
    char message[64];
    snprintf(message, sizeof(message), "wake_alarm_errno_%d", code);
    jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
    (*env)->ThrowNew(env, error, message);
}
JNIEXPORT jint JNICALL Java_com_lixingchi_repose_Daemon_alarmOpen(JNIEnv *env, jclass cls) {
    (void)cls;
    int fd = timerfd_create(CLOCK_BOOTTIME_ALARM, TFD_CLOEXEC);
    if (fd < 0) failed(env);
    return fd;
}
JNIEXPORT void JNICALL Java_com_lixingchi_repose_Daemon_alarmSet(JNIEnv *env, jclass cls, jint fd, jlong millis) {
    (void)cls;
    struct itimerspec timer = {0};
    if (millis >= 0) {
        timer.it_value.tv_sec = millis / 1000;
        timer.it_value.tv_nsec = (millis % 1000) * 1000000;
    }
    if (timerfd_settime(fd, TFD_TIMER_ABSTIME, &timer, NULL) < 0) failed(env);
}
JNIEXPORT void JNICALL Java_com_lixingchi_repose_Daemon_alarmWait(JNIEnv *env, jclass cls, jint fd) {
    (void)cls;
    uint64_t count;
    ssize_t size;
    do { size = read(fd, &count, sizeof(count)); } while (size < 0 && errno == EINTR);
    if (size != sizeof(count)) failed(env);
}

#include <jni.h>
#include <sys/timerfd.h>
#include <unistd.h>
#include <stdint.h>
#include <errno.h>
#include <assert.h>
#include <string.h>

static int failure, thrown, reads;
static struct itimerspec programmed;
static int mock_create(int clock, int flags) {
    assert(clock == CLOCK_BOOTTIME_ALARM && flags == TFD_CLOEXEC);
    if (failure) { errno = EPERM; return -1; }
    return 9;
}
static int mock_set(int fd, int flags, const struct itimerspec *timer, struct itimerspec *previous) {
    assert(fd == 9 && flags == TFD_TIMER_ABSTIME && previous == NULL);
    programmed = *timer;
    if (failure) { errno = EINVAL; return -1; }
    return 0;
}
static ssize_t mock_read(int fd, void *buffer, size_t size) {
    assert(fd == 9 && size == sizeof(uint64_t));
    if (reads++ == 0) { errno = EINTR; return -1; }
    if (failure) { errno = EBADF; return -1; }
    *(uint64_t *)buffer = 1;
    return sizeof(uint64_t);
}
static jclass find_class(JNIEnv *env, const char *name) {
    (void)env; assert(!strcmp(name, "java/lang/IllegalStateException")); return (jclass)1;
}
static jint throw_new(JNIEnv *env, jclass type, const char *message) {
    (void)env; (void)type; assert(!strncmp(message, "wake_alarm_errno_", 17)); thrown++; return 0;
}
#define timerfd_create mock_create
#define timerfd_settime mock_set
#define read mock_read
#include "../../native/alarm.c"

int main(void) {
    const struct JNINativeInterface_ table = {.FindClass = find_class, .ThrowNew = throw_new};
    JNIEnv env = &table;
    assert(Java_com_lixingchi_repose_Daemon_alarmOpen(&env, NULL) == 9);
    Java_com_lixingchi_repose_Daemon_alarmSet(&env, NULL, 9, 12345);
    assert(programmed.it_value.tv_sec == 12 && programmed.it_value.tv_nsec == 345000000);
    assert(programmed.it_interval.tv_sec == 0);
    Java_com_lixingchi_repose_Daemon_alarmSet(&env, NULL, 9, -1);
    assert(programmed.it_value.tv_sec == 0 && programmed.it_value.tv_nsec == 0);
    Java_com_lixingchi_repose_Daemon_alarmWait(&env, NULL, 9);
    assert(reads == 2 && thrown == 0);
    failure = 1;
    assert(Java_com_lixingchi_repose_Daemon_alarmOpen(&env, NULL) == -1);
    Java_com_lixingchi_repose_Daemon_alarmSet(&env, NULL, 9, 1000);
    Java_com_lixingchi_repose_Daemon_alarmWait(&env, NULL, 9);
    assert(thrown == 3);
}

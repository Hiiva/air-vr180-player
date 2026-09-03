#include "tracking_filter.hpp"
#include <jni.h>
#include <new>

extern "C" JNIEXPORT jlong JNICALL
Java_com_enricoros_nreal_player_HeadTracker_nativeCreate(JNIEnv* env, jclass) {
    auto* filter = new (std::nothrow) TrackingFilter();
    if (!filter) env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Head tracker allocation failed");
    return reinterpret_cast<jlong>(filter);
}

extern "C" JNIEXPORT void JNICALL
Java_com_enricoros_nreal_player_HeadTracker_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<TrackingFilter*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_enricoros_nreal_player_HeadTracker_nativeUpdate(JNIEnv* env, jclass, jlong handle,
        jlong timestamp, jfloat gx, jfloat gy, jfloat gz, jfloat ax, jfloat ay, jfloat az,
        jboolean freshMagnetic, jfloat mx, jfloat my, jfloat mz, jdoubleArray output) {
    auto* filter = reinterpret_cast<TrackingFilter*>(handle);
    double gyro[3] = {gx, gy, gz}, accel[3] = {ax, ay, az}, q[4];
    double magnetic[3] = {mx, my, mz};
    filter->update(timestamp, gyro, accel, freshMagnetic ? magnetic : nullptr);
    filter->quaternion(q);
    env->SetDoubleArrayRegion(output, 0, 4, q);
}

extern "C" JNIEXPORT void JNICALL
Java_com_enricoros_nreal_player_HeadTracker_nativeDiagnostics(JNIEnv* env, jclass, jlong handle,
        jdoubleArray output) {
    double values[6];
    reinterpret_cast<TrackingFilter*>(handle)->diagnostics(values);
    env->SetDoubleArrayRegion(output, 0, 6, values);
}

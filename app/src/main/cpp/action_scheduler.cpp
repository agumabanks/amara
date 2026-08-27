#include <jni.h>
#include <algorithm>

extern "C" JNIEXPORT jlong JNICALL
Java_co_sanaa_agent_core_NativeBridge_nextScheduledDelay(JNIEnv *, jobject, jlong now_ms, jlong target_ms) {
    return std::max<jlong>(0, target_ms - now_ms);
}

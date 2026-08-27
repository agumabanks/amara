#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_co_sanaa_agent_core_NativeBridge_parseMessage(JNIEnv *env, jobject, jstring raw_tree) {
    if (raw_tree == nullptr) return env->NewStringUTF("");
    const char *raw = env->GetStringUTFChars(raw_tree, nullptr);
    std::string parsed(raw);
    env->ReleaseStringUTFChars(raw_tree, raw);
    return env->NewStringUTF(parsed.c_str());
}

#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_advancedcamera_imaging_NativeImaging_version(JNIEnv* env, jobject /* this */) {
    std::string v = "imaging-core 0.1 (stubs)";
    return env->NewStringUTF(v.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_advancedcamera_imaging_NativeImaging_processHdr(JNIEnv* env, jobject /* this */) {
    // Stub: no-op
}

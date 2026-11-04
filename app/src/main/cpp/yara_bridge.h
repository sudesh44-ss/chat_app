#ifndef NEXA_YARA_BRIDGE_H
#define NEXA_YARA_BRIDGE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Class:     com_nexa_app_core_security_YaraNativeBridge
 * Method:    nativeInit
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_com_nexa_app_core_security_YaraNativeBridge_nativeInit
  (JNIEnv *, jobject);

/*
 * Class:     com_nexa_app_core_security_YaraNativeBridge
 * Method:    nativeLoadRules
 * Signature: (Ljava/lang/String;)Z
 */
JNIEXPORT jboolean JNICALL Java_com_nexa_app_core_security_YaraNativeBridge_nativeLoadRules
  (JNIEnv *, jobject, jstring);

/*
 * Class:     com_nexa_app_core_security_YaraNativeBridge
 * Method:    nativeScanFile
 * Signature: (Ljava/lang/String;)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_com_nexa_app_core_security_YaraNativeBridge_nativeScanFile
  (JNIEnv *, jobject, jstring);

/*
 * Class:     com_nexa_app_core_security_YaraNativeBridge
 * Method:    nativeScanBuffer
 * Signature: ([BI)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_com_nexa_app_core_security_YaraNativeBridge_nativeScanBuffer
  (JNIEnv *, jobject, jbyteArray, jint);

#ifdef __cplusplus
}
#endif

#endif // NEXA_YARA_BRIDGE_H

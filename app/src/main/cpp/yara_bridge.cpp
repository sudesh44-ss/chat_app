#include "yara_bridge.h"
#include <string>
#include <vector>
#include <cstring>

// Note: When building with full LibYARA, include <yara.h>
// Here we provide complete JNI callback structure and YARA integration wrappers

struct YaraScanContext {
    std::string matchedRule;
    bool threatFound;
};

#ifdef USE_LIBYARA
#include <yara.h>
static YR_RULES* g_rules = nullptr;

static int yaraCallback(YR_SCAN_CONTEXT* context, int message, void* message_data, void* user_data) {
    if (message == CALLBACK_MSG_RULE_MATCHING) {
        YR_RULE* rule = (YR_RULE*) message_data;
        YaraScanContext* ctx = (YaraScanContext*) user_data;
        ctx->matchedRule = rule->identifier;
        ctx->threatFound = true;
        return CALLBACK_ABORT; // Stop after first match for speed
    }
    return CALLBACK_CONTINUE;
}
#endif

JNIEXPORT jboolean JNICALL Java_com_nexa_app_core_security_YaraNativeBridge_nativeInit
  (JNIEnv *env, jobject thiz) {
#ifdef USE_LIBYARA
    int res = yr_initialize();
    return (res == ERROR_SUCCESS) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_TRUE;
#endif
}

JNIEXPORT jboolean JNICALL Java_com_nexa_app_core_security_YaraNativeBridge_nativeLoadRules
  (JNIEnv *env, jobject thiz, jstring rulesPath) {
    if (!rulesPath) return JNI_FALSE;
    const char *nativePath = env->GetStringUTFChars(rulesPath, 0);

#ifdef USE_LIBYARA
    if (g_rules != nullptr) {
        yr_rules_destroy(g_rules);
        g_rules = nullptr;
    }
    int res = yr_rules_load(nativePath, &g_rules);
    env->ReleaseStringUTFChars(rulesPath, nativePath);
    return (res == ERROR_SUCCESS) ? JNI_TRUE : JNI_FALSE;
#else
    env->ReleaseStringUTFChars(rulesPath, nativePath);
    return JNI_TRUE;
#endif
}

JNIEXPORT jstring JNICALL Java_com_nexa_app_core_security_YaraNativeBridge_nativeScanFile
  (JNIEnv *env, jobject thiz, jstring filePath) {
    if (!filePath) return nullptr;
    const char *nativePath = env->GetStringUTFChars(filePath, 0);

#ifdef USE_LIBYARA
    if (!g_rules) {
        env->ReleaseStringUTFChars(filePath, nativePath);
        return nullptr;
    }
    YaraScanContext ctx{"", false};
    int res = yr_rules_scan_file(g_rules, nativePath, SCAN_FLAGS_FAST_MODE, yaraCallback, &ctx, 0);
    env->ReleaseStringUTFChars(filePath, nativePath);

    if (ctx.threatFound) {
        return env->NewStringUTF(ctx.matchedRule.c_str());
    }
    return nullptr;
#else
    env->ReleaseStringUTFChars(filePath, nativePath);
    return nullptr; // Delegates to Kotlin heuristic scanner fallback
#endif
}

JNIEXPORT jstring JNICALL Java_com_nexa_app_core_security_YaraNativeBridge_nativeScanBuffer
  (JNIEnv *env, jobject thiz, jbyteArray buffer, jint length) {
    if (!buffer || length <= 0) return nullptr;
    jbyte *bytes = env->GetByteArrayElements(buffer, nullptr);

#ifdef USE_LIBYARA
    if (!g_rules) {
        env->ReleaseByteArrayElements(buffer, bytes, JNI_ABORT);
        return nullptr;
    }
    YaraScanContext ctx{"", false};
    int res = yr_rules_scan_mem(g_rules, (const uint8_t*) bytes, (size_t) length, SCAN_FLAGS_FAST_MODE, yaraCallback, &ctx, 0);
    env->ReleaseByteArrayElements(buffer, bytes, JNI_ABORT);

    if (ctx.threatFound) {
        return env->NewStringUTF(ctx.matchedRule.c_str());
    }
    return nullptr;
#else
    env->ReleaseByteArrayElements(buffer, bytes, JNI_ABORT);
    return nullptr;
#endif
}

// JNI shim over libprojectM's C API. Every call happens on the GL thread
// (GLSurfaceView's renderer), with the context current.

#include <jni.h>
#include <android/log.h>
#include <projectM-4/projectM.h>

#define TAG "Milkdrop"

namespace {

struct Vis {
    projectm_handle pm;
    int events; // bit 0: switch requested, bit 1: last load failed, bit 2: the switch is a hard cut
};

void onSwitchRequested(bool hardCut, void* user) {
    static_cast<Vis*>(user)->events |= hardCut ? (1 | 4) : 1;
}

void onSwitchFailed(const char* file, const char* message, void* user) {
    __android_log_print(ANDROID_LOG_WARN, TAG, "Preset failed (%s): %s", file ? file : "data", message ? message : "");
    static_cast<Vis*>(user)->events |= 2;
}

Vis* vis(jlong h) { return reinterpret_cast<Vis*>(h); }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_eu_bradshaw_seidr_vis_ProjectM_nativeCreate(JNIEnv* env, jclass, jstring textures, jint w, jint h) {
    projectm_handle pm = projectm_create();
    if (!pm) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "projectm_create failed");
        return 0;
    }
    auto* v = new Vis{pm, 0};
    const char* path = env->GetStringUTFChars(textures, nullptr);
    const char* paths[] = {path};
    projectm_set_texture_search_paths(pm, paths, 1);
    env->ReleaseStringUTFChars(textures, path);

    projectm_set_window_size(pm, w, h);
    projectm_set_fps(pm, 60);
    projectm_set_aspect_correction(pm, true);
    // Kotlin picks the next preset when asked; projectM's own timer only asks.
    projectm_set_preset_switch_requested_event_callback(pm, onSwitchRequested, v);
    projectm_set_preset_switch_failed_event_callback(pm, onSwitchFailed, v);
    return reinterpret_cast<jlong>(v);
}

JNIEXPORT void JNICALL
Java_eu_bradshaw_seidr_vis_ProjectM_nativeDestroy(JNIEnv*, jclass, jlong h) {
    if (!h) return;
    projectm_destroy(vis(h)->pm);
    delete vis(h);
}

JNIEXPORT void JNICALL
Java_eu_bradshaw_seidr_vis_ProjectM_nativeResize(JNIEnv*, jclass, jlong h, jint w, jint hh) {
    projectm_set_window_size(vis(h)->pm, w, hh);
}

JNIEXPORT void JNICALL
Java_eu_bradshaw_seidr_vis_ProjectM_nativeConfigure(JNIEnv*, jclass, jlong h,
                                                      jdouble presetSeconds, jdouble blendSeconds,
                                                      jint meshW, jint meshH, jboolean hardCuts) {
    auto pm = vis(h)->pm;
    projectm_set_preset_duration(pm, presetSeconds);
    // Hard cuts: on a big beat, cut straight to the next preset (no sooner than 20 s in).
    projectm_set_hard_cut_enabled(pm, hardCuts);
    projectm_set_soft_cut_duration(pm, blendSeconds);
    if (meshW > 0 && meshH > 0) projectm_set_mesh_size(pm, meshW, meshH);
}

JNIEXPORT void JNICALL
Java_eu_bradshaw_seidr_vis_ProjectM_nativeLoadPreset(JNIEnv* env, jclass, jlong h, jstring data, jboolean smooth) {
    const char* text = env->GetStringUTFChars(data, nullptr);
    projectm_load_preset_data(vis(h)->pm, text, smooth);
    env->ReleaseStringUTFChars(data, text);
}

JNIEXPORT void JNICALL
Java_eu_bradshaw_seidr_vis_ProjectM_nativeAddPcm(JNIEnv* env, jclass, jlong h, jfloatArray samples, jint frames) {
    if (frames <= 0) return;
    auto* p = static_cast<float*>(env->GetPrimitiveArrayCritical(samples, nullptr));
    projectm_pcm_add_float(vis(h)->pm, p, static_cast<unsigned>(frames), PROJECTM_STEREO);
    env->ReleasePrimitiveArrayCritical(samples, p, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_eu_bradshaw_seidr_vis_ProjectM_nativeRender(JNIEnv*, jclass, jlong h) {
    projectm_opengl_render_frame(vis(h)->pm);
    int events = vis(h)->events;
    vis(h)->events = 0;
    return events;
}

} // extern "C"

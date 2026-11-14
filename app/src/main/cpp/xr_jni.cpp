// OpenXR for the headset visualiser (XrVisualiserActivity): a session whose
// only layer is part of a sphere around the viewer (equirect2; a cylinder
// where the runtime lacks it), showing projectM's frames.
//
// projectM always draws to the default framebuffer, so the context's surface
// is an offscreen pbuffer at the largest render size. present() copies the
// frame from it into a texture, then draws that into the swapchain image,
// upscaled from whatever share the tuner rendered at. Every call happens on
// the activity's render thread, which holds the EGL context.

#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <cstring>
#include <vector>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#define TAG "MilkdropXr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Kotlin's Xr.INPUT_* bits.
constexpr int PLAY_PAUSE = 1, NEXT = 2, PREVIOUS = 4, EXIT = 8, NEXT_PRESET = 16;
// nativeBeginFrame's results other than a display time.
constexpr jlong IDLE = -1, QUIT = -2;

// The sphere: centred on the viewer (LOCAL space starts at the head), curved
// both ways, not quite half a circle wide. The image's aspect must
// match the angles' (WIDE / (UP + DOWN)), or it stretches. At infinity, so
// both eyes look straight ahead, parallel and at rest (no stereo depth).
constexpr float RADIUS = INFINITY;
constexpr float DEG = M_PI / 180;
constexpr float WIDE = 150 * DEG, UP = 50 * DEG, DOWN = 50 * DEG;
// The cylinder fallback: as wide, also at infinity.
constexpr float CYLINDER_RADIUS = RADIUS;

struct Xr {
    JavaVM* vm = nullptr;
    jobject activity = nullptr; // global ref, for the loader and the instance

    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface pbuffer = EGL_NO_SURFACE;
    int width = 0, height = 0; // largest render size: the pbuffer, the swapchain and the copy

    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE;
    XrSwapchain swapchain = XR_NULL_HANDLE;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    bool hasImage = false; // a frame has been released to the swapchain
    bool refreshRateExt = false;
    bool sphere = false; // equirect2, else a cylinder

    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    bool running = false;
    bool quit = false;
    XrFrameState frame{XR_TYPE_FRAME_STATE};

    GLuint copy = 0, fbo = 0, program = 0, vao = 0;
    GLint scaleUniform = -1;

    XrActionSet actions = XR_NULL_HANDLE;
    XrAction playPause = XR_NULL_HANDLE, exit = XR_NULL_HANDLE, skip = XR_NULL_HANDLE, preset = XR_NULL_HANDLE;
    bool skipArmed = true;
    int input = 0; // INPUT bits since the last nativeInput
};

Xr* xr(jlong h) { return reinterpret_cast<Xr*>(h); }

bool ok(XrResult r, const char* what) {
    if (XR_SUCCEEDED(r)) return true;
    LOGE("%s failed: %d", what, r);
    return false;
}

XrPath path(Xr* x, const char* s) {
    XrPath p = XR_NULL_PATH;
    xrStringToPath(x->instance, s, &p);
    return p;
}

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint good = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &good);
    if (!good) {
        char log[512];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        LOGE("shader: %s", log);
    }
    return s;
}

// One triangle covering the target. projectM's bytes are what a screen should
// show (sRGB), so they're decoded to linear here: an sRGB swapchain encodes
// them straight back, and a linear one is read as linear by the compositor.
const char* VERTEX = R"(#version 300 es
uniform vec2 scale;
out vec2 uv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    uv = p * scale;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";
const char* FRAGMENT = R"(#version 300 es
precision highp float;
uniform sampler2D frame;
in vec2 uv;
out vec4 color;
void main() {
    vec3 c = texture(frame, uv).rgb;
    color = vec4(mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)), 1.0);
})";

bool createEgl(Xr* x) {
    x->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(x->display, nullptr, nullptr)) return false;
    const EGLint attribs[] = {
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 0,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_NONE,
    };
    EGLint n = 0;
    if (!eglChooseConfig(x->display, attribs, &x->config, 1, &n) || n < 1) return false;
    const EGLint ctx[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
    x->context = eglCreateContext(x->display, x->config, EGL_NO_CONTEXT, ctx);
    if (x->context == EGL_NO_CONTEXT) return false;
    const EGLint surf[] = {EGL_WIDTH, x->width, EGL_HEIGHT, x->height, EGL_NONE};
    x->pbuffer = eglCreatePbufferSurface(x->display, x->config, surf);
    if (x->pbuffer == EGL_NO_SURFACE) return false;
    return eglMakeCurrent(x->display, x->pbuffer, x->pbuffer, x->context);
}

void createGl(Xr* x) {
    glGenTextures(1, &x->copy);
    glBindTexture(GL_TEXTURE_2D, x->copy);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, x->width, x->height);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);
    glGenFramebuffers(1, &x->fbo);
    glGenVertexArrays(1, &x->vao);
    x->program = glCreateProgram();
    GLuint vs = compile(GL_VERTEX_SHADER, VERTEX), fs = compile(GL_FRAGMENT_SHADER, FRAGMENT);
    glAttachShader(x->program, vs);
    glAttachShader(x->program, fs);
    glLinkProgram(x->program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    x->scaleUniform = glGetUniformLocation(x->program, "scale");
}

bool hasExtension(const std::vector<XrExtensionProperties>& all, const char* name) {
    for (auto& e : all) if (!strcmp(e.extensionName, name)) return true;
    return false;
}

bool createInstance(Xr* x) {
    PFN_xrInitializeLoaderKHR init = nullptr;
    xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", reinterpret_cast<PFN_xrVoidFunction*>(&init));
    if (!init) { LOGE("no xrInitializeLoaderKHR"); return false; }
    XrLoaderInitInfoAndroidKHR loader{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    loader.applicationVM = x->vm;
    loader.applicationContext = x->activity;
    if (!ok(init(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&loader)), "xrInitializeLoaderKHR")) return false;

    uint32_t count = 0;
    if (!ok(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr), "extensions")) return false;
    std::vector<XrExtensionProperties> all(count, {XR_TYPE_EXTENSION_PROPERTIES});
    xrEnumerateInstanceExtensionProperties(nullptr, count, &count, all.data());
    std::vector<const char*> wanted = {
        XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
        XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME,
    };
    for (auto* name : wanted) {
        if (!hasExtension(all, name)) { LOGE("runtime lacks %s", name); return false; }
    }
    x->refreshRateExt = hasExtension(all, XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
    if (x->refreshRateExt) wanted.push_back(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
    x->sphere = hasExtension(all, XR_KHR_COMPOSITION_LAYER_EQUIRECT2_EXTENSION_NAME);
    if (x->sphere) wanted.push_back(XR_KHR_COMPOSITION_LAYER_EQUIRECT2_EXTENSION_NAME);
    LOGI("layer: %s", x->sphere ? "sphere (equirect2)" : "cylinder");

    XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    android.applicationVM = x->vm;
    android.applicationActivity = x->activity;
    XrInstanceCreateInfo info{XR_TYPE_INSTANCE_CREATE_INFO};
    info.next = &android;
    strncpy(info.applicationInfo.applicationName, "Seidr", XR_MAX_APPLICATION_NAME_SIZE - 1);
    info.applicationInfo.applicationVersion = 1;
    strncpy(info.applicationInfo.engineName, "projectM", XR_MAX_ENGINE_NAME_SIZE - 1);
    info.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    info.enabledExtensionCount = static_cast<uint32_t>(wanted.size());
    info.enabledExtensionNames = wanted.data();
    if (!ok(xrCreateInstance(&info, &x->instance), "xrCreateInstance")) return false;

    XrSystemGetInfo sys{XR_TYPE_SYSTEM_GET_INFO};
    sys.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    return ok(xrGetSystem(x->instance, &sys, &x->system), "xrGetSystem");
}

bool createSession(Xr* x) {
    // Required before a session, whatever it says.
    PFN_xrGetOpenGLESGraphicsRequirementsKHR reqs = nullptr;
    xrGetInstanceProcAddr(x->instance, "xrGetOpenGLESGraphicsRequirementsKHR", reinterpret_cast<PFN_xrVoidFunction*>(&reqs));
    XrGraphicsRequirementsOpenGLESKHR r{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    if (!reqs || !ok(reqs(x->instance, x->system, &r), "graphics requirements")) return false;

    XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    binding.display = x->display;
    binding.config = x->config;
    binding.context = x->context;
    XrSessionCreateInfo info{XR_TYPE_SESSION_CREATE_INFO};
    info.next = &binding;
    info.systemId = x->system;
    if (!ok(xrCreateSession(x->instance, &info, &x->session), "xrCreateSession")) return false;

    XrReferenceSpaceCreateInfo space{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    space.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    space.poseInReferenceSpace.orientation.w = 1;
    return ok(xrCreateReferenceSpace(x->session, &space, &x->space), "xrCreateReferenceSpace");
}

bool createSwapchain(Xr* x) {
    uint32_t count = 0;
    xrEnumerateSwapchainFormats(x->session, 0, &count, nullptr);
    std::vector<int64_t> formats(count);
    xrEnumerateSwapchainFormats(x->session, count, &count, formats.data());
    int64_t format = 0;
    for (int64_t want : {static_cast<int64_t>(GL_SRGB8_ALPHA8), static_cast<int64_t>(GL_RGBA8)}) {
        for (auto f : formats) if (f == want && !format) format = f;
    }
    if (!format) { LOGE("no usable swapchain format"); return false; }

    XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
    info.format = format;
    info.sampleCount = 1;
    info.width = x->width;
    info.height = x->height;
    info.faceCount = 1;
    info.arraySize = 1;
    info.mipCount = 1;
    if (!ok(xrCreateSwapchain(x->session, &info, &x->swapchain), "xrCreateSwapchain")) return false;
    xrEnumerateSwapchainImages(x->swapchain, 0, &count, nullptr);
    x->images.assign(count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
    xrEnumerateSwapchainImages(x->swapchain, count, &count,
                               reinterpret_cast<XrSwapchainImageBaseHeader*>(x->images.data()));
    LOGI("swapchain %dx%d, %s, %u images", x->width, x->height, format == GL_SRGB8_ALPHA8 ? "sRGB" : "linear", count);
    return true;
}

// Controllers: A/X or a trigger plays/pauses, B/Y quits, either thumbstick
// skips, a grip moves on to the next preset. Hands (as the simple controller): a pinch plays/pauses, the left
// hand's menu gesture quits.
bool createActions(Xr* x) {
    XrActionSetCreateInfo set{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(set.actionSetName, "visualiser");
    strcpy(set.localizedActionSetName, "Visualiser");
    if (!ok(xrCreateActionSet(x->instance, &set, &x->actions), "xrCreateActionSet")) return false;
    auto action = [&](const char* name, const char* label, XrActionType type, XrAction* out) {
        XrActionCreateInfo a{XR_TYPE_ACTION_CREATE_INFO};
        a.actionType = type;
        strcpy(a.actionName, name);
        strcpy(a.localizedActionName, label);
        return ok(xrCreateAction(x->actions, &a, out), name);
    };
    if (!action("play_pause", "Play or pause", XR_ACTION_TYPE_BOOLEAN_INPUT, &x->playPause)) return false;
    if (!action("exit", "Leave", XR_ACTION_TYPE_BOOLEAN_INPUT, &x->exit)) return false;
    if (!action("skip", "Skip track", XR_ACTION_TYPE_FLOAT_INPUT, &x->skip)) return false;
    if (!action("next_preset", "Next preset", XR_ACTION_TYPE_BOOLEAN_INPUT, &x->preset)) return false;

    auto suggest = [&](const char* profile, std::vector<XrActionSuggestedBinding> b) {
        XrInteractionProfileSuggestedBinding s{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
        s.interactionProfile = path(x, profile);
        s.suggestedBindings = b.data();
        s.countSuggestedBindings = static_cast<uint32_t>(b.size());
        ok(xrSuggestInteractionProfileBindings(x->instance, &s), profile);
    };
    suggest("/interaction_profiles/oculus/touch_controller", {
        {x->playPause, path(x, "/user/hand/right/input/a/click")},
        {x->playPause, path(x, "/user/hand/left/input/x/click")},
        {x->playPause, path(x, "/user/hand/right/input/trigger/value")},
        {x->playPause, path(x, "/user/hand/left/input/trigger/value")},
        {x->exit, path(x, "/user/hand/right/input/b/click")},
        {x->exit, path(x, "/user/hand/left/input/y/click")},
        {x->skip, path(x, "/user/hand/right/input/thumbstick/x")},
        {x->skip, path(x, "/user/hand/left/input/thumbstick/x")},
        {x->preset, path(x, "/user/hand/right/input/squeeze/value")},
        {x->preset, path(x, "/user/hand/left/input/squeeze/value")},
    });
    suggest("/interaction_profiles/khr/simple_controller", {
        {x->playPause, path(x, "/user/hand/right/input/select/click")},
        {x->playPause, path(x, "/user/hand/left/input/select/click")},
        {x->exit, path(x, "/user/hand/left/input/menu/click")},
    });
    XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attach.countActionSets = 1;
    attach.actionSets = &x->actions;
    return ok(xrAttachSessionActionSets(x->session, &attach), "xrAttachSessionActionSets");
}

// 60 fps divides 120 Hz evenly (72, the default, needs a repeat every sixth
// frame); the cylinder costs the compositor next to nothing.
void requestRefreshRate(Xr* x) {
    if (!x->refreshRateExt) return;
    PFN_xrEnumerateDisplayRefreshRatesFB rates = nullptr;
    PFN_xrRequestDisplayRefreshRateFB request = nullptr;
    xrGetInstanceProcAddr(x->instance, "xrEnumerateDisplayRefreshRatesFB", reinterpret_cast<PFN_xrVoidFunction*>(&rates));
    xrGetInstanceProcAddr(x->instance, "xrRequestDisplayRefreshRateFB", reinterpret_cast<PFN_xrVoidFunction*>(&request));
    if (!rates || !request) return;
    uint32_t count = 0;
    rates(x->session, 0, &count, nullptr);
    std::vector<float> all(count);
    rates(x->session, count, &count, all.data());
    for (float r : all) LOGI("display can do %.0f Hz", r);
    for (float r : all) {
        if (std::fabs(r - 120.0f) < 0.5f) {
            if (ok(request(x->session, r), "xrRequestDisplayRefreshRateFB")) LOGI("display at 120 Hz");
            return;
        }
    }
    LOGI("no 120 Hz; staying at the default rate");
}

void pollEvents(Xr* x) {
    XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(x->instance, &event) == XR_SUCCESS) {
        switch (event.type) {
            case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED: {
                auto* e = reinterpret_cast<XrEventDataSessionStateChanged*>(&event);
                x->state = e->state;
                LOGI("session state %d", x->state);
                if (x->state == XR_SESSION_STATE_READY) {
                    XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
                    begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    if (ok(xrBeginSession(x->session, &begin), "xrBeginSession")) {
                        x->running = true;
                        requestRefreshRate(x);
                    }
                } else if (x->state == XR_SESSION_STATE_STOPPING) {
                    xrEndSession(x->session);
                    x->running = false;
                } else if (x->state == XR_SESSION_STATE_EXITING || x->state == XR_SESSION_STATE_LOSS_PENDING) {
                    x->quit = true;
                }
                break;
            }
            case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING:
                x->quit = true;
                break;
            default:
                break;
        }
        event = {XR_TYPE_EVENT_DATA_BUFFER};
    }
}

void readInput(Xr* x) {
    if (x->state != XR_SESSION_STATE_FOCUSED) return;
    XrActiveActionSet active{x->actions, XR_NULL_PATH};
    XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &active;
    if (xrSyncActions(x->session, &sync) != XR_SUCCESS) return;

    XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};
    XrActionStateBoolean b{XR_TYPE_ACTION_STATE_BOOLEAN};
    get.action = x->playPause;
    if (XR_SUCCEEDED(xrGetActionStateBoolean(x->session, &get, &b)) && b.changedSinceLastSync && b.currentState) x->input |= PLAY_PAUSE;
    get.action = x->exit;
    b = {XR_TYPE_ACTION_STATE_BOOLEAN};
    if (XR_SUCCEEDED(xrGetActionStateBoolean(x->session, &get, &b)) && b.changedSinceLastSync && b.currentState) x->input |= EXIT;
    get.action = x->preset;
    b = {XR_TYPE_ACTION_STATE_BOOLEAN};
    if (XR_SUCCEEDED(xrGetActionStateBoolean(x->session, &get, &b)) && b.changedSinceLastSync && b.currentState) x->input |= NEXT_PRESET;
    // A push past 0.7 skips once; the stick must come back under 0.3 first.
    get.action = x->skip;
    XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT};
    if (XR_SUCCEEDED(xrGetActionStateFloat(x->session, &get, &f)) && f.isActive) {
        float v = f.currentState;
        if (x->skipArmed && std::fabs(v) > 0.7f) {
            x->input |= v > 0 ? NEXT : PREVIOUS;
            x->skipArmed = false;
        } else if (std::fabs(v) < 0.3f) {
            x->skipArmed = true;
        }
    }
}

void destroy(Xr* x, JNIEnv* env) {
    if (x->context != EGL_NO_CONTEXT) {
        if (x->copy) glDeleteTextures(1, &x->copy);
        if (x->fbo) glDeleteFramebuffers(1, &x->fbo);
        if (x->vao) glDeleteVertexArrays(1, &x->vao);
        if (x->program) glDeleteProgram(x->program);
    }
    if (x->swapchain) xrDestroySwapchain(x->swapchain);
    if (x->actions) xrDestroyActionSet(x->actions);
    if (x->space) xrDestroySpace(x->space);
    if (x->session) xrDestroySession(x->session);
    if (x->instance) xrDestroyInstance(x->instance);
    if (x->display != EGL_NO_DISPLAY) {
        eglMakeCurrent(x->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (x->pbuffer != EGL_NO_SURFACE) eglDestroySurface(x->display, x->pbuffer);
        if (x->context != EGL_NO_CONTEXT) eglDestroyContext(x->display, x->context);
        eglTerminate(x->display);
    }
    if (x->activity) env->DeleteGlobalRef(x->activity);
    delete x;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_eu_bradshaw_seidr_vis_xr_Xr_nativeCreate(JNIEnv* env, jclass, jobject activity, jint width, jint height) {
    auto* x = new Xr;
    env->GetJavaVM(&x->vm);
    x->activity = env->NewGlobalRef(activity);
    x->width = width;
    x->height = height;
    if (!createInstance(x)) { destroy(x, env); return 0; }
    if (!createEgl(x)) { LOGE("EGL setup failed: 0x%x", eglGetError()); destroy(x, env); return 0; }
    if (!createSession(x) || !createSwapchain(x) || !createActions(x)) { destroy(x, env); return 0; }
    createGl(x);
    return reinterpret_cast<jlong>(x);
}

JNIEXPORT void JNICALL
Java_eu_bradshaw_seidr_vis_xr_Xr_nativeDestroy(JNIEnv* env, jclass, jlong h) {
    if (h) destroy(xr(h), env);
}

// Handles the runtime's events, then (while the session runs) waits for and
// begins the next frame: its predicted display time, in ns. IDLE when not
// running (try again shortly), QUIT when the session is over. A display time
// must be followed by nativeEndFrame.
JNIEXPORT jlong JNICALL
Java_eu_bradshaw_seidr_vis_xr_Xr_nativeBeginFrame(JNIEnv*, jclass, jlong h) {
    auto* x = xr(h);
    pollEvents(x);
    if (x->quit) return QUIT;
    if (!x->running) return IDLE;
    x->frame = {XR_TYPE_FRAME_STATE};
    if (!ok(xrWaitFrame(x->session, nullptr, &x->frame), "xrWaitFrame")) return IDLE;
    if (!ok(xrBeginFrame(x->session, nullptr), "xrBeginFrame")) return IDLE;
    readInput(x);
    return x->frame.predictedDisplayTime;
}

// INPUT bits since the last call.
JNIEXPORT jint JNICALL
Java_eu_bradshaw_seidr_vis_xr_Xr_nativeInput(JNIEnv*, jclass, jlong h) {
    int bits = xr(h)->input;
    xr(h)->input = 0;
    return bits;
}

// projectM has just drawn width x height (from the bottom left) into the
// pbuffer: into the swapchain with it, stretched to fill.
JNIEXPORT void JNICALL
Java_eu_bradshaw_seidr_vis_xr_Xr_nativePresent(JNIEnv*, jclass, jlong h, jint width, jint height) {
    auto* x = xr(h);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, x->copy);
    glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

    uint32_t index = 0;
    XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    if (!ok(xrAcquireSwapchainImage(x->swapchain, &acquire, &index), "xrAcquireSwapchainImage")) return;
    XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    wait.timeout = XR_INFINITE_DURATION;
    if (!ok(xrWaitSwapchainImage(x->swapchain, &wait), "xrWaitSwapchainImage")) return;

    glBindFramebuffer(GL_FRAMEBUFFER, x->fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, x->images[index].image, 0);
    const GLenum attachment = GL_COLOR_ATTACHMENT0;
    glDrawBuffers(1, &attachment);
    glViewport(0, 0, x->width, x->height);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glUseProgram(x->program);
    glUniform2f(x->scaleUniform, float(width) / x->width, float(height) / x->height);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, x->copy);
    glBindVertexArray(x->vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glUseProgram(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    if (ok(xrReleaseSwapchainImage(x->swapchain, &release), "xrReleaseSwapchainImage")) x->hasImage = true;
}

// Ends the frame begun by nativeBeginFrame: the sphere (or cylinder), with
// the latest frame released to the swapchain (the same one again if none
// was drawn).
JNIEXPORT void JNICALL
Java_eu_bradshaw_seidr_vis_xr_Xr_nativeEndFrame(JNIEnv*, jclass, jlong h) {
    auto* x = xr(h);
    XrSwapchainSubImage image{x->swapchain, {{0, 0}, {x->width, x->height}}, 0};
    XrCompositionLayerEquirect2KHR sphere{XR_TYPE_COMPOSITION_LAYER_EQUIRECT2_KHR};
    sphere.space = x->space;
    sphere.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    sphere.subImage = image;
    sphere.pose.orientation.w = 1;
    sphere.radius = RADIUS;
    sphere.centralHorizontalAngle = WIDE;
    sphere.upperVerticalAngle = UP;
    sphere.lowerVerticalAngle = -DOWN;
    XrCompositionLayerCylinderKHR cylinder{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
    cylinder.space = x->space;
    cylinder.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    cylinder.subImage = image;
    cylinder.pose.orientation.w = 1;
    cylinder.radius = CYLINDER_RADIUS;
    cylinder.centralAngle = WIDE;
    cylinder.aspectRatio = float(x->width) / x->height;
    const XrCompositionLayerBaseHeader* layers[] = {
        x->sphere ? reinterpret_cast<XrCompositionLayerBaseHeader*>(&sphere)
                  : reinterpret_cast<XrCompositionLayerBaseHeader*>(&cylinder),
    };

    XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};
    end.displayTime = x->frame.predictedDisplayTime;
    end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    end.layerCount = x->hasImage && x->frame.shouldRender ? 1 : 0;
    end.layers = layers;
    ok(xrEndFrame(x->session, &end), "xrEndFrame");
}

} // extern "C"

// SPDX-License-Identifier: LGPL-2.1-or-later
#include <jni.h>
#include <fluidsynth.h>
#include <cstdint>
#include <new>

namespace {
struct Engine {
    fluid_settings_t* settings = nullptr;
    fluid_synth_t* synth = nullptr;
    int font = -1;
    bool reverb = true;
    bool chorus = true;
    ~Engine() { if (synth) delete_fluid_synth(synth); if (settings) delete_fluid_settings(settings); }
};
Engine* engine(jlong handle) { return reinterpret_cast<Engine*>(static_cast<intptr_t>(handle)); }
void ambience(Engine* e, int channel) {
    // Add a restrained user-controlled send, independent of native MIDI CC91/93 modulation.
    // Unlike replacing those controllers, this retains the score's own effects messages.
    fluid_synth_set_gen(e->synth,channel,GEN_REVERBSEND,e->reverb ? 180.f : 0.f);
    fluid_synth_set_gen(e->synth,channel,GEN_CHORUSSEND,e->chorus ? 80.f : 0.f);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_tuxcompose_demo_synth_FluidSynth_nativeCreate(JNIEnv*, jobject, jint sampleRate) {
    auto* e = new(std::nothrow) Engine;
    if (!e) return 0;
    e->settings = new_fluid_settings();
    if (!e->settings) { delete e; return 0; }
    fluid_settings_setnum(e->settings,"synth.sample-rate",sampleRate);
    fluid_settings_setnum(e->settings,"synth.gain",.35);
    fluid_settings_setint(e->settings,"synth.polyphony",256);
    fluid_settings_setint(e->settings,"synth.cpu-cores",1); // one bounded pull-render thread, no driver threads
    fluid_settings_setint(e->settings,"synth.threadsafe-api",0); // Kotlin serializes every call, including rendering/close
    fluid_settings_setstr(e->settings,"synth.midi-bank-select","gs");
    e->synth = new_fluid_synth(e->settings);
    if (!e->synth) { delete e; return 0; }
    // The upstream fourth-order default avoids obsolete seventh-point ringing artifacts.
    fluid_synth_set_interp_method(e->synth,-1,FLUID_INTERP_4THORDER);
    fluid_synth_set_reverb_group_roomsize(e->synth,-1,.6);
    fluid_synth_set_reverb_group_damp(e->synth,-1,.3);
    fluid_synth_set_reverb_group_width(e->synth,-1,80.);
    fluid_synth_set_reverb_group_level(e->synth,-1,.3);
    fluid_synth_set_chorus_group_nr(e->synth,-1,3);
    fluid_synth_set_chorus_group_level(e->synth,-1,.3);
    fluid_synth_set_chorus_group_speed(e->synth,-1,.3);
    fluid_synth_set_chorus_group_depth(e->synth,-1,8.);
    for (int channel=0; channel<16; ++channel) ambience(e,channel);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(e));
}
extern "C" JNIEXPORT jint JNICALL
Java_com_tuxcompose_demo_synth_FluidSynth_nativeLoad(JNIEnv* env, jobject, jlong handle, jstring path) {
    auto* e=engine(handle);
    const char* file=env->GetStringUTFChars(path,nullptr);
    if (!file) return FLUID_FAILED;
    const int id=fluid_synth_sfload(e->synth,file,1);
    env->ReleaseStringUTFChars(path,file);
    if (id>=0) {
        if (e->font>=0) fluid_synth_sfunload(e->synth,e->font,0);
        e->font=id;
        for (int channel=0; channel<16; ++channel) ambience(e,channel);
    }
    return id;
}
extern "C" JNIEXPORT void JNICALL
Java_com_tuxcompose_demo_synth_FluidSynth_nativeMidi(JNIEnv*, jobject, jlong handle, jint status, jint a, jint b) {
    auto* e=engine(handle);
    const int channel=status & 15;
    switch (status & 0xf0) {
        case 0x80: fluid_synth_noteoff(e->synth,channel,a); break;
        case 0x90: fluid_synth_noteon(e->synth,channel,a,b); break;
        case 0xa0: fluid_synth_key_pressure(e->synth,channel,a,b); break;
        case 0xb0:
            fluid_synth_cc(e->synth,channel,a,b);
            if (a==121) ambience(e,channel);
            break;
        case 0xc0: fluid_synth_program_change(e->synth,channel,a); break;
        case 0xd0: fluid_synth_channel_pressure(e->synth,channel,a); break;
        case 0xe0: fluid_synth_pitch_bend(e->synth,channel,a | (b << 7)); break;
        default: break;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_tuxcompose_demo_synth_FluidSynth_nativeEffects(JNIEnv*, jobject, jlong handle, jboolean reverb, jboolean chorus) {
    auto* e=engine(handle);
    e->reverb=reverb; e->chorus=chorus;
    fluid_synth_reverb_on(e->synth,-1,reverb);
    fluid_synth_chorus_on(e->synth,-1,chorus);
    for (int channel=0; channel<16; ++channel) ambience(e,channel);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_tuxcompose_demo_synth_FluidSynth_nativeRender(JNIEnv* env, jobject, jlong handle, jshortArray pcm) {
    auto* e=engine(handle);
    jshort* output=env->GetShortArrayElements(pcm,nullptr);
    if (!output) return FLUID_FAILED;
    const int result=fluid_synth_write_s16(e->synth,env->GetArrayLength(pcm)/2,output,0,2,output,1,2);
    env->ReleaseShortArrayElements(pcm,output,0);
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_tuxcompose_demo_synth_FluidSynth_nativePanic(JNIEnv*, jobject, jlong handle) {
    auto* e=engine(handle);
    // Silence without resetting patches, RPN bend ranges or the router's channel state.
    for (int channel=0; channel<16; ++channel) {
        fluid_synth_all_sounds_off(e->synth,channel);
        fluid_synth_cc(e->synth,channel,64,0);
        fluid_synth_cc(e->synth,channel,66,0);
        fluid_synth_pitch_bend(e->synth,channel,8192);
    }
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_tuxcompose_demo_synth_FluidSynth_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(fluid_version_str());
}
extern "C" JNIEXPORT void JNICALL
Java_com_tuxcompose_demo_synth_FluidSynth_nativeClose(JNIEnv*, jobject, jlong handle) { delete engine(handle); }

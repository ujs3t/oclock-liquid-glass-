#include <jni.h>
#include "glass_core.h"
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_oclock_liquidglass_NativeGlass_compute(JNIEnv* env,jclass,jbyteArray alpha,jint w,jint h,jdoubleArray values) {
    try {
        if(env->GetArrayLength(values)!=7) return nullptr;
        jdouble p[7]; env->GetDoubleArrayRegion(values,0,7,p);
        std::vector<uint8_t> input(env->GetArrayLength(alpha));
        env->GetByteArrayRegion(alpha,0,input.size(),reinterpret_cast<jbyte*>(input.data()));
        glass::Params params; params.ss=p[0]; params.ior=p[1]; params.thickness=p[2]; params.bezel=p[3]; params.shadowThickness=p[4]; params.shadowAngle=p[5]; params.surface=int(p[6]);
        auto result=glass::compute(input,w,h,params,false);
        auto cls=env->FindClass("[B"); auto output=env->NewObjectArray(3,cls,nullptr);
        auto add=[&](int i,const uint8_t* data,size_t size) { auto a=env->NewByteArray(size); env->SetByteArrayRegion(a,0,size,reinterpret_cast<const jbyte*>(data)); env->SetObjectArrayElement(output,i,a); env->DeleteLocalRef(a); };
        add(0,result.displacement.data(),result.displacement.size()); add(1,result.shadow.data(),result.shadow.size());
        add(2,reinterpret_cast<const uint8_t*>(&result.maxAbsCss),sizeof(double));
        return output;
    } catch(const std::exception&) { return nullptr; }
}

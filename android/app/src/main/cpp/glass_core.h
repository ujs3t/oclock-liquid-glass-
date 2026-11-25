#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace glass {
struct Params { double ss=2, ior=1.5, thickness=40, bezel=50, shadowThickness=2.5, shadowAngle=55; int surface=0; };
struct Result { std::vector<uint8_t> displacement, shadow; double maxAbsCss=0; bool cancelled=false; };
inline double clamp(double x) { return std::max(0.,std::min(1.,x)); }
inline uint8_t byte(double x) { return uint8_t(std::max(0., std::min(255., std::floor(x+.5)))); }
inline double surface(double x,int kind) {
    x=clamp(x);
    double a=std::pow(std::max(0.,1-std::pow(1-x,4)),.25);
    if(kind==1) return std::sqrt(std::max(0.,1-(1-x)*(1-x)));
    if(kind==2) return 1-a;
    if(kind==3) { double t=x*x*x*(x*(x*6-15)+10); return a*(1-t)+(1-a)*t; }
    return a;
}
inline void edt1(const float* f,float* d,int* v,float* z,int n) {
    int k=0; v[0]=0; z[0]=-1e20f; z[1]=1e20f;
    for(int q=1;q<n;q++) {
        auto cut=[&]() { return (double(f[q])+double(q)*q-double(f[v[k]])-double(v[k])*v[k])/(2*q-2*v[k]); };
        double s=cut(); while(s<=z[k]) { --k; s=cut(); }
        ++k; v[k]=q; z[k]=float(s); z[k+1]=1e20f;
    }
    k=0; for(int q=0;q<n;q++) { while(z[k+1]<q) ++k; double dx=q-v[k]; d[q]=float(dx*dx+f[v[k]]); }
}
inline bool isCancelled(const std::atomic<uint64_t>* activeVersion, uint64_t expected) {
    return activeVersion && activeVersion->load(std::memory_order_relaxed) != expected;
}
inline std::vector<float> edt(const uint8_t* alpha,int w,int h,bool inside,
                              const std::atomic<uint64_t>* activeVersion=nullptr,uint64_t expected=0) {
    std::vector<float> grid(size_t(w)*h);
    for(size_t i=0,n=size_t(w)*h;i<n;i++) grid[i]=((alpha[i]>=128)==inside)?0.f:float(1e12);
    int m=std::max(w,h); std::vector<float> f(m),d(m),z(m+1); std::vector<int> v(m);
    for(int y=0;y<h;y++) {
        if((y&31)==0 && isCancelled(activeVersion,expected)) return {};
        float* row=grid.data()+size_t(y)*w;
        edt1(row,d.data(),v.data(),z.data(),w);
        std::copy(d.begin(),d.begin()+w,row);
    }
    for(int x=0;x<w;x++) {
        if((x&31)==0 && isCancelled(activeVersion,expected)) return {};
        for(int y=0;y<h;y++) f[y]=grid[size_t(y)*w+x];
        edt1(f.data(),d.data(),v.data(),z.data(),h);
        for(int y=0;y<h;y++) grid[size_t(y)*w+x]=float(std::sqrt(double(d[y])));
    }
    return grid;
}
inline Result compute(const uint8_t* alpha,size_t alphaLen,int w,int h,const Params& p,bool includeShadow=true,
                      const std::atomic<uint64_t>* activeVersion=nullptr,uint64_t expected=0) {
    if(w<4 || h<4 || int64_t(w)*h>3000000 || alphaLen!=size_t(w)*h) throw std::invalid_argument("invalid raster size");
    const size_t count=size_t(w)*h;
    auto sdf=edt(alpha,w,h,true,activeVersion,expected);
    if(sdf.empty()) { Result r; r.cancelled=true; return r; }
    auto inside=edt(alpha,w,h,false,activeVersion,expected);
    if(inside.empty()) { Result r; r.cancelled=true; return r; }
    double maxInside=0;
    for(size_t i=0;i<count;i++) {
        double s=sdf[i]>0 ? double(sdf[i])-.5 : -(double(inside[i])-.5);
        sdf[i]=float(s); maxInside=std::max(maxInside,-s);
    }
    std::vector<float>().swap(inside);

    const double userBezel=std::max(2.,p.bezel*p.ss), bezel=std::max(2.,std::min(userBezel,std::max(2.,.5*maxInside)));
    const double thick=p.thickness*p.ss*(bezel/userBezel);
    double profile[127],maxAbs=0;
    for(int i=0;i<127;i++) {
        double x=double(i)/126, x1=std::max(0.,x-.001),x2=std::min(1.,x+.001);
        double height=surface(x,p.surface)*thick;
        double slope=((surface(x2,p.surface)-surface(x1,p.surface))/(x2-x1))*thick/bezel;
        double len=std::hypot(-slope,1.),nx=-slope/len,nz=1./len,eta=1./p.ior;
        double sin2=eta*eta*(1-nz*nz), disp=0;
        if(sin2<=1) { double factor=eta*nz-std::sqrt(1-sin2),tx=factor*nx,ty=-eta+factor*nz; if(ty!=0) disp=(-height/ty)*tx; }
        profile[i]=disp; maxAbs=std::max(maxAbs,std::abs(disp));
    }
    if(!maxAbs) maxAbs=1e-6;
    Result result; result.maxAbsCss=maxAbs/p.ss;
    result.displacement.resize(count*4);
    if(includeShadow) result.shadow.resize(count*4);

    // 3-row rolling window avoids an extra w*h*4 allocation for the smoothed SDF.
    std::vector<float> smoothRing(size_t(w)*3);
    auto rowPtr=[&](int slot)->float* { return smoothRing.data()+size_t(slot)*w; };
    auto computeSmoothRow=[&](int ry,float* dst) {
        int rym=std::max(0,ry-1), ryp=std::min(h-1,ry+1);
        const float* r0=sdf.data()+size_t(rym)*w;
        const float* r1=sdf.data()+size_t(ry)*w;
        const float* r2=sdf.data()+size_t(ryp)*w;
        for(int x=0;x<w;x++) {
            int xm=std::max(0,x-1), xp=std::min(w-1,x+1);
            dst[x]=float((double(r0[xm])+2.*r0[x]+r0[xp]+2.*r1[xm]+4.*r1[x]+2.*r1[xp]+r2[xm]+2.*r2[x]+r2[xp])*.0625);
        }
    };
    computeSmoothRow(0,rowPtr(0));
    computeSmoothRow(std::min(h-1,1),rowPtr(1));

    const double shadowThick=std::max(1.,p.shadowThickness*p.ss),feather=std::max(.9,shadowThick*.15);
    const double angle=p.shadowAngle*3.14159265358979323846/180,ax=std::cos(angle),ay=std::sin(angle);
    const double outerCut=includeShadow ? feather : 0.;
    const bool flatCenter=std::abs(profile[126])<1e-9;

    for(int y=0;y<h;y++) {
        if((y&31)==0 && isCancelled(activeVersion,expected)) {
            Result cancelledResult; cancelledResult.cancelled=true; return cancelledResult;
        }
        if(y>=1 && y+1<h) computeSmoothRow(y+1,rowPtr((y+1)%3));
        const float* sm=rowPtr(std::max(0,y-1)%3);
        const float* sc=rowPtr(y%3);
        const float* sp=rowPtr(std::min(h-1,y+1)%3);
        const float* sdfRow=sdf.data()+size_t(y)*w;
        uint8_t* dispRow=result.displacement.data()+size_t(y)*w*4;
        uint8_t* shadRow=includeShadow ? result.shadow.data()+size_t(y)*w*4 : nullptr;

        for(int x=0;x<w;x++) {
            uint8_t* d=dispRow+size_t(x)*4;
            d[0]=d[1]=d[2]=128; d[3]=255;
            double dd=sdfRow[x];
            if(dd>outerCut) continue;
            if(!includeShadow && flatCenter && dd<=-bezel) continue;
            int xm=std::max(0,x-1), xp=std::min(w-1,x+1);
            double gx=(double(sc[xp])-sc[xm])*.5, gy=(double(sp[x])-sm[x])*.5;
            double len=std::hypot(gx,gy); if(!len) len=1;
            double nx=gx/len, ny=gy/len;
            if(dd<=0) {
                double pos=clamp(std::min(-dd,bezel)/bezel)*126;
                int a=int(std::floor(pos)), b=std::min(126,a+1);
                double t=pos-a, disp=profile[a]*(1-t)+profile[b]*t;
                d[0]=byte(128-nx*(disp/maxAbs)*127);
                d[1]=byte(128-ny*(disp/maxAbs)*127);
            }
            if(includeShadow && -dd<=shadowThick+feather) {
                uint8_t* s=shadRow+size_t(x)*4;
                double band=clamp((shadowThick+dd)/shadowThick), edgeAA=clamp((feather-dd)/(2*feather));
                s[0]=s[1]=s[2]=255;
                s[3]=byte(std::pow(band,.6)*edgeAA*std::pow(std::abs(nx*ax+ny*ay),1.8)*255);
            }
        }
    }
    return result;
}
inline Result compute(const std::vector<uint8_t>& alpha,int w,int h,const Params& p,bool includeShadow=true) {
    return compute(alpha.data(),alpha.size(),w,h,p,includeShadow);
}
}

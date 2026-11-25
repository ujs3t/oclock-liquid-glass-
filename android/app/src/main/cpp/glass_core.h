#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>
#include <stdexcept>

namespace glass {
struct Params { double ss=2, ior=1.5, thickness=40, bezel=50, shadowThickness=2.5, shadowAngle=55; int surface=0; };
struct Result { std::vector<uint8_t> displacement, shadow; double maxAbsCss; };
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
inline void edt1(const std::vector<float>& f,std::vector<float>& d,std::vector<int>& v,std::vector<float>& z,int n) {
    int k=0; v[0]=0; z[0]=-1e20f; z[1]=1e20f;
    for(int q=1;q<n;q++) {
        auto cut=[&]() { return (double(f[q])+double(q)*q-double(f[v[k]])-double(v[k])*v[k])/(2*q-2*v[k]); };
        double s=cut(); while(s<=z[k]) { --k; s=cut(); }
        ++k; v[k]=q; z[k]=float(s); z[k+1]=1e20f;
    }
    k=0; for(int q=0;q<n;q++) { while(z[k+1]<q) ++k; double dx=q-v[k]; d[q]=float(dx*dx+f[v[k]]); }
}
inline std::vector<float> edt(const std::vector<uint8_t>& alpha,int w,int h,bool inside) {
    std::vector<float> grid(w*h);
    for(int i=0;i<w*h;i++) grid[i]=((alpha[i]>=128)==inside)?0.f:float(1e12);
    int m=std::max(w,h); std::vector<float> f(m),d(m),z(m+1); std::vector<int> v(m);
    for(int y=0;y<h;y++) { for(int x=0;x<w;x++) f[x]=grid[y*w+x]; edt1(f,d,v,z,w); for(int x=0;x<w;x++) grid[y*w+x]=d[x]; }
    for(int x=0;x<w;x++) { for(int y=0;y<h;y++) f[y]=grid[y*w+x]; edt1(f,d,v,z,h); for(int y=0;y<h;y++) grid[y*w+x]=float(std::sqrt(double(d[y]))); }
    return grid;
}
inline Result compute(const std::vector<uint8_t>& alpha,int w,int h,const Params& p) {
    if(w<4 || h<4 || int64_t(w)*h>3000000 || alpha.size()!=size_t(w)*h) throw std::invalid_argument("invalid raster size");
    const int count=w*h;
    auto outside=edt(alpha,w,h,true), inside=edt(alpha,w,h,false);
    std::vector<float> sdf(count),smooth(count); double maxInside=0;
    for(int i=0;i<count;i++) { double s=outside[i]>0 ? double(outside[i])-.5 : -(double(inside[i])-.5); sdf[i]=float(s); maxInside=std::max(maxInside,-s); }
    // Distance buffers are no longer needed while shading.
    std::vector<float>().swap(outside); std::vector<float>().swap(inside);
    for(int y=0;y<h;y++) { int ym=std::max(0,y-1),yp=std::min(h-1,y+1);
        for(int x=0;x<w;x++) { int xm=std::max(0,x-1),xp=std::min(w-1,x+1);
            smooth[y*w+x]=float((double(sdf[ym*w+xm])+2.*sdf[ym*w+x]+sdf[ym*w+xp]+2.*sdf[y*w+xm]+4.*sdf[y*w+x]+2.*sdf[y*w+xp]+sdf[yp*w+xm]+2.*sdf[yp*w+x]+sdf[yp*w+xp])*.0625);
        }
    }
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
    Result result; result.maxAbsCss=maxAbs/p.ss; result.displacement.resize(count*4); result.shadow.resize(count*4);
    double shadowThick=std::max(1.,p.shadowThickness*p.ss),feather=std::max(.9,shadowThick*.15);
    double angle=p.shadowAngle*3.14159265358979323846/180,ax=std::cos(angle),ay=std::sin(angle);
    for(int y=0;y<h;y++) { int ym=std::max(0,y-1),yp=std::min(h-1,y+1);
        for(int x=0;x<w;x++) { int xm=std::max(0,x-1),xp=std::min(w-1,x+1),i=y*w+x,q=i*4; double dd=sdf[i];
            auto* d=result.displacement.data()+q; auto* s=result.shadow.data()+q;
            d[0]=d[1]=d[2]=128; d[3]=255;
            if(dd>feather) continue;
            double gx=(double(smooth[y*w+xp])-smooth[y*w+xm])*.5,gy=(double(smooth[yp*w+x])-smooth[ym*w+x])*.5;
            double len=std::hypot(gx,gy); if(!len) len=1; double nx=gx/len,ny=gy/len;
            if(dd<=0) { double pos=clamp(std::min(-dd,bezel)/bezel)*126; int a=int(std::floor(pos)),b=std::min(126,a+1); double t=pos-a,disp=profile[a]*(1-t)+profile[b]*t;
                d[0]=byte(128-nx*(disp/maxAbs)*127); d[1]=byte(128-ny*(disp/maxAbs)*127);
            }
            if(-dd<=shadowThick+feather) { double band=clamp((shadowThick+dd)/shadowThick),edgeAA=clamp((feather-dd)/(2*feather));
                s[0]=s[1]=s[2]=255; s[3]=byte(std::pow(band,.6)*edgeAA*std::pow(std::abs(nx*ax+ny*ay),1.8)*255);
            }
        }
    }
    return result;
}
}

#include "../android/app/src/main/cpp/glass_core.h"
#include <iostream>
#include <iterator>
int main(int argc,char** argv) {
    if(argc!=4 && argc!=5) return 2;
    int w=std::stoi(argv[1]),h=std::stoi(argv[2]);
    glass::Params p; p.surface=std::stoi(argv[3]);
    std::vector<uint8_t> input((std::istreambuf_iterator<char>(std::cin)),std::istreambuf_iterator<char>());
    try {
        auto result=glass::compute(input,w,h,p,argc==4);
        std::cout.write(reinterpret_cast<const char*>(&result.maxAbsCss),sizeof(double));
        std::cout.write(reinterpret_cast<const char*>(result.displacement.data()),result.displacement.size());
        std::cout.write(reinterpret_cast<const char*>(result.shadow.data()),result.shadow.size());
    } catch(const std::exception& e) { std::cerr<<e.what(); return 1; }
}

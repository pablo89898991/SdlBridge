cmake_minimum_required(VERSION 3.16)
project(SdlBridge C)

# SDL sources are fetched into app/jni/SDL by the CI workflow (or by hand, see README).
add_subdirectory(SDL)
add_subdirectory(src)

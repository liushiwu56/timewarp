@echo off
"C:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk\\ndk-bundle\\ndk-build.cmd" ^
  "NDK_PROJECT_PATH=null" ^
  "APP_BUILD_SCRIPT=E:\\Desktop\\newshell\\timewarp-1106\\app\\src\\main\\jni\\Android.mk" ^
  "NDK_APPLICATION_MK=E:\\Desktop\\newshell\\timewarp-1106\\app\\src\\main\\jni\\Application.mk" ^
  "APP_ABI=arm64-v8a" ^
  "NDK_ALL_ABIS=arm64-v8a" ^
  "NDK_DEBUG=1" ^
  "APP_PLATFORM=android-26" ^
  "NDK_OUT=E:\\Desktop\\newshell\\timewarp-1106\\app\\build\\intermediates\\cxx\\Debug\\2t481w2h/obj" ^
  "NDK_LIBS_OUT=E:\\Desktop\\newshell\\timewarp-1106\\app\\build\\intermediates\\cxx\\Debug\\2t481w2h/lib" ^
  twclock

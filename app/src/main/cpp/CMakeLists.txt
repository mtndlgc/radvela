cmake_minimum_required(VERSION 3.22.1)
project(otoradyo LANGUAGES C CXX)

# GPL-2.0-veya-sonrası: welle.io, librtlsdr ve faad2 GPL olduğu için bu proje de GPL'dir (bkz. LICENSE, NOTICE.md).
# Tüm yerel kod bu ağaçtan derlenir: hazır (kaynaksız) kitaplık yoktur, 16 KB sayfa boyutu sağlanır.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
# Android Gradle eklentisi Debug/Release için bayrakları boşaltır (=> optimizasyonsuz, -O0). Sinyal işleme
# gerçek zamanlı olduğundan her yapılandırmada -O2 zorunludur.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -DNDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -DNDEBUG")

option(OTO_HOST_TESTS "Bilgisayarda testleri derle" OFF)
# Play yapısı: AAC yalnız cihazın çözücüsüyle çözülür, pakette AAC çözücü (FAAD2) bulunmaz
option(OTO_SYSTEM_AAC_ONLY "FAAD2 olmadan derle" OFF)

find_package(Threads REQUIRED)

set(T ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# ---------------------------------------------------------------- AAC / MP2 kod çözücüler (kaynaktan)
set(mpg123_sources
  ${T}/mpg123/compat.c ${T}/mpg123/compat_str.c ${T}/mpg123/parse.c
  ${T}/mpg123/frame.c ${T}/mpg123/format.c ${T}/mpg123/dct64.c
  ${T}/mpg123/equalizer.c ${T}/mpg123/id3.c ${T}/mpg123/icy.c
  ${T}/mpg123/icy2utf8.c ${T}/mpg123/optimize.c ${T}/mpg123/readers.c
  ${T}/mpg123/tabinit.c ${T}/mpg123/libmpg123.c ${T}/mpg123/index.c
  ${T}/mpg123/layer1.c ${T}/mpg123/layer2.c ${T}/mpg123/layer3.c
  ${T}/mpg123/dither.c ${T}/mpg123/feature.c ${T}/mpg123/synth.c
  ${T}/mpg123/synth_real.c ${T}/mpg123/ntom.c ${T}/mpg123/synth_8bit.c
  ${T}/mpg123/synth_s32.c ${T}/mpg123/stringbuf.c
)

if(OTO_SYSTEM_AAC_ONLY)
  set(faad_sources "")
else()
  file(GLOB faad_sources ${T}/faad2/libfaad/*.c)
endif()

add_library(codecs STATIC ${mpg123_sources} ${faad_sources})
target_compile_definitions(codecs PUBLIC HAVE_CONFIG_H OPT_GENERIC)
target_include_directories(codecs PUBLIC
  ${T}/mpg123
  ${T}/faad2
  ${T}/faad2/include
  ${T}/faad2/libfaad
  ${T}/faad2/libfaad/codebook
)
target_link_libraries(codecs PUBLIC Threads::Threads)
target_compile_options(codecs PRIVATE -w)  # üçüncü taraf kaynak uyarılarını bastır

# ---------------------------------------------------------------- welle.io DAB arka ucu
file(GLOB welle_sources ${T}/welle/backend/*.cpp)
add_library(welle STATIC
  ${welle_sources}
  ${T}/welle/various/channels.cpp
  ${T}/welle/various/fft.cpp
  ${T}/welle/various/profiling.cpp
  ${T}/welle/various/Xtan2.cpp
  ${T}/kiss_fft/kiss_fft.c
  ${T}/fec/decode_rs_char.c
  ${T}/fec/encode_rs_char.c
  ${T}/fec/init_rs_char.c
)
target_compile_definitions(welle PUBLIC KISSFFT)
if(OTO_SYSTEM_AAC_ONLY)
  target_compile_definitions(welle PUBLIC RADVELA_SYSTEM_AAC_ONLY)
else()
  target_compile_definitions(welle PUBLIC DABLIN_AAC_FAAD2)
endif()
target_include_directories(welle PUBLIC
  ${T}/welle
  ${T}/welle/backend
  ${T}/welle/various
  ${T}/kiss_fft
  ${T}/fec
)
target_link_libraries(welle PUBLIC codecs)
target_compile_options(welle PRIVATE -w -O3)
if(ANDROID)
  # Cihazın AAC çözücüsü (MediaCodec); kullanılamazsa FAAD2'ye düşülür
  target_sources(welle PRIVATE core/aac_system.cpp)
  target_include_directories(welle PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/core)
  target_link_libraries(welle PUBLIC mediandk log)
endif()

# ---------------------------------------------------------------- Kendi çekirdeğimiz
set(core_sources
  core/rds_decoder.cpp
  core/fm_mpx.cpp
  core/fm_frontend.cpp
  core/fm_scanner.cpp
  core/fm_receiver.cpp
  core/rtl_tcp_source.cpp
  core/dab_control.cpp
  core/engine.cpp
)

if(ANDROID)
  # libusb (LGPL-2.1-veya-sonrası) ve librtlsdr (GPL-2.0-veya-sonrası, rtlsdr_open_fd eklenmiş)
  add_library(usb1 STATIC
    ${T}/libusb/libusb/core.c ${T}/libusb/libusb/descriptor.c ${T}/libusb/libusb/hotplug.c
    ${T}/libusb/libusb/io.c ${T}/libusb/libusb/strerror.c ${T}/libusb/libusb/sync.c
    ${T}/libusb/libusb/os/linux_usbfs.c ${T}/libusb/libusb/os/linux_netlink.c
    ${T}/libusb/libusb/os/events_posix.c ${T}/libusb/libusb/os/threads_posix.c
  )
  target_include_directories(usb1 PRIVATE ${T}/libusb/android PUBLIC ${T}/libusb/libusb)
  target_compile_options(usb1 PRIVATE -w -fvisibility=hidden)

  add_library(rtlsdr STATIC
    ${T}/librtlsdr/src/librtlsdr.c ${T}/librtlsdr/src/tuner_e4k.c ${T}/librtlsdr/src/tuner_fc001x.c
    ${T}/librtlsdr/src/tuner_fc2580.c ${T}/librtlsdr/src/tuner_r82xx.c
  )
  target_include_directories(rtlsdr PUBLIC ${T}/librtlsdr/include PRIVATE ${T}/librtlsdr/src)
  target_compile_definitions(rtlsdr PUBLIC rtlsdr_STATIC PRIVATE DETACH_KERNEL_DRIVER)
  target_link_libraries(rtlsdr PUBLIC usb1)
  target_compile_options(rtlsdr PRIVATE -w)

  list(APPEND core_sources core/rtlsdr_usb_source.cpp)
endif()

add_library(oto_core STATIC ${core_sources})
target_include_directories(oto_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/core)
target_link_libraries(oto_core PUBLIC welle codecs)
target_compile_options(oto_core PRIVATE -Wall -Wextra -Wno-unused-parameter)

if(ANDROID)
  target_compile_definitions(oto_core PUBLIC OTO_WITH_USB)
  target_link_libraries(oto_core PUBLIC rtlsdr)
  add_library(otoradio SHARED core/jni_bridge.cpp)
  target_link_libraries(otoradio PRIVATE oto_core log)
  target_link_options(otoradio PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()

if(OTO_HOST_TESTS)
  add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../native-tests native-tests)
endif()

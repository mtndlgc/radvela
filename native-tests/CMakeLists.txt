# Bilgisayarda çalışan çekirdek testleri (Android gerektirmez).
# Derleme:
#   cmake -S app/src/main/cpp -B build-host -DOTO_HOST_TESTS=ON
#   cmake --build build-host -j
foreach(t test_rds_bits test_fm_mpx test_fm_frontend test_fm_scanner test_engine test_dab)
  add_executable(${t} ${CMAKE_CURRENT_SOURCE_DIR}/${t}.cpp)
  target_link_libraries(${t} PRIVATE oto_core)
endforeach()

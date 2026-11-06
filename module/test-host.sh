#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
mkdir -p build/host-tests/java
guard_host_cxx=${GUARD_HOST_CXX:-clang++}
"$guard_host_cxx" -std=c++17 -Wall -Wextra -Werror -g -fsanitize=address,undefined \
    -Isrc/main/cpp tests/hook_target_test.cpp src/main/cpp/hook_target.cpp \
    -ldl -o build/host-tests/hook-target-test
build/host-tests/hook-target-test
javac --release 17 -d build/host-tests/java \
    src/main/java/dev/systemhelper/guard/RegistrationLedger.java \
    tests/java/dev/systemhelper/guard/RegistrationLedgerTest.java
java -cp build/host-tests/java dev.systemhelper.guard.RegistrationLedgerTest

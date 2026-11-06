#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
guard_sdk=${GUARD_ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}}
guard_ndk=${GUARD_ANDROID_NDK:-$guard_sdk/ndk/28.2.13676358}
guard_bt=$guard_sdk/build-tools/36.0.0
guard_android=$guard_sdk/platforms/android-36/android.jar
guard_cxx=$guard_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++
rm -rf build/classes build/dex build/staging build/native
mkdir -p build/classes build/dex build/native build/staging/lib/arm64-v8a .keys ../dist libs/modern
chmod 700 .keys
for guard_dependency in api service interface; do
    if [[ ! -f libs/modern/$guard_dependency.aar ]]; then
        curl --fail --location --connect-timeout 10 --max-time 60 \
          "https://repo.maven.apache.org/maven2/io/github/libxposed/$guard_dependency/102.0.0/$guard_dependency-102.0.0.aar" \
          -o "libs/modern/$guard_dependency.aar.tmp"
        mv "libs/modern/$guard_dependency.aar.tmp" "libs/modern/$guard_dependency.aar"
    fi
done
sha256sum --check dependencies.sha256
python3 - <<'PY'
from zipfile import ZipFile
for name in ['api', 'service', 'interface']:
    with ZipFile('libs/modern/'+name+'.aar') as archive:
        archive.extract('classes.jar','libs/modern/'+name)
PY
guard_api=libs/modern/api/classes.jar
guard_service=libs/modern/service/classes.jar
guard_interface=libs/modern/interface/classes.jar
"$guard_cxx" -std=c++17 -O2 -fvisibility=hidden -fstack-protector-strong \
  -shared -fPIC -static-libstdc++ -Wl,--exclude-libs,ALL,-z,relro,-z,now,-z,max-page-size=16384 \
  src/main/cpp/guard.cpp src/main/cpp/hook_target.cpp src/main/cpp/sandbox.cpp -llog -ldl \
  -o build/staging/lib/arm64-v8a/libsystemhelperguard.so
"${guard_cxx%/*}/llvm-strip" --strip-unneeded build/staging/lib/arm64-v8a/libsystemhelperguard.so
"$guard_cxx" -std=c++17 -O2 -static-libstdc++ -Wl,-z,relro,-z,now \
  src/main/cpp/sandbox_test.cpp src/main/cpp/sandbox.cpp -o build/native/sandbox-test
"$guard_cxx" -std=c++17 -O2 -static-libstdc++ \
  src/main/cpp/native_test.cpp src/main/cpp/hook_target.cpp src/main/cpp/sandbox.cpp -llog -ldl -o build/native/native-test
mapfile -t guard_sources < <(rg --files src/main/java -g '*.java' | sort)
javac --release 17 -cp "$guard_android:$guard_api:$guard_service:$guard_interface" \
  -d build/classes "${guard_sources[@]}"
mapfile -t guard_classes < <(rg --files build/classes -g '*.class' | sort)
"$guard_bt/d8" --min-api 31 --lib "$guard_android" --classpath "$guard_api" \
  --output build/dex "${guard_classes[@]}" "$guard_service" "$guard_interface"
"$guard_bt/aapt2" compile --dir src/main/res -o build/resources.zip
"$guard_bt/aapt2" link -o build/unsigned.apk -I "$guard_android" \
  --manifest AndroidManifest.xml --min-sdk-version 31 --target-sdk-version 36 build/resources.zip
cp -r src/main/resources/META-INF build/staging/
cp ../LICENSE build/staging/META-INF/LICENSE-systemhelper-guard.txt
cp build/dex/classes.dex build/staging/
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_STORED
with ZipFile('build/unsigned.apk', 'a') as apk:
    for path in sorted(Path('build/staging').rglob('*')):
        if path.is_file(): apk.write(path, str(path.relative_to('build/staging')), compress_type=ZIP_STORED)
PY
"$guard_bt/zipalign" -P 16 -f 4 build/unsigned.apk build/aligned.apk
if [[ ! -f .keys/guard.p12 ]]; then
    python3 - <<'PY'
import os,secrets
fd=os.open('.keys/password',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as f:f.write(secrets.token_hex(24)+'\n')
PY
    keytool -genkeypair -keystore .keys/guard.p12 -storepass:file .keys/password \
      -alias guard -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Local SystemHelper Guard'
    chmod 600 .keys/guard.p12
fi
"$guard_bt/apksigner" sign --ks .keys/guard.p12 --ks-pass file:.keys/password \
  --ks-key-alias guard --out ../dist/systemhelper-guard.apk build/aligned.apk
"$guard_bt/apksigner" verify --verbose ../dist/systemhelper-guard.apk
"$guard_bt/zipalign" -c -P 16 -v 4 ../dist/systemhelper-guard.apk > build/alignment.txt
sha256sum ../dist/systemhelper-guard.apk

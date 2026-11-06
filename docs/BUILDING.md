# 构建与验证

## 环境

当前脚本支持 Linux x86_64 开发机，生成 Android arm64-v8a APK：

- JDK 17（`java`、`javac`、`keytool` 在 PATH 中）
- Android SDK Platform 36、Build Tools 36.0.0、NDK 28.2.13676358
- Bash、Python 3、curl、ripgrep、sha256sum
- 主机检查还需 Make、Clang（包含 AddressSanitizer/UndefinedBehaviorSanitizer）和 Node.js

SDK 路径按 `GUARD_ANDROID_SDK`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`$HOME/Android/Sdk` 的顺序选择。可用 `GUARD_ANDROID_NDK` 单独指定 NDK。

```sh
export GUARD_ANDROID_SDK="$HOME/Android/Sdk"
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358'
make check
make build
```

首次构建需要访问 Maven Central。三个 libxposed 依赖固定为 102.0.0，下载后校验 SHA-256。更新依赖时，应同时核对上游版本和授权、重新核对摘要，更新 `module/dependencies.sha256`。

输出为 `dist/systemhelper-guard.apk`，以及 `module/build/native/` 中的独立 Android 测试程序。构建检查 APK 签名与 16 KiB ZIP 对齐；不会安装 APK 或操作手机。

## 签名

首次构建在 `module/.keys/` 中生成本地签名及密码，后续构建复用。该目录不会进入 Git。请自行安全备份；不同机器的新签名不能直接覆盖已使用另一签名安装的 APK。

CI 每次使用临时本地签名，只检查构建，不能作为正式发布签名。GitHub Actions 不读取生产签名密钥，也不自动发布版本。

## 检查层级

`make check` 检查脚本语法、差异中的空白错误，并运行 native 入口与内存边界测试、Java 注册清理测试，不需要 Android SDK 或手机。`GUARD_HOST_CXX` 可指定主机 C++ 编译器，默认 `clang++`。

`make build` 编译模块、管理页和设备测试程序。它不执行设备测试，不验证真实目标事件，更不证明多个挂钩模块共存稳定。实际测试结果、设备记录和交接说明保留在本地，不随源码发布；能力覆盖边界见 [README](../README.md)。

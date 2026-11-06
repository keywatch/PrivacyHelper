# 第三方组件

本项目原创源码采用 [MIT](LICENSE) 授权。下列依赖保留上游授权，不能被项目 MIT 授权替代。

| 组件 | 固定版本 | 用途 | 上游授权 |
| --- | --- | --- | --- |
| `io.github.libxposed:api` | 102.0.0 | 仅编译，不打包 API 实现 | [Apache-2.0](https://github.com/libxposed/api/blob/102.0.0/LICENSE) |
| `io.github.libxposed:service` | 102.0.0 | 打包管理端服务通信代码 | [Apache-2.0](https://github.com/libxposed/service/blob/102.0.0/LICENSE) |
| `io.github.libxposed:interface` | 102.0.0 | 打包服务 AIDL 接口代码；来自 service 项目 | [Apache-2.0](https://github.com/libxposed/service/blob/102.0.0/LICENSE) |

构建脚本从 Maven Central 下载依赖，并按 `module/dependencies.sha256` 校验缓存和新下载的文件。依赖二进制不进入 Git。

APK 中保留 [Apache-2.0 许可文本](module/src/main/resources/META-INF/LICENSE-libxposed.txt) 和 [组件说明](module/src/main/resources/META-INF/NOTICE)。Android SDK/NDK、JDK、Clang、Python、Node.js、ADB 和可选 Frida 为开发或调查工具，由各自项目授权；不随本仓库分发。手机导出的厂商 APK、共享库和第三方工具下载目录亦不随仓库分发。

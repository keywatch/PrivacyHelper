# 隐私保护（预览版）

按应用、按敏感能力管理 LSPosed 策略。应用包名只是配置键；Java 和 native 适配器不以两个 SystemHelper 包名判定行为。预置包为 `com.qualcomm.qti.services.systemhelper` 和 `com.mobiletools.systemhelper`。管理界面允许添加其他包名和申请作用域。

本版使用 **libxposed API 102**。入口位于 `META-INF/xposed/`，Java 使用 `XposedModule` 和拦截链；管理应用通过 Xposed Service 写框架远程偏好，注入进程只读策略并接收变更通知。不调用 legacy Xposed API，不使用世界可读偏好文件。最低 Android 12，当前仅构建 arm64-v8a。

## 处理方式

| 模式 | 行为 | 应用侧影响 |
| --- | --- | --- |
| 保持原样 | 调用原方法 | 不替换结果 |
| 语义处理 | 空结果、稳定替代标识、无事件或静音 | 尽量遵守返回值、回调、取消和清理约定；不能保证不可检测 |
| 明确拒绝 | Java 异常或 native 错误码 | 应用可以发现操作失败 |
| 可选内核网络兜底 | 进程内 TSYNC seccomp 禁止新建 INET/INET6/PACKET socket 和 io_uring | 不可在线撤销；关闭后须重启目标进程 |

语义处理不伪造权限检查失败，不跳过 Application/Service 初始化，不按 UID 修改系统权限，不阻止普通文件读取和 Binder 生命周期操作。录屏和网络没有通用、可靠的“隐形成功”替代，因此只提供保持原样和明确拒绝。

预置模板选中事件、定位、标识、剪贴板、记录查询和传感器的语义处理，但**应用策略默认停用**，须在管理页主动启用。音频、录屏、网络和内核兜底默认关闭。这不是对目标包所有采集及传输路径的完整封锁；不会在重装后主动导入历史文件或自动启用模板。

## 当前适配范围

| 能力 | 已实现的入口 | 未覆盖或限制 |
| --- | --- | --- |
| 敏感事件 | 电话/SIM/屏幕等指定广播的动态过滤与静态接收；Telephony listen 和 callback 注册 | 不覆盖所有广播、DisplayManager、其他服务监听和 native Binder 订阅；混合广播保留其余 action |
| 定位 | LocationManager 最后位置、更新订阅和当前定位 Consumer 回调 | 其他 SDK、GNSS 原始数据、Wi-Fi/基站推算和原生 IPC 不在通用保证内 |
| 标识 | Telephony 的 IMEI/DeviceId 等读取、Settings.Secure.getString 的 android_id | IMEI 类为应用专属稳定替代值；其余已列电话标识返回 null；未覆盖所有硬件/系统属性标识 |
| 剪贴板 | 常见 ClipboardManager 读取、存在检查和新增变化监听 | 已存在的真实监听需清理或重启；清理调用始终保留 |
| 通讯记录 | ContentResolver.query 对 contacts、call_log、sms、mms 等已识别 authority 返回空 Cursor | 显式 projection 原样保留；默认 schema 通过 false selection 查询获取后替换为空游标；不覆盖直接 Provider Binder、文件和未知 Provider |
| 传感器 | SystemSensorManager 新订阅；NDK enable/register/getEvents/hasEvents | 不覆盖直接共享内存、其他 native API、服务代理；切换策略前已有订阅可能需重启 |
| 音频 | AudioRecord.read、AAudio 输入 read 静音；明确拒绝 AudioRecord/MediaRecorder 部分启动与 AAudio 输入启动/读取 | 静音不等于关闭麦克风硬件；未覆盖 OpenSL ES、MediaRecorder 编码输出、AAudio callback 数据通道和直接设备访问 |
| 录屏 | 若干 MediaProjection、ScreenCapture、SurfaceControl Java 入口的明确拒绝 | native SurfaceComposer/私有 HAL/直接 Binder 未覆盖；无语义模式 |
| 网络 | 常见 Java Socket/DatagramSocket 和 native connect/sendto/sendmsg/sendmmsg 的明确拒绝 | 不挂钩全局 write/writev；已有 socket 的 write、直接 syscall、IPC 代理等可绕过此层 |
| 内核兜底 | 禁止该进程后续创建 IP/PACKET socket 与 io_uring，覆盖已有线程直接 syscall | 不关闭已有 socket，不阻止继承/接收的 FD、其他进程代理、已存在 io_uring；不是全路径断网 |

Java 和 native 适配器只对进程启动时选中的能力安装挂钩。native 安装位于 `Instrumentation.callApplicationOnCreate` 调用原 Application.onCreate 之前，执行一次；配置变更只发布原子策略位，不进行新的指令改写。取消了 native 库加载回调中的挂钩安装，避免在 linker 锁路径中执行安装流程。未选择任何 native 能力且未启用内核选项时，不加载本模块 native 库。

此前的静态初始化、native 库构造函数和其他模块更早的行为不在保证内。native 入口检查读取与执行映射边界、跳转链长度、dispatch 槽归属和短函数体；识别到常见既有跳转或无法确认的入口会跳过并记录 `native-adapter-skipped`，不能算作覆盖。`requested/complete` 日志区分请求能力和完整安装能力。检查不能保证所有指令重定位或模块加载顺序都安全，仍需实机共存验证。

ARM64 的短 BTI 跳转入口须先解析至实现位置。直接改写 Bionic 16 字节 dispatch veneer 或 AAudio 8 字节 veneer 会破坏相邻函数。全局 write/writev 挂钩已移除，避免破坏日志、Looper 和其他模块的 trampoline。

## 使用与生效

1. 在 LSPosed 中启用“隐私保护”。
2. 打开应用，按应用名称选择或搜索要保护的应用。
3. 打开“开启保护”，选择“隐藏位置”“替代标识”“只提供静音”“禁止联网”等选项；更改会自动保存。如果提示还需完成启用，按提示到 LSPosed 允许保护该应用。
4. “更多设置”中保留可选的“加强断网”和系统应用信息入口。

列表中的“保护已开启”表示设置与作用域已启用，不等于已验证每种行为。首次启用、新增能力、作用域变化及内核选项变化后需重启目标进程。已有能力的普通模式变更由框架通知读取，但此前跳过的监听不会自动重建，此前已有的数据和订阅也不会被追溯清除。

广播注销按 Context 和监听对象记录真实／虚拟注册的清理归属。同一对象既有真实注册又有虚拟注册时，注销继续交给框架；只跳过纯虚拟注册的注销。电话 callback 采用对应的清理归属记录。

不建议将核心系统进程放入作用域。本模块跳过 `android`、SystemUI、shell 和自身；按进程执行，不能在多个包共享的同一进程中精确区分每段代码的来源。共享 UID 不等于共享进程。

## 构建及证据

`make build` 使用 Android SDK 36、NDK 28.2、JDK 17，下载并校验固定版本 libxposed API/Service/interface 102.0.0。API 仅用于编译；APK 包含 Service 和 AIDL 接口实现。构建同时生成两个 Android arm64 原生测试程序。签名私钥仅在本地 `module/.keys/`。环境准备、路径配置及签名说明见 [构建说明](docs/BUILDING.md)。

`bash module/test-host.sh` 运行可在开发机执行的 native 入口回归测试（AddressSanitizer/UndefinedBehaviorSanitizer）和 Java 注册清理回归测试。它们不需要连接手机，也不证明 LSPosed 指令改写或与其他模块共存稳定。

产物：`dist/systemhelper-guard.apk`。编译、独立测试、真实目标命中和稳定性观察属于不同验证层级，不能把“挂钩已注册”当成完整拦截证明。设备验证记录、现场笔记、调查报告及采集数据保留在本地，不进入 Git。

日志读取与含义见 [DIAGNOSTICS.md](DIAGNOSTICS.md)。技术细节保留在诊断中，主界面按应用和具体保护选项组织。

接口依据：[现代模块开发说明](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)、[libxposed API](https://github.com/libxposed/api)、[Xposed Service](https://github.com/libxposed/service)、[Native Hook ABI](https://github.com/LSPosed/LSPosed/wiki/Native-Hook)。

## 项目维护

`make check` 执行脚本语法和主机回归检查；GitHub Actions 另外检查 APK 构建，不连接手机或自动发布。

- [参与开发](CONTRIBUTING.md)
- [安全反馈](SECURITY.md)
- [更新记录](CHANGELOG.md)
- [调查工具](tools/README.md)
- [MIT 授权](LICENSE) 与 [第三方组件授权](THIRD_PARTY.md)

开发计划、设计预览、agent 交接信息、验证快照和本机构建数据均由 `.gitignore` 排除。需要跨环境协作时单独转交经过核对的资料，不使用 `git add -f` 将它们加入仓库。

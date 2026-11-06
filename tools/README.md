# 调查工具

这些工具不参与 APK 运行。输出写入本地 `evidence/`，不要把原始手机数据加入 Git。使用前须核对设备和进程身份。

| 文件 | 用途 | 运行条件与边界 |
| --- | --- | --- |
| `watch_guard.py` | 限时、只读采集指定应用的进程变化和模块/异常日志 | 开发机 Python 3、ADB、设备 su；使用必填 `--serial`，示例见 [日志说明](../DIAGNOSTICS.md) |
| `run_native_capture.py`、`native_agent.js` | 向明确 PID 附加 Frida，记录原生调用元数据 | 可选 Python frida 包、匹配的设备服务、明确进程；命令行支持 `--token-file`，不要把令牌放进命令历史或提交 |
| `capture_binder.sh` | 历史现场 tracefs Binder 采样 | Android shell、root、tracefs；目前包含当次硬编码 PID 和实例名，必须先人工修改，不能直接照搬运行 |
| `StorageProbe.java` | 独立 StorageStatsManager 查询实验 | 需 Android SDK 编译并在授权设备启动；此前设备调用失败，没有有效当前存储统计结果 |

从仓库根目录运行 Frida 采集脚本，因为它从 `tools/native_agent.js` 读取 agent。该脚本输出可包含目标应用的元数据；附加/卸载挂钩可能影响进程，不能作为只读无影响操作。仓库不会自动启动这些工具或执行设备操作。

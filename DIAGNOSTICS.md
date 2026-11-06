# 运行日志

主界面只显示保护选项；详细判断依据在日志中。标签为 `SystemHelperGuard`，平台崩溃通常在 `AndroidRuntime`、`libc`、`DEBUG` 中。

## 记录分别证明什么

| 记录 | 可以确认 | 不能据此确认 |
| --- | --- | --- |
| `manager-create` / `manager-resumed` | 管理页进入相应生命周期；是否已连上框架服务 | 目标进程已经加载拦截 |
| `settings-saved package=...` | 对应应用的设置已保存 | 已有进程完成全部策略切换 |
| `module-loaded modern-api=102 process=...` | 模块入口在该进程加载 | 每个适配器均可用 |
| `adapters-installed ... hooks=N` | Java 挂钩安装数量 | 每个入口都实际执行过 |
| `native-coverage hooks=N requested=X complete=Y` | 请求能力与完整安装能力 | 所有 native 访问均已覆盖 |
| `native-adapter-skipped ... reason=...` | 指定入口未安装，应视为覆盖缺口 | 已成功阻止该入口 |
| `HIT package=... adapter=...` | 对应 Java 适配器实际执行过 | 未列出的行为也被处理 |
| `native-hit adapter=...` | 对应 native 路径实际命中 | 是目标进程还是独立测试程序，需要按 PID 对照 |
| fatal / ANR / 进程变化 | 有相应运行异常或进程变化线索 | 单凭 PID 变化不能判定崩溃，可能是人为停止或系统回收 |

native 能力位：1 为传感器、2 为音频、4 为网络。`requested=7 complete=7` 表示这三组请求入口均报告安装完整，不等于阻止所有系统调用、跨进程代理或未知私有接口。

命中日志按进程去重，不对每次读取刷屏。日志不主动记录位置、音频、通讯内容、查询参数或替代标识种子。平台异常日志可能包含应用自身输出，分享前仍应检查内容。

## 采集

```sh
python3 tools/watch_guard.py \
  --serial IP:PORT \
  --seconds 120 \
  --output evidence/runtime-watch \
  dev.systemhelper.guard \
  com.qualcomm.qti.services.systemhelper \
  com.mobiletools.systemhelper \
  com.estrongs.android.pop
```

此脚本只读日志和进程，不启用策略、不停止应用。开始时建立历史日志基线，仅记录随后出现的相关新行，输出进程变化、实际命中和失败线索。遇到 ADB 断开会记录采集失败，不能将无输出视为运行正常。`summary.json` 只对应该次有限观察窗口，不能用作长期稳定性证明。

这次的修正版安装记录、界面截图、设置对比和高通新进程日志位于 `evidence/module-validation/v23/`。人工确认的高通开启操作单独标注在 `policy-comparison.json` 中，没有伪装成自动验证结果。

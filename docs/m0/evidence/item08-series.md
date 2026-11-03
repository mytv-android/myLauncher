# 第 8 项：系列应用直连联调

## 夹具协议验证

在 api31g 上运行验收自检：

- K08：探针以 `com.github.mytv.mlfixture.probe` 身份调用 `com.github.mytv.mlfixture.series` 的 `LAUNCHER_BRIDGE` Provider，`hello` 成功，series 记录到 `bridge_call`，调用方证书摘要与探针一致。
- 夹具 series 同时验证了 `set_launcher_cert` 固定证书、`hello/init/approve/hide` 命令、`notifyChange` 与行数据查询。
- 冒名应用 `series_impostor`（同包名、证书 B）与未授信 `rogue` 的拒绝逻辑由 M3 的设备检查覆盖。

## 真实系列应用

检查本机现有 APK 的清单：

| 应用 | APK | 版本 | `com.github.mytv.action.LAUNCHER_BRIDGE` Provider |
|---|---|---|---|
| myBV | `C:\PythonProject\_apk_r1119\myBV-1.0.0.r1119.19397ab8.alpha_.apk` | 1.0.0.r1119 | 无 |
| mydv | `C:\PythonProject\mydv\app\build\outputs\apk\release\myDV-2.1.1.apk` | 2.1.1 | 无 |
| mytv-android | `...\mytv-android-tv-3.0.0.2806-x86-sdk23-original.apk` | 3.0.0.2806 | 无 |

结论：

- 直连协议本身在夹具上可用了。
- 三个真实系列应用当前构建都还没有导出 `LAUNCHER_BRIDGE` Provider，因此无法与它们做端到端直连联调。按 AGENTS.md 硬规则 5，本仓库不修改这三个应用；需要在系列应用侧加入 Provider 后再联调。
- 在此之前，系列应用仍按标准 TvProvider 路径工作；API 23–25 的直连回退无法对真实应用生效。

原始证据：

- `docs/m0/evidence/api31g/selftest-results.json`（K08）
- APK 清单由 `apkanalyzer manifest print` 读取，命令与结果记录在本文件。

# 第 10 项：HOME 兼容

## 命令可用性

| 设备 | `cmd package set-home-activity` | 结果 | `cmd role add-role-holder` | HOME 键实际启动 |
|---|---|---|---|---|
| api23 | 不存在（无 `cmd`） | — | 不存在 | 系统 leanbacklauncher |
| api28 | 存在，返回 Success | `resolve-activity` 仍是 `com.google.android.tvlauncher` | `cmd: Can't find service: role` | 仍是 tvlauncher |
| api31g | 存在，返回 Success | 仍是 `com.google.android.apps.tv.launcherx` | 可执行 | 仍是 launcherx |
| api34g | 存在，返回 Success | 仍是 launcherx | 可执行 | 仍是 launcherx |
| api36 | 存在，返回 Success | 仍是 `com.google.android.tvlauncher` | 可执行 | 仍是 tvlauncher |

## 验证过的旁路

在 api28 上执行 `pm disable-user --user 0 com.google.android.tvlauncher` 后，`cmd package set-home-activity` 立即生效，`resolve-activity` 返回 spike，HOME 键也启动 spike。恢复系统桌面后回到原状。

## COMMON_HOME / HOME.CC / TCL meta-data

- 模拟器镜像上没有厂商对 `COMMON_HOME`、`HOME.CC` 或 `com.tcl.app.type` 的处理逻辑；spike 的这些声明没有改变解析结果。
- 这些声明只能在国产固件上验证，属于 PLAN 4.1 的真机项。

## 结论

- `cmd package set-home-activity` 在五台模拟器上都“返回成功但不生效”，系统桌面被优先级（priority=2）或厂商钉住。
- 验收工具 `prepare()` 在 API≥24 上调用 `Device.set_home()`，并用 `home_component()`（`resolve-activity`）判断是否成功；按当前 AVD 行为，这个判断会失败，M1 的设备检查会在 `prepare` 阶段报 D000。
- 这不是本仓库代码能单独解决的：需要用户裁决验收工具是否应在测试期间停用系统桌面，或接受 ADR 变更。M0 报告将此列为阻塞项。
- 第三方 HOME 在 Google TV 上的默认桌面页、`COMMON_HOME` 兼容与国产固件行为仍只能真机验证。

原始证据：本项命令输出；各设备 `P01-handlers.json` 的 `home`/`homeDefault` 字段。

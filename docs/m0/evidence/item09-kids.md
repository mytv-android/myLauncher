# 第 9 项：儿童模式的执行手段（默认桌面后台拉起锁屏）

方法：spike 应用声明 HOME 与一个 `LockscreenActivity`。先用 `am start` 打开系统设置把 spike 置于后台，再向 spike 的导出 Receiver 发送显式广播，由后台上下文 `startActivity`。观察 `dumpsys activity activities` 的 resumed Activity。

| 设备 | 无豁免 | 悬浮窗 appop allow | HOME 角色/默认桌面 |
|---|---|---|---|
| api23 | 允许（LockscreenActivity） | 未测（无限制） | 无 `cmd` 命令 |
| api28 | 允许（LockscreenActivity） | 未测（无限制） | 系统桌面仍占 HOME |
| api31g | 阻止（Abort background activity starts） | 允许 | 阻止（launcherx 仍是实际 HOME） |
| api34g | 阻止 | 允许 | 未测（launcherx 仍是实际 HOME） |
| api36 | 阻止 | 允许 | 未测（tvlauncher 仍是实际 HOME） |

关键日志：

- api31g：`Background activity start ... allowed because SYSTEM_ALERT_WINDOW permission is granted.`
- api34g：`Background activity start ... allowed because SYSTEM_ALERT_WINDOW permission is granted.`
- api36：`Background activity start ... allowed because SYSTEM_ALERT_WINDOW permission is granted.`
- 无豁免时：`Abort background activity starts from <uid>`。

结论：

- Android 6/9 没有后台启动限制，儿童模式可正常工作。
- Android 12/14/16 的模拟器上，普通后台启动会被拦截；`SYSTEM_ALERT_WINDOW` appop 是唯一已验证可用的 standard 变体路径。HOME 角色/默认桌面在 Google TV 上不生效，因为系统桌面被钉住（见第 10 项）。
- `PACKAGE_USAGE_STATS` 只用于前台应用轮询，不改变 Activity 启动限制；M4 的儿童模式需要验收工具按 PLAN 授予悬浮窗与使用情况访问。
- 目标固件上的默认桌面后台启动豁免无法在模拟器验证，留给真机。

原始证据：各设备 `P-checks-results.json`；本项命令与日志记录在 `docs/m0/evidence/item09-kids.md`。

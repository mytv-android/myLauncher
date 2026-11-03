# 验收工具问题记录

## A-001：矩阵 AVD 上无法通过 `set-home-activity` 设置第三方默认桌面

- 发现于：M0，2026-11-03
- 检查编号：M1 `prepare()` 抛 `D000`；D102、D110、D114 及所有依赖 `ctx.home()` 的检查
- 严重性：阻塞 M1 设备验收

### 复现

在 matrix 内的任一 API≥24 AVD 上安装一个声明 HOME 的第三方 Activity（M0 用 `com.github.mytv.m0spike/.MainActivity`），然后执行：

```bash
adb -s <serial> shell cmd package set-home-activity --user 0 com.github.mytv.m0spike/.MainActivity
adb -s <serial> shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s <serial> shell am start -a android.settings.SETTINGS
adb -s <serial> shell input keyevent KEYCODE_HOME
adb -s <serial> shell dumpsys activity activities | grep -m1 mResumedActivity
```

### 实测

- api23：没有 `cmd`，无法执行。
- api28：`set-home-activity` 返回 `Success`；`resolve-activity` 仍是 `com.google.android.tvlauncher/.HomeScreen`；HOME 键仍是 tvlauncher；`cmd role` 服务不存在。
- api31g / api34g：`set-home-activity` 返回 `Success`；仍是 `com.google.android.apps.tv.launcherx`；`cmd role add-role-holder` 可执行但 HOME 键仍是 launcherx。
- api36：`set-home-activity` 返回 `Success`；仍是 `com.google.android.tvlauncher`；HOME 键仍是 tvlauncher。
- 对照：在 api28 上 `pm disable-user --user 0 com.google.android.tvlauncher` 后，`set-home-activity` 立即生效，`resolve-activity` 与 HOME 键都指向第三方桌面；恢复系统桌面后回到原状。

证据：`docs/m0/evidence/item10-home.md`，各设备 `docs/m0/evidence/api*/P01-handlers.json`。

### 期望行为

验收工具在设备测试期间应能把第三方桌面设为实际默认桌面，并在结束后恢复系统桌面。建议在 `prepare()` 设置默认桌面前停用系统桌面（`pm disable-user`），在 `teardown()` 恢复；或者改用能真正切换 HOME 的 AVD。

### 状态

等用户裁决。M0 的 P01–P04 不依赖默认桌面，因此 M0 可继续正式验收；M1 前必须解决。相关提案见 `docs/adr/ADR-001-home-default-on-tv-emulators.md`。

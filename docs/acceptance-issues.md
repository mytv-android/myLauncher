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

已由用户裁决：采用 ADR-001 方案 A，并授权实现方修改工具包。`acceptance/run.py` 已修改为在 `prepare()` 停用原系统桌面、在 `teardown()` 恢复并移除本桌面的 HOME 角色；工具包已重新冻结。

- 新根哈希：`988b6b447cedda7c0e00b64a4cd43bf2b2e6f9e3db03a6137ae911540ef157b0`
- 新压缩包：`C:\PythonProject\myLauncher-acceptance.zip`，SHA-256 `1cb8470859f9b2639cc7bd2a1e19fb42c15722b038c8ea14778c26111db3e5b6`
- 已在 api36 上验证 D101 通过，teardown 后原系统桌面已恢复。M1 起使用新工具包。

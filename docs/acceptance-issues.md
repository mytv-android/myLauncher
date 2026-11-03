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

## A-002：api23 的 `pidof` 不按进程名过滤，D114 无法通过

- 发现于：M1，2026-11-03
- 检查编号：D114（api23）
- 严重性：阻塞 M1 在 api23 上的设备验收

### 复现

在 matrix 的 api23 AVD（Television_720p_2，Android 6.0.1）上执行：

```bash
adb -s emulator-5564 shell pidof com.github.mytv.launcher
adb -s emulator-5564 shell pidof com.github.mytv
adb -s emulator-5564 shell pidof init
adb -s emulator-5564 shell ps | grep -E 'mytv|backuptest'
```

### 实测

- 三条 `pidof` 都输出系统内**全部** PID：`1 2 3 4 5 6 7 8 … 1636 1637 1638 1639`，与参数无关；`pidof init` 也不只输出 1。
- `ps` 正常工作，能按进程名列出 `com.android.backuptester` 等进程。
- 触发 D114 的判据：`acceptance/mla/device.py` 的 `Device.pid()` 先执行 `pidof <包名>`，只要有输出就返回第一个 token。api23 上该值恒为 `1`，于是 `need(not ctx.dev.pid(PKG), "am kill 后桌面进程仍在")` 恒失败。
- D114 的 `am kill` 本身是成功的：logcat 有 `ActivityManager: Killing 9378:com.github.mytv.launcher/u0a76 (adj 7): kill background` 与 `Spurious death for ProcessRecord …`，随后按 BACK 也重新拉起了桌面进程。

证据：`acceptance/out/20261103-150934-M1-device-api23/`（D114 单独复现报告）、`acceptance/out/20261103-144704-M1-dev/`（完整 api23 报告，含 D114 失败）、该次运行的 logcat。api23 的 toolbox `pidof` 在 Android 6.0.1 上就是这个行为，与本仓库代码无关。

### 期望行为

`Device.pid()` 不应把 `pidof` 的任意输出当作匹配结果。建议在 API < 26 上直接用 `ps` 按包名匹配（或校验 `pidof` 输出长度/与包名对应），这样才符合 D114 的原意“确认进程已被回收”。

### 状态

待用户裁决。未修改工具包；D114 之外的 api23 检查不受影响。

## A-003：D199 的禁用词扫描把应用名也当成桌面文案

- 发现于：M1，2026-11-03
- 检查编号：D199（api23）
- 严重性：阻塞 M1 在 api23 上的设备验收

### 复现

在 api23 上安装全部夹具，进入应用页，遍历一次应用页（D105 的蛇形走法即可），D199 汇总本设备无障碍树后报：

```
「BACKUP TEST」禁用词 /(?i)\btest\b/ @ api23/013-walk-75
```

### 实测

- `BACKUP TEST` 是 api23 系统镜像自带的应用 `com.android.backuptester` 的名称：

```bash
adb -s emulator-5564 shell dumpsys package com.android.backuptester | grep -E 'flags=|firstInstallTime'
# flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]
# firstInstallTime=2020-07-21 04:48:45
```

- 该应用有 Launcher Activity（`com.android.backuptester/.MainActivity`），没有 banner。按设计稿 §06“没有 banner 的应用用图标加名称合成”，应用页必须显示它的名称。
- `acceptance/mla/launcher.py` 的 `copy_violations()` 在检查 `exemptTagPrefixes`（含 `app:`）之前先跑禁用词，因此 `app:` 节点里的应用名也会命中 `(?i)\btest\b`。
- `CONTRACT.md` §5 与 `ui_copy.json` 的 `_note` 都写明“应用名不计”；同一份 `copy_violations()` 在禁用词阶段却未排除应用名。

### 期望行为

禁用词只应约束桌面自身的文案；位于 `app:`（以及 `hidden:app:`、`folder:` 等已豁免标签）子树里的应用名应跳过禁用词检查，或在 `content_strings()` 里加入已安装应用的名称。否则任何在名称里含 “test” 的应用都会让所有合规桌面在 D199 失败。

### 状态

待用户裁决。未修改工具包；该问题只在应用页展示了系统测试应用名称时触发。

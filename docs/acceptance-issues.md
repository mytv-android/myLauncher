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

已由用户裁决（2026-11-03）：授权修改工具包。

- 修改 `acceptance/mla/device.py` 的 `Device.pid()`：API < 26 直接解析 `ps`（`ps -A` 仅用于 API ≥ 26），不再使用 `pidof`。
- 重新冻结：根哈希 `fa89122330b63740bc69f48dca8c135c3dbf35963ac85124673599d8b7f66832`；压缩包 `C:\PythonProject\myLauncher-acceptance.zip`，SHA-256 `bd63bdbfc254088f38851bcc8bafda9eaf60382417bc3424d34a1f899fecb5fb`。
- 验证：api23 上 D114 PASS（`acceptance/out/20261103-171038-M1-dev/`）。

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

已由用户裁决（2026-11-03）：授权修改工具包。

- 修改 `acceptance/mla/launcher.py` 的 `copy_violations()`：先判断节点的豁免标签子树（`exemptTagPrefixes`、小组件宿主），豁免子树同时跳过禁用词与白名单；其余文字仍按原规则检查。
- 与 A-002 同一次重新冻结：根哈希 `fa89122330b63740bc69f48dca8c135c3dbf35963ac85124673599d8b7f66832`；压缩包 SHA-256 `bd63bdbfc254088f38851bcc8bafda9eaf60382417bc3424d34a1f899fecb5fb`。
- 验证：api23 上 D105/D110 的无障碍树汇总后 D199 PASS（`acceptance/out/20261103-171038-M1-dev/`），系统应用名 `BACKUP TEST` 不再误报。

## A-004：D202 把路径上其他应用的主题切换当成低彩度来源的结果

- 发现于：M2，2026-11-03
- 检查编号：D202（api31g；api23/api28 同样适用）
- 严重性：阻塞 M2 设备验收

### 复现

D202 先让焦点停在「棱镜直播」（`prism`），等 1.4 秒并 `settle()` 后记录 `theme.seed`；再用 `seek(..., ("RIGHT", "LEFT"), 12)` 找「文件管理」（`files`），等 1.4 秒并 `settle()` 后再次记录种子，要求两者 ΔE < 3。

应用行按安装时间倒序显示。D202 开始前 `ctx.F.install(*PROTO_APPS)` 会按 dolphin、spark、cloud、prism、pulse、sky、files… 的顺序重装，当前行序为：

```
book, fit, store, files, sky, pulse, prism, cloud, spark, dolphin
```

从 `prism` 到 `files` 的每格路径是 `prism → pulse → sky → files`（先向右走到头，再向左）。`seek()` 每次按键后 `press()` 都会 `settle()`；本机一次 `content query` 约 1.05 秒，`settle()` 约 3.4 秒，两次按键之间约 5.2 秒。桌面按设计稿 §07 在焦点停留 180ms 后切换主题，因此 `pulse`、`sky` 的主题在各自焦点停留期间正常生效。最后 `files` 是低彩度来源，按设计保持当前全局主题（即 `sky`）。

### 实测（api31g，2026-11-03）

在 emulator-5562（Television_1080p）上按 D202 的步骤逐键记录焦点与 `theme.seed`：

```
 83.20s RIGHT -> prism  #F7AC19
 94.95s RIGHT -> cloud  #39B77B
100.27s RIGHT -> spark  #DB354C
105.48s RIGHT -> dolphin #1B97A5
110.65s RIGHT -> dolphin #1B97A5（到头）
117.85s LEFT  -> spark  #DB354C
123.17s LEFT  -> cloud  #39B77B
128.92s LEFT  -> prism  #F7AC19
134.16s LEFT  -> pulse  #C257D0
139.55s LEFT  -> sky    #468AE9
144.62s LEFT  -> files  #468AE9
before #F7AC19, after #468AE9
```

`files` 的图标是灰色（`acceptance/fixtures/art/ico_files.png` 的主要颜色为 #606163 等），本地取色返回 `LowChroma`，聚焦 `files` 没有产生新的主题过渡；`after` 的 `#468AE9` 是路径上 `sky` 的合法主题，不是 `files` 的回退蓝 `#4285f4`。D202 的失败信息把路径上的 `sky` 误判成 `files` 的结果。

证据：本节的逐键时间线；`acceptance/out/20261103-202332-M2-device-api31g/results.json` 中 D202 的 `message`；`acceptance/out/20261103-205544-M2-device-api31g/`（复跑）。

### 期望行为

「低彩度来源不改变全局主题」只约束 `files` 自身，不能约束从 `prism` 到 `files` 路径上其他应用的合法主题切换。检查应满足以下任一条件：

1. 确保移动发生在两个相邻项之间（例如先聚焦 `files` 的相邻项，再移到 `files`）；或
2. 在 `files` 获得焦点后比较「聚焦瞬间的种子」与「稳定后的种子」，并断言 `counters.themeTransitions` 在 `files` 聚焦期间没有增加、`theme.sourceKey` 不是 `files` 的键；不要与路径起点的 `prism` 种子比较。

按当前实现（每步之间约 5.2 秒的停顿），任何符合设计稿 180ms 焦点停留规则、且在 `files` 上保持当前主题的桌面都会在 D202 失败；除非它刻意让路径中间的应用不切换主题，这与设计稿 §07 相矛盾。

### 状态

待用户裁决。实现方未修改 `acceptance/` 下的任何文件。

## A-005：api31g 上 `KEYCODE_TV_INPUT` 不会送达到应用窗口

- 发现于：M4，2026-11-04
- 检查编号：D407（api31g，Television_1080p，Android 12 Google TV）
- 严重性：阻塞 M4 在 api31g 上的设备验收

### 复现

在 api31g 上把被测桌面设为默认桌面并置于前台，然后执行：

```bash
adb -s emulator-5562 shell input keyevent KEYCODE_TV_INPUT
adb -s emulator-5562 shell dumpsys activity activities | grep -m1 mResumedActivity
```

D407 期望桌面显示信号源浮层（`overlay=inputs`）。实测浮层不出现，前台仍是桌面本身。

### 实测

- 桌面的 `Activity.dispatchKeyEvent` 记录所有到达的按键。同一窗口里 `MENU`(82)、`DPAD_UP`(19)、`DPAD_DOWN`(20)、`DPAD_CENTER`(23) 都能收到；`KEYCODE_TV_INPUT`(178) 从未到达（日志见下方证据）。
- `dumpsys input` 的 `RecentQueue` 里能看到两次 `KeyEvent`（按下/抬起）已入队，但之后没有任何窗口收到该事件；`dumpsys window` 的 `mCurrentFocus` 一直是被测桌面。
- 对照：系统自带的 Google TV 桌面（`com.google.android.apps.tv.launcherx`）在前台时发送同一按键，也没有出现信号源面板，前台 Activity 不变（`acceptance/out/20261104-062133-M4-device-api31g` 之外的手工复现截图）。
- 同一台设备上 D407 的失败信息始终是 `overlay=None，前台 com.github.mytv.launcher/...MainActivity`，说明按键没有送达应用，而不是浮层实现有误（`LauncherModel.openInputs()`、`MainActivity.dispatchKeyEvent` 均已实现，MENU/方向键路径正常）。

证据：`docs/m4/evidence/d407-tv-input-dispatch.txt`（本机 logcat 摘录与 dumpsys 输出）。

### 期望行为

`KEYCODE_TV_INPUT` 在 Android TV 上属于系统键，可能由 WindowManager 策略层消费。请确认：

1. 验收工具在矩阵设备上是否应当用另一种方式触发信号源页（例如 `am broadcast`、或验收工具提供的测试入口）；
2. 或者明确本需求在 API 31+ Google TV 上不可由第三方桌面实现，需要 ADR 调整设计稿 §14「信号源键打开信号源页」的验收方式。

实现方不修改验收工具包，也未用任何方式绕过系统按键分发。

### 状态

待用户裁决。实现方已实现该按键的正常分发路径（在事件能到达应用时生效），但本机平台不把该键送达应用窗口，D407 无法通过。

## A-006：D406 改名后 D413 仍要求夹具原始名称

- 发现于：M4，2026-11-04
- 检查编号：D413（同一轮中 D406 先执行；api31g）
- 严重性：阻塞 M4 设备验收

### 复现

```
python acceptance/run.py device M4 --serial emulator-5562 --name api31g --only D406,D413
```

D406 通过后紧接着执行 D413，结果：

```
[ FAIL] api31g  D413   长辈模式：大磁贴每排 4 个、名称常显、强制高对比焦点框  - 没有常显名称：['dolphin']
```

### 实测

- D406 的协议步骤里有 `POST /api/apps/label`，把 `com.github.mytv.mlfixture.dolphin/…LauncherActivity` 改名为「改名测试」；D406 末尾还断言主页应用卡片必须显示「改名测试」，即改名要作用到桌面的应用名称并保持。
- D413 用 `acceptance/fixtures/content.json` 的原始名称（海豚视频）逐个比对磁贴文字：

```python
missing = [f for f in allowed if not any(label(f) in t for t in tree.find(app_tag(f)).texts())]
```

- 此时海豚视频的磁贴显示的是「改名测试」，因此必然缺失。无障碍树证据里五个磁贴名称都在，只有 dolphin 一项是「改名测试」：`acceptance/out/20261104-132159-M4-device-api31g/api31g/002-elders.xml`。
- 改名是本项目的产品功能（设计稿 §14「手机整理：改名…」，AppNames 持久保存），不是测试钩子；D406 自己要求它生效且保留。

### 期望行为

D413 不应假设夹具的原始名称。可选做法：

1. D406 结束时通过 `POST /api/apps/label` 把名称改回原值；
2. D413 对每个应用先经 `GET /api/apps` 读取当前名称，用当前名称比对磁贴；
3. D413 不比对具体文字，只要求磁贴内存在非空名称文本。

否则任何正确实现「改名并保持」的桌面都会在完整 M4 跑批里被 D413 判为失败。

### 状态

待用户裁决。实现方未修改 `acceptance/` 下的任何文件。

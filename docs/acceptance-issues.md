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

已由用户裁决（2026-11-05）：授权修改工具包。

- 修改 `acceptance/mla/checks.py` 的 D202：先移动到「文件管理」，记录聚焦瞬间的 `counters.themeTransitions`；稳定 1.4 秒后要求过渡次数不增加、`theme.sourceKey` 不是 `files`。不再与路径起点的种子比较。
- 与 A-005、A-006、A-007、A-008 同一次重新冻结：根哈希 `1d454045d27f76d39702da04addc78347239118d70c16efe01eb18000efa9695`；压缩包 `C:\PythonProject\myLauncher-acceptance.zip`，SHA-256 `6316fc24ce7f90de09329e7be04e53e11f89f10e66d226abdebb5f41de700f56`。
- 验证：api31g 上 D202 PASS（`acceptance/out/20261105-084541-M4-device-api31g/`）。

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

已由用户裁决（2026-11-05）：授权修改工具包。

- 修改 `acceptance/mla/checks33.py` 的 D407：先按原方式发送 `KEYCODE_TV_INPUT`；浮层没有打开时，改用快速设置的「信号源」（`qs:inputs`）打开同一个 `inputs` 浮层并验证 BACK 关闭，结论附带说明「KEYCODE_TV_INPUT 未被平台送达应用窗口」。按键分发路径仍由产品实现（`MainActivity.dispatchKeyEvent`），在能送达按键的设备上走原路径。
- 验证过程中发现并修复了一个产品缺陷：没有信号源时 `inputs` 浮层内没有可聚焦节点，状态接口焦点仍是已消失的 `qs:inputs`，快照会触发 STATE_MISMATCH。产品改为空列表时让浮层容器本身以 `inputs` 标签可聚焦，打开时把焦点放上去；D405 与 D407 均通过。
- 已随 A-004 同一次重新冻结（根哈希 `1d454045…`，压缩包 SHA-256 `6316fc24…`）。
- 验证：api31g 上 D405/D407 PASS（`acceptance/out/20261105-090013-M4-device-api31g/`）。

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

已由用户裁决（2026-11-05）：授权修改工具包。

- 修改 `acceptance/mla/checks33.py` 的 D413：不再与夹具原始名称比对，只要每个允许应用的磁贴里存在非空名称文字即通过（设计只要求「名称常显」）。
- 已随 A-004 同一次重新冻结（根哈希 `1d454045…`，压缩包 SHA-256 `6316fc24…`）。
- 验证：api31g 上 D413 PASS（`acceptance/out/20261105-084541-M4-device-api31g/`）；用改名后的旧证据 `074-elders.xml`（海豚视频显示为「改名测试」）离线核对，新判据不再报缺失。

## A-007：D103/D199 把设备上真实应用发布的 Watch Next 当成桌面内容与文案

- 发现于：M4，2026-11-04
- 检查编号：D103、D199（api34g）
- 严重性：阻塞 M4 在 api34g 上的设备验收

### 复现

api34g（Television_4K）上装有真实应用 `com.github.mytv.android`，它已经在系统 TvProvider 中发布了 Watch Next。按正式验收顺序执行 D101→D103→…→D199，结果：

```
[ FAIL] api34g  D103  没有内容来源时：无 Hero、无继续观看和片单，应用行为第一行，焦点在第一个应用  - 没有内容时仍显示 Hero
[ FAIL] api34g  D199  界面文案：只有白名单内的产品文字，没有提示性文字  - 「4K超高清电影 4K」不在 ui_copy 白名单 @ api34g/001-home-key
```

### 实测

- D103 的 `ctx.F.cleanup()` 只清理验收夹具，不清除设备上真实应用的 TvProvider 行。D103 的截图状态里 `continue` 行有 1 项 `wn:com.github.mytv.android:1932832475`，Hero 取自该行。这是设计要求的正确行为（继续观看读取系统 TvProvider 的全部来源）。
- D199 命中的「4K超高清电影 4K」是该 Watch Next 卡片的节目标题。CONTRACT「文案」一节写明“屏幕上属于桌面自身的文字”才受白名单约束，夹具内容、应用名、第三方小组件内部文字不计；`acceptance/mla/launcher.py` 的豁免只覆盖 `app:` 子树与小组件宿主，没有覆盖 `wn:`。
- 证据：`acceptance/out/20261104-201108-M4-accept/api34g/001-home-key.xml`、`api34g/002-empty-home.state.json`、`api34g/002-empty-home.png`。

### 期望行为

1. D103 在“没有内容来源”的用例前清除系统 TvProvider 的 Watch Next（或把设备上的真实内容应用卸载/停用），否则任何正确读取 Watch Next 的桌面都会失败；
2. D199 的豁免范围加入 `wn:`（以及频道行里的 `prog:`）子树，或把现有 TvProvider 内容的标题加入白名单。

### 状态

已由用户裁决（2026-11-05）：授权修改工具包。

- 修改 `acceptance/mla/checks.py` 的 D103：开场检测系统 TvProvider 中是否有夹具之外的应用发布的 Watch Next；存在时以 `Skip("设备上有其他应用发布的 Watch Next…")` 结束，不再误判。
- 修改 `acceptance/mla/launcher.py` 的 `copy_violations()`：Hero 内容根（最近的非空标签为 `hero`）下的文字都是外部内容（标题、副标题、元数据），整体豁免；`hero:play` 等按钮仍按白名单检查。`wn:`、`prog:` 等子树原本已在 `exemptTagPrefixes` 中。
- 已随 A-004 同一次重新冻结（根哈希 `1d454045…`，压缩包 SHA-256 `6316fc24…`）。
- 验证：api31g 上 D103/D199 PASS（`acceptance/out/20261105-084541-M4-device-api31g/`）；用正式验收里的 `api34g/001-home-key.xml`、`002-empty-home.xml` 离线复核，`copy_violations()` 不再报违规。

## A-008：prepare() 停用整个原桌面包，连带停用了系统设置

- 发现于：M4，2026-11-04
- 检查编号：D404（api31g）
- 严重性：阻塞 M4 在 api31g 上的设备验收（环境相关）

### 复现

api31g 的默认桌面被禁用（`com.google.android.apps.tv.launcherx` 的 `enabled=3`），系统解析到 `com.android.tv.settings/.system.FallbackHome`。此时执行：

```
python acceptance/run.py device M4 --serial emulator-5562 --name api31g --only D404
```

D404 在「Wi-Fi 打开系统网络设置」处失败：前台仍是 `com.github.mytv.launcher/.MainActivity`。

### 实测

- 设备日志显示 `openWifiSettings()` 对 `android.settings.WIFI_SETTINGS` 与 `android.settings.SETTINGS` 都抛 `ActivityNotFoundException`；两个 Activity 的 `exported=true`，手动 `am start -a android.settings.WIFI_SETTINGS` 能正常打开 `com.android.tv.settings/.connectivity.NetworkActivity`。
- prepare() 记录的原桌面是 `com.android.tv.settings/.system.FallbackHome`，于是停用了整个 `com.android.tv.settings` 包；该包同时提供系统设置页，导致检查期间设置页不可解析。teardown 后单独复跑 D404 通过。
- 对照 api34g：原桌面是 `com.google.android.apps.tv.launcherx`，只停用 launcherx，D404 通过。

### 期望行为

prepare() 不应在停用原桌面时连带停用同一包里的其他系统入口（如 FallbackHome 属于系统设置）。

### 状态

已由用户裁决（2026-11-05）：授权修改工具包。

- 先尝试只停用原桌面的 Activity 组件，但 Android 12 的 shell 不允许修改组件状态：`SecurityException: Shell cannot change component state for … to 3`。该方案不可行。
- 改为在 `acceptance/run.py` 的 `prepare()` 里做环境归一化：当前桌面是 `FallbackHome` 时，先启用已安装的原厂桌面（`com.google.android.apps.tv.launcherx/.home.HomeActivity` 或 `com.google.android.tvlauncher/.HomeScreen`），重新解析出的原桌面即为独立包，再按原逻辑停用该包。报告 meta 增加 `fallbackHome` 记录归一化前的桌面。
- 已随 A-004 同一次重新冻结（根哈希 `1d454045…`，压缩包 SHA-256 `6316fc24…`）。
- 验证：在 api31g 上把 launcherx 置为禁用、桌面回到 FallbackHome 后运行 D404/D103/D202/D406/D413，D404 通过（prepare 自动恢复 launcherx 并正常切换桌面），其余检查无回归（`acceptance/out/20261105-084541-M4-device-api31g/`）。

## A-009：按“每台模拟器 10 分钟”重做测试执行层

- 发现于：M4，2026-11-05
- 检查编号：全部设备检查（耗时问题）；D403（浸泡窗口）
- 严重性：阻塞验收节奏（单设备 61–118 分钟）
- 用户指示：每台模拟器的测试限定在 10 分钟以内，严格按此重新设计测试（2026-11-05）

### 原因

- `content query` 每次约 0.96 秒（设备端每次冷启 app_process）。`settle()` 每次按键至少 3 次状态查询，`seek()` 每步 4–5 次，D115 的 120 步随机遍历单独就接近 500 秒。
- `uiautomator dump` 每次约 2 秒；检查里有大量未被断言使用的 dump 与 `sleep`。
- `Fixtures.install()` 在每个检查开头重复安装 10 个夹具，即使已经安装。
- D403 的 3 分钟浸泡是工具包自定的窗口，设计稿与 CONTRACT 没有规定 3 分钟。

### 修改（工具包 + debug 构建）

1. debug 状态接口增加仅绑定 `127.0.0.1:8765` 的 HTTP 传输（同一份 `StateHub.json()`；`GET /state`、`GET /call?method=&arg=`），ContentProvider 保留。CONTRACT §3 已同步。release 构建不含该代码。
2. `Launcher.state()` 走 HTTP（约 15ms），`settle()` 以 40ms 轮询、两次稳定即返回；`seek()`/`seek_state()` 每步一次状态查询；`press()` 统一保证两次 OK 间隔 600ms（契约 §5）。
3. `Fixtures.install()` 跳过已安装夹具；`publish` 先全部广播再逐项校验。
4. 删除冗余等待与未被断言的 dump；D304 收齐全部比例后提前结束；D115 只在 BACK 后查前台、每 30 步取一次证据。
5. D403 改为 60 秒窗口（0/30/60 秒三次时钟位置采样），标题同步改为“60 秒”。时钟每分钟漂移一次，60 秒窗口仍能覆盖一次漂移。
6. `run.py` 记录每台设备的 `deviceSeconds`（含启动与回收），超过 `spec/matrix.json` 的 `deviceBudgetSeconds`（600）时新增 D000 FAIL，避免超时静默通过。
7. 启动桌面统一用 HOME 隐式意图并先 `am force-stop`，避免显式组件启动在 standard 任务里再叠加一个 home 任务实例（两个 MainActivity 会让 uiautomator 取到已停止实例的无障碍树）。D101 冷启动同样处理。
8. 同一 AVD 并行三个实例（`instancesPerDevice=3`，都以 `-read-only` 启动，各自临时覆盖层），按依赖安全的簇分组：a 跑 D1xx+渠道，b 跑视觉+桌面设置+浸泡，c 跑系列+小组件+受限/屏保；D199 在合并无障碍树后统一判定。所有检查仍各执行一次，编号、断言与设备型号不变。
9. 模拟器改用 `-gpu host`：原先 headless 启动落在 SwiftShader 软件渲染，1920×1080 下每帧 30–100ms，三项并行时 CPU 被渲染吃满。宿主 RTX 4060 上 `dumpsys SurfaceFlinger` 报告 NVIDIA GLES，启动从约 35 秒降到约 24 秒，单台设备的检查总耗时从约 1500 秒降到约 1100 秒。
10. 启动/回收加固：同一 AVD 的 read-only 实例错开 4 秒启动（同时启动会在 `sdcard/userdata` qcow2 上抢锁，报 Permission denied）；启动失败时按进程树强杀并重试一次；启动前清理同 AVD 的残留 qemu；非 read-only 启动改用 `-no-snapshot`（`-no-snapshot-save` 仍会改写 snapshots 元数据）；`adb emu kill` 后最多等 30 秒优雅关机，避免强杀把 userdata qcow2 留脏导致后续 read-only 实例起不来。
11. D412 的 5 次错误 PIN 用一次 `input keyevent` 注入 20 个数字键（应用每 4 位校验一次），快速设置里用一次 10 连 DOWN 越过面板前半段，均只通过真实按键路径。
12. 页面/行导航统一走 `open_qs` 与状态轮询：快设浮层最多等 4 秒再判定，避免浮层尚未上屏就被 BACK 关掉又重试；行内目标先等状态接口出现该项再按键。应用行只显示更新时间前 10 个（真实应用会挤占），依赖“某夹具在行内”的检查改为动态选取行内实际存在的夹具。

### 验证

2026-11-06 正式验收 M4（`acceptance/out/20261106-095743-M4-accept/`，结论通过）：api28 449 s、api31g 497 s、api34g 395 s，均在 600 s 预算内。三实例分组、夹具并发安装、D115 40 步等改动落在同一版工具包（根哈希 `b5275277…`）。

补充结论：

- 曾试过 4 实例：宿主同时跑 4 个 1080p Google TV 实例后，检查普遍变慢，且把检查移出原分区会破坏它们对「同分区先装好夹具/先建立状态」的隐性依赖（D111 从 2.5 s 涨到 111 s、D401 从 12 s 涨到 183 s），api31g 反而到 675 s，已回退。
- 夹具安装仍是主要固定成本（单个约 7 s）：改为 3 线程并发安装，失败的串行重试；只用到部分夹具的检查按需安装。
- 宿主负载会显著影响墙钟（同一套检查在宿主繁忙时 api34g 曾到 855 s），预算判定按 600 s 硬性执行。

## A-010：api28 上无障碍树取不到（焦点永远停在同一个磁贴）

- 发现于：M4，2026-11-05
- 检查编号：api28 上所有 `snap()`（表现为 STATE_MISMATCH），D316
- 严重性：阻塞 api28 的全部界面检查

### 现象

api28 上 `snap()` 反复报 `STATE_MISMATCH：无障碍树 app:com.github.mytv.mlfixture.files/…，状态接口 wn:…/hero:…/picker:auth:adb`。无论焦点在继续观看、Hero 还是小组件授权页，无障碍树里的焦点节点始终是同一个应用磁贴；保存下来的 XML 里 `focused="true"` 也确实只有那一个节点。D316 则在授权页报 `uiautomator dump 失败`。

### 实测与原因（三层叠加）

1. 继续观看卡片底部的 Wavy 进度条使用 Material3 的定值波形，默认 `waveSpeed` 等于波长，波形一直平移。首页因此永远拿不到无障碍空闲态，`uiautomator dump` 直接以 `ERROR: could not get idle state.` 退出，不写文件。对照：同设备在设置页、应用页（没有波形进度条）都能正常 dump；`dumpsys gfxinfo` 显示帧数不变，说明是绘制动画而非布局抖动。
2. `Device.dump()` 的兜底路径在 dump 失败后直接 `cat /data/local/tmp/mla_ui.xml`，没有先删除旧文件，于是把上一次（甚至上一轮）的树当成当前树返回——焦点“永远停在 files 磁贴”就是这么来的。
3. api28 的 `AppWidgetServiceImpl$AppWidgetManagerLocal.getHostedWidgetPackages()` 在遇到 provider 为空的小组件记录时抛 NPE。桌面在展示“用电脑授权”页时已经 `allocateAppWidgetId()` 但尚未绑定，正好产生这种记录；无障碍服务取根节点时在 system_server 里被远程异常打断，uiautomator 进程崩溃。上一轮 teardown 先删夹具再卸载桌面，又会在系统里留下带空 provider 的小组件记录，使下一轮在打开选择器/授权页时必然复发。

### 处理

- 产品：`LauncherWavyProgress` 传 `waveSpeed = 0.dp`，波形静止（形状不变，界面空闲）。
- 产品：没有系统确认界面、走授权指令页时，不在授权前保留已分配的组件 ID；授权完成后再分配并绑定（有系统确认界面的路径仍沿用原 ID，供系统界面绑定）。
- 工具包：`Device.dump()` 兜底前先 `rm -f` 目标文件，且只在文件存在时读取，绝不返回旧树。
- 工具包：prepare/teardown 改为先卸载桌面、再清理夹具，避免桌面挂着夹具小组件时先删 provider；`Fixtures.cleanup()` 在卸载前先广播 `unpublish`，避免夹具写入 TvProvider 的继续观看行残留（否则 D103 的“没有内容来源”场景不成立）。

### 状态

用户授权修改工具包（2026-11-05），随 A-009 一起重新冻结。

## A-011：夹具安装与 TvProvider 残留

- 发现于：M4，2026-11-05
- 检查编号：全部设备检查（耗时）；D103（残留行）
- 严重性：影响每台模拟器 10 分钟预算

### 实测

- 单个夹具 APK 的 `pm install` 在并行实例下要 4–14 秒，一轮里每个实例都要装 10 个以上夹具，安装本身就占掉 1–2 分钟，是单台设备用时的主要部分。
- 曾尝试把夹具常驻 AVD userdata（读写启动安装一次、写标记文件、后续轮次跳过安装），读写启动之间确实能保留；但工具包的多实例用 `-read-only` 启动，读到的 userdata 不是当前 qcow2 覆盖层（例如 `/data/local/tmp` 里只有 10 月的旧文件），常驻方案在并行实例上无效，已撤回。
- `adb emu kill` 不经过客户机关机流程，页缓存里的写入会丢；`Device.kill()` 现在先 `sync` 再关机（对读写启动的持久化有效）。
- 2026-11-06 复测：在 `Television_1080p`（api31g）上读写启动安装 15 个夹具并 `sync`，随后 `-read-only -no-snapshot` 实例里 `pm list packages com.github.mytv.mlfixture` 仍为空。只读实例的用户数据不是当前 `userdata-qemu.img` 的内容（与 `/data/local/tmp` 的旧文件现象一致），预装方案在本工具包的并行实例模式下不可用，维持「每实例各自安装」。

### 修改（工具包）

- `Fixtures` 记录本轮真正 `publish` 过的夹具包名，teardown 只给它们广播 `unpublish`；prepare 仍对全部已安装夹具 `unpublish`，清掉上一轮异常退出留下的行（否则 D103 的“没有内容来源”场景不成立）。
- 常驻设备上不再卸载夹具（read-only 覆盖层随实例销毁），省掉 teardown 的卸载时间。
- `Device.install()` 遇到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 时能解析 `Existing package …`，先卸载再重装（`series` 与 `series_impostor` 共用包名、签名不同）。

### 结果

单台设备的总用时通过分组均衡与减少按键/取树次数控制在预算内，见 A-009 的验证一节。

### 状态

用户授权修改工具包（2026-11-05），随 A-009/A-010 一起重新冻结。

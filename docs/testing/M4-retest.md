# M4 回归测试说明（给测试执行方）

## 目的

m4 分支在上次正式验收通过（提交 `fb0dbd5`，报告 `acceptance/out/20261106-095743-M4-accept/`）之后又改了一批界面。这次测试要回答两件事：

1. 这些改动有没有引入回归；
2. 跑出一份新的正式报告（结论"通过"、`formal: true`）。

**你只负责测试和报告，不改代码、不提交、不改文档。** 发现问题按第 6 节回报，由实现方修。

## 1. 必须遵守

- 遵守仓库根目录 `AGENTS.md` 的硬规则，其中与测试有关的：
  - 不改 `acceptance/`、`AGENTS.md`、`PLAN.md`、`CLAUDE.md`、`tv-launcher-design-v3.html`；
  - 不手工编辑 `acceptance/out/` 下的任何文件；
  - `--no-build`、`--devices`、`--only` 跑出的结果只能当自查，不能当正式结果。
- 设备：
  - 只用 `acceptance/spec/matrix.json` 里的 AVD。M4 用到 api28 = `Television_720p`、api31g = `Television_1080p`、api34g = `Television_4K`；
  - 不 wipe-data，不删除、不新建快照，不改 AVD 配置；
  - 不碰 `emulator-5554`（用户的手机模拟器），也不碰矩阵外的 AVD。目前 `emulator-5566` 是 `TV_Android9`，属于别的会话。
- 不改 myBV、mydv、mytv-android 的代码与仓库。
- 同一时间只跑一个验收命令。验收运行期间不要构建（`gradlew`），因为检查中途会重装 `app-standard-debug.apk`。

## 2. 被测对象

- 仓库：`C:\PythonProject\myLauncher`，分支 `m4`。
- 提交：`git log -1` 应为本说明所在的提交或更新的提交。开始前记下 `git rev-parse HEAD`。
- 上次通过以来的改动：

| 提交 | 内容 | 重点关注的检查 |
|---|---|---|
| 2d4e217 | 海报与台标经 Coil + OkHttp 从网络加载（ADR-003） | D201–D204、D304–D306 |
| d1c7713 | Hero 按 PLAN 3.1 取频道精选、补足继续观看，最多 5 个，可左右切换、9 秒自动轮播 | D102、D106、D109、D118、D204 |
| 267425e | 快速设置按原型重做（网格、音量条、分段选择），顶栏搜索与环境模式按钮接通 | D120、D212、D404、D409 |
| 19e0cf0 | 时钟小组件按 OK 进入环境模式 | D322–D328、D401 |
| 713b7f4 | 小组件选择器改为右侧玻璃面板，画在顶栏之上 | D316–D326 |
| 56d8075 | 修复：Hero 在行内时按钮不可聚焦导致 BACK 链断；主题无动画切换后屏幕与状态接口不一致 | D108–D110、D120、D202、D204、D207 |
| 49f53f6 | 顶栏方案 A：天气与时钟并入工具胶囊，日期与农历移到快速设置标题下（ADR-004） | D207–D210、D212、D327、D328 |

- 上一轮在 api34g 上的自查里还有四项没查清。当时设备被别的会话干扰，下面列的可能是产品问题，也可能是干扰造成的。复现与否请明确回报：

| 检查 | 当时的失败信息 |
|---|---|
| D114 | `am kill` 后桌面进程仍在（无法模拟回收） |
| D119 | 菜单里没有「移出文件夹」（menu:unfolder） |
| D121 | HOME 恢复焦点或触屏操作发出了音效：`{'nav': 3, 'click': 1, 'back': 0, 'invalid': 2}` |
| D305 | 管理频道页找不到 `channels:com.github.mytv.mlfixture.prism:prism.live` |

## 3. 设备独占（最重要）

2026-11-06 的测试被其他会话干扰过，现象如下：

- 有脚本在 adb shell 里执行 `pm disable-user com.github.mytv.launcher`。包信息里 `lastDisabledCaller: shell:1000`，验收工具和桌面代码里都没有这条命令。随后它用 shell 拉起 `com.github.mytv.android`，播放 `mytv://play?url=http://10.0.2.2:8123/test.mp4`。
- 它同时作用于 emulator-5562（api34g）和新开的 emulator-5564（api31g）。
- 被停用之后，后面的检查全部报"状态接口没有返回（契约 §3）"。

所以：

1. **开始前先请用户确认**：没有其他会话在用 `Television_720p`、`Television_1080p`、`Television_4K`。正式验收会先 `emu kill` 这三个 AVD 正在运行的实例，再为每台各起 3 个 read-only 实例。如果别人正在用，这一步也会打断别人。
2. 看清当前在跑的模拟器：

   ```bash
   ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
   "$ADB" devices
   for s in $("$ADB" devices | awk '/^emulator-[0-9]+\tdevice/{print $1}'); do
     echo "$s $("$ADB" -s $s emu avd name | head -1 | tr -d '\r')"
   done
   ```

3. 整个测试期间在另一个终端开一个干扰监视，输出存档备查。验收工具从不停用桌面包，所以只要出现 `DISABLED`，就说明有外部干扰：

   ```bash
   ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
   while true; do
     for s in $("$ADB" devices | awk '/^emulator-[0-9]+\tdevice/{print $1}'); do
       [ "$s" = emulator-5554 ] && continue
       if "$ADB" -s $s shell pm list packages -d 2>/dev/null | tr -d '\r' | grep -qx 'package:com.github.mytv.launcher'; then
         echo "$(date +%T) $s 桌面被停用：$("$ADB" -s $s shell dumpsys package com.github.mytv.launcher | grep -m1 lastDisabledCaller | tr -d '\r')"
       fi
     done
     sleep 30
   done | tee "$TEMP/m4-interference.log"
   ```

4. 运行中一旦出现 `DISABLED`，让当前命令跑完。这样 teardown 能恢复设备状态。然后回报，这次结果作废，等用户协调好再重跑。

## 4. 准备

```bash
cd /c/PythonProject/myLauncher
git status --short          # 必须为空（S15）
git branch --show-current   # m4
git rev-parse HEAD
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

以下是已知的环境陷阱。遇到时先按这里处理，别当成产品缺陷报：

| 现象 | 原因与处理 |
|---|---|
| D404 等打不开系统设置页（ActivityNotFoundException） | Google TV 上 `com.google.android.apps.tv.launcherx` 被停用，系统桌面变成 FallbackHome，prepare 于是停用了整个系统设置包。处理：在该设备上 `pm enable com.google.android.apps.tv.launcherx` 后重跑 |
| 模拟器起不来或启动超时，`boot_completed` 为空 | 用户 0 卡在 `onStartUser`。api31g 可执行 `adb -s <serial> shell am start-user 0`。api28 上无效，报环境阻塞 |
| D103 显示 Skip | 设备上有真实应用（如 mytv-android）发布的 Watch Next，按 A-007 跳过，正常 |
| Hero 显示真实应用的节目（比如标题是 "10.0.2.2"） | PLAN 3.1：没有已批准频道时由继续观看补足，属正常行为 |
| D213 为 WARN | 模拟器上的帧时间只作参考，不阻塞 |
| D214 为 MANUAL | 由用户对照原型判断，见第 7 节 |

## 5. 执行

### 5.1 静态与单元测试（约 5 分钟，会从源码构建）

```bash
python acceptance/run.py static M4
```

期望结论"通过"：S01–S11、S13、S15–S17 全部 PASS（S12 从 M5 起检查，S14 只在 M0 检查）。S17 会自己跑 `gradlew test`。

### 5.2 单台自查（建议，约 15 分钟）

正式验收前先在一台 api31g 上跑一遍，便于尽早发现问题。目前 emulator-5564 就是一台非 read-only 的 api31g，没有的话用 `python acceptance/run.py boot api31g` 起一台。

```bash
python acceptance/run.py device M4 --serial emulator-5564 --name api31g
```

这一步复用 5.1 构建出的 APK，期间不要构建。

### 5.3 正式验收（约 40–60 分钟，不要中途打断）

```bash
python acceptance/run.py kit verify
python acceptance/run.py accept M4
```

- 设备矩阵：api28、api31g、api34g，各 3 个 read-only 实例并行。
- 门禁：S01–S17；D1xx–D3xx、D401–D413、D199 没有 FAIL；单元测试 T01–T14、T18–T21。
- 输出目录：`acceptance/out/<时间戳>-M4-accept/`，内含 `report.html`、`results.json`，以及按设备实例分的截图、状态、无障碍树。
- 记下 `results.json` 的 SHA-256：

  ```bash
  python -c "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" acceptance/out/<目录>/results.json
  ```

## 6. 有 FAIL 时

1. 不改代码，不改工具包。
2. 在同一类设备上单独重跑该项，最多两次，看能否复现：

   ```bash
   python acceptance/run.py accept M4 --devices api31g --only D119 --no-build
   ```

3. 收集证据：
   - 报告目录里该检查的 `NNN-*.png`、`*.state.json`、`*.xml`；
   - 崩溃日志：`adb -s <serial> logcat -d -b crash`；
   - 怀疑卡顿或 ANR 时，再看 `adb -s <serial> logcat -d -b events | grep -E "am_anr|am_crash|am_kill"`。
4. 先排除干扰再下结论。符合下面任一特征，按干扰处理：
   - 连续多项报"状态接口没有返回（契约 §3）"；
   - 干扰监视里出现 `DISABLED`；
   - `logcat -b events` 里有 `am_kill … change com.github.mytv.launcher`，或 shell 拉起了非夹具应用（`wm_create_activity` 后跟 `com.github.mytv.android` 等）。

## 7. 人工看界面（给用户判断 D214 用）

在 api31g（1080p）上截下面这些场景，与原型 `tv-launcher-design-v3.html` 中的同名场景并排放。原型直接用浏览器打开即可。截图命令：`adb -s <serial> exec-out screencap -p > 文件.png`，放进 `%TEMP%\m4-manual\`。

| 场景 | 怎么到 | 看什么 |
|---|---|---|
| 主页 Hero | 按 HOME | 海报是否从网络加载，标题、来源行、两个按钮、指示点；焦点在「片单」时再按右，切到下一个精选 |
| 浏览态 | Hero 上按下 | 信息头（来源、标题、元数据、简介）；行顶 y≈236dp；应用图标聚焦时变 Cookie 形 |
| 顶栏 | 任意页 | 右侧只有一块胶囊：搜索、环境模式、快速设置、分隔线、天气、时钟。明亮海报下文字也要清楚 |
| 快速设置 | 顶栏齿轮 OK | 标题下是日期与农历；磁贴网格、音量条、三组分段选择；面板不滚动就能显示全部条目 |
| 小组件页与选择器 | Tab「小组件」→ 添加 | 时钟小组件、虚线的添加磁贴；选择器是右侧玻璃面板，盖在顶栏之上 |

## 8. 回报内容

1. 被测提交（`git rev-parse HEAD`）。
2. 正式报告目录、结论、`formal` 值、`results.json` 的 SHA-256。
3. 所有 FAIL 与 WARN，按设备列出检查编号与失败信息原文。
4. 每个 FAIL 的复现情况（单独重跑两次的结果）和证据路径。
5. 第 2 节四项（D114、D119、D121、D305）是否复现。
6. 干扰监视日志（`%TEMP%\m4-interference.log`），以及测试期间看到的其他异常：别的模拟器、别的会话、设备卡死等。
7. 第 7 节的截图目录。

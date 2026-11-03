# 验收工具包（给用户看的说明）

验收工具包用来判断实现方是否按 `PLAN.md` 和设计稿完成了每个里程碑。它从外部观察桌面，看这几样东西：

- 构建产物；
- 依赖树和清单；
- 无障碍树、屏幕像素；
- 夹具应用在自己进程里记下的日志；
- debug 构建里的只读状态接口。

它不信任实现方的任何说法。

## 1. 防篡改

工具包冻结时生成三样东西：

- `acceptance/MANIFEST.sha256`：工具包里每个文件的哈希。它的整体哈希称为"根哈希"。
- `acceptance/spec/frozen_docs.json`：设计稿、`PLAN.md`、`AGENTS.md`、`CLAUDE.md` 的哈希。
- `C:\PythonProject\myLauncher-acceptance.zip`：整个工具包的压缩包。

两个哈希值记在交付说明里，请自己另存一份：

- 压缩包的 SHA-256；
- 根哈希。

**正式验收前**，用你手里的压缩包替换工程里的工具包，再运行：

```powershell
cd C:\PythonProject\myLauncher
certutil -hashfile ..\myLauncher-acceptance.zip SHA256      # 与另存的值一致
Remove-Item -Recurse -Force acceptance
Expand-Archive ..\myLauncher-acceptance.zip -DestinationPath .
python acceptance\run.py kit verify                          # 显示的根哈希与另存的值一致
python acceptance\run.py accept M1
```

建议把压缩包再复制一份到工程目录以外、实现方碰不到的地方。

检查 S01 与 S16 也会在每次验收时报告：

- S01：工具包被修改过；
- S16：设计稿、`PLAN.md`、`AGENTS.md`、`CLAUDE.md` 被修改过。

## 2. 一次正式验收做什么

`python acceptance\run.py accept Mx` 按顺序执行：

1. 在工程里执行 `gradlew clean` 并构建 standardDebug 与 standardRelease。不接受外部 APK。
2. 静态门禁 S01–S17：
   - 工具包与文档完整性；
   - git 工作区干净；
   - 包名与 minSdk；
   - APK 内的 androidx 版本（material3 必须是 1.5.0-alpha25）；
   - 依赖树（必需与禁止的库）；
   - 模块与依赖方向；
   - 源码规则（无 androidx.tv、无 TextureView、无中文字面量等）；
   - 清单；
   - 测试钩子只在 debug 里；
   - 文案白名单；
   - 片源许可；
   - Baseline Profile；
   - 必需的单元测试（S17）：执行 `gradlew test --continue`，按 `spec/required_tests.json` 核对测试类、数量与结果。
3. 按 `spec/matrix.json` 依次处理每台模拟器：
   1. 启动模拟器；已在运行的直接使用。
   2. 安装 debug 包并设为默认桌面。
   3. 跑本里程碑及之前所有里程碑的设备检查（累计回归）。
   4. 卸载桌面和夹具，恢复原来的默认桌面。
   5. 关闭由本工具启动的模拟器。
4. 在 `acceptance\out\<时间>-Mx-accept\` 写出 `report.html` 和 `results.json`。

退出码：0 表示通过，1 表示不通过，2 表示工具或环境错误。

耗时参考：每台设备 15–40 分钟，M3 与 M5 全矩阵可能要 2–3 小时。

## 3. 对模拟器做了什么

只对 `spec/matrix.json` 里的 AVD 操作，具体如下：

- 安装和卸载 `com.github.mytv.launcher` 与 `com.github.mytv.mlfixture.*`；
- 设置默认桌面，结束后还原；
- 执行 `appwidget grantbind`；
- M5 中临时打开飞行模式，结束后还原；
- M3 起临时修改 `time_12_24`（D327），结束后还原；
- M4 中给桌面授予悬浮窗与使用情况访问的 appops（儿童模式）；D411 会重启一次模拟器；
- 用 `adb reverse` / `adb forward` 把本机上的模拟服务器（天气、更新）和「手机与电脑」接口接到模拟器，结束后移除；
- 启动时带 `-no-snapshot-save`，不覆盖你的快速启动快照。

不会 wipe 数据，不碰 emulator-5554。不是电视的设备（没有 `android.software.leanback`），工具会拒绝操作。

## 4. 看报告

| 结果 | 含义 |
|---|---|
| PASS | 通过 |
| FAIL | 不通过。消息里写明实际值与期望值，证据列里有截图、无障碍树和状态 JSON |
| WARN | 不阻塞，但实现方必须在 `docs/milestones/Mx.md` 里解释原因（多为模拟器上的性能参考值） |
| SKIP | 不适用于这台设备，例如 API 23 上的 TvProvider 检查 |
| MANUAL | 需要你人工判断，见第 5 节 |

报告头部的这几项要留意：

- `formal`：为 false 说明用了 `--no-build`、`--devices` 或 `--only`，只能算自查。
- `git`、`gitDirty`：本次验收对应的提交，以及工作区是否有未提交的改动。
- `apkDebugSha256`、`apkReleaseSha256`：本次构建产物的哈希。

## 5. 人工判断项

MANUAL 检查（例如 D214）会截下 Hero、应用图标焦点、浏览态、玻璃顶栏。截图请和原型 `tv-launcher-design-v3.html` 中同一场景对照，重点看以下几处：

- 焦点形变是否为 Cookie 形；
- 顶栏胶囊是否是玻璃质感；
- 配色是否随内容；
- 有没有多出任何提示文字。

## 6. 实现方耍花样的迹象

- `acceptance/` 或文档的 git 历史里有改动：用 `git log -- acceptance PLAN.md AGENTS.md` 查看。
- 把 `formal: false` 的报告当作正式结果交来。
- `docs/milestones/Mx.md` 里的报告路径或 `results.json` 的哈希与 `acceptance\out\` 对不上。
- 代码里出现 `mlfixture`、`uiautomator`、`acceptance` 等字样。S10 只检查 release，debug 状态接口以外的出现都值得追问。
- 状态接口和界面对不上：报告里的 `STATE_MISMATCH`。
- `spec/required_tests.json` 里的测试类只是空壳，或断言与 `assert` 一栏对不上。S17 只看数量与结果，内容需要你抽查。

## 7. 只能在真机上确认的项

见 `PLAN.md` 4.1 节：HDMI 实时画面作壁纸、PixelCopy 在目标芯片上的表现、CEC、国产固件对第三方 HOME 的限制、杜比视界、Google TV 屏保列表。模拟器验收通过不代表这些没问题，实现方应在里程碑说明的「已知问题」中写明。

## 8. 维护工具包

工具包有错时，实现方会写进 `docs/acceptance-issues.md` 并停下等你裁决。确认要改时，由你（或我）修改工具包，然后重新冻结：

```powershell
python acceptance\run.py kit freeze
```

会输出新的根哈希与压缩包哈希，请替换另存的值。夹具 APK 由 `fixtures\build.py` 生成，改了夹具源码后需重新构建再冻结。

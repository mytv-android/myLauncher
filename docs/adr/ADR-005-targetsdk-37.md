# ADR-005：targetSdk 36 → 37，并为「手机与电脑」申请本地网络权限

- 状态：接受（2026-11-07，用户指示「改37」）
- 日期：2026-11-07
- 影响：偏离设计稿 §15 的 `targetSdk = 36`；改动 `app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`MainActivity`、`LauncherModel`、`feature:phone-organizer`。不改动验收工具包，不影响 S01–S17 的既有判定，不影响矩阵内任何设备检查（矩阵最高 api36）

## 背景

- 设计稿 §15 锁定 `minSdk = 23`、`targetSdk = 36`、`compileSdk = 37`（compileSdk 37 是 Compose 1.12.1 的 AAR 元数据要求，M0 已记录）。
- Android 17（API 37）起，本地网络保护对 targetSdk ≥ 37 的应用强制生效：**接受局域网入站 TCP 连接**同样需要 `android.permission.ACCESS_LOCAL_NETWORK`。targetSdk ≤ 36 的应用由 `INTERNET` 隐式授予本地网络访问，暂不受限（见 `docs/android17-local-network.md`）。
- 「手机与电脑」是常驻能力，用户会在 Android 17 的电视上用手机访问它。targetSdk 停在 36 今天照样能用，但平台要求只会前进，且系列应用 myDV 已按 37 实现。用户 2026-11-07 指示改为 37。

## 方案

SDK 级别按用户 2026-11-07 的要求统一为「minSdk 23，其余全部 37」：

1. `app/build.gradle.kts`：`targetSdk = 37`（standard / oem 共用 defaultConfig）。主工程其余模块本就 `compileSdk = 37`、`minSdk = 23`，不动。
2. `spikes/app/build.gradle.kts`（M0 的独立验证工程，不进主工程 `settings.gradle.kts`）：`targetSdk` 一并改为 37。`docs/m0/evidence/item07-material3.md` 里「targetSdk 36」是 M0 当时的测量记录，不回改。
3. 清单声明 `android.permission.ACCESS_LOCAL_NETWORK`（属 `NEARBY_DEVICES` 权限组；用户给过蓝牙等同组权限时不再弹窗）。
4. 新增 `LocalNetworkAccess`（`feature:phone-organizer`）：
   - `isEnforced(sdkInt)`：`sdkInt >= 37` 才执行限制；
   - `isGranted(context, sdkInt)`：未执行限制时视为已授予；
   - `shouldRequest(context, sdkInt)`：仅在「系统 37+ 且未授予」时为真。
5. `MainActivity` 用 `ActivityResultContracts.RequestPermission()` 申请，两个调用点：
   - `LauncherModel.openOrganizerFrom()`（快速设置「传文件」与桌面设置两个入口）经 `localNetworkRequester` 钩子触发；
   - `onResume()` 时若当前页是 organizer 再检查一次，覆盖在系统设置里撤销权限后回到页面的情况。
   页面打开与申请同时进行：服务照常启动，权限按 UID 生效，已在监听的 socket 在授予后立刻恢复可达，不需要重启服务。
6. 不新增界面文字。权限弹窗是系统文案；拒绝后页面表现与「连不上」时一致，不额外解释。解释性文字受 `acceptance/spec/ui_copy.json` 白名单约束（AGENTS.md 硬规则 4），工具包已冻结，本次既不加文字也不改工具包。

## 不采用

- **保持 targetSdk 36**：今天可用，但每次平台提高要求都要补，且与系列应用的分支策略不一致。
- **只声明不申请**：targetSdk 37 下这是运行时权限，仅声明等于没有。
- **用系统选择器绕开**（`NsdManager` 的 `FLAG_SHOW_PICKER`、Output Switcher）：本功能是用户主动打开页面后让手机持续访问电视，属于宽泛、持续的本地网络访问，选择器不适用。
- **加理由说明页或状态文案**：需要白名单外的新文字，走不通（硬规则 4）。

## 影响

### Android 17 行为变更逐条核对（仅列 targetSdk 37 生效的项）

| 变更 | 对本工程 |
|---|---|
| 本地网络权限 `ACCESS_LOCAL_NETWORK` | 按上面实现；唯一需要改代码的一条 |
| `updateAppWidget` 的 RemoteViews 位图内存上限由告警改为抛 `IllegalArgumentException` | 本工程只做 AppWidget **宿主**，没有 `AppWidgetProvider`，不涉及 |
| MessageQueue 无锁实现、`static final` 不可反射修改 | 工程无相关用法 |
| ECH（加密 Client Hello） | OkHttp 4.12.0 未集成 ECH，平台侧不会启用，无影响 |
| 证书透明度（CT）默认开启 | 工程访问 Open-Meteo、ipwho.is、必应每日一图；这些服务证书链内嵌 SCT，预期无影响。真机若出现 TLS 失败，用 network security config 的 `certificateTransparency` 逐域名关闭 |
| 后台音频加固 | 按键音与环境模式音频只在前台播放，其他应用到前台 2.5 秒内已释放播放器；预期无影响，真机确认 |
| 大屏（sw ≥ 600dp）忽略 orientation / resizability 限制，且 targetSdk 37 不再可退出 | `screenOrientation="landscape"`（S09 要求）在 4K 等大屏上会被平台忽略。电视物理横屏，预期无影响；真机确认不会被拉竖屏或进多窗口 |
| SMS OTP、Contacts CP2、密码字段隐藏、RFCOMM `read()`、原生库只读加载 | 工程不涉及 |
| 应用内存上限、跨配置 loopback 被禁（这两条对所有应用生效，不限 targetSdk） | 前者影响 M5 的内存预算，真机测；后者只禁跨配置，同配置的 `127.0.0.1` 不受影响，`adb forward` 的验收路径照旧可用 |

### 验收影响

- 矩阵最高 api36。targetSdk 37 的行为变更只在 Android 17 设备上生效，因此现有设备检查（D 系列、P 系列）结果不变，S01–S17 判定不变（S09 只禁 `ACCESS_ALL_EPG_DATA` / `BIND_APPWIDGET` 与定位权限，新增 `ACCESS_LOCAL_NETWORK` 不冲突）。
- 权限弹窗、授予与拒绝在矩阵上不会出现（API 36 不执行限制），需要在真机或 API 37 模拟器上补验。

## 验证方式

- `adb shell dumpsys package com.github.mytv.launcher | grep ACCESS_LOCAL_NETWORK` 看授予状态。
- API 37 设备：打开「手机与电脑」，从另一台同局域网设备访问 `<地址>:<端口>` 能出页面；在系统设置里撤销权限后重新访问应回到超时，回到电视页面应再次弹窗。
- API 36 设备（矩阵 api36）：不出现权限弹窗，`adb forward` 访问服务照旧。

### 已做的自查（2026-11-07，非正式验收）

```
python acceptance/run.py accept M4 --devices api28 --only D406,D408
```

结论「通过」，18 项 PASS、0 FAIL：

- 静态 S01–S17 全 PASS，其中 S02 从源码构建 standardDebug 与 standardRelease 均成功（146 秒，release 走 R8）；
- 设备侧 `D406`（手机与电脑：Host 校验、连接经电视确认、令牌一次性、离开即停止）、`D408`（自己的视频：流式上传、非视频拒绝、Content-Length 预检）、`D199`（界面文案白名单）PASS；
- api28 用时 101 秒。报告：`acceptance/out/20261107-180618-M4-dev/`。

矩阵里没有 API 37 设备，权限弹窗与授予 / 拒绝 / 撤销后重弹仍待真机确认。

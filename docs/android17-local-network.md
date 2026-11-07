# Android 17 本地网络保护与「手机与电脑」

记录时间：2026-11-07。依据：<https://developer.android.com/privacy-and-security/local-network-permission>（2026-11-07 读取）。

## 结论：现在不需要改动

- 本工程 `targetSdk = 36`（设计稿 §15 锁定，见 `app/build.gradle.kts`），`compileSdk = 37`、`minSdk = 23`，standard / oem 两个变体一致。
- Android 17（API 37）起，本地网络保护**只对 targetSdk ≥ 37 的应用强制生效**；targetSdk ≤ 36 的应用由 `INTERNET` 权限隐式获得本地网络访问。
- 官方对低 targetSdk 的明确要求是**既不声明也不申请**：

  > The ACCESS_LOCAL_NETWORK permission is required only if your app targets Android 17 (SDK 37) or higher. If your app targets SDK 36 or lower, local network access is implicitly granted using the INTERNET permission. For these lower target SDKs, don't add ACCESS_LOCAL_NETWORK to your manifest or request it at runtime.

- 矩阵里的 api36（ATV16_Test，Android 16）同样不受影响：Android 16 默认放开本地网络，只有已废弃的 `RESTRICT_LOCAL_NETWORK` 兼容开关会限制（且那时要的是 `NEARBY_WIFI_DEVICES`），本应用不开启该开关。

因此「清单声明 + 运行时申请」整套提前搬进来是反效果：声明一个当前 targetSdk 不需要的权限，申请只会拿到直接拒绝，平白多一个系统弹窗。常见的「声明权限、只把运行时申请用 `SDK_INT >= 37` 守住」的写法适用于**已经** targetSdk 37 的应用；对 targetSdk 36 的 myLauncher，连声明都应省略。

## 受影响的面（将来审计范围）

主工程里唯一的本地网络 socket 是「手机与电脑」的服务：`PhoneOrganizerServer.freePort()` 在选中的局域网地址和 `127.0.0.1`（adb forward）上各监听一个端口，仅页面打开时存在（`LauncherModel` 调 `onPageOpened()` / `onPageClosed()`）。

- 系列应用直连走 binder（ContentProvider / LauncherApps），不走局域网。
- 没有 WebView、没有 mDNS / SSDP、没有 UDP。
- 公网访问（天气、必应每日一图、Coil 图片）不受本地网络保护影响。

## 触发条件与届时要做的事

`targetSdk` 升到 37 时动手——这本身是设计变更，需 ADR 并经用户批准（§15 目前锁定 36）。届时：

1. 清单声明 `android.permission.ACCESS_LOCAL_NETWORK`（属 `NEARBY_DEVICES` 权限组）。
2. 在「手机与电脑」页面可见、二维码与地址展示之前申请运行时权限（与 `onPageOpened()` 同一时机，必须在前台弹）。权限按 UID 生效，已在监听的 socket 无需重启服务即可恢复可达。
3. 处理拒绝与撤销：用户给过同组权限（如蓝牙）时不再弹窗；拒绝过该组则 `shouldShowRequestPermissionRationale` 为 false，需要有再次申请或跳系统设置的路径，否则功能静默失效。
4. 界面文字受 `acceptance/spec/ui_copy.json` 白名单约束（AGENTS.md 硬规则 4）：理由说明、状态文案要先进白名单或走 ADR，Kotlin 里不得出现中文字面量。系统权限弹窗自身是系统文案，不受此限。
5. 不要改 bind 地址：限制落在网络栈底层，按对端 / 本端地址是否属于本地网络判定，`0.0.0.0`、多网卡分别 bind、白名单都无效。
6. 排查时不要只看服务是否起来：`start()` 成功、端口 listen 正常，也可能连不上；缺权限时 TCP 表现为超时（不是 connection refused），日志通常无输出。必须从另一台设备真实访问一次。
7. 验收需要 API 37 的设备或模拟器；矩阵当前最高 api36，加机要用户确认。

## 备注

- 撤销权限后本机 `127.0.0.1` 仍可访问（loopback 不算本地网络），「本机能打开、局域网打不开」是这个问题的典型误判点。
- 同一权限覆盖全部端口，但要在每一处入口（页面端口、第二个端口、诊断导出下载）都确认权限状态，不要只在一处申请。

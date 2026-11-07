# Android 17 本地网络保护与「手机与电脑」

> **已决**：2026-11-07 用户指示「改37」，`targetSdk` 由 36 升到 37，本地网络权限已按 [ADR-005](../adr/ADR-005-targetsdk-37.md) 实现。本文只保留事实与核对记录。

依据：<https://developer.android.com/privacy-and-security/local-network-permission>（2026-11-07 读取）。

## 规则本身

- Android 17（API 37）起，本地网络保护对 **targetSdk ≥ 37** 的应用强制生效；targetSdk ≤ 36 的应用由 `INTERNET` 隐式获得本地网络访问。
- 官方对低 targetSdk 的明确要求是既不声明也不申请：

  > The ACCESS_LOCAL_NETWORK permission is required only if your app targets Android 17 (SDK 37) or higher. If your app targets SDK 36 or lower, local network access is implicitly granted using the INTERNET permission. For these lower target SDKs, don't add ACCESS_LOCAL_NETWORK to your manifest or request it at runtime.

- 被拦的不只是主动连接：**接受入站 TCP 连接**、UDP 收发都要这个权限。限制在网络栈底层，所有网络 API 一视同仁，改 bind 地址（`0.0.0.0`、多网卡分别 bind、白名单）无效。
- `127.0.0.1` / `::1` 是 loopback，不算本地网络——「本机能打开、局域网打不开」是这个问题的典型误判点。同配置的 loopback 也不受 Android 17「跨配置 loopback 被禁」影响，`adb forward` 的验收路径照旧可用。
- 权限属 `NEARBY_DEVICES` 组：用户给过蓝牙等同组权限时不再弹窗；拒绝过该组则后续申请不再弹窗（静默失败），需要给再次申请的入口。
- 缺权限时 TCP 表现为超时（不是 connection refused），日志通常无输出——排查必须从另一台设备真实访问一次。

## 本工程的本地网络面

主工程里唯一的本地网络 socket 是「手机与电脑」的服务：`PhoneOrganizerServer.freePort()` 在选中的局域网地址和 `127.0.0.1`（adb forward）上各监听一个端口，仅页面打开时存在（`LauncherModel.openOrganizerFrom()` → `onPageOpened()`）。

- 系列应用直连走 binder（ContentProvider / LauncherApps），不走局域网。
- 没有 WebView、没有 mDNS / SSDP、没有 UDP；OkHttp 4.12.0 未集成 ECH，Android 17 的 ECH 不会启用。
- 公网访问（天气、必应每日一图、Coil 图片）不受本地网络保护影响，但受 Android 17 对 targetSdk 37 默认开启的证书透明度（CT）约束，见 ADR-005 的真机项。

## targetSdk 37 之后仍待真机确认的项

- 权限弹窗是否在页面可见时出现、授予后是否立即可达（不重启服务）。
- 在系统设置里撤销权限、再回到「手机与电脑」页面，是否再次弹窗。
- 拒绝（含「不允许」两次后的静默拒绝）后，页面无解释文字——白名单外不能加文字，这是已知取舍。
- Open-Meteo / ipwho.is / 必应的 TLS 在 CT 默认开启下是否仍旧通。
- Android 17 上的应用内存上限对 M5 内存预算的影响。

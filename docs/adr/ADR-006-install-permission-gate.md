# ADR-006：拆除 `canRequestPackageInstalls()` 前置门槛，安装授权交给 PackageInstaller 会话

- 状态：接受（2026-11-08，用户批准「接受」）
- 日期：2026-11-08
- 影响：偏离设计稿 §14（`tv-launcher-design-v3.html:2428`）中「API 26+ 用 `canRequestPackageInstalls()` 判断」这一句。改动 `feature:phone-organizer` 的 `InstallManager`、`PhoneOrganizerScreen`、`PhoneOrganizerServer` 与 `app` 的 `LauncherModel`、`LauncherRoot`、`MainActivity`。不改验收工具包。

## 已实施（2026-11-08）

- `InstallManager.canRequestInstalls()` 已删除；`prepare()` 不再据权限状态分流，真实校验通过后一律 `ready`。
- `install()` 去掉前置判断；`createSession` / `commit` 抛 `SecurityException` 时转入 `blocked` + `unknown_sources`，其余异常仍为 `failed` + `unreadable`。
- 结果接收器的 `mapStatus` 增加 `STATUS_FAILURE_BLOCKED -> unknown_sources`。
- 设置页多级降级、尝试上限 2 次、回前台重试、ADB 命令兜底与本次改动同一批落地。
- 未做：APK 导出到外部目录并在卡片上给路径（需要白名单外的新文字，待用户裁决）。

## 背景

1. 设计稿 §14 把 `canRequestPackageInstalls()` 当作安装的**前置判断**：API 26+ 用它判断，未允许时安装卡显示「未允许安装未知应用」，有 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 处理者时给「去设置」，没有时显示 ADB 命令；API 23–25 读 `Settings.Secure.INSTALL_NON_MARKET_APPS`，为 0 时打开安全设置页。
2. 该接口在部分定制 ROM（尤其是电视盒子）上**恒返回 false，而系统安装器实际完全可用**。myLauncher 的目标设备正是这一人群。此时：
   - `InstallManager.prepare()` 在验签通过后立刻把安装卡置为 `blocked`，`PhoneOrganizerServer.onInstallState()` 随即将上传的 APK 传输项标成失败；
   - 用户按「安装」没有任何反应——`install()` 第一行就 `return false`；
   - 用户到「安装未知应用」页面打开开关后 appop 依旧不认，回到卡片仍是 `blocked` → **永久死路**。
3. 而这条链路本身走的是 `PackageInstaller` 会话（`createSession` → `openSession` → `openWrite` → `commit`），不依赖任何安装器 Activity 的 IntentFilter。它本来最有可能在这些 ROM 上直接装上，却被前置判断挡死。
4. M0 的证据（`docs/m0/evidence/item12-files-install.md`）显示矩阵 5 台设备从 API 26 起都有 `com.android.tv.settings/...ExternalSourcesActivity` 处理带包名的 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`，所以「去设置」在验收矩阵上打得开；打不开的是那些 appop 说谎的固件。
5. 判断授权应该是**安装器自己的事**：让 `createSession` / `commit` 的结果说话，而不是替系统提前做决定。

## 方案

1. 删除 `InstallManager.canRequestInstalls()`，以及 `install()` 与 `prepare()` 里的两处前置判断。验签、分包选择、降级、minSdk、ABI 等**真实**校验通过后，安装卡一律进入 `ready`。
2. 安装结果由会话自己回答：
   - `commit` 成功 → `installing` → `installed`；
   - `STATUS_PENDING_USER_ACTION` → 前台拉起 `EXTRA_INTENT` 的系统确认界面（现有逻辑不变）；
   - `createSession` / `commit` 抛 `SecurityException`，或回传 `STATUS_FAILURE_BLOCKED` 等授权类失败 → 才判定为真的缺授权，转入 `blocked` + `unknown_sources`。
3. 「去设置」的多级降级与尝试上限（**本次已实现，不依赖本 ADR 的批准**）：
   - `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 带 `package:` → 不带 data → `ACTION_APPLICATION_DETAILS_SETTINGS` 带 `package:` →（API 23–25）`ACTION_SECURITY_SETTINGS`；
   - 全部解析不到时按 §14 显示 ADB 命令（白名单内已有对应字符串）；
   - 跳转上限 2 次，超限后卡片改显 ADB 命令、不再给「去设置」按钮——既避免死按钮，也避免「回前台重试 → 再跳设置页」打转。
4. 回前台（`LauncherModel.onHostResumed()`）时，若此前跳转过设置页且安装卡仍处于 `ready` / `blocked`，**无条件重试一次**安装；不再用权限状态决定是否续装。（本次已实现）
5. `canRequestInstalls()` 随之删除。CONTRACT 的 `install` 对象只有 `state` / `pkg` / `versionCode` / `error`，没有权限字段，状态接口不受影响。

## 不采用

- **保留前置判断再包一层 try/catch**：问题正是前置判断本身，包一层抓不到根因。
- **改用 `ACTION_VIEW` / `ACTION_INSTALL_PACKAGE` 作为主路径**：依赖安装器 Activity 的 IntentFilter，ROM 差异比会话更大。会话路径是本工程已有的最稳一条，继续作主路径；两者可作为会话失败后的补充，但需要 FileProvider 与 `clipData`，且导出路径的提示文字超出白名单，本次不做。
- **会话失败后把 APK 导出到 `getExternalFilesDir(null)` 并在卡片上给路径**：需要白名单外的新文字（例如「安装包已保存到…」），属于改冻结工具包，需用户裁决后再做。当前的最后出路是 ADB 命令（白名单内已有）。

## 影响

### 验收

- 没有 D 检查覆盖推送安装的交互路径（D406–D413 均不涉及安装卡片机），现有结论不变。
- S11 / D199 的文案白名单判定不变：本次改动与本次提案都**没有新增界面文字**，只用白名单内已有的「去设置」「未允许安装未知应用」「无法安装」以及两条 ADB 命令。
- CONTRACT 的 `install` 对象字段与 `install`、`install:ok`、`install:cancel`、`install:settings`、`install:open`、`install:done` 标签均不变。

### 其他

- M5 的 `core:update`（应用内更新）会复用同一套会话逻辑，本 ADR 的结论同样适用于它；`core:update` 落地时应直接按「无前置门槛」实现，不要再引入 `canRequestPackageInstalls()`。
- 在「完全禁用第三方安装」的固件上，会话创建 / 提交会失败，卡片转 `blocked`，此时仍走「去设置」与 ADB 命令两条路，与现状一致。

## 验证方式

- 矩阵 api23 / api28 / api31g / api34g / api36：上传一个 APK → 卡片进入 `ready` → 按安装出现系统确认界面 → 确认后 `installed`，传输项变「已安装」。api23 顺带覆盖 `Session.fsync(OutputStream)`（该方法是 API 21，minSdk 23 可用，无需守卫）。
- 核心验证点「接口恒 false 但装得上」：矩阵模拟器上**无法复现**（`appops set ... deny` 在原生 ROM 上会同时让会话失败），只能在一台 appop 说谎的电视盒子上确认。需要用户提供设备。

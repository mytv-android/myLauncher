# 可测性契约（contract v1）

验收工具只从外部观察桌面：构建产物、清单、无障碍树（uiautomator dump）、屏幕像素、夹具应用的日志，以及一个只在 debug 构建里存在的只读状态接口。实现方必须满足本文件的每一条，否则对应检查直接判 FAIL。本文件属于冻结的验收工具包，实现方不得修改；需要变更时向用户提出。

## 1. 工程与构建

| 项 | 约定 |
|---|---|
| 位置 | 工程根目录即 `C:\PythonProject\myLauncher`，使用 Gradle Wrapper（`gradlew.bat`） |
| 模块 | 设计稿 §16 的模块名原样使用（`:app`、`:core:designsystem` …），按里程碑逐步出现，见 PLAN.md |
| 包名 | `com.github.mytv.launcher`，standard 变体的 debug 与 release 相同，不加 applicationIdSuffix |
| 构建任务 | `:app:assembleStandardDebug`、`:app:assembleStandardRelease`；验收工具自己执行 `gradlew clean` 后构建，不接受外部提供的 APK |
| 产物路径 | `app/build/outputs/apk/standard/debug/app-standard-debug.apk`、`app/build/outputs/apk/standard/release/app-standard-release.apk` |
| release 签名 | 没有正式签名配置时回退到 debug 签名，保证无密钥也能构建 |
| 依赖报告 | `gradlew :app:dependencies --configuration standardReleaseRuntimeClasspath` 必须能运行 |
| 版本文件 | 不得在 packaging 中排除 `META-INF/*.version`，验收从 APK 内读取 androidx 版本 |
| 文案 | 界面文字全部放在各模块 `src/main/res/values/strings.xml`，默认语言即简体中文；Kotlin 源码（main 源集）中不得出现含中文字符的字符串字面量 |

## 2. 测试标签

根布局设置 `Modifier.semantics { testTagsAsResourceId = true }`。下列标签必须**设在可获得焦点的那个节点上**，这样 uiautomator dump 中该节点同时带 `resource-id` 与 `focused="true"`。

### 条目键（同时作为卡片的 testTag 和状态接口里的 key）

| 条目 | 键 |
|---|---|
| 应用 | `app:<包名>/<Activity 全类名>` |
| 频道节目 | `prog:<包名>:<internalProviderId>`；节目没有 internalProviderId 时 `prog:<包名>:#<_id>` |
| Watch Next | `wn:<包名>:<internalProviderId>`（同上回退） |
| 小组件 | `widget:<appWidgetId>`；内置小组件 `widget:builtin:<名称>`（时钟与天气为 `widget:builtin:clock`）；添加入口 `widget:add` |
| 文件夹 | `folder:<id>`（主页应用行与应用页里的磁贴；状态接口 rows 中同样用这个键） |
| 信号源 | `input:<inputId>` |
| 片单条目 | `wl:<来源包名>:<internalProviderId>` |

### 行

`row:<rowId>`，rowId 取值：`continue`（继续观看）、`watchlist`（片单）、`apps`（你的应用）、`ch:<包名>:<频道键>`（频道键为 internalProviderId，缺失时 `name:<显示名>`）、`widgets`（小组件速览）、`manage_channels`（行末管理频道入口）。

### 固定控件

| 区域 | 标签 |
|---|---|
| 顶栏 | `nav:home` `nav:apps` `nav:widgets` `nav:search` `nav:ambient` `nav:settings`（齿轮，打开快速设置）；顶栏时钟 `clock` |
| Hero | 容器 `hero`；按钮 `hero:play` `hero:watchlist` `hero:info` |
| 浏览信息头 | `info` |
| 快速设置 | 面板 `qs`；`qs:wifi` `qs:bt` `qs:dnd` `qs:ambient` `qs:volume` `qs:source` `qs:glass` `qs:style` `qs:saver`（屏保 · 立即启动） `qs:parent`（家长） `qs:launcher_settings` |
| 信号源页 | 浮层 `inputs`（快速设置的信号源页，`KEYCODE_TV_INPUT` 打开）；条目 `input:<inputId>` |
| 上下文菜单 | 面板 `menu`；`menu:move` `menu:hide` `menu:uninstall` `menu:channels` `menu:resize` `menu:reconfigure` `menu:to_home` `menu:remove` `menu:folder`（移到文件夹） `menu:unfolder`（移出文件夹） |
| 文件夹选择 | 面板 `folder_pick`；已有文件夹 `folder_pick:<id>`；`folder_pick:new`；预设名称 `folder_name:av` `folder_name:game` `folder_name:tools` `folder_name:study` `folder_name:other`（影音 / 游戏 / 工具 / 学习 / 其他） |
| 文件夹浮层 | 面板 `folder_open`；其中的应用仍用 `app:` 键 |
| 小组件选择器 | 面板 `picker`；条目 `picker:<provider 组件名>`；授权 `picker:auth:shizuku` `picker:auth:adb` `picker:auth:cancel`；电脑授权页 `picker:adb:done` `picker:adb:back` |
| 小组件占位卡 | `widget:<id>:reload` `widget:<id>:remove` |
| 频道确认页 | `confirm_channel:allow` `confirm_channel:deny` |
| 添加小组件确认页 | `confirm_pin:add` `confirm_pin:cancel` |
| 管理频道 | 主页行末入口（可聚焦）`manage_channels`；页面 `channels`；每个频道的开关 `channels:<包名>:<频道键>` |
| 桌面设置 | 页面 `settings`；`settings:hidden_apps`；已隐藏应用的恢复项 `hidden:app:<包名>/<类名>`；`settings:default_home`（页面 `default_home`，按钮 `default_home:set`）；`settings:restricted`；`settings:update`；`settings:weather`；`settings:wallpaper`（必应署名文字 `wallpaper:copyright`）；`settings:boot_target` |
| 儿童 / 长辈模式 | 锁屏页面 `kids_lock`；家长选项 `kids_lock:extend15` `kids_lock:extend30` `kids_lock:unlimited`；PIN 输入浮层 `pin`（数字可用遥控器数字键 `KEYCODE_0`–`KEYCODE_9` 输入） |
| 手机请求确认 | 浮层 `confirm_phone`；`confirm_phone:allow` `confirm_phone:deny` |
| 环境模式与屏保 | 时钟 `ambient:clock`；日期、农历与天气行 `ambient:date`；片源名称与地点 `ambient:info` |
| 手机整理 | 桌面设置里的入口 `settings:organizer`；页面 `organizer`；二维码 `organizer:qr` |
| 提示条 | `toast` |
| 首次启动引导 | 页面 `onboarding`；`onboarding:done` `onboarding:skip` |

## 3. 只读状态接口（仅 standardDebug）

- `ContentProvider`，authority `com.github.mytv.launcher.acceptance`，`android:exported="true"`，`android:readPermission="android.permission.DUMP"`
- 放在 `app/src/debug/`（或 `standardDebug`）源集里，release 构建中不得存在（验收会检查清单和 dex）
- `content query --uri content://com.github.mytv.launcher.acceptance/state` 返回 1 行 1 列 `json`，值为**单行** JSON：

```json
{
  "contract": 1,
  "page": "home | apps | widgets | channels | settings | onboarding | organizer | default_home | kids_lock",
  "overlay": "null | quick_settings | inputs | picker | menu | folder_pick | folder_open | confirm_channel | confirm_pin | confirm_phone | pin | dialog",
  "mode": "hero | browse | ambient | saver",
  "focus": {"tag": "prog:com.x:abc", "rowId": "ch:com.x:feat", "index": 2},
  "hero": {"visible": true, "index": 0, "key": "prog:…"},
  "rows": [{"id": "continue", "items": ["wn:…", "wn:…"]}],
  "theme": {
    "seed": "#RRGGBB", "sourceKey": "…", "style": "tonal", "contrast": 0.0, "transitioning": false,
    "roles": {"primary": "#…", "onPrimary": "#…", "primaryContainer": "#…", "onPrimaryContainer": "#…",
              "secondaryContainer": "#…", "onSecondaryContainer": "#…", "surface": "#…", "onSurface": "#…",
              "surfaceContainer": "#…", "surfaceContainerHigh": "#…", "outline": "#…", "focusRing": "#…"}
  },
  "glass": {"tier": "T1 | T2 | T3", "blurRegions": 2},
  "deviceTier": "low | mid | high",
  "approvals": [{"pkg": "…", "channelKey": "…", "state": "approved | pending | denied"}],
  "widgets": [{"id": 12, "provider": "pkg/cls", "page": 0, "x": 0, "y": 0, "spanX": 4, "spanY": 2, "onHome": true, "state": "ok | error | missing"}],
  "watchlist": ["wl:…"],
  "hiddenApps": ["app:…"],
  "series": [{"pkg": "…", "trusted": true, "protocol": 1}],
  "organizer": {"running": true, "port": 8686, "token": "…"},
  "folders": [{"id": "f1", "name": "影音", "apps": ["app:…"]}],
  "ambient": {"source": "aerial | own | photo | bing | input | wallpaper", "clip": "…", "positionMs": 0, "player": "none | playing | paused | released"},
  "restricted": {"mode": "off | kids | elders", "paused": false, "locked": false, "remainingS": 1800, "hasPin": true, "pinLockedUntil": 0, "unlockedUntil": 0},
  "clock": {"is24h": true},
  "weather": {"visible": true, "tempC": 23, "code": 0, "city": "…"},
  "defaultHome": {"isDefault": true, "current": "pkg/cls"},
  "memory": {"imageCacheBytes": 0, "frozenBlurBytes": 0},
  "update": {"state": "idle | checking | available | downloading | ready | error", "available": null, "lastError": null},
  "counters": {"rowRecompositionsDuringThemeTransition": 0, "themeTransitions": 3, "widgetReloads": {"12": 1},
               "soundEffects": {"nav": 0, "click": 0, "back": 0, "invalid": 0}, "weatherRequests": 0, "updateChecks": 0}
}
```

- `rows` 只列出当前显示的行，顺序与屏幕一致；空行不出现
- 焦点在第三方小组件内部（AppWidgetHostView 的子 View）时，`focus.tag` 为该小组件的 `widget:<id>`
- 所有颜色为 `#RRGGBB`（不含透明度）
- `counters` 只在 debug 构建统计
- `organizer`：手机整理服务运行时给出端口与二维码里的一次性令牌，未运行时为 `{"running": false}`
- `counters.soundEffects`：每次调用 `AudioManager.playSoundEffect` 时按类型加一（nav = FX_FOCUS_NAVIGATION_*，click = FX_KEY_CLICK，back = FX_BACK，invalid = FX_KEYPRESS_INVALID）
- `memory`：图片内存缓存与冻结模糊位图当前占用的字节数
- 尚未实现的功能，对应字段给 null 或空值，不得省略

**交叉校验**：验收会把状态接口与无障碍树、屏幕像素对照。焦点标签、行顺序、卡片数量、主色任何一项对不上，判 `STATE_MISMATCH`，等同 FAIL。状态接口只能反映真实界面，不得另行维护一份。

## 4. 调试命令（仅 standardDebug）

同一个 Provider 的 `call` 方法，只允许下列命令，别的命令返回 null：

```
adb shell content call --uri content://com.github.mytv.launcher.acceptance --method <命令> --arg <参数>
```

| 命令 | 参数 | 作用 |
|---|---|---|
| `set_backdrop` | `checker:<格子px>:<#RRGGBB>:<#RRGGBB>`、`solid:<#RRGGBB>`、`none` | 把主页背景层替换为程序生成的图案（`none` 恢复）。只换背景层，玻璃、取色和其他逻辑照常运行 |
| `set_pref` | `<键>=<值>` | 只允许下表中的键 |
| `reset_counters` | — | 清零 counters |
| `net_check` | `https://…` | 用桌面自己的网络栈（含内置根证书）请求该地址，返回 `{"ok":true,"code":200}` 或 `{"ok":false,"error":"…"}` |

`set_pref` 允许的键（从 M1 起全部接受；功能未实现时只保存）：

| 键 | 值 |
|---|---|
| `ambient_idle_s` | 秒，0 关闭 |
| `saver_idle_s` | 桌面内屏保的空闲秒数，0 恢复默认（10 分钟） |
| `glass_tier` | auto / T1 / T2 / T3 |
| `color_source` | content / icon / wallpaper / fixed |
| `scheme_style` | tonal / content / vibrant / expressive |
| `contrast` | 0 / 0.5 / 1 |
| `device_tier` | auto / low / mid / high |
| `onboarding_done` | true / false |
| `high_contrast_focus` | true / false |
| `restricted_mode` | off / kids / elders |
| `restricted_allowed` | 逗号分隔的包名（允许的应用） |
| `kids_quota_s` | 今日额度（秒），覆盖工作日 / 周末设置，只供测试 |
| `pin` | 4 位数字；按正式流程加盐哈希保存 |
| `boot_target` | home / app:<包名>/<类名> / input:<inputId> |
| `weather_city` | 城市中文名（等同在手机整理页设置） |
| `weather_base_url` | 天气接口的基地址（替换 `https://api.open-meteo.com`） |
| `bing_base_url` | 必应接口的基地址（替换 `https://cn.bing.com`） |
| `update_url` | 版本文件地址 |

除此之外，验收只通过遥控器按键（`input keyevent`）、触摸（`input tap` / `input swipe`）、adb 命令、手机整理接口和夹具应用操作桌面。基地址类的键只存在于 debug 构建。

## 5. 按键与时序

| 项 | 约定 |
|---|---|
| 按键 | `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT/CENTER`、`BACK`、`HOME`、`MENU`；长按用 `input keyevent --longpress KEYCODE_DPAD_CENTER` |
| 稳定 | 最后一次按键后 1.5 秒内：焦点不再变化，`theme.transitioning` 为 false |
| 空闲 | 主页非环境模式下，静止 3 秒后 uiautomator dump 必须成功（界面不得持续产生无障碍事件） |
| OK 去重 | 500ms 内的重复 OK 只处理一次；验收两次 OK 之间至少间隔 600ms |
| 文案 | 屏幕上属于桌面自身的文字，必须出现在 `spec/ui_copy.json` 中（允许占位符）。夹具内容、应用名、第三方小组件内部文字不计 |

## 6. 构建产物中的禁止项

- release APK：不得包含 authority `com.github.mytv.launcher.acceptance`；dex 中不得出现夹具包名 `com.github.mytv.mlfixture` 或夹具证书摘要
- standard 变体：不得声明 `ACCESS_ALL_EPG_DATA`、`BIND_APPWIDGET`（只属于 oem 变体）
- 任何变体：不得依赖 `androidx.tv:*`、`dev.chrisbanes.haze:haze-glass`

## 7. 系列直连的测试名单

debug 构建的信任名单在正式名单之外，额外加入 `com.github.mytv.mlfixture.series → spec/project.json 中的 fixtureCertA`。release 构建只含正式名单（myBV、mydv、mytv-android）。

## 8. 手机整理服务（验收用到的部分）

| 项 | 约定 |
|---|---|
| 启停 | 只在手机整理页面打开期间监听；离开页面 5 秒内停止监听 |
| 令牌 | 每次打开页面生成新的一次性令牌，放在二维码 URL 中 |
| `POST /api/session` | 请求头 `X-Token: <令牌>`；返回 200 与 `{"session": "…", "expiresInS": 600}`。令牌只能换一次，再用返回 401 |
| 会话 | 其余 `/api/*` 都要带请求头 `X-Session: <会话>`，缺少、错误或过期（10 分钟）返回 401 |
| `GET /api/apps` | 200，应用列表 JSON：`[{"component": "pkg/cls", "label": "…"}]` |
| `POST /api/apps/label` | JSON `{"component": "pkg/cls", "label": "新名称"}`；电视上弹出 `confirm_phone`，允许后返回 200，拒绝返回 403 |
| `POST /api/clips` | 原始请求体（不用 multipart），请求头 `Content-Length`、`X-Filename`。电视上先弹出 `confirm_phone`。超出限额按 Content-Length 直接返回 413；没有可解码视频轨返回 415 与 `{"error": "无法播放这个视频"}`；成功返回 200 与 `{"id": "…"}` |
| `GET /api/diag` | 纯文本诊断报告（§14 诊断导出） |

## 9. 版本文件（应用内更新）

`update_url` 指向的 JSON：

```json
{"versionCode": 120, "versionName": "1.2.0", "minSdk": 23, "url": "https://…/myLauncher-1.2.0.apk",
 "size": 12345678, "sha256": "…64 位小写十六进制…", "dm": null,
 "notes": [{"versionCode": 120, "text": "…"}]}
```

桌面设置的「更新」页中，确认下载安装的按钮标签为 `update:install`。校验失败时 `update.state` 为 `error`，`update.lastError` 取 `package`、`signature`、`version`、`sha256`、`size`、`network` 之一。

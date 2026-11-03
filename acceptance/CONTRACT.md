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
| 快速设置 | 面板 `qs`；`qs:wifi` `qs:bt` `qs:dnd` `qs:ambient` `qs:volume` `qs:source` `qs:glass` `qs:style` `qs:saver`（屏保 · 立即启动） `qs:parent`（家长） `qs:transfer`（传文件） `qs:launcher_settings` |
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
| 连接请求 | 浮层 `confirm_phone`（手机或电脑请求连接）；`confirm_phone:allow` `confirm_phone:deny` |
| 环境模式与屏保 | 时钟 `ambient:clock`；日期、农历与天气行 `ambient:date`；片源名称与地点 `ambient:info` |
| 手机与电脑 | 入口 `qs:transfer` 与桌面设置的 `settings:organizer`；页面 `organizer`；二维码 `organizer:qr`；地址 `organizer:address`（文字为 `<地址>:<端口>`）；配对码 `organizer:code`（6 位数字，可含空格分组）；设备胶囊 `organizer:device:<会话号>`；传输行 `transfer:<id>`；设置行 `organizer:target`（保存位置） `organizer:storage`（所有文件访问 / 存储权限） |
| 传输与设备菜单 | 面板 `menu`；`menu:open` `menu:delete` `menu:cancel_transfer` `menu:remove_transfer` `menu:disconnect` |
| 安装卡 | 浮层 `install`；`install:ok`（安装） `install:cancel` `install:open` `install:done` `install:settings`（去设置） |
| 文件夹选择抽屉 | 浮层 `folder_choose`；根 `folder_choose:root:<根 id>`；子目录 `folder_choose:dir:<名称>`；`folder_choose:up` `folder_choose:select` |
| 离开确认 | 浮层 `leave_transfers`；`leave:background` `leave:stop` |
| 查看页 / 播放页 | 页面 `viewer`、`player`；查看页菜单 `viewer:wallpaper` `viewer:save` `viewer:delete`；播放页控制 `player:back10` `player:toggle` `player:fwd10` |
| 提示条 | `toast` |
| 首次启动引导 | 页面 `onboarding`；`onboarding:done` `onboarding:skip` |

## 3. 只读状态接口（仅 standardDebug）

- `ContentProvider`，authority `com.github.mytv.launcher.acceptance`，`android:exported="true"`，`android:readPermission="android.permission.DUMP"`
- 放在 `app/src/debug/`（或 `standardDebug`）源集里，release 构建中不得存在（验收会检查清单和 dex）
- `content query --uri content://com.github.mytv.launcher.acceptance/state` 返回 1 行 1 列 `json`，值为**单行** JSON：

```json
{
  "contract": 1,
  "page": "home | apps | widgets | channels | settings | onboarding | organizer | default_home | kids_lock | viewer | player",
  "overlay": "null | quick_settings | inputs | picker | menu | folder_pick | folder_open | confirm_channel | confirm_pin | confirm_phone | pin | install | folder_choose | leave_transfers | dialog",
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
  "organizer": {"running": true, "address": "192.168.1.23", "port": 38517, "token": "…", "code": "482913",
                "sessions": [{"id": "s1", "device": "iPhone · Safari", "approved": true}],
                "transfers": [{"id": "t1", "kind": "upload | download | push | open", "name": "…", "bytes": 0, "total": 0,
                               "state": "queued | active | done | pending | failed | cancelled", "action": "install | view | play | none",
                               "result": "installed | failed | cancelled | shown | played | null", "error": null}],
                "target": {"root": "primary", "path": "Download"}},
  "storage": {"allFiles": true, "legacy": false, "writePermission": true, "roots": ["primary", "usb:1A2B-3C4D"]},
  "install": {"state": "idle | ready | blocked | pending_user | installing | installed | failed | cancelled", "pkg": "…", "versionCode": 0,
              "error": "signature | downgrade | min_sdk | abi | unreadable | unknown_sources | null"},
  "viewer": {"name": "…", "index": 0, "count": 1},
  "player": {"state": "idle | buffering | playing | paused | ended | error", "name": "…", "positionMs": 0, "durationMs": 0},
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
- `organizer`：服务运行时给出局域网地址、端口、二维码里当前的一次性令牌、当前配对码、会话与传输列表（与电视上的传输列表一致，最多 200 项），未运行时为 `{"running": false}`
- `storage`：`allFiles` 为 API 30+ 的「所有文件访问」状态（更低版本为 null）；`legacy` 为 API 29 的 legacy 存储是否生效；`writePermission` 为 API 23–29 的存储运行时权限；`roots` 为网页当前可用的根 id
- `install`：安装卡的状态；没有安装卡时为 `{"state": "idle"}`
- `viewer`、`player`：对应页面不在前台时为 null
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
| `weather_city` | 城市中文名（等同在「手机与电脑」网页设置） |
| `weather_base_url` | 天气接口的基地址（替换 `https://api.open-meteo.com`） |
| `bing_base_url` | 必应接口的基地址（替换 `https://cn.bing.com`） |
| `update_url` | 版本文件地址 |

除此之外，验收只通过遥控器按键（`input keyevent`）、触摸（`input tap` / `input swipe`）、adb 命令、「手机与电脑」接口和夹具应用操作桌面。基地址类的键只存在于 debug 构建。

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

## 8. 手机与电脑服务（验收用到的部分）

验收经 `adb forward` 访问服务，因此服务除局域网地址外还要监听 127.0.0.1。所有请求的 `Host` 头必须等于 `<organizer.address>:<organizer.port>`，否则返回 421；验收会显式设置这个头。

| 项 | 约定 |
|---|---|
| 启停 | 只在「手机与电脑」页打开期间接受新连接；离开页面且没有进行中的传输时 5 秒内停止监听；有进行中的传输时弹出 `leave_transfers`：`leave:background` 后新的 `POST /api/session` 返回 503，进行中的传输做完后停止；`leave:stop` 立即中断并删除临时文件 |
| 静态页面 | `GET /`、`/app.js`、`/app.css`、`/icons.svg` 不需要会话；不引用任何外部地址 |
| `POST /api/session` | 请求头 `X-Token: <令牌>` 或 `X-Code: <6 位配对码>`，可选 `X-Device`。电视弹出 `confirm_phone`，请求一直等到有结果（最长 60 秒）：允许 → 200 `{"session": "…", "expiresInS": 600}`；拒绝 → 403；60 秒无响应 → 408；令牌或配对码错误 → 401。允许或拒绝之后令牌与配对码都作废并刷新。配对码连续错 5 次立即刷新，之后 30 秒内该来源地址的会话请求返回 429 |
| 会话 | 其余 `/api/*` 都要带 `X-Session`；缺少、错误、过期（10 分钟无请求）返回 401。`DELETE /api/session` 断开 |
| `GET /api/apps` | 200，`[{"component": "pkg/cls", "label": "…"}]` |
| `POST /api/apps/label` | `{"component": "pkg/cls", "label": "新名称"}` → 200（会话已在连接时确认，不再逐项确认） |
| `POST /api/clips` | 原始请求体，请求头 `Content-Length`、`X-Filename`。超出限额按 Content-Length 在读取正文前返回 413；没有可解码视频轨返回 415 与 `{"error": "无法播放这个视频"}`；成功 200 `{"id": "…"}` |
| `GET /api/diag` | 纯文本诊断报告 |
| `GET /api/fs/roots` | `[{"id": "primary", "label": "内部存储", "total": 0, "free": 0, "writable": true, "fs": "…", "restricted": false}]`。根 id：`primary`、`usb:<卷 UUID>`；受限模式为 `std:Download` `std:Movies` `std:Pictures` `std:Music` `std:Documents` |
| `GET /api/fs/list?root=&path=&cursor=&hidden=0` | `{"path": "…", "entries": [{"name": "…", "dir": false, "size": 0, "mtime": 0, "mime": "…"}], "cursor": null}`；路径非法 400，不存在或不可见 404 |
| `POST /api/fs/mkdir` | `{"root", "path", "name"}` → 200；同名 409 |
| `POST /api/fs/rename` | `{"root", "path", "name"}` → 200；同名 409 |
| `POST /api/fs/move` | `{"root", "paths": [...], "to": "目录"}` → 200 |
| `POST /api/fs/delete` | `{"root", "paths": [...]}` → 200 `{"results": [{"path", "ok", "error"}]}` |
| `POST /api/fs/uploads` | `{"root", "path", "name", "size", "mtime", "conflict": "rename | overwrite | skip"}` → 201 `{"id", "offset": 0, "name": "实际文件名"}`；推送用 `{"target": "push", "name", "size", "keep": false}`。预检不通过 413 `{"error": "…"}`；skip 且同名 409 |
| `PUT /api/fs/uploads/<id>` | 请求头 `Upload-Offset` 与 `Content-Length`，正文追加写入。偏移不符 409 `{"offset": n}`；未完成 200 `{"offset": n}`；完成 200 `{"offset": n, "path": "…"}`（推送为 `{"offset": n, "push": "<传输 id>"}`）；写满 507 |
| `HEAD /api/fs/uploads/<id>` | 响应头 `Upload-Offset` |
| `DELETE /api/fs/uploads/<id>` | 取消并删除临时文件 |
| `POST /api/fs/ticket` | `{"root", "paths": [...]}` → `{"url": "/dl/<票据>/<文件名>"}`；票据一次性、60 秒有效；单个文件的下载支持 `Range`（206）；多项或目录返回 `application/zip` 流（STORED） |
| `POST /api/open` | `{"root", "path"}` → 200 `{"transfer": "<id>"}`，电视对该文件执行推送动作 |
| `GET /api/transfers` | 与状态接口 `organizer.transfers` 相同的数组 |

## 9. 版本文件（应用内更新）

`update_url` 指向的 JSON：

```json
{"versionCode": 120, "versionName": "1.2.0", "minSdk": 23, "url": "https://…/myLauncher-1.2.0.apk",
 "size": 12345678, "sha256": "…64 位小写十六进制…", "dm": null,
 "notes": [{"versionCode": 120, "text": "…"}]}
```

桌面设置的「更新」页中，确认下载安装的按钮标签为 `update:install`。校验失败时 `update.state` 为 `error`，`update.lastError` 取 `package`、`signature`、`version`、`sha256`、`size`、`network` 之一。

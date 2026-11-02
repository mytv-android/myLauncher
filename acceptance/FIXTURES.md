# 验收用夹具应用（fixtures）

夹具是一组独立于桌面实现的小应用，由验收工具包自带、预先编译、随工具包一起冻结。它们扮演"第三方内容应用 / 小组件提供方 / 系列应用"，并在**自己的进程里**记录桌面对它们做了什么（绑定、尺寸下发、点击、审批结果、直连调用）。这些记录由夹具产生，桌面无法伪造，是验收的主要证据来源。

- 源码：`fixtures/src/`（Java 8 语法，不依赖 AndroidX，minSdk 23，targetSdk 35，按 android-35 的 android.jar 编译）。`src/java/` 是全部 APK 共用的一套代码，`src/res/` 是共用资源，`src/AndroidManifest.xml.in` 是清单模板
- 构建：`python fixtures/build.py`，只用 SDK 自带工具（aapt2、javac、d8、zipalign、apksigner），不用 Gradle，不联网。脚本从 `content.json` 为每个 APK 生成清单、`assets/fixture.json`、图标与 banner、小组件 provider XML、按比例裁好的海报，再逐个 APK 编译（每个 APK 有自己的 R 类）。同样的输入得到逐字节相同的 APK
- 产物：`fixtures/apk/*.apk` 与 `fixtures/apk/index.json`：`{"certA", "certB", "apks": [{"id", "file", "package", "signer", "sha256", "certSha256"}]}`
- 签名：`fixtures/keys/fixture-a.jks`、`fixture-b.jks`（JKS，别名 `fixture`，口令 `mlfixture`，RSA 2048，30 年）。证书 A 的 SHA-256 写进 `spec/project.json` 的 `fixtureCertA`，当前值 `9168ea28aebc901cb78f79776dbc415233e7f8a231ca13b70a27958fd79bf49c`（以 `apk/index.json` 为准）
- 内容与配图：取自设计稿原型（`fixtures/art/`，由原型的绘制代码导出），标题、描述、进度与原型一致，便于和原型截图对照

## 应用清单

所有 Java 类在包 `com.github.mytv.mlfixture` 下；每个 APK 的 applicationId 不同，行为由 APK 内的 `assets/fixture.json` 决定。

| id | applicationId 后缀 | 名称 | 签名 | 用途 |
|---|---|---|---|---|
| dolphin | `.dolphin` | 海豚视频 | A | 频道 `dolphin.featured`「海豚精选」16:9：深海之歌、林间四季；Watch Next：火山岛纪行、沙漠星空（继续观看） |
| spark | `.spark` | 星火影院 | A | 频道 `spark.weekly`「星火影院 · 本周热映」2:3：赤色行星、水母之夜、霓虹回响、秋日来信、初雪、莲池、沙丘回声；频道 `spark.later`「星火影院 · 稍后再看」（不自动请求，用于确认页）；Watch Next：樱花街 7 号 |
| cloud | `.cloud` | 云相册 | A | 频道 `cloud.memories`「云相册 · 精选回忆」16:9：北境极光；Watch Next：冰川来信 |
| prism | `.prism` | 棱镜直播 | A | 频道 `prism.live`「棱镜直播」：3 个 TYPE_CHANNEL 节目，海报为台标、不设比例、LIVE=1（模拟 mytv-android 的台标卡） |
| pulse | `.pulse` | 律动音乐 | A | Watch Next：咖啡与雨；小组件「播放控制」4×1（只有 ImageView 可点） |
| sky | `.sky` | 晴空天气 | A | 小组件「天气」4×2（TextView 可点；API 31+ 使用 system_accent 颜色资源） |
| files | `.files` | 文件管理 | A | 灰色图标（低彩度取色规则） |
| store | `.store` | 应用商店 | A | 无内容 |
| fit | `.fit` | 燃动健身 | A | 小组件「今日活动」2×2，集合视图（ListView + RemoteViewsService，8 项） |
| book | `.book` | 有声书 | A | 小组件「继续收听」2×2，带配置页（未声明 configuration_optional，声明 `widgetFeatures="reconfigurable"`，该属性 API 28 起生效） |
| aspect | `.aspect` | 比例测试 | A | 频道 `aspect.ratios`「比例测试」：16:9、3:2、4:3、1:1、2:3、电影海报各 1 个；另有"无图节目"（无海报无缩略图）、"图片失效"（海报 URI 不存在）、"越权跳转"（INTENT_URI 指向 probe 的 `LauncherActivity`） |
| crash | `.crash` | 崩溃小组件 | A | 小组件「崩溃」2×1：布局里含 RemoteViews 不允许的 View（EditText），宿主加载必然失败 |
| series | `.series` | 系列测试 | A | 系列直连 Provider；频道 `series.main`「系列测试 · 推荐」16:9 两个节目 |
| series_impostor | `.series` | 系列测试 | **B** | 与 series 同包名、同代码，证书不同。桌面必须拒绝。与 series 不能同时安装（覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`） |
| rogue | `.rogue` | 野生桥接 | A | 有直连 Provider，但不在桌面的信任名单里。桌面不得调用，频道请求进入待审 |
| probe | `.probe` | 验收探针 | A | 工具应用，见下文 |

Watch Next 的 `last_engagement_time_utc_millis` 按原型顺序递减：火山岛纪行 → 冰川来信 → 樱花街 7 号 → 沙漠星空 → 咖啡与雨。取值为 `publish` 时刻减去 `rank × 10 分钟`（rank 1–5），各应用在 10 分钟内先后 `publish` 时顺序不变；`publish` 的 `arg` 可给出统一的基准时刻（毫秒）。进度与原型一致（0.62 / 0.28 / 0.83 / 0.12 / 0.47），时长取整分钟，`last_playback_position_millis = round(进度 × duration_millis)`，使"剩余分钟"与原型文字一致。所有 Watch Next 行为 `WATCH_NEXT_TYPE_CONTINUE`。

频道海报尺寸按比例由构建脚本从原型配图居中裁出，经各应用的 `ArtProvider`（`content://<applicationId>.art/<文件名>`，导出、只读）提供。文件名为 `<配图>_<16x9|3x2|4x3|1x1|2x3|movie>.jpg`，台标为 `logo_<id>.png`，应用图标为 `icon.png`；"图片失效"的海报是不存在的 `missing.jpg`。节目的 `INTENT_URI` 指向本应用的 `LauncherActivity`，带 `program=<internalProviderId>`（`intent:#Intent;component=<包名>/com.github.mytv.mlfixture.LauncherActivity;S.program=<id>;end`）。

字段映射：`type` 用 TvContract 的整数（movie 0、tv_series 1、tv_episode 3、clip 4、channel 6）；`poster_art_aspect_ratio` 16:9=0、3:2=1、4:3=2、1:1=3、2:3=4、电影海报=5（5 是 androidx 的 `ASPECT_RATIO_MOVIE_POSTER`，framework 没有这个常量）；`genre` 原样写入 `canonical_genre`（TvContract 的节目表没有自由文本类型列，这里故意写非规范值）；`year` 写入 `release_date`；`season` / `episode` 写入 `season_display_number` / `episode_display_number`。频道的 `app_link_intent_uri` 指向本应用 `LauncherActivity`，带 `channel=<internalProviderId>`；频道台标是应用图标，用 `TvContract.buildChannelLogoUri()` 写入。

## 每个夹具 APK 共有的组件

| 组件 | 说明 |
|---|---|
| `LauncherActivity` | MAIN + LEANBACK_LAUNCHER + LAUNCHER（probe 除外，见下文），`android:banner` 为原型 banner，图标为原型图标（API 26+ 同时提供自适应图标，背景层是同一张图）。`launchMode="singleTask"`，每次启动（含 `onNewIntent`）记录 `launched`：`program`、`channel`、`action`、`flags`、`referrer`、`via`。界面是带应用名的纯色页 |
| `CmdReceiver` | 导出，`android:permission="android.permission.DUMP"`（只有 adb shell 能发）。见"命令" |
| `LogProvider` | `content://<applicationId>.log/events`，导出，`readPermission="android.permission.DUMP"`，只读。每行一列 `line`，值为单行 JSON：`{"seq":1,"ts":…,"event":"…",…}`，非 ASCII 字符一律转义为 `\uXXXX`，在任何控制台编码下都可解析。`seq` 在 `clear_log` 后继续递增。同一内容也以 tag `MLFixture` 写入 logcat |
| `ArtProvider` | `content://<applicationId>.art/…`，导出、只读，供桌面读海报和台标 |
| `RequestBrowsableActivity` | 导出（DUMP 权限）。用 `startActivityForResult(ACTION_REQUEST_CHANNEL_BROWSABLE)` 请求显示指定频道，记录 `browsable_result`：`channel`、`channelId`、`target`、`handler`（解析到的处理 Activity）、`resultCode`、`error`（`ActivityNotFoundException`、`channel_not_found`、`api<26`） |
| `PinWidgetActivity` | 导出（DUMP 权限）。调用 `requestPinAppWidget()`，记录 `pin_requested`（含 `isRequestPinAppWidgetSupported` 与 `requestPinAppWidget` 返回值；API < 26 记 `error: "api<26"`）；成功回调（不导出的 `PinResultReceiver`，PendingIntent 为 FLAG_MUTABLE，系统要填入 appWidgetId）记录 `pin_result` |
| 小组件 provider | 记录 `widget_update`（`appWidgetIds`、计数、`source`：system / cmd / configure）、`widget_options`（宿主下发的 min/max 宽高与 category，API 31+ 含 sizes）、`widget_deleted`；点击记录 `widget_click`（provider、appWidgetId、按钮名）。点击由不导出的 `ClickReceiver` 接收，只有夹具自己的 PendingIntent（由宿主触发）能到达，桌面无法直接发广播伪造点击。可点的 View 都声明了 `focusable`，API 23–25 上也能用方向键进入。尺寸：`minWidth/minHeight = 70×格数−30 dp`，API 31+ 另有 `targetCellWidth/Height` |
| 配置页 | book 的 `BookConfigActivity`（导出，响应 `APPWIDGET_CONFIGURE`）：两个大按钮"完成""取消"，记录 `configure_result`（`result`：done / cancel / back，`resultCode`）。完成时以 `source: "configure"` 刷新该小组件 |
| 直连 Provider | series / series_impostor / rogue：action `com.github.mytv.action.LAUNCHER_BRIDGE`，authority `<applicationId>.launcherbridge`。`call` 支持 hello / init / approve / hide，每次调用记录 `bridge_call`（`method`、`arg`、`extras`、`caller` 调用方包名、`callerUid`、`callerCert` 调用方证书 SHA-256、`pinned`、`certOk` 是否通过证书校验、`accepted`、`result`）；`query` 支持 /channels、/preview_programs、/watch_next_programs，只在 API < 26 或没有 TvProvider 时返回数据（列名与 TvContract 相同，见下），否则返回空表，记录 `bridge_query`（`path`、调用方字段、`serve`、`rows`） |

直连 Provider 的行为：`hello` 返回 `version=1`、`package`；`init` 立即执行一次 `publish`，返回 `published`（`tv_provider` 或跳过原因）；`approve` 的 `arg` 为频道 internalProviderId，`extras.approved` 缺省为 true，结果写入 /channels 的 `browsable`；`hide` 的 `arg` 为节目 internalProviderId，记墓碑，之后 /preview_programs 与 /watch_next_programs 不再返回该行；approve / hide 后对 `content://<applicationId>.launcherbridge` 调用 `notifyChange()`。未执行 `set_launcher_cert` 时接受任何调用方（`pinned:false`，`certOk:null`）；设置后证书不符一律抛 `SecurityException`（先记录 `accepted:false` 再抛出）。行数据的列：

- /channels：`_id, package_name, type(TYPE_PREVIEW), display_name, app_link_intent_uri, internal_provider_id, browsable`
- /preview_programs：`_id, package_name, channel_id, type, title, short_description, poster_art_uri, poster_art_aspect_ratio, intent_uri, internal_provider_id, weight, live, canonical_genre, release_date, season_display_number, episode_display_number, duration_millis, last_playback_position_millis, browsable`
- /watch_next_programs：同上去掉 `channel_id, weight, live`，加 `watch_next_type, last_engagement_time_utc_millis`

## 命令

```
adb shell am broadcast -n <applicationId>/com.github.mytv.mlfixture.CmdReceiver --es cmd <命令> [--es arg <参数>]
```

`am broadcast` 的输出为 `Broadcast completed: result=1, data="<JSON 摘要>"`；`result=2` 表示出错（摘要里有 `error`），摘要超过 3000 字符时截断。完整结果以 LogProvider 为准。

| 命令 | 作用 |
|---|---|
| `publish` | API 26+：按 fixture.json 写入频道、节目、Watch Next；对标记为 `request: true` 的频道调用 `TvContract.requestChannelBrowsable()`。记录 `published`（频道 id、internalProviderId、请求结果和写入后读回的 `browsable`，节目与 Watch Next 的行 id）。可重复执行：频道按 internalProviderId 原地更新（id 与审批状态保留），节目和 Watch Next 删除后重写，fixture.json 里没有的频道删除。API < 26 或没有 TvProvider 时只记录 `published`（`skipped`），直连应用对 Provider 发 `notifyChange()` |
| `unpublish` | 删除本应用写入的全部行 |
| `request_browsable` | `arg` 为频道 internalProviderId，调用 `requestChannelBrowsable()`，记录 `request_browsable` |
| `widget_update` | 让本应用全部小组件刷新一次内容（计数加一），用于验证实时更新 |
| `set_launcher_cert` | 直连 Provider 用：`arg` 为桌面证书 SHA-256（小写十六进制，无冒号；带冒号或大写也会被规整）。设置后只接受该证书的调用方；`arg` 为空则取消固定。记录 `cert_set` |
| `clear_log` | 清空日志 |

`RequestBrowsableActivity`：`adb shell am start -n <applicationId>/com.github.mytv.mlfixture.RequestBrowsableActivity --es channel <internalProviderId> [--es target <包名>]`。给出 target 时对 Intent 调用 setPackage。

`PinWidgetActivity`：`adb shell am start -n <applicationId>/com.github.mytv.mlfixture.PinWidgetActivity --es provider <类名>`（如 `MusicWidget`）。这个 Activity 不显示界面、立即结束，不要加 `-W`（API 23 上会一直等）。

## 探针 probe

探针是验收工具自己的"第三方应用"，有两个用途：自检夹具；在 M0 里独立测出设备事实，与实现方的 M0 报告对照。

探针的桌面入口是 `ProbeActivity`（MAIN + LAUNCHER + LEANBACK_LAUNCHER），它是探针的 AppWidgetHost，每行最多 3 个小组件，宿主因加载失败换成错误视图时记录 `host_error_view`。探针的 `LauncherActivity` 导出但没有桌面入口，是"越权跳转"节目的目标。探针声明 `READ_TV_LISTINGS`（另声明 `com.android.providers.tv.permission.READ_EPG_DATA`），并在 `<queries>` 里列出全部夹具包名和 HOME、`APPWIDGET_BIND`、`APPWIDGET_UPDATE`、`INITIALIZE_PROGRAMS`、`CHANNEL_BROWSABLE_REQUESTED`、`LAUNCHER_BRIDGE` 这几个 Intent，模拟一个正常声明了可见性的第三方桌面。

| 命令 | 作用 |
|---|---|
| `dump_tv` | 以普通第三方应用身份（需 `pm grant … android.permission.READ_TV_LISTINGS`）整表读取 channels、preview_programs、watch_next_programs，按包名汇总记录可见行数、频道名、节目标题（每张表一条 `dump_tv`，Watch Next 含 `last_engagement_time_utc_millis`、进度、时长）；另记录带 selection 查询是否抛 SecurityException（`dump_tv_selection`，channel 用 `type=?`，preview_program 用 `weight>=?`） |
| `probe_handlers` | 记录（`handlers`）以下 Intent 的处理者：`ACTION_REQUEST_CHANNEL_BROWSABLE`、`ACTION_APPWIDGET_BIND`、HOME（全部与默认）、`INITIALIZE_PROGRAMS` 与 `CHANNEL_BROWSABLE_REQUESTED` 接收器、`LAUNCHER_BRIDGE` Provider；系统特性 leanback、leanback_only、app_widgets、live_tv、`com.google.android.feature.AMATI_EXPERIENCE`；TvProvider 是否存在；AppWidgetManager 是否可用 |
| `bind_widget` | `arg` 为 provider 组件名（`包名/类名`）。用探针自己的 AppWidgetHost 分配 ID 并 `bindAppWidgetIdIfAllowed()`，记录结果（失败时释放 ID）；成功则在 `ProbeActivity` 中显示（`ProbeActivity` 在前台时立即刷新，否则下次打开时显示）。不启动配置页，只在结果里记下 `configure` |
| `unbind_widget` | `arg` 为 appWidgetId 或 `all`，删除探针绑定的小组件 |
| `call_bridge` | `arg` 为 `包名:方法:参数`，以探针身份调用直连 Provider，记录返回值或异常 |
| `query_bridge` | `arg` 为 `包名:/路径`（如 `com.github.mytv.mlfixture.series:/preview_programs`），以探针身份查询直连 Provider，记录行数、标题与列名或异常 |
| `read_log` | `arg` 为包名，以普通应用身份读取该夹具的 LogProvider，用来确认没有 DUMP 权限时被拒绝 |

探针声明 `CHANNEL_BROWSABLE_REQUESTED` 接收器，收到时记录 `browsable_requested`（请求方包名、频道 id），作为系统确实发出了广播的旁证。探针必须先被启动过一次（不处于 stopped 状态）才能收到这个广播。

## 各 API 级别的命令差异（模拟器实测）

- `adb shell appwidget grantbind --package com.github.mytv.mlfixture.probe --user 0` 在 API 23、28、31 上写法相同（`--user current` 也可以）；成功时没有输出。卸载探针后授权失效，需要重新执行
- `adb shell content query --uri content://<applicationId>.log/events` 在 API 23、28、31 上写法相同
- `am broadcast -n …` 在 API 23、28、31 上都能送达从未启动过的夹具，不需要 `-f 0x20`
- `pm grant … READ_TV_LISTINGS`：API 28、31 上需要执行；API 23 的系统镜像把它作为安装时权限直接授予。`READ_EPG_DATA` 不能 `pm grant`（"not a changeable permission type"）
- 在 Windows 的 Git Bash 里调用 adb 时设置 `MSYS_NO_PATHCONV=1`，否则 `/sdcard/...` 之类的设备路径会被改写；本地 APK 路径则要写成 `C:/...`
- 用 `am start` 启动 intent URI 时整条命令要放进一层引号：`adb shell "am start 'intent:#Intent;…;end'"`
- API 28 镜像上 `uiautomator dump` 偶尔第一次报 `ERROR: null root node returned by UiTestAutomationBridge`，重试即可

模拟器上测得的设备事实（供对照 M0 报告）：API 23 没有 preview_program / watch_next_program 表（`Unknown URI`），`publish` 记 `skipped:"api<26"`；API 28 的 tvrecommendations 在 `requestChannelBrowsable()` 后自动把频道置为 browsable=1，`RequestBrowsableActivity` 弹出系统确认框；API 31 的 launcherx 不改 browsable，`AddChannelActivity` 不显示界面、立即返回 resultCode 0。三个级别上带 selection 的查询都抛 `SecurityException: Selection not allowed for …`；API 28、31 上 `isRequestPinAppWidgetSupported()` 为 false；`ACTION_APPWIDGET_BIND` 都没有处理者。

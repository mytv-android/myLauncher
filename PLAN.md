# myLauncher 实现计划

设计依据是 `tv-launcher-design-v3.html`（v3.4），文中"§n"指它的第 n 节。可测性要求见 `acceptance/CONTRACT.md`，属于需求的一部分。硬规则见 `AGENTS.md`。

冲突时按以下顺序取舍：用户的明确指示 > `AGENTS.md` > `CONTRACT.md` > 本文件 > 设计稿。设计稿与本文件都没写到的，按 Google TV 的行为实现，并在里程碑说明里列出。

## 1. 验收门禁

| 项 | 要求 |
|---|---|
| 命令 | `python acceptance/run.py accept Mx`，结论"通过"，`formal: true` |
| 静态 | S01–S17 全部 PASS（S12 从 M5 起、S13 从 M4 起、S14 只在 M0、S17 从 M1 起） |
| 单元测试 | `acceptance/spec/required_tests.json` 中不晚于本里程碑的测试类全部存在、数量达标、全部通过（S17）。验收工具自己运行 `gradlew test` 并读取 JUnit XML |
| 设备 | 矩阵中每台设备上，本里程碑及之前所有里程碑的设备检查没有 FAIL |
| WARN | 不阻塞；在 `docs/milestones/Mx.md` 中逐条解释 |
| MANUAL | 由用户对照原型判断，用户确认后才算完成 |

各里程碑的设备矩阵（`acceptance/spec/matrix.json`）：

| 里程碑 | api23 | api28 | api31g | api34g | api36 |
|---|---|---|---|---|---|
| M0 | ● | ● | ● | ● | ● |
| M1 | ● | ● | ● | | |
| M2 | ● | ● | ● | | ● |
| M3 | ● | ● | | ● | ● |
| M4 | | ● | ● | ● | |
| M5 | ● | ● | ● | ● | ● |

设备 id 与 AVD 的对应：api23 = Television_720p_2，api28 = Television_720p，api31g = Television_1080p（Google TV），api34g = Television_4K（Google TV），api36 = ATV16_Test。

## 2. 工程约定

- **构建**：Gradle Wrapper，Kotlin DSL，version catalog（`gradle/libs.versions.toml`）。
  - productFlavor 维度 `dist`，取值 `standard` 与 `oem`。
  - `oem` 从 M5 起才需要有实际内容。
  - 版本以 §15 为准；material3 用 `resolutionStrategy.force` 锁定 1.5.0-alpha25。
- **模块**：名称与职责按 §16。每个里程碑必须存在的模块见下文，由 S06 检查。依赖方向如下：
  - `core:*` 不依赖 `data:*`、`feature:*`、`:app`、`opt:*`；
  - `data:*` 不依赖 `feature:*`、`:app`；
  - `feature:*` 之间互不依赖。
- **Expressive 实验 API**（`@ExperimentalMaterial3ExpressiveApi`）只在 `core:designsystem` 里调用，其他模块通过它的封装使用。
- **测试钩子**：状态接口 Provider 和调试命令放在 `app/src/debug/`。debug 的系列应用信任名单额外加入夹具证书，也只放在 debug 源集（CONTRACT §7）。
- **测试**：单元测试与 Compose UI 测试随功能编写，放在各模块的 `src/test`、`src/androidTest`。
  - 模拟器上无法从外部观测的规则（取色对比度、天气退避、农历、上传限额、PIN、更新校验、路径校验、断点续传、分包选择、配对等）列在 `acceptance/spec/required_tests.json`（T01–T21），类名与模块必须一致，`assert` 一栏是测试必须覆盖的断言，用户会抽查测试代码。
  - 验收工具在 M1 起运行 `gradlew test --continue`，任何失败的单元测试都会让 S17 不通过。
  - 里程碑说明里附 `gradlew test` 的结果。
- **验收前置**：儿童模式等检查需要的 appops（悬浮窗、使用情况访问）由验收工具用 adb 授予，实现方不需要也不得在代码里绕开授权。

## 3. 补充规则

以下几点设计稿写得不够具体，按这里实现。

1. **Hero 的来源**
   - 每个已批准频道取权重最高的 1 个节目，按频道在主页的顺序排列，最多 5 个。
   - 不足时，用继续观看的前几项补足。
   - 两者都没有时隐藏 Hero，首页从第一个内容行开始；没有内容行时从应用行开始。
2. **继续观看的排序**
   - 按 `last_engagement_time_utc_millis` 从新到旧。
   - 副标题格式：
     - 剧集："第 n 集 · 剩 m 分钟"，或 "Sx Ey · 剩 m 分钟"；
     - 其他："剩 m 分钟"；
     - m = round((时长 − 进度) / 60000)。
3. **系列应用的 `init` 调用时机**
   - 首次启动引导完成后，对已安装的可信系列应用各调用一次；
   - 之后在可信包安装或更新时（LauncherApps.Callback）再调用。
   - 调用顺序：`hello` → `init`。
4. **频道审批**
   - 系列应用（证书匹配）的频道自动批准，并回传 `approve`。
   - 其他应用的 `requestChannelBrowsable` 进入待审，不弹窗打扰。
   - App 自己用 `ACTION_REQUEST_CHANNEL_BROWSABLE` 发起的请求，显示确认页。
   - 在"管理频道"里随时可以开关。
5. **节目跳转的安全性**：节目 `INTENT_URI` 解析出的目标包名必须等于该行的 `package_name`，否则不跳转。
6. **测试标签与状态接口**：从 M0 的空壳起就必须存在，按里程碑逐步填充字段。尚未实现的功能，状态字段给空值，不得省略 `contract`。
7. **调试命令 `set_pref`**：从 M1 起必须接受 CONTRACT §4 列出的全部键。对应功能尚未实现时只保存，不报错。

## 4. 里程碑

### M0 技术验证

**目标**：先测设备事实，再承诺界面。

**内容**：§20 的 12 个验证项（①–⑧ 同 v3.2；⑨ 儿童模式的执行手段；⑩ HOME 兼容；⑪ IP 推测服务的使用条款；⑫ 文件与安装的系统入口）。

⑫ 在 api23 / api28 / api31g / api34g / api36 上分别记录：
- 「所有文件访问」设置页（`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 带包名、`ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`）与「安装未知应用」设置页（`ACTION_MANAGE_UNKNOWN_APP_SOURCES`）是否有处理者；
- DocumentsUI 是否存在，`ACTION_OPEN_DOCUMENT_TREE` 能否用遥控器完成授权；
- 挂载一个 U 盘镜像后，在未授权、存储权限、所有文件访问、目录授权四种状态下能否列出与写入；
- PackageInstaller 确认界面能否用遥控器完成安装与取消，`STATUS_PENDING_USER_ACTION` 的确认界面能否从前台拉起。

- 验证代码放在 `spikes/` 下的独立 Gradle 工程，不进入主工程 `settings.gradle.kts`。
- 同时搭好主工程空壳：`:app`（standard / oem），一个带 HOME 与 LEANBACK_LAUNCHER 的空 Compose Activity。空壳要满足的条件：
  - 依赖锁定生效，APK 内 material3 为 1.5.0-alpha25；
  - debug 状态接口返回 `{"contract":1,…}`；
  - 能通过 S01–S11、S15、S16。

**交付物**：`docs/m0/m0-results.json`，以及 `docs/m0/evidence/` 下的证据（截图、日志、数据表）。

```json
{
  "items": {
    "1": {"title": "Haze T1 帧时间", "conclusion": "…", "data": {}, "evidence": ["docs/m0/evidence/…"]},
    "…": {},
    "8": {"title": "系列应用直连联调", "conclusion": "…", "data": {}, "evidence": ["…"]},
    "9": {"title": "儿童模式的执行手段", "conclusion": "…", "data": {}, "evidence": ["…"]},
    "10": {"title": "HOME 兼容", "conclusion": "…", "data": {}, "evidence": ["…"]},
    "11": {"title": "IP 推测服务的使用条款", "conclusion": "…", "data": {}, "evidence": ["…"]},
    "12": {"title": "文件与安装的系统入口", "conclusion": "…", "data": {}, "evidence": ["…"]}
  },
  "devices": {
    "api23": {
      "appwidgetBindHandler": false,
      "requestChannelBrowsableHandlers": [],
      "tvProvider": false,
      "leanback": true,
      "gtv": false,
      "readTvListings": {"otherSearchableRows": null, "selectionSecurityException": null},
      "grantbindWorks": true,
      "browsableRequestedDeliveredToThirdParty": null
    },
    "api28": {}, "api31g": {}, "api34g": {}, "api36": {}
  }
}
```

`devices` 的各字段由实现方在 5 台模拟器上自行测量，验收工具会用探针独立重测并逐项比对（P01–P04），不一致即 FAIL。

| 字段 | 含义与测法 |
|---|---|
| `appwidgetBindHandler` | 是否有 Activity 处理 `ACTION_APPWIDGET_BIND` |
| `requestChannelBrowsableHandlers` | 处理 `ACTION_REQUEST_CHANNEL_BROWSABLE` 的 Activity 组件名，排序后的数组（`包名/类名` 短格式） |
| `tvProvider` | 是否存在 authority `android.media.tv` |
| `leanback` | 是否有特性 `android.software.leanback` |
| `gtv` | 是否有特性 `com.google.android.feature.AMATI_EXPERIENCE` |
| `readTvListings.otherSearchableRows` | 测法：安装 `acceptance/fixtures/apk/` 中的 probe、dolphin、spark；两个夹具执行 `publish`；给 probe 授予 READ_TV_LISTINGS 后执行 `dump_tv`。结果为 `{"channels": n, "preview_programs": n, "watch_next_programs": n}`，即 probe 能读到的 dolphin 与 spark 两个包的行数之和。API < 26 填 null |
| `readTvListings.selectionSecurityException` | 上述读取中，带 selection 的查询是否抛 SecurityException。API < 26 填 null |
| `grantbindWorks` | 对 probe 执行 `appwidget grantbind` 前，`bindAppWidgetIdIfAllowed` 失败；执行后成功 |
| `browsableRequestedDeliveredToThirdParty` | spark 执行 `request_browsable spark.weekly` 后，probe 的 `CHANNEL_BROWSABLE_REQUESTED` 接收器是否收到。API < 26 填 null |

夹具的用法见 `acceptance/FIXTURES.md`，P01–P04 的源码见 `acceptance/mla/checks0.py`。

M0 的结论如果推翻了设计稿的某个前提（例如某设备没有 AppWidgetService、默认桌面无法从后台拉起锁屏），先写 ADR，用户批准后再进入 M1。

**门禁**：S01–S16，P01–P04（5 台）。

### M1 骨架

**设计稿**：§06、§09、§10，以及 §11 中 Watch Next 的读取。

**范围**：

- **顶栏**：Tab「主页 / 应用 / 小组件」，胶囊组「搜索 / 环境模式 / 快速设置」。
  - 焦点停在 Tab 上 320ms 后切换页面。
  - 没有头像，没有账号。
- **Hero**：按第 3 节的来源规则。M1 只有继续观看这一个来源。主按钮「播放」、「片单」、「详情」。
- **片单**：本机存储；Hero 的「片单」按钮加入或移出；空时隐藏。
- **继续观看行**（API 26+ 读 TvProvider）：排序与副标题按第 3 节。
- **你的应用行**（最多 10 个，圆形图标）与**应用页网格**（banner）。
- **文件夹**（§06）：长按菜单「移到文件夹」，新建时从预设名称中选（影音 / 游戏 / 工具 / 学习 / 其他）；文件夹磁贴为 3×3 小图标；OK 打开浮层，BACK 回到文件夹磁贴；移空后文件夹消失。
- **按键音效**（§10）：方向键真正移动焦点时播放 FX_FOCUS_NAVIGATION_*，到边界时 FX_KEYPRESS_INVALID；程序移动焦点（HOME、恢复）与触屏操作不发声。
- **触屏与鼠标**（§10）：点按打开一次，按住打开长按菜单，点浮层外关闭浮层。
- **焦点引擎**：
  - 焦点基线：浏览态焦点行顶部 y 236dp，行内焦点 x 48dp；
  - 焦点记忆；
  - BACK 链：行 → Hero → 顶栏；
  - HOME 键回 Hero 主按钮；
  - 进程被回收后恢复页面与焦点。
- **长按 OK 菜单**：移动、隐藏、卸载。
- **快速设置浮层**的框架，以及其中「桌面设置」入口。
- **桌面设置页**：「已隐藏的应用」，可逐个恢复。
- **小组件页**：网格框架、内置时钟小组件（`widget:builtin:clock`）、添加入口（`widget:add`）。M1 的选择器只列内置小组件。
- **材质**：只有 T3（纯色）。玻璃抽象层与 Haze 的接线在 `core:glass` 中先建好。
- **测试标签与状态接口**按 CONTRACT 完整实现。
- **HOME 兼容清单**（§14）：HOME Activity 的 intent-filter、属性与 meta-data 按 §14 声明（S09 检查）。

**新增模块**：`:app`、`core:designsystem`、`core:tvfocus`、`core:glass`、`data:apps`、`data:channels`、`feature:home`、`feature:apps`、`feature:settings`。

**新增依赖**：material3、haze 2.0.1、tvprovider、Room、DataStore。

**门禁**：D101–D121、D199，S17。

### M2 视觉

**设计稿**：§05、§07、§08、§09，以及 §17 的档位判定。

**范围**：

- **取色全链路**（`core:dynamiccolor`）：
  - 用 MCU 的 QuantizerCelebi 与 Score 从图标、海报中取种子色；
  - 用 DynamicScheme 生成配色；
  - 低彩度来源不改主题；
  - 节流：连续移动时最多 2 次过渡；
  - 支持 3 个对比度级别和 4 种风格；
  - SeedCache；
  - 主题过渡在绘制阶段读取，过渡期间内容行不重组。
- **玻璃**（`core:glass`）：
  - T1 用 Haze 实时模糊（API 31+）；
  - T2 是冻结模糊位图；
  - T3 是纯色；
  - 自动档位与运行中降档；
  - 模糊区域不超过 3 块。
- **背景层**（`core:ambient`）：焦点停留 260ms 后切换，交叉淡入。
- **Expressive**：
  - 用 MotionScheme 弹簧；
  - 焦点形变用 MaterialShapes（应用图标在焦点时变为 Cookie 形）；
  - Wavy 进度条。
- **快速设置**：增加「取色来源」、「玻璃效果」、「配色风格」三行。
- **高对比焦点框**（§09）：4dp 白框加 2dp 黑描边，在桌面设置中开关；长辈模式强制开启。

**新增模块**：`core:dynamiccolor`、`core:ambient`、`feature:quicksettings`。

**新增依赖**：haze-blur 2.0.1、coil3。APK 内必须有 QuantizerCelebi、DynamicScheme、Hct。

**门禁**：D101–D121，D201–D215，D199。D214 由用户对照原型人工判断。单元测试 T01–T02。

### M3 系统能力

**设计稿**：§11、§12。

**范围**：

- **频道**：
  - 读取频道与节目；
  - 审批表；
  - 管理频道页；
  - 确认页（`REQUEST_CHANNEL_BROWSABLE`）；
  - 导出的 `CHANNEL_BROWSABLE_REQUESTED` 接收器。
- **卡片宽度**按海报比例计算，行高统一为 144dp。
  - `TYPE_CHANNEL` 节目显示台标卡，带「直播」角标。
  - 没有图或图片失效时，显示实色卡加标题。
  - 节目跳转按第 3 节做安全校验。
- **在桌面移除节目**：只在本地生效，应用重新发布后也不再显示。
- **系列应用直连**（`data:series`）：
  - 信任名单与双向证书校验；
  - `hello`、`init`、`approve`、`hide`；
  - API 23–25 从直连读取行数据。
  - 冒名应用（证书不符）和未授信的 Provider 一律不调用。
- **第三方小组件**（`data:widgets`、`feature:widgets`）：
  - 选择器；
  - 五条授权路径（Shizuku、ADB 页面显示 `grantbind` 命令、系统确认界面等，见 §12）；
  - 配置页：取消时释放 ID；
  - 方向键在小组件内部交互，OK 兜底点击；
  - 崩溃隔离、失效占位；
  - 重启后持久保留；
  - `CONFIRM_PIN_APPWIDGET`；
  - 移动与调整大小；
  - API 31+ 的 `setColorResources`，3 秒节流；
  - 主页速览最多 4 个；
  - 内置小组件。
- **可选模块** `opt:shizuku`。
- **时钟、天气与农历**（`data:weather`，§12）：
  - 顶栏时钟与时钟小组件按 `TIME_12_24` 决定 12 / 24 小时制，只有值为 "12" 时才用 12 小时制；
  - 天气用 Open-Meteo，城市在「手机与电脑」网页设置，否则每天最多一次 IP 推测；最多每小时请求一次，只带两位小数的经纬度；
  - 农历自行计算，不引入第三方库；
  - 不申请任何定位权限（S09）。

**新增模块**：`data:series`、`data:widgets`、`feature:widgets`、`opt:shizuku`、`data:weather`。

**新增依赖**：WorkManager。

**门禁**：D1xx、D2xx、D301–D328（D309 已并入 D118）、D199。单元测试 T01–T05。

### M4 环境

**设计稿**：§13、§14。

**范围**：

- **环境模式**：
  - 空闲进入，任意键退出且焦点还原；
  - 航拍视频用 Media3 + SurfaceView；
  - 断网时回退到壁纸或静态图；
  - 用 PixelCopy 抓帧取色，并为 T2 提供背景；
  - 时钟防烧屏位移。
- **系统屏保** DreamService。
- **快速设置**：
  - 网络：打开系统设置；
  - 蓝牙；
  - 勿扰；
  - 音量；
  - 信号源；
  - 亮度：只在 oem 变体中提供。
- **HDMI 信号源**以圆形图标进入应用行（`input:<id>`）。
- **手机与电脑**（§14，CONTRACT §8）：
  - 入口：快速设置「传文件」与桌面设置；NanoHTTPD + 二维码（ZXing）；服务只在页面打开时接受连接；
  - 电视页：二维码、地址、6 位配对码、设备、传输列表、保存位置、存储授权；离开时有进行中的传输则询问「后台继续 / 全部停止」；
  - 连接：二维码一次性令牌或配对码换会话，电视在连接时确认一次，之后该会话可做全部读写；Host 校验；
  - 网页（`assets/web/`）：手机与电脑两种布局，栏目「文件 / 推送 / 桌面 / 更多」，文字同样受白名单与禁用词约束（S11）；
  - 文件（`data:files`）：浏览、上传（断点续传）、下载（票据、Range、zip 流）、新建、重命名、移动、删除；存储按 §14 的授权分级；
  - 推送：安装包（`core:install`，含 .apks / .apkm / .xapk 分包与 OBB，始终经系统确认）、图片（查看页）、视频与音乐（播放页，`feature:viewer`）。
- **片源**：`data:media/src/main/assets/ambient/playlist.json`，每条带 `url`、`title`、`license`、`source`（S13）。
- **环境模式的来源**（§13）：航拍、自己的视频、照片、必应每日一图（署名常显）、HDMI 实时画面、壁纸。
  - 自己的视频经「手机与电脑」网页流式上传：按 Content-Length 预检限额、没有视频轨拒绝（CONTRACT §8）；
  - 解码器交接：其他应用来到前台时释放播放器；
  - 系统屏保未选本应用时，空闲后进入桌面内屏保；快速设置「屏保」可立即启动。
- **信号源键**：`KEYCODE_TV_INPUT` 打开信号源页。
- **开机 / 唤醒目标**：主页、某个应用或某个信号源；开机与唤醒各打开一次，回到主页不再打开。
- **儿童 / 长辈模式与家长 PIN**（`data:restricted`，§14）：
  - 儿童模式：只显示允许的应用，拦截其他应用，额度用完拉起锁屏，家长输入 PIN 加时；
  - 长辈模式：大磁贴每排 4 个、名称常显、强制高对比焦点框；
  - PIN 加盐哈希保存，连续输错 5 次锁定 60 秒，可用 ADB（`.config.ConfigReceiver`）清除。
- **设备档案与诊断基础**（`core:diag`，§16）：`devices.json` 预置结论、ADB 覆盖。

**新增模块**：`data:media`、`feature:phone-organizer`、`data:restricted`、`core:diag`、`data:files`、`core:install`、`feature:viewer`。

**新增依赖**：media3-exoplayer、nanohttpd、zxing core。清单中声明 DreamService，以及 REQUEST_INSTALL_PACKAGES、MANAGE_EXTERNAL_STORAGE、WRITE_EXTERNAL_STORAGE（`maxSdkVersion="29"`）与 `requestLegacyExternalStorage="true"`（S09）。

**门禁**：D1xx–D3xx、D401–D413、D199。单元测试 T01–T14、T18–T21。设备矩阵增加 api34g。

### M5 加固

**设计稿**：§17、§18、§19。

**范围**：

- **首次启动引导**：包含「启用第三方小组件」一步。
- **性能与内存**：按 §17 的预算。
- **崩溃隔离**：来源应用被卸载时，相关内容及时消失。
- **断网健壮性**。
- **release 构建**：R8 后能正常运行；包含 Baseline Profile（profileinstaller）。
- **oem 变体**的内容。
- **应用内更新**（`core:update`，§16）：版本文件格式见 CONTRACT §9；包名、签名、versionCode、SHA-256、大小任一不符即拒绝并删除，不弹安装器。
- **诊断导出**（§14）：经「手机与电脑」网页下载，含帧时间 P50 / P90 / P99 与固件指纹，IP、MAC、序列号、令牌、应用列表均脱敏。
- **默认桌面页**：显示是否为默认桌面；被替换时显示当前默认桌面的名称，并给出设置入口。
- **内存回收**：`onTrimMemory` 在 UI_HIDDEN 时保留缓存，在 BACKGROUND / RUNNING_LOW 时清空图片缓存与冻结模糊位图。
- **刷新率**（§17）：API 31+ 选 ≤120Hz 的最高无缝刷新率。
- **旧系统根证书**：API 23–24 内置 ISRG Root X1，访问 Let's Encrypt 站点不失败。

**新增模块**：`feature:onboarding`、`:oem`、`core:update`。

**新增依赖**：profileinstaller。

**门禁**：全部检查（D101–D121、D201–D215、D301–D328、D401–D413、D501–D511、D199，S01–S17，单元测试 T01–T21），5 台设备。

## 4.1 只能在真机上确认的项

以下行为模拟器无法复现，验收工具不检查。实现方在里程碑说明的「已知问题」中逐条写明实现方式与在模拟器上能确认到哪一步，用户在真机上确认：

- HDMI 实时画面作壁纸（TvView，需要真实信号源）；
- PixelCopy 在目标 SoC 上的真实表现（受保护内容黑帧、耗时），以及 `devices.json` 的预置结论；
- HDMI-CEC 的开关机与信号源切换；
- 国产固件对第三方 HOME 的限制（HOME.CC、TCL meta-data 是否生效）；
- 杜比视界等 HDR 片源的播放与降级；
- Google TV 系统设置中屏保列表是否出现本应用的 DreamService；
- 「手机与电脑」在真实 Wi-Fi 下的吞吐（目标见 §14）、U 盘与 SD 卡在各授权状态下的写入，以及国产固件上 PackageInstaller 确认界面与「安装未知应用」入口。

## 5. 变更与问题

- **设计变更**：写 `docs/adr/ADR-0xx-标题.md`，状态为"提议"。用户批准后改为"接受"，才能按新方案实现。如果影响验收，由用户决定是否修改工具包。
- **工具包问题**：写进 `docs/acceptance-issues.md`，然后停下该项。不得绕过，不得改工具包。
- **里程碑说明** `docs/milestones/Mx.md`，内容包括：
  - 报告目录与 `results.json` 的 SHA-256；
  - git commit；
  - `gradlew test` 结果；
  - 每条 WARN 的解释；
  - MANUAL 截图路径；
  - 已知问题；
  - ADR 列表；
  - 设计稿未写明、按 Google TV 行为实现的细节。

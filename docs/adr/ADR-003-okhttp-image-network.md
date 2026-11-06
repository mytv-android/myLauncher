# ADR-003：海报与台标经 Coil + OkHttp 加载

- 状态：接受（2026-11-06，用户选择 OkHttp）
- 日期：2026-11-06
- 影响：M2 取色（D201–D204 的内容取色来源）、M3 频道卡片（D30x）、主页 Hero 与背景层；不改动验收工具包

## 背景

设计稿 §19 列了 Coil 3.x，但没有写网络层。Coil 3 把网络拆成单独模块，只引入 `io.coil-kt.coil3:coil` 时不能取 http(s) 图片。原来的 `rememberArtwork()` 用 `ContentResolver.openInputStream()` 打开所有 URI，所以：

- 频道里的 http(s) 海报全部加载失败，节目卡片只剩实色块；
- Hero、背景层没有图，内容取色从不触发；
- 验收夹具用的是本地 `android.resource://` 图片，工具包没有发现这个问题。

2026-11-06 的界面走查里，在 api34g 上用 myBV、YouTube 的真实频道复现了这个问题。

## 选项

- **A. coil-network-okhttp + OkHttp 4.12.0**：Coil 官方的网络模块，JVM 上最成熟；OkHttp 支持 HTTP/2、连接复用、磁盘缓存配合 Coil 的 DiskCache。APK 增加约 0.8 MB（R8 前）。
- **B. coil-network-ktor3**：需要再引入 Ktor 客户端和引擎，体积更大，对本工程没有额外好处。
- **C. 自己用 HttpURLConnection 写一个 Fetcher**：不加依赖，但要自己处理重定向、缓存、超时、并发，容易出错。

用户选择 A。

## 方案

- `core:designsystem` 新增 `LauncherImages`，作为全桌面唯一的 `ImageLoader`：
  - 网络层是 `OkHttpNetworkFetcherFactory`，连接超时 8 s，读取超时 15 s，整次调用 25 s；
  - 拦截器把 http 改写成 https，不放开明文流量（不改 `usesCleartextTraffic`）；
  - 内存缓存为可用内存的 12%，磁盘缓存 64 MB，放在 `cacheDir/artwork`；
  - 关闭硬件位图，因为取色与冻结模糊要读像素（§19 已要求取色缩略图关闭硬件位图）。
- `rememberArtwork()` 与 `ArtworkLoader` 先走 Coil。非 http(s) 的 URI 在 Coil 失败时，再用 ContentResolver 解码兜底，保持夹具与系列应用 Provider 图片的行为不变。
- `ArtworkLoader.pixels()` 只回收自己新建的缩放副本，不回收 Coil 缓存里的位图。

## 新增依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| `io.coil-kt.coil3:coil-network-okhttp` | 3.6.3（随 Coil） | Coil 的网络 Fetcher |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP 客户端 |

两者都不在 `acceptance/spec/versions.json` 的 `forbiddenDeps` 里，也不影响 minSdk 23。

## 影响

- 首页节目卡片、Hero、背景层显示真实海报；内容取色在真实频道上生效。
- M5 的 `onTrimMemory` 可以调用 `clearArtworkCache()` 清空图片内存缓存（本 ADR 只提供接口，接线留给 M5）。
- 验收检查不受影响：夹具图片仍走本地 URI。

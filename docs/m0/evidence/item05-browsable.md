# 第 5 项：ACTION_REQUEST_CHANNEL_BROWSABLE 在系统桌面仍安装时由谁接收

探针 `probe_handlers` 列出处理 `android.media.tv.action.REQUEST_CHANNEL_BROWSABLE` 的 Activity（排序后的 `包名/类名` 短格式）。

| 设备 | 处理者 |
|---|---|
| api23 | 无（空数组） |
| api28 | `com.google.android.tvrecommendations/.AddChannelActivity` |
| api31g | `com.google.android.apps.tv.launcherx/.coreservices.localchannels.AddChannelActivity` |
| api34g | `com.google.android.apps.tv.launcherx/.coreservices.localchannels.AddChannelActivity` |
| api36 | `com.google.android.tvrecommendations/.AddChannelActivity` |

结论：

- API 26+ 的 AOSP ATV 与 Google TV 都由系统自带的推荐/桌面组件接管这个请求，第三方桌面安装后不会成为默认处理者。
- API 23 没有处理者。
- 因此桌面必须自己声明 `REQUEST_CHANNEL_BROWSABLE` Activity，并在自建审批表里记录请求；不能依赖系统把请求转给桌面。设计稿 §11 与 ADR-006 的前提成立。

原始证据：各设备 `P01-handlers.json` 与 `P-checks-results.json`。

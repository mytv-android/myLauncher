# 第 4 项：READ_TV_LISTINGS 的可见范围

使用验收探针以普通第三方身份读取 TvProvider。探针安装后被 `pm grant ... READ_TV_LISTINGS`，夹具 dolphin 与 spark 执行 `publish`，探针执行 `dump_tv`。

| 设备 | TvProvider 存在 | channels | preview_programs | watch_next_programs | 带 selection 查询抛 SecurityException |
|---|---|---|---|---|---|
| api23 | 是（authority `com.android.providers.tv`） | null（API<26 不测） | null | null | null |
| api28 | 是 | 3 | 10 | 3 | 是 |
| api31g | 是 | 3 | 10 | 3 | 是 |
| api34g | 是 | 3 | 10 | 3 | 是 |
| api36 | 是 | 3 | 10 | 3 | 是 |

说明：

- 行数是探针能读到的 dolphin 与 spark 两个包的行数之和，不含探针自己。
- API 23 镜像存在 `com.android.providers.tv` authority，但没有 preview_program / watch_next_program 表；`publish` 记录 `skipped: api<26`。因此“TvProvider 存在”不等于“有频道表”。
- API 28+ 的所有设备上，带 selection 的查询都抛 `SecurityException`，与设计稿“查询不能带 selection，过滤在本地做”一致。
- 结果与 AOSP ATV / Google TV 的系统桌面版本无关：五种镜像上的可见范围一致。

原始证据：

- `docs/m0/evidence/api23/P01-handlers.json`
- `docs/m0/evidence/api28/P02-dump_tv.json`
- `docs/m0/evidence/api31g/P02-dump_tv.json`
- `docs/m0/evidence/api34g/P02-dump_tv.json`
- `docs/m0/evidence/api36/P02-dump_tv.json`
- 各设备的 `P-checks-results.json`

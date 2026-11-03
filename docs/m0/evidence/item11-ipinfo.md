# 第 11 项：IP 推测服务的使用条款

设计稿 §12 写的是“未设城市时用 ipinfo.io 推测，最多每天一次”。M0 于 2026-11-03 核对 ipinfo.io 的公开条款：

## 事实

1. **API token 是必需的。** `https://ipinfo.io/developers` 说明 token 用于认证，示例均在 URL 里带 `?token=`。
2. **免费 Lite 只到国家级别。** `https://ipinfo.io/pricing` 的 Free 计划写明 “Country-level only”，API 请求不限量；城市级字段属于付费 Core（$26/月，150k 请求/月）及以上。
3. **无 token 的旧接口不可用。** 实测 `curl https://ipinfo.io/json` 返回 HTTP 429：`{"status":429,"error":{"title":"Rate limit hit","message":"Sign up at ipinfo.io"}}`。
4. **自动化访问受限制。** Terms of Service（最后更新 2024-06-03）允许 “internal business purposes”，禁止转售；Acceptable Use Policy 禁止未经授权的自动化访问（spiders/robots/scrapers）。
5. **条款没有给出缓存时长或保留期限。** 隐私政策把 IP 地址列为收集的设备信息，用于生成“generalized location”，按实现目的保留。
6. **没有署名要求。** 免费 Lite 的定价页写的是 “Attribution required”，但条款正文没有对应的展示条款；以定价页为准时需要署名 IPinfo。

## 结论

按设计稿的“无密钥、免费、城市级、每天一次”四个条件，ipinfo.io 现在无法同时满足：

- 免费 Lite 没有城市字段；
- 城市级需要付费 Core 和 token；
- 无 token 的自动请求被 429 拒绝，条款也禁止未授权自动化访问。

因此 M3 的天气城市推测不能直接按设计稿实现。需要用户在以下方向中裁决（建议在 M3 前写 ADR）：

1. 申请 ipinfo.io token 并使用 Core 付费计划（需要密钥分发与费用）；
2. 换一个允许免费城市级查询的服务（需重新核对条款、配额与隐私）；
3. 取消 IP 推测，未设城市时显示“当前城市：未设置”，由用户在「手机与电脑」网页里设置城市（最简单、无第三方依赖）。

在裁决前，M3 不实现 IP 推测；Open-Meteo 的免费/非商业条款本身也需要按 §20 的风险预案处理。

## 原始来源

- https://ipinfo.io/terms-of-service
- https://ipinfo.io/acceptable-use-policy
- https://ipinfo.io/privacy-policy
- https://ipinfo.io/pricing
- https://ipinfo.io/developers
- 本地实测：2026-11-03，`curl -sS -D - https://ipinfo.io/json` → HTTP 429

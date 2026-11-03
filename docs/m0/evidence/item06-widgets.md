# 第 6 项：小组件绑定授权与遥控器交互

## 绑定处理者

`probe_handlers` 在五台设备上均记录 `appwidgetBind: []`，即没有 Activity 处理 `ACTION_APPWIDGET_BIND`；`appWidgetService` 特性均存在。

## ADB 授权

验收探针先执行 `appwidget revokebind`，`bind_widget` 失败；执行 `appwidget grantbind --package ... --user 0` 后再次 `bind_widget` 成功。五台设备均通过（P03 `grantbindWorks=true`）。

## 宿主加载与 provider 回调

在 api31g 上运行 `python acceptance/run.py selftest --serial emulator-5562 --name api31g`：

- K06：探针成功绑定 `com.github.mytv.mlfixture.sky/com.github.mytv.mlfixture.WeatherWidget`，记录 `ok=true` 与 configure 信息。
- K07：sky provider 记录到 `widget_update`，说明宿主加载后系统正常调用 `onUpdate`。
- 自检 K01–K08 全部通过。

## Shizuku 与常见手机小组件

- Shizuku 未安装在本机模拟器上，`opt:shizuku` 的端到端授权路径未能在 M0 实测；M3 需要真机或安装 Shizuku 后验证。
- 夹具小组件（sky/pulse/fit/book/crash）按电视遥控器设计，方向键可进入；常见手机小组件的触屏布局与焦点表现没有素材，未实测。
- 结论：ADB 授权路径可用；系统确认界面路径不存在；Shizuku 与 oem 路径留到 M3。

原始证据：

- 五台设备 `P01-handlers.json`、`P-checks-results.json`（P03）
- `docs/m0/evidence/api31g/selftest-results.json`

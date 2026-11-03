# ADR-001：电视模拟器上的默认桌面设置

- 状态：提议
- 日期：2026-11-03
- 影响：M0 结论、M1 验收前置、HOME 兼容

## 背景

M0 在矩阵内五台电视模拟器上测量了 `cmd package set-home-activity`、`cmd role add-role-holder` 与 HOME 键的实际行为（证据：`docs/m0/evidence/item10-home.md`）。

- 五台设备上 `cmd package set-home-activity --user 0 <launcher>` 都返回 `Success`，但 `resolve-activity -a MAIN -c HOME` 仍返回系统桌面。
- API 31g / 34g / 36 上 `cmd role add-role-holder ... android.app.role.HOME <pkg>` 可执行，但按 HOME 键仍启动 launcherx / tvlauncher。
- API 28 上 `cmd role` 服务不存在。
- 在 api28 上执行 `pm disable-user --user 0 com.google.android.tvlauncher` 后，`set-home-activity` 立即生效，`resolve-activity` 与 HOME 键都指向第三方桌面；恢复系统桌面后回到原状。

验收工具 `acceptance/mla/device.py` 的 `Device.set_home()` 通过 `home_component()`（即 `resolve-activity`）判断是否成功；`run.py` 的 `prepare()` 在 API≥24 上调用它，失败就抛 `D000`。按当前 AVD 行为，M1 的设备检查会在准备阶段直接失败，D102/D110/D114 等依赖 HOME 键的检查也无法执行。

这不是本仓库代码能单独解决的：系统桌面在镜像里是 priority=2 的预装应用，第三方桌面没有权限停用它。

## 选项

### A. 验收工具在设备测试期间停用系统桌面，测试结束后恢复（推荐）

- 在 `prepare()` 设置默认桌面前执行 `pm disable-user --user 0 <system home pkg>`，在 `teardown()` 恢复。
- 需要修改冻结的验收工具包并重新冻结，由用户批准。
- 不改变产品行为；模拟的是用户真正把第三方桌面设为默认桌面的状态。
- 风险：部分检查期望系统桌面仍存在（例如 P01 的 `home`/`homeDefault`、D103 的“没有内容来源时”）；需要确认这些检查在系统桌面停用后的预期。P01 在 M0 的 `homeDefault` 可能变为第三方桌面；M0 报告需相应更新，或者只在 M1+ 的准备阶段停用。

### B. 产品在 HOME intent-filter 上提高 priority

- 在 `AndroidManifest.xml` 的 HOME filter 上设置 `android:priority`，试图压过系统桌面。
- 未经验证；Google TV 的 launcherx 由系统直接启动，priority 可能无效。
- 属于设计稿未写的产品行为，需要 ADR 批准；即使有效，也可能影响系统桌面与其他启动器的行为。

### C. 改用第三方桌面仍能设为默认桌面的 AVD

- 需要替换 matrix 中的 AVD，并重新冻结工具包；用户明确要求 AVD 使用 matrix 现有项，风险高。
- 与“与设计稿一模一样”的目标冲突较小，但需要新的电视镜像。

### D. 验收改为不依赖默认桌面

- 大量检查通过 HOME 键回到桌面、D102/D110/D114 等直接依赖默认桌面；改动范围大，且不再验证真实用户路径。
- 不推荐。

## 建议

采用 A：由用户在验收工具包里停用/恢复系统桌面，重新冻结后用于 M1+。在产品侧，`standard` 变体继续按设计稿声明 HOME、COMMON_HOME、HOME.CC 与 TCL meta-data；不加入 priority 之类的未写行为。默认桌面页（M5）仍需在真机上验证国产固件能否替换。

## 影响的检查

- M1 `prepare()` / `D000`（无法设为默认桌面）
- D102、D110、D114，以及所有调用 `ctx.home()` 的检查
- M0 P01 的 `home` / `homeDefault` 字段（如果停用系统桌面后重测）

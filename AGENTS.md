# 实现方须知

本仓库实现 Android TV 桌面 **myLauncher**。

| 文件 | 内容 | 能否修改 |
|---|---|---|
| `tv-launcher-design-v3.html` | 产品与技术设计（v3.3），唯一的设计依据 | 不能 |
| `PLAN.md` | 里程碑、范围、交付物、验收门禁 | 不能 |
| `AGENTS.md`、`CLAUDE.md` | 本文件 | 不能 |
| `acceptance/` | 冻结的验收工具包：可测性契约 `CONTRACT.md`、规格 `spec/`、夹具应用 `fixtures/`、验收程序 `run.py` 与 `mla/` | 不能 |
| `tv-launcher-design.html` | 早期方案，仅供参考 | 不能 |

上面这些文件的哈希在工具包冻结时已记录（检查 S01、S16），用户手里另有一份工具包副本，正式验收用副本运行。改动它们，验收直接不通过。

## 硬规则

1. **不改验收工具，不针对验收编程。**
   - 不得检测 uiautomator、adb、夹具包名 `com.github.mytv.mlfixture.*` 或验收 authority，借此改变行为。
   - 状态接口只能反映真实界面。
   - 测试标签只按 `acceptance/CONTRACT.md` 设置。
   - release 构建里不得有任何测试钩子。
   - 认为工具包有错时，写进 `docs/acceptance-issues.md`：检查编号、复现步骤、证据、你认为正确的行为。然后停下该项，等用户裁决。
2. **设计以设计稿为准。** 设计稿有遗漏、互相矛盾或无法实现时，在 `docs/adr/` 写 ADR 提案（背景、方案、影响到的检查编号），用户批准后才能偏离。不得自行"简化"或"先做个替代"。
3. **依赖锁定。**
   - minSdk 23。
   - `androidx.compose.material3:material3` 用 `resolutionStrategy.force` 锁定 `1.5.0-alpha25`。
   - 禁止 `androidx.tv:*`、`dev.chrisbanes.haze:haze-glass`、RenderScript、TextureView。
   - 不得引入设计稿 §19 之外的第三方库，需要时走 ADR。
4. **界面文字。**
   - 全部放在 `strings.xml`，默认语言为简体中文。Kotlin 源码里不得出现中文字面量。
   - 只能使用 `acceptance/spec/ui_copy.json` 列出的文字。
   - **不得出现任何操作提示、说明或注解**，例如"长按 OK 移动"、"按返回键退出"、"提示：…"。说明性内容只写在文档里。
5. **系列应用不改。** 不得修改 myBV、mydv、mytv-android 的代码与仓库；直连协议以设计稿为准。
6. **模拟器。**
   - 只使用 `acceptance/spec/matrix.json` 中的 AVD。
   - 不得 wipe-data、删除或新建快照、修改 AVD 配置。
   - 不得操作 `emulator-5554`（用户的手机模拟器）或其他非电视设备。
7. **按里程碑推进。**
   - 一次只做一个里程碑。
   - 上一个里程碑没有正式验收通过并经用户确认，不得开始下一个。
   - 不得提前实现后续里程碑的功能来"顺便"凑检查。
8. **完成的唯一依据是验收报告。**
   - 里程碑完成要有 `python acceptance/run.py accept Mx` 的报告，结论为"通过"，且 `formal: true`。
   - 不得手工编辑 `acceptance/out/` 下的任何文件，不得用 `--no-build`、`--devices`、`--only` 的自查结果冒充正式结果。
9. **git。**
   - 工程是 git 仓库，每个里程碑一个分支 `m0`…`m5`，小步提交。
   - 正式验收前工作区必须干净（S15）。
   - `acceptance/out/` 与 `__pycache__/` 加入 `.gitignore`。

## 每个里程碑的流程

1. 读 `PLAN.md` 中该里程碑一节，以及它列出的设计稿章节。
2. 实现。开发中可随时自查：
   ```
   python acceptance/run.py accept M2 --devices api28 --only D201,D204 --no-build
   python acceptance/run.py device M2 --serial emulator-5556 --name api28 --only D207
   python acceptance/run.py static M2 --no-build
   ```
3. 提交全部改动，确认 `git status` 干净。
4. 正式验收（从头构建，依次启动矩阵内的模拟器，耗时较长，不要中途打断）：
   ```
   python acceptance/run.py kit verify
   python acceptance/run.py accept M2
   ```
5. 结论为"通过"后，写 `docs/milestones/M2.md`，内容包括：
   - 报告目录和 `results.json` 的 SHA-256；
   - git commit；
   - 每个 WARN 的原因；
   - MANUAL 项的截图路径；
   - 已知问题；
   - 本里程碑涉及的 ADR。
6. 停下，等用户确认。

验收不通过时，修代码，不要改检查。同一个检查连续三次修不过，写进 `docs/acceptance-issues.md` 并停下。

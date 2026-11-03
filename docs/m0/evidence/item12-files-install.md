# 第 12 项：文件与安装的系统入口

## 设置页处理者

| 设备 | `MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`（带包名） | `MANAGE_ALL_FILES_ACCESS_PERMISSION` | `MANAGE_UNKNOWN_APP_SOURCES`（带包名） | `OPEN_DOCUMENT_TREE` | DocumentsUI 包 |
|---|---|---|---|---|---|
| api23 | 无 | 无 | 无 | 无 | 无 |
| api28 | 无 | 无 | `com.android.tv.settings/.device.apps.specialaccess.ExternalSourcesActivity` | 无（`com.android.documentsui` 存在但无 Activity） | 有包、无入口 |
| api31g | 无 | 无 | `com.android.tv.settings/.device.apps.specialaccess.ExternalSourcesActivity` | `com.android.tv.frameworkpackagestubs/.Stubs$DocumentsStub` | 无 |
| api34g | `com.android.tv.settings/.device.apps.specialaccess.AllFilesAccessActivity` | 无 | 同上 | DocumentsStub | 无 |
| api36 | `AllFilesAccessActivity` | 无 | 同上 | DocumentsStub | 无 |

结论：所有文件访问页从 API 34 起出现在 AOSP/Google TV 模拟器上；API 23/28/31 没有。安装未知应用页从 API 26 起存在。DocumentsUI 在电视镜像上不存在可用入口（framework stub 不是文件选择器），API 28 的 `com.android.documentsui` 包也没有声明 `OPEN_DOCUMENT_TREE`。因此“目录授权”无法在模拟器上用遥控器完成。

## PackageInstaller 确认界面

spike 的 InstallActivity 用 `PackageInstaller` 会话提交一个夹具 APK，接收 `STATUS_PENDING_USER_ACTION` 后在前台拉起系统确认界面，然后用遥控器按键操作。

| 设备 | 确认界面 | 安装 | 取消 | 备注 |
|---|---|---|---|---|
| api23 | 有 | 成功（`INSTALL_SUCCEEDED`） | 成功（`INSTALL_FAILED_ABORTED`） | 需先 `settings put secure install_non_market_apps 1`；焦点默认在 Cancel，按右键到 Install |
| api28 | 有 | 成功 | 成功 | 焦点默认在对话框，按 DOWN 到 Cancel，再 RIGHT 到 Install |
| api31g | 有 | 成功 | 成功 | 焦点默认在 Cancel |
| api34g | 有 | 成功（`Session installed`） | 成功 | 焦点默认在 Cancel，按 LEFT 到 Install |
| api36 | 有 | 成功 | 成功 | 焦点默认在 Cancel，按 LEFT 到 Install |

四张截图：`api23/install-dialog.png`、`api28/install-dialog.png`、`api31g/install-dialog.png`、`api36/install-dialog.png`。

## 存储授权与写入

api34g 有一个可移除的 public vfat 卷 `public:253,80` 挂在 `/storage/0000-0000`，可作为 U 盘的近似：

- `MANAGE_EXTERNAL_STORAGE` deny：写 `/storage/0000-0000/<new>.txt` 失败 `EPERM`。
- `MANAGE_EXTERNAL_STORAGE` allow：同一路径写入成功。
- `/sdcard/Download/<new>.txt` 在 deny 与 allow 下都写入成功（API 34 的 Download 通过 FUSE 允许应用新建文件）。
- 应用私有外部目录 `/storage/emulated/0/Android/data/<pkg>/files/` 始终可写。

其他设备没有可移除卷（`sm list-disks` 为空），无法挂载 U 盘镜像；U 盘在真实硬件与各授权状态下的写入按 PLAN 4.1 留给真机确认。

原始证据：本文件；各设备 `P01-handlers.json`；`api34g` 的 storage 命令日志（记录在 `docs/m0/evidence/item12-files-install.md`）。

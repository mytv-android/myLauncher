# 第 7 项：material3 1.5.0-alpha25 运行验证与 MaterialKolor

## material3 / Compose 运行

主工程空壳与 `spikes/app` 都使用 material3 `1.5.0-alpha25`、Compose `1.12.1`、AGP `9.4.1`、Kotlin `2.4.20`（AGP 9 内置 Kotlin），compileSdk 37，targetSdk 36，minSdk 23。`MaterialExpressiveTheme`、`MotionScheme.expressive()` 在五台设备上均可运行，无 AndroidRuntime 崩溃。

冷启动 `am start -W`（MainActivity）：

| 设备 | TotalTime |
|---|---|
| api23 | 875 ms |
| api28 | 1818 ms |
| api31g | 1615 ms |
| api34g | 1825 ms |
| api36 | 7313 ms |

api36 的 7.3 秒是模拟器首次安装后的冷启动参考值，包含模拟器自身负载，不能当作真机预算。静态门禁 S04 已在 APK 内读到 material3 `1.5.0-alpha25`、Compose `1.12.1`。

## MaterialKolor / MCU

| 组件 | 版本 | minSdk | 依赖 |
|---|---|---|---|
| `com.materialkolor:material-kolor-android` | 5.0.1 | 21 | JetBrains Compose material3 1.10.0-alpha05、runtime/ui 1.10.0、colormath 3.6.1、kotlin-stdlib 2.3.0 |
| `com.materialkolor:material-color-utilities-android`（MCU） | 6.0.0 | 21 | kotlin-stdlib 2.4.21、poko-annotations 0.23.2 |

结论：MaterialKolor 的 minSdk 满足 23，但它会引入 JetBrains Compose material3 1.10.0-alpha05 与 Compose 1.10.0，和本项目锁定的 AndroidX material3 1.5.0-alpha25 / Compose 1.12.1 冲突；不引入 MaterialKolor。按 PLAN M2 直接使用 MCU（QuantizerCelebi / Score / DynamicScheme / Hct），其 minSdk 21 且依赖只有 Kotlin 标准库与 poko 注解，满足 `resolutionStrategy.force` 的前提。

原始证据：`docs/m0/evidence/api31g/spike-log-full.txt`；依赖树、APK 版本文件由 `python acceptance/run.py static M0` 的 S04/S05 输出记录。

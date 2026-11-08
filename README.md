# 时空变速器（TimeWarp）

一个运行在 LSPosed / Xposed 框架上的 Android 时间变速模块。可对指定应用（包括游戏）加速或减慢时间流速，支持 0.1x ~ 100x 倍率。

## ✨ 功能

- **多应用独立变速** — 每个应用单独设置倍率和启停状态
- **悬浮快捷球** — 应用内直接切换开关、拖动调整位置，磨砂玻璃质感
- **帧控兼容** — 只缩放墙钟时间，**不碰单调时钟**（`nanoTime` / `uptimeMillis` 等），避免 Cocos2d / Unity 游戏引擎跳过 `sleep` 导致主线程 100% spin 和 ANR
- **原生引擎 GOT Hook** — 对 Cocos2d 等原生引擎额外 hook 其 `.so` 内部的 `clock_gettime` / `gettimeofday`，Java 层和 Native 层同时生效
- **会话级开关** — 悬浮球点击翻转本进程加速，不影响控制端预设
- **HyperOS 3 磨砂效果** — 悬浮球背后的游戏画面由系统 `blurBehindRadius` 做高斯模糊

## 📋 兼容

| 项目 | 要求 |
|------|------|
| Android | 8.0+（API 26） |
| LSPosed | 1.9.6+（libxposed API 102） |
| 架构 | arm64-v8a、armeabi-v7a |
| 系统 | 原生 Android / MIUI / HyperOS / ColorOS / OneUI 等常见定制 ROM |

## 🚀 安装

1. 在 [Releases](https://github.com/liushiwu56/timewarp/releases) 下载最新 `app-release.apk`
2. 在 LSPosed 管理器中勾选本模块，选择目标应用
3. 重启设备（或强制停止目标应用后重开）
4. 打开模块控制界面，勾选要变速的应用并选择倍率

## 🎮 使用

1. 启动已启用变速的目标应用
2. 应用角落会出现磨砂玻璃悬浮球
3. 点击悬浮球 → 切换本应用会话级加速启停
4. 拖动悬浮球 → 移动位置，位置按应用独立保存在各应用沙箱内
5. 切回控制界面 → 可调整倍率、开关总开关

## 🏗️ 构建

### 环境要求

| 工具 | 版本 |
|------|------|
| JDK | 21+ |
| Android SDK | compileSdk 36 |
| Android NDK | 26.3.11579264（`ndkVersion` 可在 `app/build.gradle` 中修改） |
| Gradle | 9.8.2（项目自带 Wrapper） |

### 构建命令

```powershell
# 调试版
.\gradlew.bat assembleDebug

# Release 版
.\gradlew.bat assembleRelease

# 清理 + 重编
.\gradlew.bat clean assembleRelease
```

产物位置：`app/build/outputs/apk/release/app-release.apk`

## 📁 项目结构

```
timewarp-1106/
├── app/
│   └── src/main/
│       ├── java/com/timewarp/speed/
│       │   ├── MainHook.java        # Xposed 入口，hook System.currentTimeMillis + native .so GOT
│       │   ├── MainActivity.java    # 控制界面（应用列表、倍率设置）
│       │   ├── FloatEntry.java      # 悬浮球 UI（拖动/点击切换/磨砂玻璃）
│       │   └── SpeedReceiver.java    # 跨进程配置写入接收器
│       ├── jni/
│       │   ├── Android.mk            # NDK 构建脚本
│       │   ├── twclock.c            # native hook（clock_gettime / gettimeofday GOT 表重写）
│       │   └── libs/
│       │       ├── arm64-v8a/libtwclock.so
│       │       └── armeabi-v7a/libtwclock.so
│       ├── res/                     # 控制界面资源
│       └── resources/META-INF/xposed/
│           └── module.prop          # LSPosed 模块声明
├── gradle/wrapper/                  # Gradle Wrapper
├── build.gradle                     # 根构建脚本
└── settings.gradle
```

## 🔧 原理简述

### 缩放算法

```
virtual = virtBase + (real - realBase) × factor
```

- `factor` 变化时以最近一次 `(real, virt)` 为基准重设，保证时间不回跳
- 所有字段 `volatile`，读路径无锁，每秒可被调用上万次不产生锁竞争
- `factor > 1.0` 时循环重定基准防止 `real - realBase` 溢出 `Long.MAX_VALUE / factor`

### 为什么只 hook `currentTimeMillis`

| 时钟类型 | 是否 hook | 用途 |
|---------|:---------:|------|
| `System.currentTimeMillis()` | ✅ | 游戏内 `Date.now()`、服务器时间戳、真实时间计时 |
| `System.nanoTime()` | ❌ | 游戏引擎帧间隔控制、`Thread.sleep()` 唤醒 |
| `SystemClock.uptimeMillis()` | ❌ | 同上 |
| `elapsedRealtime()` | ❌ | 同上 |

放大单调时钟 → 引擎认为时间过得慢 → 跳过 `sleep` → 主线程 100% spin → InputDispatcher 5s ANR → 系统 kill。

### Native GOT Hook

`twclock.c` 遍历目标 `.so` 的 ELF GOT 表，找到 `clock_gettime` / `gettimeofday` 的重定位项并改写指向 trampoline。引擎 dlopen `.so` 后延迟 1 秒重扫，扫描上限 20 次自动退出。

### 配置存储

- **跨应用配置**（倍率、启停、总开关）→ LSPosed 守护进程管理的远程 SharedPreferences（组名 `"speed"`），控制端写、目标进程读
- **悬浮球位置** → 各目标应用进程自己的沙箱 `SharedPreferences`（`timewarp_float_pos.xml`），不依赖远程 prefs

## ⚠️ 注意事项

- 本模块**不修改**任何应用内的排行榜/战斗/抽奖等游戏逻辑，只影响时间感知
- 部分游戏有服务端时间校验，加速不会影响服务器返回的数据
- 切换倍率后需要等引擎下一帧才会生效

## 📄 License

本项目仅供学习和个人使用。使用本模块所产生的一切后果由使用者自行承担。

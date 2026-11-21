# SaKaTouRin 1.4

- 龙娘站立和打字的显示高度由 145dp 缩到 116dp，宽度和触控范围同步缩小。点击动画时脚底固定。
- 轻点龙娘依次打开五小时额度气泡、切到周额度、收起气泡。额度气泡显示剩余百分比、进度条和重置时间。点左上角可以回到菜单查看总计 Token、当前执行的任务及任务进度。
- 保留拖动和长按切换打字。菜单开合、页面切换、数值、进度条、打字和形态切换都有过渡。
- 任务数据显示 Termux 里可读取的本机 Codex 会话。多个会话同时执行时，优先显示最近启动的任务。有计划步骤时按已完成步骤计算百分比，未收到计划步骤时显示当前阶段。进程阶段来自 Codex 状态和会话活动，任务详情不会写进 App 的上次额度缓存。

## 升级与连接

Android 安装包使用原 GitHub Actions 签名时可以覆盖安装，继续使用已有的配对密钥。把本包里的新版 `quota_bridge.py` 替换到 Debian 的 `/root/quota_bridge.py`，在旧桥接的 Termux 会话里按 Ctrl+C 停止旧服务，再运行：

```bash
proot-distro login debian -- python3 -u /root/quota_bridge.py
```

如果原桥接通过 `--background` 或 `start_quota_bridge.sh` 启动，请先停掉旧桥接进程和对应的守护进程再启动新版，避免 8765 端口占用。旧桥接可以继续提供三项额度，任务两项需要新版桥接。独立的云端 Work 会话需要它自己的数据源。

## 构建 APK

仓库的 `.github/workflows/build.yml` 使用此 ZIP，并通过既有密钥签名。源码解压后也可以在安装了 Android SDK 35 和 Gradle 8.9 的环境执行 `gradle assembleRelease` 构建未签名包。正式签名由仓库的 Actions 工作流完成。

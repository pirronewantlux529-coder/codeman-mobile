# Codeman Mobile

[Codeman](https://github.com/Ark0N/Codeman) 的**第三方安卓客户端**（非官方）：在手机上管理跑在你电脑（WSL/Linux）里的 Claude Code / Codex / OpenCode 会话。

> Unofficial Android client for [Codeman](https://github.com/Ark0N/Codeman) — manage your Claude Code / Codex / OpenCode sessions running on a WSL/Linux box from your phone, with an embedded WireGuard tunnel and multi-machine switching.

## 为什么需要它

Codeman 本身有非常好的手机 Web UI，但用浏览器访问有几件麻烦事：每次输密码、内网穿透要自己想办法、多台电脑要记多个地址。这个 App 把这些都包掉了：

- **多机器管理**：添加任意多台电脑（名称/IP/端口/账号密码），悬浮球菜单一键切换
- **内嵌 WireGuard**：用官方 `com.wireguard.android:tunnel` 库，把你现有的 WireGuard 客户端配置（wg-quick `.conf` 格式）粘贴进去即可，打开 App 自动连接隧道，不依赖系统 WireGuard 客户端
- **自动登录**：原生代答 Codeman 的 HTTP Basic 认证，打开就是终端
- **全屏 WebView + 可拖动悬浮球**：不遮挡 Codeman 自己的多窗口/标签 UI；VPN 连通时悬浮球变绿
- **会话永不丢**：会话本来就跑在电脑端 tmux 里，手机退出/断网/熄屏都不影响 agent 继续干活

## 下载

直接安装 [`releases/`](releases/) 目录下的 APK（arm64/universal，Android 8.0+）。

## 自己构建

```bash
# 需要 JDK 17 + Android SDK 34
cp keystore.properties.example keystore.properties   # 填你自己的签名信息
gradle assembleRelease
```

`keystore.properties`（不入库）：

```properties
storeFile=你的.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

## 服务端部署（电脑侧）

1. 在 WSL/Linux 里安装 [Codeman](https://github.com/Ark0N/Codeman)
2. 以 HTTP 模式启动（**重要**：WebView 的 WebSocket 不走自签证书放行通道，自签 HTTPS 会导致终端连不上；加密交给 WireGuard）：
   ```bash
   CODEMAN_PASSWORD=你的密码 CODEMAN_PORT=8095 CODEMAN_HOST=0.0.0.0 codeman web
   ```
3. **WSL 用户必看**，`deploy/` 目录提供了开箱即用的长期运行方案：
   - `wsl_codeman_keepalive.sh.example` — 把 codeman 包进独立 tmux server 跑。**这是关键**：经 `wsl.exe` 启动的后台进程（即使 `setsid`）会在 wsl.exe 退出后被 WSL 清场，tmux server 是唯一可靠的幸存方式
   - `codeman_watchdog.ps1.example` — Windows 计划任务：portproxy 自动跟随 WSL IP 变化 + 进程保活
   - `tcp-relay.py.example` — 手机路由不到目标网段时，在一台两边都通的服务器上做 TCP 中转

## 中文/Unicode 路径补丁

Codeman 上游目前用 ASCII 白名单正则校验项目名和路径，中文目录会被拒绝。`patches/apply-unicode-patch.py` 把校验改为 Unicode 属性类（保留防注入设计）：

```bash
python3 patches/apply-unicode-patch.py ~/.codeman/app
cd ~/.codeman/app && npm run build
```

## 致谢与协议

- [Codeman](https://github.com/Ark0N/Codeman)（MIT）— 本项目是它的配套客户端，不包含其代码；补丁脚本以 MIT 协议对其源码做最小修改
- [wireguard-android](https://git.zx2c4.com/wireguard-android/)（Apache-2.0）— 内嵌隧道能力
- WireGuard 是 Jason A. Donenfeld 的注册商标

本项目代码以 [MIT](LICENSE) 协议发布。与 Codeman、WireGuard 官方均无隶属关系。

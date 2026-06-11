# Codeman Mobile

**中文** | [English](#english)

[Codeman](https://github.com/Ark0N/Codeman) 的**第三方安卓客户端**（非官方）：在手机上管理跑在你电脑（WSL/Linux）里的 Claude Code / Codex / OpenCode 会话，内嵌 WireGuard 隧道，支持多台电脑切换。

## 为什么需要它

Codeman 本身有非常好的手机 Web UI，但用浏览器访问有几件麻烦事：每次输密码、内网穿透要自己想办法、多台电脑要记多个地址。这个 App 把这些都包掉了：

- **多机器管理**：添加任意多台电脑（名称/IP/端口/账号密码），悬浮球菜单一键切换
- **内嵌 WireGuard**：用官方 `com.wireguard.android:tunnel` 库，把你现有的 WireGuard 客户端配置（wg-quick `.conf` 格式）粘贴进去即可，打开 App 自动连接隧道，不依赖系统 WireGuard 客户端
- **自动登录**：原生代答 Codeman 的 HTTP Basic 认证，打开就是终端
- **全屏 WebView + 可拖动悬浮球**：不遮挡 Codeman 自己的多窗口/标签 UI；VPN 连通时悬浮球变绿
- **会话永不丢**：会话本来就跑在电脑端 tmux 里，手机退出/断网/熄屏都不影响 agent 继续干活

## 下载

到 [Releases](../../releases) 页面或 [`releases/`](releases/) 目录下载 APK（arm64/universal，Android 8.0+）。

## 自己构建

```bash
# 需要 JDK 17 + Android SDK 34
cp keystore.properties.example keystore.properties   # 填你自己的签名信息
gradle assembleRelease
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

---

# English

An **unofficial Android client** for [Codeman](https://github.com/Ark0N/Codeman): manage Claude Code / Codex / OpenCode sessions running on your computer (WSL/Linux) from your phone, with an embedded WireGuard tunnel and multi-machine switching.

## Why

Codeman already ships an excellent mobile web UI, but raw browser access has friction: typing passwords every time, figuring out remote access yourself, juggling multiple addresses for multiple machines. This app wraps all of that:

- **Multi-machine management** — add any number of computers (name/IP/port/credentials), switch with one tap from the floating bubble menu
- **Embedded WireGuard** — uses the official `com.wireguard.android:tunnel` library; paste your existing wg-quick `.conf` and the tunnel auto-connects on app launch, no system WireGuard client needed
- **Auto login** — natively answers Codeman's HTTP Basic auth, you land straight in the terminal
- **Full-screen WebView + draggable bubble** — never covers Codeman's own multi-window/tab UI; the bubble turns green while the VPN is up
- **Sessions never die** — sessions live in tmux on the computer; closing the app, losing signal, or locking the screen never interrupts your agents

## Download

Grab the APK from [Releases](../../releases) or the [`releases/`](releases/) directory (arm64/universal, Android 8.0+).

## Build it yourself

```bash
# Requires JDK 17 + Android SDK 34
cp keystore.properties.example keystore.properties   # fill in your own signing info
gradle assembleRelease
```

## Server-side setup (on the computer)

1. Install [Codeman](https://github.com/Ark0N/Codeman) in WSL/Linux
2. Run it in HTTP mode (**important**: WebView's WebSocket connections bypass the self-signed-cert allowance, so self-signed HTTPS breaks the terminal stream; let WireGuard handle encryption):
   ```bash
   CODEMAN_PASSWORD=yourpass CODEMAN_PORT=8095 CODEMAN_HOST=0.0.0.0 codeman web
   ```
3. **WSL users**: the `deploy/` directory has ready-to-use templates for long-term operation:
   - `wsl_codeman_keepalive.sh.example` — runs codeman inside a dedicated tmux server. **This matters**: background processes spawned via `wsl.exe` (even with `setsid`) get reaped when that wsl.exe exits; a tmux server is the reliable way to survive
   - `codeman_watchdog.ps1.example` — Windows scheduled task: keeps the portproxy in sync with the changing WSL IP + keeps the process alive
   - `tcp-relay.py.example` — TCP relay for when your phone can't route to the target machine's subnet

## Unicode/CJK path patch

Upstream Codeman currently validates case names and paths with ASCII-whitelist regexes, rejecting Chinese/Japanese/Korean directories. `patches/apply-unicode-patch.py` switches them to Unicode property classes while keeping the anti-injection design intact:

```bash
python3 patches/apply-unicode-patch.py ~/.codeman/app
cd ~/.codeman/app && npm run build
```

## Credits & License

- [Codeman](https://github.com/Ark0N/Codeman) (MIT) — this project is a companion client and contains none of its code; the patch script makes minimal MIT-licensed modifications to its source
- [wireguard-android](https://git.zx2c4.com/wireguard-android/) (Apache-2.0) — embedded tunnel capability
- WireGuard is a registered trademark of Jason A. Donenfeld

Released under the [MIT License](LICENSE). Not affiliated with Codeman or WireGuard.

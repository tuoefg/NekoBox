<div align="center">

```
 /\_/\
       ( o.o )   ← 这只猫负责替你的流量看门
 > ^ <
```

# 🐱 NekoBox · 猫盒

**把复杂的代理配置装进一个盒子里——你只管撸猫，剩下的交给它。**

*A purr-fectly simple proxy cat for Android, powered by sing-box & Xray.*

[![GitHub stars](https://img.shields.io/github/stars/tuoefg/NekoBox?style=flat-square&logo=github)](https://github.com/tuoefg/NekoBox/stargazers)
[![GitHub release](https://img.shields.io/github/v/release/tuoefg/NekoBox?style=flat-square&logo=github&color=orange)](https://github.com/tuoefg/NekoBox/releases)
[![License](https://img.shields.io/github/license/tuoefg/NekoBox?style=flat-square&color=blue)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-7.0%2B%20%F0%9F%93%BA-3DDC84?style=flat-square&logo=android&logoColor=white)](#)
[![Core](https://img.shields.io/badge/core-sing--box%20%2B%20Xray-ff69b4?style=flat-square)](#)

</div>

---

## 🙀 这是什么猫？

NekoBox（猫盒）是一只住在 Android 设备里的 GUI 代理猫，帮你管好服务器、订阅和分流规则，
把「连不上、跑得慢、该走哪条线」这类烦心事一口吞掉。

- 📱 支持 Android 7.0（API 24）及以上，**手机、平板、电视都能住**（Android TV 优化过）
- 🧬 出身名门：基于 [Throne for Android](https://github.com/throneproj/ThroneForAndroid) 的开源分支，家谱可追溯到 NekoBox for Android
- 🧠 双内核：[sing-box](https://github.com/SagerNet/sing-box) + [Xray-core](https://github.com/XTLS/Xray-core)，与桌面版 Throne 同一套配置和备份格式
- ✍️ 用 Kotlin 写的，遵守 GPL-3.0

## ✨ 猫会哪些把戏？

| 猫的把戏 | 说明 |
| --- | --- |
| 🧶 **订阅 & 分组** | 像自动喂食器：定时更新订阅、过滤、清理，更新完自动测速，还会打小报告告诉你今天新来了哪些节点 |
| ⚡ **Auto Selector** | 猫自己挑窝：盯着一组节点实时排名，谁快睡谁，掉线了自动搬家 |
| 🧭 **路由配置** | 与桌面版共用一套分流规则，还能按 App、Wi-Fi（SSID / BSSID）、网络类型、计费网络来分——哪个 App 钻哪个猫洞，安排得明明白白 |
| 🛰️ **Cloudflare WARP** | 内置 WARP 注册（WireGuard / MASQUE 身份）和 WARP 路由模式，出门自带小雨衣 |
| 📊 **批量测速** | 实时进度、延迟直方图、一键连最快、踢掉不可用——谁是懒猫一目了然 |
| 🖥️ **内置仪表盘** | sing-box 官方面板直接塞进 App：实时流量、连接、日志，一屏撸完 |
| ✏️ **JSON 编辑器** | 自带 schema 校验、补全、格式化，手写配置不再抓瞎 |
| 📦 **备份同步** | `.thrbackup` 一份罐头手机 / 桌面通吃，还能走 WebDAV 云端存粮 |
| 🔘 **小部件 & 磁贴** | 桌面小部件、快捷设置磁贴、通知栏直接换节点——顺手撸一下就行 |
| 📺 **Android TV** | 启动器横幅、遥控器方向键导航、上一只 / 下一只切换 |

## 🍚 喂什么猫粮都吃

**协议（挑食？不存在的）：**

SOCKS · HTTP(S) · Shadowsocks · Trojan · VMess · VLESS · TUIC · Hysteria / Hysteria2 · AnyTLS · Mieru · Snell · NaïveProxy · Juicity · TrustTunnel · ShadowTLS · WireGuard · AmneziaWG · MASQUE · SSH · **DNSTT / VayDNS** · **MasterDNS** · **Xray VLESS（XHTTP / XDrive）** · OpenVPN / OpenConnect · 自定义出站（sing-box & Xray）· 链式出站

**订阅格式：**

分享链接、各类 sing-box JSON、v2rayN 链接格式，以及有限的 Shadowsocks / Clash 格式支持。
（只导入出站节点，订阅里的分流规则会被礼貌地无视掉。）

## 📦 去哪领养？

按你的设备挑一只（当前推荐 **v2.0.6**）：

| 你的设备 | 领养链接 |
| --- | --- |
| 📱 现代手机（ARMv8） | [![APK ARMv8](https://img.shields.io/badge/APK-ARMv8-168039.svg?logo=android)](https://github.com/tuoefg/NekoBox/releases/download/v2.0.6/NekoBox-2.0.6-arm64-v8a.apk) |
| 📱 老手机（ARMv7） | [![APK ARMv7](https://img.shields.io/badge/APK-ARMv7-45bf55.svg?logo=android)](https://github.com/tuoefg/NekoBox/releases/download/v2.0.6/NekoBox-2.0.6-armeabi-v7a.apk) |
| 💻 模拟器 / x64 设备 | [![APK x64](https://img.shields.io/badge/APK-x64-96ed89.svg?logo=android)](https://github.com/tuoefg/NekoBox/releases/download/v2.0.6/NekoBox-2.0.6-x86_64.apk) |

- 🗂️ 全部版本看这里：[Releases](https://github.com/tuoefg/NekoBox/releases) · 每天吃了啥见 [CHANGELOG](CHANGELOG.md)
- 🩺 下载后可以对一下 `SHA256SUMS`——这是猫的体检报告，确认没被调包

> ⚠️ **从 1.x 版本升级的猫奴注意**：v2.0.0 起换了新签名，Android 不允许直接覆盖安装。
> 先送走旧版本（卸载），再导入订阅和配置重新安家——旧本地数据不会自动搬家。

## 🔨 自己孵一只

```bash
git clone https://github.com/tuoefg/NekoBox.git
cd NekoBox
./gradlew assembleRelease
```

或者用 Android Studio 打开项目，插上手机，点 ▶️ Run。就这么多，猫会自己长大。

> 🤖 懒得自己孵？上游每天自动同步 + 自动构建发版，你也可以只负责撸猫。

## 🙏 家谱与隔壁的好猫们

NekoBox 站在巨猫的肩膀上：

- **上游**：[throneproj/ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid)（Throne for Android）——本仓库的直接父母
- **内核**：[throneproj/sing-box](https://github.com/throneproj/sing-box)（fork 自 [SagerNet/sing-box](https://github.com/SagerNet/sing-box)）· [throneproj/Xray-core](https://github.com/throneproj/Xray-core)（fork 自 [XTLS/Xray-core](https://github.com/XTLS/Xray-core)）
- **GUI 老祖宗与灵感**：[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) · [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet) · [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- **分流规则包**：[throneproj/routeprofiles](https://github.com/throneproj/routeprofiles)

## 🐾 Star History

猫粮储备（现在还不算多，但猫有九条命）：

<a href="https://www.star-history.com/?repos=tuoefg/NekoBox&type=Date">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=tuoefg/NekoBox&type=Date&theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=tuoefg/NekoBox&type=Date" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=tuoefg/NekoBox&type=Date" />
 </picture>
</a>

## 📜 许可证

[GPL-3.0](LICENSE) —— 自由领养，也请同样善待它的后代。
贡献者名单见 [AUTHORS](AUTHORS)。

---

<div align="center">

**喵。** 🐾

*如果这只猫帮到了你，欢迎丢一颗 ⭐ 当猫粮——它真的会吃。*

</div>

# Bilibili Audio Player

在 [NetMusic](https://github.com/TartaricAcid/NetMusic) 的基础上，让音乐 CD 支持播放 **B 站 BV 号**音频。

**不新增任何物品**，完全复用 NetMusic 的音乐 CD / 唱片机方块。

## 兼容范围

一个 jar 包同时兼容以下 NetMusic 版本（Forge 1.20.1）：

| NetMusic 版本 | 适配方式 | 状态 |
| --- | --- | --- |
| 1.1.8 ~ 1.3.1 | Mixin 注入 | ✓ 已测试 |
| 1.4.0 | — | ✗ 不支持 |
| 1.5.0 ~ 1.5.1 | 原生 API 注册 | ✓ 已测试 |

### 双版本兼容原理

NetMusic 在 1.4.0 引入了 `AudioStreamHandlerManager`，在 1.5.0 引入了 `IAsyncSongUrlResolver`。本 mod 在启动时检测这两个类是否存在，自动选择适配路径：

**1.1.8 ~ 1.3.1（Mixin 路径）**：
- `MusicPlayManagerMixin` 拦截 `play()`，把 `bilibili://BVxxxx` 异步解析为 CDN 直链
- `NetMusicSoundMixin` 拦截 `getStream()`，用 jaad 解码 MP4/AAC
- `ChunkedAudioStreamMixin` 给 B 站 CDN 请求加 Referer 头
- mod 自带 relocated jaad 库（1.1.8 的 NetMusic jar 不含 AAC 解码器）

**1.5.0 ~ 1.5.1（API 路径）**：
- 注册 `BiliSongUrlResolverV15`（`IAsyncSongUrlResolver`）做 BV 号解析
- 注册 `BiliAudioStreamHandlerV15`（`IAudioStreamHandler`）做音频流解码
- Mixin 通过 `hasStreamHandlerManager()` / `hasResolverManager()` 守卫自动跳过

## 用法

### 1. 安装

将本 mod 和 NetMusic 同时放入 `mods` 文件夹。无需额外安装 jaad 库——本 mod 已自带。

### 2. 设置 Cookie（可选但推荐）

- **无 cookie**：走匿名 `try_look=1`，多数视频可播（低清音轨）
- **有 cookie**：登录态，可播 VIP / 高码率内容，自动刷新 `bili_ticket`

把 cookie 头字符串写入：

```
<游戏目录>/config/bilibili_audio/bili_cookie.txt
```

也可在游戏内 **Mods 菜单 -> Bilibili Audio -> Config** 界面粘贴 cookie。

### 3. 制作 B 站唱片

**CD 刻录机**：在 NetMusic 的 CD 刻录机界面输入 BV 号或视频链接，点击刻录。

**命令**：

```
/bilicd <BV1xxxx | https://www.bilibili.com/video/BV1xxxx>
```

权限等级 2（OP）。成功后获得一张以视频标题命名的音乐 CD。

### 4. 播放

把 CD 放入唱片机（右键 / 红石触发），即可播放。

## 构建

```bash
./gradlew build
```

产物：`build/libs/bilibili_audio-0.1.0-forge+mc1.20.1-all.jar`（含 relocated jaad）。

> NetMusic 仅作为编译期依赖，**不打包**进本 mod。运行时需同时安装 NetMusic。

## 代码结构

```
com.github.wsure.bilibiliaudio/
├── api/              # B 站 HTTP 客户端、WBI 签名、视频信息
├── config/           # 常量、日志、cookie 路径
├── command/          # /bilicd 命令
├── client/
│   ├── BiliStreamCore.java          # jaad 解码核心（两版本共用）
│   ├── BiliAudioStreamAdapter.java  # AudioStream 适配器（1.1.8 路径用）
│   └── BiliLoginScreen.java         # 配置页
├── resolver/
│   └── BiliResolveCore.java         # BV 号解析核心（两版本共用）
├── compat/
│   └── NetMusicCompat.java          # 版本检测 + 反射注册
├── mixin/
│   └── CDBurnerMenuScreenMixin.java # CD 刻录机拦截（两版本共用）
├── adapter/
│   ├── v118/                        # 1.1.8~1.3.1 专属 Mixin
│   │   ├── MusicPlayManagerMixin.java
│   │   ├── NetMusicSoundMixin.java
│   │   └── ChunkedAudioStreamMixin.java
│   └── v15/                         # 1.5.0~1.5.1 接口实现
│       ├── BiliAudioStreamHandlerV15.java
│       └── BiliSongUrlResolverV15.java
└── BilibiliAudioMod.java            # 入口：按版本检测走不同注册路径
```

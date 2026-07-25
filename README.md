# Bilibili Audio Player

在 [NetMusic](https://github.com/TartaricAcid/NetMusic) 的基础上，让音乐 CD 支持播放 **B 站 BV 号**音频。

**不新增任何物品**，完全复用 NetMusic 的音乐 CD / 唱片机方块，仅通过 NetMusic 的两个扩展点接入：

| 扩展点 | 侧 | 本 mod 实现 | 作用 |
| --- | --- | --- | --- |
| `IAsyncSongUrlResolver` | 服务端 | `BiliSongUrlResolver` | 把 CD 里的 `bilibili://BVxxxx` 标识异步解析为音频直链 |
| `IAudioStreamHandler` | 客户端 | `BiliAudioStreamHandler` | 带 Referer 拉取 B 站 CDN 直链并解码（fragmented MP4 / AAC） |

## 工作流程

1. `/bilicd <BV 号或视频链接>` —— 服务端异步获取视频信息，生成一张 `songUrl = bilibili://BVxxxx` 的音乐 CD（复用 NetMusic `music_cd`）。
2. 把 CD 放入唱片机（右键 / 红石触发），NetMusic 调用 `MusicPlayResolverManager.resolve()`。
3. `BiliSongUrlResolver` 命中 `bilibili://` 标识，调用 B 站 `view` + `wbi/playurl`（fnval=4048）拿到 AAC 音频直链，写回 `SongInfo.songUrl`。
4. 服务端把直链通过网络包下发客户端；客户端 `AudioStreamHandlerManager` 选中 `BiliAudioStreamHandler`（带 Referer 绕防盗链）解码播放。

## Cookie（可选但推荐）

- **无 cookie**：走匿名 `try_look=1`，多数视频可播（低清音轨）。
- **有 cookie**：登录态，可播 VIP / 高码率内容，并自动刷新 `bili_ticket`。

放置 cookie：把 PoC 产出的 `.bili_cookie`（cookie 头字符串）复制到：

```
<游戏目录>/config/bilibili_audio/bili_cookie.txt
```

例如 dev 运行时为 `BilibiliAudioMod/run/config/bilibili_audio/bili_cookie.txt`。文件权限建议 `0600`。

> in-mod 扫码登录（`/bililogin`）暂未实现，目前沿用 PoC 的 cookie 文件。

## 构建

```bash
./gradlew build
```

产物：`build/libs/bilibili_audio-0.1.0-forge+mc1.20.1.jar`。

> NetMusic 仅作为编译期依赖（`libs/netmusic-1.5.1-forge+mc1.20.1.jar`），**不打包**进本 mod。运行时需同时安装 NetMusic（其 shadow jar 自带 relocated `javasound-aac`，提供 `.m4s` 解码 SPI）。

## dev 运行

```bash
./gradlew runClient
```

dev 环境下，`build.gradle` 通过 `minecraftLibrary` 额外引入 `javasound-aac`，以便本地测试 B 站音频解码。

## 命令

```
/bilicd <BV1xxxx | https://www.bilibili.com/video/BV1xxxx>
```

权限等级 2（OP）。成功后获得一张以视频标题命名的音乐 CD。

## 关键文件

- `api/BiliClient.java` —— B 站 HTTP 客户端（cookie / WBI 签名 / bili_ticket 刷新 / view / playurl），移植自 `bili_audio_poc.py`
- `api/WbiSigner.java` —— WBI 签名（mixin key 推导 + MD5）
- `resolver/BiliSongUrlResolver.java` —— 服务端解析器
- `client/BiliAudioStreamHandler.java` —— 客户端音频流处理器
- `command/ModCommands.java` —— `/bilicd` 命令

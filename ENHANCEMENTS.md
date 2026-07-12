# mpvKt 增强说明（可配置多线程 / 缓存时间）

基于 [abdallahmehiz/mpvKt](https://github.com/abdallahmehiz/mpvKt) 二次开发，保留原有 **本地媒体 + 网络串流** 能力，并增加可在设置中直接调节的 **缓存时间 / 缓存容量 / 解码线程数**。

## 功能概览

| 能力 | 说明 |
|------|------|
| 本地媒体 | 文件选择器 / 目录浏览 / content URI |
| 网络串流 | 首页 URL 输入，支持 http(s)、rtmp、hls 等 mpv 协议 |
| 前向/后向 demuxer 缓存 | 8–512 MiB 可调 |
| 缓存预读时间 | `demuxer-readahead-secs`，0–120 秒 |
| 最大缓存时长 | `cache-secs`，1–300 秒 |
| 视频/音频解码线程 | `vd-lavc-threads` / `ad-lavc-threads`，0=自动 |
| 解复用独立线程 | `demuxer-thread` |
| 串流优化 | 断线重连、强制可 seek 等 |

## 设置入口

**设置 → 网络与缓存（Network & cache）**

对应实现：

- `preferences/NetworkPreferences.kt` — 偏好存储
- `ui/preferences/NetworkPreferencesScreen.kt` — Compose 设置页
- `ui/player/MPVView.kt` → `setupNetworkAndCacheOptions()` — 写入 libmpv

## 映射到 mpv 选项

| UI 项 | mpv 选项 | 默认 |
|-------|----------|------|
| 前向 demuxer 缓存 | `demuxer-max-bytes` | 64 MiB |
| 后向 demuxer 缓存 | `demuxer-max-back-bytes` | 64 MiB |
| 缓存预读时间 | `demuxer-readahead-secs` | 10 s |
| 最大缓存时长 | `cache-secs` | 50 s |
| 等待初始缓存 | `cache-pause-initial` | 开 |
| 卡顿后恢复等待 | `cache-pause-wait` | 1 s |
| 视频解码线程 | `vd-lavc-threads` | 自动(0) |
| 音频解码线程 | `ad-lavc-threads` | 自动(0) |
| 解复用线程 | `demuxer-thread` | 开 |
| 网络超时 | `network-timeout` | 60 s |
| 流缓冲 | `stream-buffer-size` | 128 KiB |
| TLS 校验 | `tls-verify` | 开 |
| 串流优化 | `force-seekable` / `hr-seek`（可选） | 关 |
| 长暂停续播 | 保守 `stream-lavf-o` reconnect + 暂停≥30s 后 unpause 时 seek 重开流 | 默认开 |

更细的 mpv 参数仍可通过 **高级 → mpv.conf** 覆盖。

## 分片多连接加载（Segmented multi-connection）

实现：`network/SegmentedHttpCache.kt` + `PlayerActivity` 播放入口。

```
1) Range 探测
2) 同步下载片头 ~1MB（保证开播）
3) N 条连接并行 Range 拉剩余分片 → 临时稀疏缓存文件
4) 本地 http://127.0.0.1:port 代理给 mpv 边下边播
失败 → 自动回退原始 URL 直连
退出播放器 / 切换媒体 / 播完 → 删除 segmented-http 缓存与数据（不跨次复用）
```

设置：**网络与缓存 → 分片多连接加载**（默认开，v3 key）

| 适用 | 不适用 |
|------|--------|
| http(s) 整文件 mp4/mkv/webm 且服务器支持 Range | HLS/m3u8、DASH、不支持 Range 的 CDN |

推荐：并行连接 **6–12**，分片 **512–1024 KiB**。

## 推荐调参

- **弱网 / 易卡顿**：开分片多连接；前向缓存 128–256 MiB，预读 20–60 s  
- **内存紧张设备**：前向/后向缓存 16–32 MiB，预读 5–10 s；连接数 4  
- **高清本地文件**：线程可设为 CPU 核数（如 4–8），缓存可保持默认  
- **自签名 HTTPS 源**：关闭 TLS 校验（有安全风险）  
- **无法播放时**：关闭「分片多连接加载」即可恢复直连

## 构建

```bash
cd mpvKt
./gradlew :app:assembleDebug
# 或
./gradlew :app:assembleRelease
./gradlew :app:assemblePreview   # debug 签名，可直接安装
```

依赖：`io.github.abdallahmehiz:mpv-android-lib`（libmpv）、Jetpack Compose、Koin、Room。

## GitHub Actions → Release

工作流文件：`.github/workflows/release.yml`

### 触发方式

1. **打 tag 并推送**（推荐）
   ```bash
   git tag v0.1.7-enhanced
   git push origin v0.1.7-enhanced
   ```
2. **Actions 手动运行**  
   GitHub → Actions → **Release** → Run workflow，填写 version（如 `v0.1.7-enhanced`）

### 产物

- 各 ABI APK：`universal` / `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`
- 源码包：`mpvKt-source-<tag>.tar.gz`
- `CHECKSUMS.md`（SHA-256）
- 自动创建 GitHub Release 并挂上以上文件

### 签名 Secrets（可选，正式发布用）

在仓库 Settings → Secrets and variables → Actions 配置：

| Secret | 说明 |
|--------|------|
| `SIGNING_KEYSTORE` | keystore 文件的 base64（`base64 -w0 release.jks`） |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `KEY_PASSWORD` | key 密码 |

未配置时仍会发布 **debug 签名** 的 preview APK（可安装测试；`applicationId` 带 `.preview` 后缀）。

## 相对上游的改动文件

1. `NetworkPreferences.kt`（新建）
2. `NetworkPreferencesScreen.kt`（新建）
3. `PreferencesModule.kt` — 注册 NetworkPreferences
4. `PreferencesScreen.kt` — 菜单入口
5. `MPVView.kt` — 应用可配置缓存/线程
6. `values/strings.xml` / `values-zh-rCN/strings.xml` — 文案
7. 本文件 `ENHANCEMENTS.md`

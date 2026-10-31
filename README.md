# 如戏 · Ruxi

> 人生如戏，全靠设定。

一个 Android 角色扮演（RP）应用，Kotlin + Jetpack Compose 开发。

## 功能

### 角色卡（每个角色独立提示词）
每张角色卡包含：
- **系统设定** —— 世界观、性格、说话方式，**每轮注入**
- **用户设定** —— 你在这个故事里的身份、与角色的关系
- **参考图片** —— 可逐张加备注（如「角色一」「场景参考」），**仅首轮注入**
- **开场白** —— 新对话时自动作为第一句

### 供应商
- 支持配置多个供应商，随时切换
- 两种协议：OpenAI 兼容（`/chat/completions`）/ Anthropic（`/v1/messages`）
- **API Key 轮换池**：一行一个 key，遇到限流自动切换
- 一键测试连接，错误信息翻译成中文（401/404/429 分别给不同提示）
- 支持自定义额外请求头（部分中转站需要）

## 技术要点

### 流式请求的连接隔离
流式请求走**独立的 OkHttpClient 实例**且带 `Connection: close`。
原因是踩过的坑：一次流超时后坏连接留在连接池里，被后续所有请求复用，
表现为「一个请求超时之后全部卡死，重启才恢复」。连接用完即弃即可根除。

### 图片必须先落盘、且必须压缩
相册选图返回的 SAF Uri 读权限是**随 Intent 临时授予**的，进程重启后失效。
所以选图后立刻复制到应用私有目录，之后只认文件路径 ——
否则「杀进程重进，历史消息里的图全变空白」，且这个 bug 只在重启后复现。

**压缩是硬需求**：图片会以 base64 塞进请求体（还要再膨胀 33%）。
实测相册原图 3MB 上传要 130 秒，首字延迟被拖到几十秒，模型根本还没开始推理。
现在导入时统一缩到长边 1280 + JPEG 85，典型 3MB → 200KB。

**读 Uri 只能读一次**：部分相册/文件管理器的 provider 只保证单次可读。
先读尺寸、再读像素的话第二次 `openInputStream` 直接失败，表现为「选图没反应」。
所以一次性 `readBytes()` 进内存，再从字节数组解码两次。

**读不到的图必须跳过，不能发空串**：空 base64 会生成 `"url": ""`，
上游以 `param: image_url / invalid_request_error` 整条拒绝 ——
一张坏图能让整个会话再也发不出去。

### 消息版本切换
每条消息带 `groupId` / `versionIndex`，会话记 `activeVersion: Map<groupId, messageId>`。
编辑和重新生成都是**追加新版本**（不覆盖旧的），气泡旁 `‹ 1/2 ›` 左右切换，无上限。

重新生成按**位置**截断：找到目标消息前最近的一条用户消息，只保留它之前的内容，
用那条用户消息重建请求 —— 不能按 id 找，多版本混排时会取错。

### 失败自动重试
超时、429、5xx、连接重置会退避重试（2s / 4s / 8s，最多 3 次）。
认证失败、参数错误、额度用尽不重试 —— 重试多少次结果都一样。
每次重试前清空已收到的内容：整轮重来，拼半截输出会得到重复前缀。

### 请求组装的顺序
**先组装请求、再把用户消息落盘**。反过来的话，PromptEngine 会把刚追加的用户消息
当成「历史消息」收进上下文，末尾又追加一次本轮输入 → 用户的话发了两遍。

## 构建

本地不编译（Termux 上 Compose 编译会卡死），全部走 GitHub Actions：

```bash
git push   # 自动触发 .github/workflows/build.yml
```

产物在 Release 页：`build-<run_number>`，下载加速：

```bash
curl -L -o ruxi.apk \
  "https://gh-proxy.com/https://github.com/wzy-20130517/ruxi/releases/download/build-N/RUXI-N.apk"
```

APK 用仓库内固定 keystore 签名（`keystore/ruxi-release.jks`），
保证每次覆盖安装不会因签名不一致被拦。

## 目录结构

```
app/src/main/java/com/ruxi/app/
├── data/
│   ├── model/      数据模型（角色卡/供应商/消息/设置）
│   ├── store/      持久化（SharedPreferences + JSON）
│   ├── api/        网络层（SSE 流式 + 双协议适配）
│   └── prompt/     提示词组装
└── ui/
    ├── theme/      配色与排版
    ├── components/ 通用组件
    ├── home/       角色卡列表
    ├── character/  角色卡编辑
    ├── chat/       对话
    └── settings/   供应商与全局设置
```

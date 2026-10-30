# 如戏 · Ruxi

> 人生如戏，全靠设定。

一个 Android 角色扮演（RP）应用，Kotlin + Jetpack Compose 开发。

## 功能

### 角色卡（每个角色独立提示词）
每张角色卡包含：
- **系统设定** —— 世界观、性格、说话方式，**每轮注入**
- **用户设定** —— 你在这个故事里的身份、与角色的关系
- **输出规范 / 隐藏要求** —— 高级项，留空则用全局模板
- **参考图片** —— 可逐张加备注（如「角色一」「场景参考」），**仅首轮注入**
- **开场白** —— 新对话时自动作为第一句

### 隐藏要求
角色卡可以附加一段「输出规范」，在系统提示词末尾注入（模型对末尾内容注意力更强）。
每张角色卡独立填写；留空则用全局模板，全局也留空则不附加。

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

### 图片必须先落盘
相册选图返回的 SAF Uri 读权限是**随 Intent 临时授予**的，进程重启后失效。
所以选图后立刻复制到应用私有目录，之后只认文件路径 ——
否则「杀进程重进，历史消息里的图全变空白」，且这个 bug 只在重启后复现。

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
│   └── prompt/     提示词引擎（分隔/还原/组装）
└── ui/
    ├── theme/      配色与排版
    ├── components/ 通用组件
    ├── home/       角色卡列表
    ├── character/  角色卡编辑
    ├── chat/       对话
    └── settings/   供应商与全局设置
```

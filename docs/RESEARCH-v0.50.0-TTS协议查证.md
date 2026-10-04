# RESEARCH · v0.50.0 TTS 三家协议查证（施工前必读）

> 目的：`data/TtsClient.kt` / `data/VoiceCloner.kt` 落地前，把 **硅基流动 / 小米 MiMo / 通用 OpenAI 兼容** 三条 TTS 链路的 HTTP 协议钉死到字段级。
> **本项目无任何一家的 API Key，全部结论来自文档 + 开源实现 + 反编译参考 App，没有一次真实请求验证过。**
>
> 证据分级（每条结论都标了）：
> - 🟢 **[官方文档]** = 厂商自己的文档/API 参考，可直接照抄。
> - 🔵 **[开源实现]** = 高星或完整开源仓库的真实代码，旁证。
> - 🟡 **[反编译]** = 爱语 2.9.5 dex/资源串，只能证明「有人这么做且能上线」，不等于协议正确。
> - 🔴 **[未确认]** = 文档没写、只能推测。**§5 有汇总表，写代码前先看那节。**
>
> 查证日期：2026-10-04。文档页脚标注的最后更新时间一并记录（厂商改协议不通知）。

---

## 目录

1. [硅基流动 SiliconFlow（CosyVoice2 + 音色克隆）](#1-硅基流动-siliconflow)
2. [小米 MiMo（chat/completions 音频模态）](#2-小米-mimo)
3. [通用 OpenAI 兼容 `/v1/audio/speech` 基线](#3-通用-openai-兼容-v1audiospeech-基线)
4. [开源参考实现（含逐字代码）](#4-开源参考实现)
5. [「未能确认」汇总（写代码前必读）](#5-未能确认汇总)
6. [附：反编译参考 App（爱语 2.9.5）证据清单](#6-附反编译参考-app爱语-295证据清单)

---

<a id="1-硅基流动-siliconflow"></a>
## 1. 硅基流动 SiliconFlow

### 1.0 一句话结论

`POST /v1/audio/speech` 收 JSON、**回裸二进制音频**（不是 JSON、不是 base64）。
音色克隆是**两步**：`POST /v1/uploads/audio/voice`（multipart 或 base64 JSON）拿到 `uri` → 把 `uri` 整串塞进后续请求的 `voice` 字段。
**没有 `purpose` 字段、没有 `/v1/uploads`（无 `/audio/voice` 后缀）这种 OpenAI Files 式接口** —— 任务描述里那个猜测是错的。

### 1.1 合成接口 `/v1/audio/speech`

| 项 | 值 | 证据 |
|---|---|---|
| Method | `POST` | 🟢 |
| URL | `https://api.siliconflow.cn/v1/audio/speech` | 🟢 |
| Header | `Authorization: Bearer <API Key>`（**必填**） | 🟢 |
| Header | `Content-Type: application/json`（**必填**） | 🟢 |
| Header | `X-Trace-Id: <自定义串>`（可选，排障用；原样回显在响应头 `x-siliconcloud-trace-id`） | 🟢 |
| Header | `traceparent`（可选，W3C Trace Context） | 🟢 |

**请求体字段（CosyVoice2-0.5B 分支，官方 API 参考逐字）**

| 字段 | 类型 | 必填 | 允许值 / 默认 | 说明 |
|---|---|---|---|---|
| `model` | string | **是** | `"FunAudioLLM/CosyVoice2-0.5B"`（另有 `"fnlp/MOSS-TTSD-v0.5"`） | 官方 OpenAPI 的 enum 只列了这两个，但文档同时说「支持 fishaudio / GPT-SoVITS / CosyVoice2-0.5B 系列模型」，且第三方实现用 `IndexTeam/IndexTTS-2` 也能跑 → **enum 不是穷举，model 必须做成用户可填** 🟢+🔵 |
| `input` | string | **是** | `1 <= length <= 128000` | 待合成文本。官方注意：**「输入内容不要加空格」**。自然语言指令（情感/语速/角色扮演/方言）写在文本前并加结束标记 `<\|endofprompt\|>`；细节标签 `[laughter]` `[breath]` 可插在文本中 🟢 |
| `voice` | string | 否（但实际要给） | 见 §1.2 | 三种形态：系统预置 `"<model>:<alias>"`、用户克隆 `uri` 整串、动态音色传 `""`。官方注明：**`voice` 与 `references` 互斥** 🟢 |
| `response_format` | string | 否 | `"mp3"` \| `"opus"` \| `"wav"` \| `"pcm"`，**默认 `"mp3"`** | 🟢 |
| `sample_rate` | number | 否 | 见下，**默认 44100** | `opus` 只支持 48000；`wav`/`pcm` 支持 8000/16000/24000/32000/44100；`mp3` 支持 32000/44100。⚠️ 官方 API 参考在这一栏的 `Default` 印的是 `32000`，与正文「默认 44100」矛盾 —— 见 §5 🔴 🟢 |
| `speed` | number(float) | 否 | `0.25 <= v <= 4.0`，**默认 1.0** | 🟢 |
| `gain` | number(float) | 否 | `-10 <= v <= 10`，**默认 0.0** | 单位 dB，音量增益。**OpenAI 没有这个字段**，是硅基私有 🟢 |
| `stream` | boolean | 否 | `false` \| `true` | 「是否使用流式输出」。**流式 = chunked 裸二进制，不是 SSE**（官方示例 `stream:True` 后直接 `res.content` 写 `.mp3`）🟢 |
| `references` | array&lt;object&gt; | 否 | `[{ "audio": <url 或 base64>, "text": <参考音频文字> }]` | **动态音色克隆**（不预先上传）。用它时 `voice` 传 `""`。参考音频 **要小于 30s** 🟢 |
| `max_tokens` | integer | 否 | 输入+输出不超过 32k token | 文档只在 MOSS-TTSD 章节列出，CosyVoice2 分支未列 🟢 |

> ❗ **没有** `instructions`、**没有** `metadata`、**没有** `stream_format`、**没有** `voice` 对象形态（`{"id":...}`）。这些是 OpenAI 的，别往硅基请求里塞。

**响应**

| 项 | 值 | 证据 |
|---|---|---|
| 200 body | **裸二进制音频字节**（`file` / `Format: binary`）。官方原文：「接口生成的数据为音频二进制数据，需要用户自行处理」 | 🟢 |
| 200 Content-Type | 文档只说「随 `response_format` 变化」，**没给逐字值**。第三方代理按 `audio/mpeg` 兜底 | 🔴 见 §5 |
| 200 响应头 | `x-siliconcloud-trace-id`（追踪 ID） | 🟢 |
| 计费 | 按 `input` 文本的 **UTF-8 字节数** 计费 | 🟢 |

**错误响应体（官方 API 参考逐字）**

| HTTP | Body |
|---|---|
| 400 | `{"code": 20012, "message": "string", "data": "string"}` |
| 401 | `"Invalid token"`（**裸字符串，不是 JSON 对象**） |
| 403 | `"Forbidden"`（裸字符串） |
| 404 | `"404 page not found"`（裸字符串） |
| 429 | `{"message": "Request was rejected due to rate limiting. If you want more, please contact contact@siliconflow.cn. Details:TPM limit reached.", "data": "string"}` |
| 503 | `{"code": 50505, "message": "Model service overloaded. Please try again later.", "data": "string"}` |
| 504 | 文档列了该状态码，**未给 body 形状** 🔴 |

> ⚠️ **实现要点**：错误体**有时是 JSON、有时是裸字符串**（401/403/404）。所以取错误信息时必须
> 「先试 JSON 取 `message`，失败就把整个 body 当纯文本」，不能只按 JSON 解析。
> ⚠️ **成功/失败判据不能只看 Content-Type**：200 是二进制、非 200 可能是 JSON 也可能是纯文本。
> 稳妥做法 = `if (!response.isSuccessful) → 读文本报错；else → 读 bytes`，再对 bytes 做一次
> 「开头是 `{` 就当 JSON 错误解析」的兜底嗅探。

**逐字示例请求（官方 API 参考的 cURL）**

```bash
curl --request POST \
  --url https://api.siliconflow.cn/v1/audio/speech \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "fnlp/MOSS-TTSD-v0.5",
    "input": "你站在桥上看风景，看风景的人在楼上看你。明月装饰了你的窗子，你装饰了别人的梦",
    "voice": "fnlp/MOSS-TTSD-v0.5:alex",
    "response_format": "mp3"
  }' \
  --output output.mp3
```

**本项目实际会发的请求体（CosyVoice2 + 系统预置音色）**

```json
{
  "model": "FunAudioLLM/CosyVoice2-0.5B",
  "input": "今天过得怎么样？",
  "voice": "FunAudioLLM/CosyVoice2-0.5B:anna",
  "response_format": "mp3",
  "sample_rate": 44100,
  "speed": 1.0,
  "gain": 0.0,
  "stream": false
}
```

**响应** = HTTP 200，body 直接是 MP3 字节流（`FF FB ...` 或 `ID3` 开头），无 JSON 包裹。

### 1.2 `voice` 字段三种形态（🟢 全部官方确认）

| 形态 | `voice` 取值 | 备注 |
|---|---|---|
| ① 系统预置 | `"FunAudioLLM/CosyVoice2-0.5B:alex"` | **必须带模型名前缀**，官方原文：「在使用对应的系统预置音色时，需要在前面加上模型名称」 |
| ② 用户克隆（预置音色） | `"speech:your-voice-name:cm02pf7az00061413w7kz5qxs:mttkgbyuunvtybnsvbxd"` | 就是上传接口返回的 `uri` **整串原样**塞进去，格式 `speech:<customName>:<accountId>:<voiceId>` |
| ③ 动态音色 | `""`（空字符串） | 配合 `references: [{audio, text}]` 使用，一次请求内即用即弃，不落库 |

**CosyVoice2-0.5B 系统预置音色（8 个，官方逐字）**

| 性别 | 描述 | voice alias | 完整 `voice` 值 |
|---|---|---|---|
| 男 | 沉稳男声 | `alex` | `FunAudioLLM/CosyVoice2-0.5B:alex` |
| 男 | 低沉男声 | `benjamin` | `FunAudioLLM/CosyVoice2-0.5B:benjamin` |
| 男 | 磁性男声 | `charles` | `FunAudioLLM/CosyVoice2-0.5B:charles` |
| 男 | 欢快男声 | `david` | `FunAudioLLM/CosyVoice2-0.5B:david` |
| 女 | 沉稳女声 | `anna` | `FunAudioLLM/CosyVoice2-0.5B:anna` |
| 女 | 激情女声 | `bella` | `FunAudioLLM/CosyVoice2-0.5B:bella` |
| 女 | 温柔女声 | `claire` | `FunAudioLLM/CosyVoice2-0.5B:claire` |
| 女 | 欢快女声 | `diana` | `FunAudioLLM/CosyVoice2-0.5B:diana` |

> 官方原文「目前系统预置了如下 **8** 种音色」。
> 🟡 反编译的爱语有一条「预置音色 — **9** 个内置音色」，但该串**未带厂商名**，无法判定说的是硅基还是 MiMo
> （MiMo 官方恰好 9 个预置音色）。若它指的是硅基，多的那 1 个大概率是它自加的「自定义/克隆」占位项。
> **以官方「8 种」为准**，别按 9 实现。
> 🔵 开源 `yythl/siliconflow_tts` 的 `Config.VOICES` 恰好就是这 8 个，逐字对上。
> **没有中文别名**（不是「安娜」「贝拉」），alias 就是英文小写。

### 1.3 音色克隆 · 第 1 步：上传参考音频

| 项 | 值 | 证据 |
|---|---|---|
| Method | `POST` | 🟢 |
| URL | `https://api.siliconflow.cn/v1/uploads/audio/voice` | 🟢 |
| Header | `Authorization: Bearer <API Key>` | 🟢 |
| 前置条件 | **⚠️ 使用用户预置音色需要「实名认证」**（官方原文：「注意：使用用户预置音色，需要进行实名认证。」） | 🟢 |

**官方给了两种互斥的上传方式（同一个 URL）：**

**方式 A — base64（`Content-Type: application/json`）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | string | **是** | 预定义音色风格模型名称，例 `"FunAudioLLM/CosyVoice2-0.5B"` |
| `customName` | string | **是** | 用户自定义音色风格名称，例 `"your-voice-name"` |
| `text` | string | **是** | **音频对应的文本内容**（参考音频的逐字稿） |
| `audio` | string | **是** | base64 音频，**头部格式必须是 `data:audio/mpeg;base64`**，例 `"data:audio/mpeg;base64,aGVsbG93b3JsZA=="` |

```json
{
  "model": "FunAudioLLM/CosyVoice2-0.5B",
  "customName": "your-voice-name",
  "audio": "data:audio/mpeg;base64,SUQzBAAAAAAAIlRTU0UAAAAOAAAD...",
  "text": "在一无所知中, 梦里的一天结束了，一个新的轮回便会开始"
}
```

**方式 B — multipart 文件上传（`Content-Type: multipart/form-data`）**

| part | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | file(binary) | **是** | 要上传的音频文件 |
| `model` | form field | **是** | 同上 |
| `customName` | form field | **是** | 同上 |
| `text` | form field | **是** | 同上 |

官方 cURL 逐字：

```bash
curl -X POST "https://api.siliconflow.cn/v1/uploads/audio/voice" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -F "file=@test.mp3" \
  -F "model=IndexTeam/IndexTTS-2" \
  -F "customName=your-voice-name" \
  -F "text=慢工出细活，再给我两分钟，你马上就能见识到超梦分析的厉害了"
```

**上传响应（🟢 逐字）**

```json
{ "uri": "speech:your-voice-name:xxx:xxx" }
```

真实样例（用户指南页）：`{'uri': 'speech:your-voice-name:cm04pf7az00061413w7kz5qxs:mjtkgbyuunvtybnsvbxd'}`
错误体形状同 §1.1（400 = `{"code":20012,...}`，429 / 503 同款）。

> **⚠️ 关键坑：`text` 是必填，且必须是参考音频的逐字稿。**
> 这意味着 App 的克隆流程**不能只让用户选一个 MP3** —— 必须同时拿到「这段音频说了什么」。
> 三条可行路线（按代价排序，见 §5 决策项）：
> 1. 让用户手填一句转写文本（最省事，体验差）；
> 2. **用本项目已有的端侧 SenseVoice ASR 自动转写**（推荐：v0.50.0 步骤 1/2 正好在做离线识别，克隆前跑一次即可，零额外依赖）；
> 3. 传空串 `""` 赌服务端容忍 —— 🔴 未验证，有第三方脚本这么做（见 §4.3），但官方标 required，**不要赌**。

**参考音频要求（🟢 官方「参考音频的最佳实践」逐字）**

- 时长：**要小于 30s**；**建议 8~10s 左右**
- 格式：**支持 mp3, wav, pcm, opus**；推荐 **192kbps 以上的 mp3**；「未压缩格式（例如 WAV）提供的额外优势有限」
- 质量：仅限**单一说话人**；吐字清晰、音量/音调/情绪稳定；短暂停顿（建议 0.5 秒）；理想为无背景噪音、专业录音质量、无房间回声
- **体积上限：官方文档没写** 🔴（`10MB` 那个数字是 MiMo 的，别混用 —— 见 §2.4）

### 1.4 音色克隆 · 第 2 步：列出 / 使用 / 删除

**列出（🟢）**

| 项 | 值 |
|---|---|
| Method / URL | `GET https://api.siliconflow.cn/v1/audio/voice/list` |
| Header | `Authorization: Bearer <API Key>`（无 body） |

响应逐字：

```json
{
  "results": [
    {
      "model": "fishaudio/fish-speech-1.4",
      "customName": "your-voice-name",
      "text": "在一无所知中, 梦里的一天结束了，一个新的轮回便会开始",
      "uri": "speech:your-voice-name:xxx:xxx"
    }
  ]
}
```

> ⚠️ **数组键名有三份互相矛盾的证词**：官方文档/API 参考 = **`results`**；
> 第三方 skill 笔记 = `result`；第三方 Python 客户端同时兜 `result` / `data`。
> **实现必须三个键都试**（`results` → `result` → `data`），见 §5。

🔵 第三方实现给这个 GET 额外带了 `?model=<model>` 查询参数 —— **官方文档未记载**，属可选。🔴

**使用（🟢）**：把 `uri` 整串当 `voice` 值发给 `/v1/audio/speech`，见 §1.2 ②。

**删除（🟢）**

| 项 | 值 |
|---|---|
| Method / URL | `POST https://api.siliconflow.cn/v1/audio/voice/deletions` |
| Header | `Authorization: Bearer <API Key>` + `Content-Type: application/json` |
| Body | `{"uri": "speech:your-voice-name:xxx:xxxx"}` |
| 200 响应 | 一个 **string**（官方 API 参考标的返回类型就是 `response: string`，示例位是 `"string"`，未给具体值） 🔴 |

> ⚠️ 第三方 skill 笔记把删除端点写成 `POST /v1/uploads/audio/voice/deletions`，**与官方文档不符**。以官方 `/v1/audio/voice/deletions` 为准；若要更稳，可在 404 时回退试另一个路径（🔴 未验证）。

### 1.5 动态音色（不上传、一次一用）

🟢 官方示例 5.3 逐字（Python SDK 形态，`extra_body` 就是平铺进 JSON body）：

```python
client.audio.speech.with_streaming_response.create(
  model="FunAudioLLM/CosyVoice2-0.5B",
  voice="",                                   # 空值 = 使用动态音色
  input="  [laughter]有时候，看着小孩子们的天真行为[laughter]，我们总会会心一笑。",
  response_format="mp3",
  extra_body={"references": [
      {"audio": "https://sf-maas-uat-prod.oss-cn-shanghai.aliyuncs.com/voice_template/fish_audio-Alex.mp3",
       "text": "在一无所知中, 梦里的一天结束了，一个新的轮回便会开始"}
  ]}
)
```

等价 JSON body：

```json
{
  "model": "FunAudioLLM/CosyVoice2-0.5B",
  "input": "[laughter]有时候，看着小孩子们的天真行为[laughter]，我们总会会心一笑。",
  "voice": "",
  "response_format": "mp3",
  "references": [
    { "audio": "https://.../fish_audio-Alex.mp3", "text": "在一无所知中, 梦里的一天结束了，一个新的轮回便会开始" }
  ]
}
```

- `references[].audio`：**URL 或 base64 都支持**（官方注释：「参考音频 url。也支持 base64 格式」）
- 参考音频 **< 30s**
- **`voice` 与 `references` 互斥**（官方原文）
- 同样**需要实名认证**（「使用用户预置音色，需要进行实名认证」写在 §2.4 使用动态音色章节下）

> **对本项目的意义**：这条路径**不需要上传/管理音色**，可以把用户选的那段 MP3 直接 base64 塞进
> `references`，每次合成都带上。代价是每次请求多传一份 base64 音频（8~10s mp3 ≈ 200KB → base64 ≈ 270KB）。
> 对「每个人设一个专属声音」这种低频场景，**上传换 `uri` 更省流量**；但若实名认证卡住用户，动态音色是唯一无需认证的退路？—— 🔴 **未确认**：文档只说「用户预置音色」和「用户动态音色」都要实名，没说不认证能不能用动态音色。**按「两个都要认证」实现更安全。**

### 1.6 文档出处（逐条可点）

- 用户指南 · 语音合成（含预置音色表 / 上传 base64 / 上传文件 / list / deletions / 动态音色 / 参考音频最佳实践 / 5 个示例）
  - `https://docs.siliconflow.cn/cn/userguide/capabilities/text-to-speech`
  - 镜像：`https://docs.siliconflow.com/cn/userguide/capabilities/text-to-speech`
  - 旧域名：`https://api-docs.siliconflow.cn/docs/userguide/capabilities/text-to-speech`
- API 参考 · 创建文本转语音请求（字段类型/enum/range/default + 错误体 + cURL）
  - `https://api-docs.siliconflow.cn/docs/api/audio-speech-post`
  - 镜像：`https://docs.siliconflow.com/cn/api-reference/audio/create-speech`
- API 参考 · 上传参考音频 `https://api-docs.siliconflow.cn/docs/api/uploads-audio-voice-post`
- API 参考 · 获取参考音频列表 `https://api-docs.siliconflow.cn/docs/api/audio-voice-list-get`
- API 参考 · 删除参考音频 `https://api-docs.siliconflow.cn/docs/api/audio-voice-deletions-post`
- API Key 获取页 `https://cloud.siliconflow.cn/account/ak`

---

<a id="2-小米-mimo"></a>
## 2. 小米 MiMo

### 2.0 一句话结论 —— **你的假设成立，但有一处要改**

✅ **成立**：MiMo TTS 走 **`POST /v1/chat/completions`**，音频在 **`choices[0].message.audio.data`** 的 **base64** 里。
❌ **要改**：**不需要、也没有 `modalities` 字段**。MiMo 的请求体里**只有** `model` / `messages` / `audio` / `stream`（voicedesign 另有 `audio.optimize_text_preview`）。
官方 OpenAI 兼容参考页里 `modalities` 出现次数 = **0**。三个独立开源实现也都没传。
👉 **别照抄 OpenAI `gpt-4o-audio-preview` 的 `modalities: ["text","audio"]`** —— 那是 OpenAI 的要求，MiMo 文档里没有；多传大概率被忽略，但属于未验证行为。

另一个**极易踩的坑**：官方所有 cURL 用的鉴权头是 **`api-key: $MIMO_API_KEY`**，不是 `Authorization: Bearer`。
两种都支持（官方 FAQ 明写「`api-key: $MIMO_API_KEY` 或 `Authorization: Bearer $MIMO_API_KEY`」），
但**如果只写 Bearer 一种，遇到只认 api-key 的网关就会静默 401**。→ **实现时两个头都发**（值相同），零成本消除该风险。

### 2.1 端点与鉴权

| 项 | 值 | 证据 |
|---|---|---|
| Method | `POST` | 🟢 |
| URL | `https://api.xiaomimimo.com/v1/chat/completions` | 🟢 |
| OpenAI SDK `base_url` | `https://api.xiaomimimo.com/v1` | 🟢 |
| Header（二选一，**建议都发**） | `api-key: <KEY>` **或** `Authorization: Bearer <KEY>` | 🟢 |
| Header | `Content-Type: application/json` | 🟢 |
| API Key 形态 | 按量付费 = `sk-xxxxx`；Token Plan 订阅 = `tp-xxxxx`。**两者相互独立、不可混用** | 🟢 |
| ⚠️ Token Plan Base URL | **与按量付费不同**，「以 Token Plan 页面提供的 Base URL 为准」→ **App 必须允许用户改 baseUrl，不能写死** | 🟢 |
| 文档页脚更新时间 | API 参考 2026-07-17；错误码 2026-07-15；模型列表 2026-09-22 | 🟢 |

### 2.2 请求体（官方 API 参考 `inline-schema` 逐字整理）

| 字段 | 类型 | 必填 | 默认 | 允许值 / 说明 |
|---|---|---|---|---|
| `model` | string | **是** | — | `mimo-v2.5-tts` \| `mimo-v2.5-tts-voicedesign` \| `mimo-v2.5-tts-voiceclone` |
| `messages` | array | **是** | — | 见下 |
| `messages[].role` | string | **是** | — | `user` \| `assistant` |
| `messages[].content` | string | **是** | — | 见下（**要合成的文本必须放 `assistant`**） |
| `audio` | object | 否（要出音频就得给） | — | 见下 |
| `audio.format` | string | 否 | `wav`；**若 `stream:true` 则默认 `pcm`** | `wav` \| `mp3` \| `pcm` \| `pcm16`。官方原文：「传入 `pcm` 或 `pcm16`，均表示指定使用 `pcm16` 格式」 |
| `audio.voice` | string | 视模型 | `mimo_default` | 见 §2.3 |
| `audio.optimize_text_preview` | boolean | 否 | `false` | 智能润色播报文本；为 `true` 时可**省略 `assistant` 消息**。**仅 `mimo-v2.5-tts-voicedesign` 支持** |
| `stream` | boolean | 否 | `false` | `true` = SSE 流式 |

**messages 的角色语义（🟢 官方「调用规则」，这是 MiMo 最反直觉的地方）**

- **要合成的目标文本 → 必须放在 `role: "assistant"` 的 `content` 里**。「不可放在 `user` 角色的消息内」。
- `role: "user"` 的消息**可选**，用来传**风格/语气指令**或对话历史 —— **其内容不会被念出来**。
  - 但用 `mimo-v2.5-tts-voicedesign` 时，`user` 消息**必填**（它就是音色描述文本）。
- **自然语言风格指令 → 放 `user.content`；音频标签控制 → 放 `assistant.content`**（官方明确的分工）。
- 音频标签：整体风格标签加在目标文本**开头**，形如 `(风格)`；方言形如 `(东北话)` `(四川话)` `(河南话)` `(粤语)`；唱歌模式需在目标文本**开头紧贴** `(唱歌)`。
- **流式调用时 `audio.format` 请指定 `pcm16`**，官方原文：「采用流式调用时，输出音频的格式请指定为 `pcm16`，以便拼接成完整音频」。

**三个模型的能力矩阵（🟢）**

| Model ID | 能力 | 音色来源 | 限制 |
|---|---|---|---|
| `mimo-v2.5-tts` | 预置精品音色合成 | 预置音色列表 | 支持唱歌模式；**不支持**音色设计与音色复刻 |
| `mimo-v2.5-tts-voicedesign` | 文本描述定制音色 | 文本描述（`user.content`） | **不支持**唱歌模式、预置音色、音色复刻；`audio.voice` **不支持** |
| `mimo-v2.5-tts-voiceclone` | 音频样本复刻任意音色 | base64 音频样本 | **不支持**唱歌模式、预置音色、音色设计；**低延迟流式未上线**（见 §2.5） |

限流（🟢 模型列表页）：`mimo-v2.5-tts` 上下文窗口 8K、最大输出 8K、**最大 RPM 100、最大 TPM 10M**。voiceclone/voicedesign 同一张表未单列数值 🔴。

### 2.3 `audio.voice` 三种取值（🟢 官方 API 参考逐字）

| 模型 | `voice` 是否必填 | 取值 |
|---|---|---|
| `mimo-v2.5-tts` | **可选**，默认 `mimo_default` | 预置音色 ID（下表 9 个之一） |
| `mimo-v2.5-tts-voiceclone` | **必填** | **只支持音频样本的 base64 编码**，且样本**只支持 mp3 / wav** |
| `mimo-v2.5-tts-voicedesign` | **不支持该字段** | —（音色由 `user.content` 的文本描述生成） |

**预置音色列表（9 个，🟢 逐字）**

| 音色名 | Voice ID | 语言 | 性别 |
|---|---|---|---|
| MiMo-默认 | `mimo_default` | 因部署集群而异：**中国集群默认为 `冰糖`，其他集群默认为 `Mia`** | 混合 |
| 冰糖 | `冰糖` | 中文 | 女性 |
| 茉莉 | `茉莉` | 中文 | 女性 |
| 苏打 | `苏打` | 中文 | 男性 |
| 白桦 | `白桦` | 中文 | 男性 |
| Mia | `Mia` | 英文 | 女性 |
| Chloe | `Chloe` | 英文 | 女性 |
| Milo | `Milo` | 英文 | 男性 |
| Dean | `Dean` | 英文 | 男性 |

> ⚠️ **中文音色 ID 就是中文字符串本身**（`"voice": "冰糖"`），不是拼音、不是英文代号。
> JSON 序列化时**不要**转成 `\uXXXX` 转义（Gson 默认不转义，安全；但 `org.json` 也不转义 —— 两边都行，
> 只要别手工做 ASCII escape）。请求体必须声明 `charset=utf-8`。
> 🔵 开源 `MiMoTTSReader` 的 `VoiceRegistry.PRESET_VOICES` 与这 9 项**逐项对上**（含 `mimo_default`）。

### 2.4 音色克隆（voiceclone）—— **没有上传接口，base64 内联**

🟢 官方逐字：

> 将音频文件样本转换为 Base64 编码字符串后传入。转换后的 **Base64 编码的字符串大小不能超过 10 MB**，
> 目前仅支持传入 **mp3 和 wav** 格式的音频样本文件。
> 请在 Base64 编码前携带前缀：`data:{MIME_TYPE};base64,$BASE64_AUDIO`
> - `{MIME_TYPE}`：取值可以为 `audio/mpeg`（或 `audio/mp3`）、`audio/wav`
> - `$BASE64_AUDIO`：音频文件的**纯 Base64 编码字符串（不含任何前缀）**

**要点**

1. **没有独立的上传/管理端点**。克隆音色 = 每次请求把整段样本 base64 塞进 `audio.voice`。**无 list、无 delete、无持久化 voice id**。
2. **10 MB 的限制是「base64 编码后字符串」的大小**，不是原文件大小 → 原文件上限 ≈ **7.5 MB**（base64 膨胀 4/3）。实现时要按**编码后长度**校验，别按文件字节数。
3. **必须带 `data:<mime>;base64,` 前缀**，官方示例逐字：`"voice": f"data:audio/mpeg;base64,{voice_base64}"`。
4. **音频时长上限：官方没写** 🔴。🟡 反编译的爱语有「正在截取前15秒音频...」「用户选择(前15秒)」，说明它自己截到 15s；`v0.50.0` 执行方案里也写了「自动截取前 15 秒」—— 这是**项目自定的安全值，不是 MiMo 的规定**，别当协议约束写进注释。
5. `mimo-v2.5-tts-voiceclone` 时 `user.content` 可以传**空串** `""`（官方示例就是这么写的），目标文本仍放 `assistant`。

### 2.5 响应形状

**非流式（🟢 官方 API 参考逐字响应示例）**

```json
{
    "id": "6ebed286b58546f6b87fa7fa9d0e806b",
    "choices": [
        {
            "finish_reason": "stop",
            "index": 0,
            "message": {
                "content": "",
                "role": "assistant",
                "audio": {
                    "id": "979a91904f9a4143928d9e1f54837b4f",
                    "data": "base64Data",
                    "expires_at": null,
                    "transcript": null
                },
                "tool_calls": null
            }
        }
    ],
    "created": 1776954802,
    "model": "mimo-v2.5-tts",
    "object": "chat.completion",
    "usage": {
        "completion_tokens": 97,
        "prompt_tokens": 213,
        "total_tokens": 310,
        "completion_tokens_details": { "reasoning_tokens": 0 },
        "prompt_tokens_details": { "cached_tokens": 109 }
    }
}
```

**响应字段（🟢）**

| 路径 | 类型 | 说明 |
|---|---|---|
| `choices[0].message.audio.data` | string | **「模型生成的 Base64 编码音频，格式为请求中指定的格式」** ← 音频就在这里 |
| `choices[0].message.audio.id` | string | 此音频响应的唯一标识符 |
| `choices[0].message.audio.expires_at` | number \| null | 过期 Unix 秒。**「当前仅为 `null`」** |
| `choices[0].message.audio.transcript` | string \| null | 音频文字记录。**「当前仅为 `null`」** |
| `choices[0].message.content` | string | 示例中为 `""`（音频请求下不返回文本） |
| `choices[0].message.final_text_preview` | string | 润色后的最终播报文本，**仅当 `optimize_text_preview=true` 时返回** |
| `choices[0].finish_reason` | string | `stop` \| `length` \| `content_filter`（内容审核拦截） |
| `object` | string | 恒为 `chat.completion` |
| `usage.*` | object \| null | token 用量；`completion_tokens_details.reasoning_tokens` **固定为 0** |

> ⚠️ **`data` 里是纯 base64 还是带 `data:audio/wav;base64,` 前缀？**
> 🟢 官方示例把 `base64.b64decode(message.audio.data)` 的结果**直接写成 `.wav` 文件**，说明官方口径是**纯 base64、无前缀**。
> 🟡 但反编译的爱语里同时存在字面量 `data:audio/wav;base64,` 和正则 `data:[^"\s]+;base64,[A-Za-z0-9+/=]+` —— 它**防御性地剥前缀**。
> 👉 **实现照抄爱语**：解码前先 `if (s.startsWith("data:")) s = s.substringAfter("base64,")`，再 trim 掉换行/空白。零成本，防线上变体。
> 🔵 开源 `MiMoTTSReader` / `astrbot_plugin_mimo_tts_voiceclone` / `pyvideotrans` 三家都**直接 decode 不剥前缀** —— 说明目前线上返回的确实是纯 base64。

**流式（🟢）**

- SSE，`data: ` 前缀 + JSON chunk，`data: [DONE]` 结束。
- 音频在 **`choices[0].delta.audio.data`**（注意是 `delta` 不是 `message`），**每 chunk 一段 base64**，客户端需**按序 decode 后拼接字节**。
- 拼接结果是 **24kHz / PCM16LE / 单声道**裸 PCM（官方 Python 示例注释：`# 24kHz PCM16LE mono audio`，`sf.write(..., samplerate=24000)`）。
- **要自己补 44 字节 WAV 头**才能存成 wav（🔵 两个开源实现都手写了 `pcm16ToWav(pcm, 24000, 1, 16)`）。
- ⚠️ `mimo-v2.5-tts-voiceclone` 的**低延迟流式未上线**：官方原文「流式调用接口目前降级为兼容模式，**仅在所有推理完成后**以流式格式返回**一次**结果」。→ 用 voiceclone 时别指望首包快。

**错误码（🟢 官方错误码页，2026-07-15）**

| HTTP | 原因 | 官方给的解决方法 |
|---|---|---|
| 400 | 请求体格式错误 | 检查 JSON 格式 / 必需参数 / 参数值范围 / 消息格式 / 模型是否存在 / 字段名 / 多模态文件格式与大小 / 多模态文件是否可公开访问 |
| 401 | 缺少或无效 API Key，或 `Authorization` 头格式错误；**混用了 Token Plan 与按量付费的 Key** | 检查 Key 与请求头格式；Token Plan 要用专属 Base URL + 专属 Key |
| 402 | 账户余额不足 | 充值 |
| 403 | 服务暂不支持当前地区，或 API Key 被风控 | 新建 API Key |
| 404 | 接口或模型不支持该能力 | 确认模型/接口能力 |
| **421** | **内容审核拦截** | 避免输入不安全或敏感内容 |
| 429 | 请求过频，或 Token Plan 额度耗尽 | 指数退避重试 / 降频 / 升级套餐 |
| 500 | 服务器内部故障 | 稍后重试 |
| 503 | 服务器负载过高 | 稍后重试 |

> 🔴 **错误响应的 JSON body 形状官方没给**。
> 最佳猜测（置信度 **中**）：OpenAI 兼容网关的通用形状 `{"error": {"message": "...", "type": "...", "code": "..."}}`。
> 🟡 反编译爱语的错误串是 `MiMo API 返回错误: ` / `MiMo 请求失败: HTTP <code>` / `MiMo 请求过快 (429)` / `解析 MiMo 响应失败`，
> 看不出它按什么键取的 message。
> 👉 **实现建议**：错误信息提取做成三级兜底 —— `error.message` → `message` → 整个 body 纯文本（截断 300 字）。
> **特别注意 421**：这是内容审核拦截，不是网络错误，重试无意义，UI 文案要区别对待（本项目 AI 陪伴场景很容易触发）。

### 2.6 逐字示例

**① 预置音色 · 非流式（官方 cURL 逐字）**

```bash
curl --location --request POST 'https://api.xiaomimimo.com/v1/chat/completions' \
--header "api-key: $MIMO_API_KEY" \
--header 'Content-Type: application/json' \
--data-raw '{
    "model": "mimo-v2.5-tts",
    "messages": [
        {
            "role": "user",
            "content": "Bright, bouncy, slightly sing-song tone — like you are bursting with good news you can barely hold in. Fast pace, rising pitch at the end."
        },
        {
            "role": "assistant",
            "content": "Hey boss — guess what, guess what? I just got the results back and I actually passed!"
        }
    ],
    "audio": {
        "format": "wav",
        "voice": "Chloe"
    }
}'
```

**② 音色克隆 · 非流式（官方 cURL 逐字）**

```bash
curl --location --request POST 'https://api.xiaomimimo.com/v1/chat/completions' \
--header "api-key: $MIMO_API_KEY" \
--header 'Content-Type: application/json' \
--data-raw '{
    "model": "mimo-v2.5-tts-voiceclone",
    "messages": [
        { "role": "user", "content": "" },
        { "role": "assistant", "content": "Yes, I had a sandwich." }
    ],
    "audio": {
        "format": "wav",
        "voice": "data:{MIME_TYPE};base64,$BASE64_AUDIO"
    }
}'
```

**③ 音色克隆 · 流式（官方 cURL 逐字）**

```bash
curl --location --request POST 'https://api.xiaomimimo.com/v1/chat/completions' \
--header "api-key: $MIMO_API_KEY" \
--header 'Content-Type: application/json' \
--data-raw '{
    "model": "mimo-v2.5-tts-voiceclone",
    "messages": [
        { "role": "user", "content": "" },
        { "role": "assistant", "content": "You are UN-BE-LIEVABLE! I am sooooo done with your constant lies. GET. OUT!" }
    ],
    "audio": { "format": "pcm16", "voice": "data:{MIME_TYPE};base64,$BASE64_AUDIO" },
    "stream": true
}'
```

**④ 本项目实际会发的请求体（中文预置音色，最小可用）**

```json
{
  "model": "mimo-v2.5-tts",
  "messages": [
    { "role": "user", "content": "温柔一些，语速稍慢" },
    { "role": "assistant", "content": "今天过得怎么样？" }
  ],
  "audio": { "format": "wav", "voice": "冰糖" },
  "stream": false
}
```

**⑤ 音色设计（voicedesign，`user.content` = 音色描述，必填）**

```json
{
  "model": "mimo-v2.5-tts-voicedesign",
  "messages": [
    { "role": "user", "content": "一位年迈的老先生，说带北方口音的普通话，语速缓慢而沉稳，嗓音略带沙哑和沧桑感。" },
    { "role": "assistant", "content": "夜深了，早点休息吧。" }
  ],
  "audio": { "format": "wav" }
}
```

> voicedesign 的 `audio` 里**不要**带 `voice`（官方：「不支持该字段」）。
> 音色描述写法建议（🟢）：1-4 句即可；覆盖 性别与年龄 / 音色质感 / 情绪语气 / 语速节奏；可选 角色人设、说话风格、场景描写、年代参照。
> **避免**：矛盾特征、音质效果词（混响/回声/EQ/压缩）、模糊词（"普通的""正常的""外国的"）。

### 2.7 文档出处

- **API 参考 · 语音合成（TTS）** ← 最权威，请求体 schema + 响应 schema + 三个 tab 的 cURL/响应示例
  `https://mimo.mi.com/docs/zh-CN/api/audio/tts`
- 使用指南 · 语音合成（MiMo-V2.5-TTS 系列）← 预置音色表、风格控制、导演模式、音频标签、voiceclone 10MB/前缀规则、6 段 Python/cURL 示例
  `https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5`
- 首次调用 API（鉴权头 `api-key` 的 cURL 逐字）`https://mimo.mi.com/docs/zh-CN/quick-start/summary/first-api-call`
- 常见问题 · API 接入（「`api-key` 或 `Authorization: Bearer`」二选一原文、Token Plan Base URL）`https://mimo.mi.com/docs/zh-CN/quick-start/faq/api-integration`
- 错误码 `https://mimo.mi.com/docs/zh-CN/api/guidance/error-codes`
- 限流 / 模型列表（RPM 100、TPM 10M、上下文 8K）`https://mimo.mi.com/docs/zh-CN/api/guidance/rate-limit`、`https://mimo.mi.com/docs/zh-CN/quick-start/summary/model`
- OpenAI Chat Completions 兼容参考（**全文无 `modalities`**）`https://mimo.mi.com/docs/zh-CN/api/chat/openai-api`
- 旧域名镜像：`https://platform.xiaomimimo.com/docs/usage-guide/speech-synthesis`

---

<a id="3-通用-openai-兼容-v1audiospeech-基线"></a>
## 3. 通用 OpenAI 兼容 `/v1/audio/speech` 基线

用户自填 baseUrl 时的默认协议。**以 openai-python SDK 的类型定义为准**（该文件由 OpenAI 官方 OpenAPI spec 生成，等于协议本身）。

### 3.1 请求

| 项 | 值 | 证据 |
|---|---|---|
| Method / Path | `POST {baseUrl}/audio/speech` | 🟢 SDK `self._post("/audio/speech", ...)` |
| Header | `Authorization: Bearer <key>`（SDK `security={"bearer_auth": True}`） | 🟢 |
| Header | `Content-Type: application/json` | 🟢 |
| Header | **`Accept: application/octet-stream`** ← SDK 显式加了这个头 | 🟢 |

| 字段 | 类型 | 必填 | 允许值 / 默认 |
|---|---|---|---|
| `input` | string | **是** | 待合成文本，**最大 4096 字符** |
| `model` | string | **是** | `tts-1` / `tts-1-hd` / `gpt-4o-mini-tts` / `gpt-4o-mini-tts-2025-12-15`（自定义端点则是它自己的模型名 → **必须可填**） |
| `voice` | string **或** `{"id": "voice_1234"}` | **是** | 内置：`alloy` `ash` `ballad` `coral` `echo` `fable` `onyx` `nova` `sage` `shimmer` `verse` `marin` `cedar`；**也接受对象形态**（自定义音色） |
| `instructions` | string | 否 | 语气控制。**`tts-1` / `tts-1-hd` 不支持** |
| `response_format` | string | 否 | `mp3` \| `opus` \| `aac` \| `flac` \| `wav` \| `pcm` |
| `speed` | float | 否 | `0.25` ~ `4.0`，默认 `1.0` |
| `stream_format` | string | 否 | `sse` \| `audio`。**`tts-1` / `tts-1-hd` 不支持 `sse`** |

### 3.2 响应

- **裸二进制音频字节**（SDK 返回 `HTTPAPIResponse[bytes]`，`Accept: application/octet-stream`）。**不是 JSON、不是 base64。**
- 失败时返回 JSON 错误体，通用形状 `{"error": {"message": ..., "type": ..., "param": ..., "code": ...}}`（🔵 代理实现按 `err.error?.message || err.error || JSON.stringify(err)` 三级取值）。

### 3.3 「常被自定义端点不支持」的字段 → 建议全部设为可选

按被支持概率从高到低（依据：多家 OpenAI 兼容网关的 DTO 只保留前三项）：

| 字段 | 支持面 | 处理建议 |
|---|---|---|
| `model` / `input` / `voice` | 几乎所有端点 | **必填三项**，缺任一直接不发请求 |
| `response_format` | 很广（但**允许值集合各家不同**：硅基无 `aac`/`flac`；MiMo 根本没有这个字段） | 可选；UI 只给 `mp3` / `wav` 两档最稳 |
| `speed` | 较广（硅基有；OpenAI 有；MiMo **没有**） | 可选；不填就不进 JSON |
| `stream` / `stream_format` | **窄**（OpenAI 用 `stream_format`，硅基用 `stream`，MiMo 用 `stream` 且语义是 SSE） | **三家语义不一致 → 不要做成统一开关**，每个 provider 各自内部决定 |
| `gain` | **只有硅基有** | 只在硅基分支出现 |
| `sample_rate` | **只有硅基有** | 只在硅基分支出现 |
| `instructions` | 只有 OpenAI 系（且 `tts-1` 不支持） | 只在通用分支出现，默认不发 |
| `metadata` | 极少数（`new-api` DTO 里有） | **不做** |

🔵 **`new-api` 的通用音频 DTO（一个成熟网关认为「值得转发」的全集）**：
`model` `input` `voice` `instructions` `response_format` `speed` `stream_format` `metadata`
—— 注意它**没有** `gain` / `sample_rate` / `stream`，反向印证这三个是厂商私有的。

🔵 **`lobe-chat` 的 `TextToSpeechPayload` 只有三项**：`{ input, model, voice }` —— 最小公约数。

### 3.4 逐字示例

```bash
curl -X POST "https://<用户填的 baseUrl>/audio/speech" \
  -H "Authorization: Bearer <KEY>" \
  -H "Content-Type: application/json" \
  -H "Accept: application/octet-stream" \
  -d '{
    "model": "tts-1",
    "input": "今天过得怎么样？",
    "voice": "nova",
    "response_format": "mp3",
    "speed": 1.0
  }' \
  --output out.mp3
```

响应：HTTP 200，body = MP3 字节流。

---

<a id="4-开源参考实现"></a>
## 4. 开源参考实现

> 按 AGENTS.md 零.1 要求：**不自己发明**。以下每个仓库都给出「构建请求 + 解析响应」的逐字代码与可点链接。
> 优先级排序：**MiMo 那条 Kotlin/OkHttp 实现和本项目技术栈完全同构，直接照抄改造**。

### 4.1 ⭐ MiMo · `timyang2005/MiMoTTSReader`（Kotlin + OkHttp + Gson，Android TTS 引擎）

- 仓库：`https://github.com/timyang2005/MiMoTTSReader`（19★，描述「Android TTS engine for Legado via MiMo TTS v2.5 API」）
- 文件：`app/src/main/java/com/mimo/ttsreader/api/MiMoTtsClient.kt`
  直链：`https://github.com/timyang2005/MiMoTTSReader/blob/HEAD/app/src/main/java/com/mimo/ttsreader/api/MiMoTtsClient.kt`
- **为什么最值得抄**：同为 Kotlin + OkHttp + `suspend` + `Dispatchers.IO`，非流式与流式两条路径都写全了，还带 PCM→WAV 头补齐。

**非流式（请求构建 + 响应解析）逐字：**

```kotlin
val request = MiMoTtsRequest(
    model = model,
    messages = listOf(
        Message(role = "user", content = userContent),
        Message(role = "assistant", content = assistantContent)
    ),
    audio = AudioConfig(format = "wav", voice = voiceId),
    stream = false
)
val json = gson.toJson(request)
val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

val httpRequest = Request.Builder()
    .url("https://api.xiaomimimo.com/v1/chat/completions")
    .addHeader("api-key", apiKey)
    .addHeader("Content-Type", "application/json")
    .post(body)
    .build()

client.newCall(httpRequest).execute().use { response ->
    if (!response.isSuccessful) {
        val errorBody = response.body?.string() ?: "Unknown error"
        throw RuntimeException("MiMo API error ${response.code}: $errorBody")
    }
    val responseBody = response.body?.string() ?: throw RuntimeException("Empty response body")
    val ttsResponse = gson.fromJson(responseBody, MiMoTtsResponse::class.java)
    val audioData = ttsResponse.choices.firstOrNull()
        ?.message?.audio?.data
        ?: throw RuntimeException("No audio data in response")
    Base64.decode(audioData, Base64.DEFAULT)
}
```

**流式（SSE 逐行 + `delta.audio.data` 拼接 + 补 WAV 头）逐字：**

```kotlin
// 请求体同上，但 audio = AudioConfig(format = "pcm16", voice = voiceId), stream = true
val pcmBuffer = ByteArrayOutputStream()
client.newCall(httpRequest).execute().use { response ->
    val source = response.body?.source() ?: throw RuntimeException("Empty response body")
    while (!source.exhausted()) {
        val line = source.readUtf8Line() ?: continue
        if (line.startsWith("data: ")) {
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break
            try {
                val chunk = gson.fromJson(data, MiMoStreamChunk::class.java)
                val audioData = chunk.choices.firstOrNull()?.delta?.audio?.data
                if (audioData != null) pcmBuffer.write(Base64.decode(audioData, Base64.DEFAULT))
            } catch (_: Exception) { }
        }
    }
}
AudioUtils.pcm16ToWav(pcmBuffer.toByteArray(), 24000, 1, 16)   // 24kHz / mono / 16bit
```

**DTO 逐字（`model/MiMoTtsModels.kt`）：**

```kotlin
data class MiMoTtsRequest(val model: String, val messages: List<Message>,
                          val audio: AudioConfig, val stream: Boolean = false)
data class Message(val role: String, val content: String)
data class AudioConfig(val format: String, val voice: String)

data class MiMoTtsResponse(val choices: List<Choice>) {
    data class Choice(val message: MessageResponse)
    data class MessageResponse(val audio: AudioData?)
    data class AudioData(val data: String, val expires_at: Long? = null, val transcript: String? = null)
}
data class MiMoStreamChunk(val choices: List<StreamChoice>) {
    data class StreamChoice(val delta: StreamDelta)
    data class StreamDelta(val audio: AudioData? = null)
    data class AudioData(val data: String)
}
```

**预置音色表逐字（与官方 9 项完全一致）：**

```kotlin
object VoiceRegistry {
    val PRESET_VOICES = listOf(
        VoiceInfo("mimo_default", "MiMo-默认", "中英", "混合"),
        VoiceInfo("冰糖", "冰糖", "中文", "女性"),
        VoiceInfo("茉莉", "茉莉", "中文", "女性"),
        VoiceInfo("苏打", "苏打", "中文", "男性"),
        VoiceInfo("白桦", "白桦", "中文", "男性"),
        VoiceInfo("Mia", "Mia", "英文", "女性"),
        VoiceInfo("Chloe", "Chloe", "英文", "女性"),
        VoiceInfo("Milo", "Milo", "英文", "男性"),
        VoiceInfo("Dean", "Dean", "英文", "男性"),
    )
    val DIALECT_STYLES = mapOf("" to "", "东北话" to "(东北话)", "四川话" to "(四川话)",
                               "河南话" to "(河南话)", "粤语" to "(粤语)")
}
```

**超时设置**（同文件，可直接借用）：connect 30s / **read 120s** / write 30s。
> 💡 值得抄的两个细节：① 语速用**自然语言指令**塞进 `user.content`（`speed<=2 → "语速很慢，每个字都慢慢说"` … `else → "语速很快，快速朗读"`），因为 MiMo **没有 `speed` 字段**；② 方言/风格标签**前缀拼在 `assistant.content` 开头**（`"$dialectPrefix$stylePrefix$text"`），与官方「音频标签放 assistant」的规定一致。

`util/AudioUtils.kt` 里有完整的 `pcm16ToWav` / `extractPcmFromWav` / `addSilenceToWav`（44 字节 RIFF 头手写，Little-Endian）——
本项目 `data/VoiceRecorder.kt` 已有手写 WAV 头，可对照校验，**不必重复实现**。

### 4.2 ⭐ MiMo · `jianchang512/pyvideotrans`（~20k★ 级别的高星项目，两家都有）

- 仓库：`https://github.com/jianchang512/pyvideotrans`
- **`videotrans/tts/_xiaomi.py`** — `https://github.com/jianchang512/pyvideotrans/blob/HEAD/videotrans/tts/_xiaomi.py`

```python
self.api_url = "https://api.xiaomimimo.com/v1"
refer_text = " ".join([it['text'] for it in self.queue_tts[max(0,idx-5):min(idx+6,len(self.queue_tts))]])
post_message = [
    {"role": "user",      "content": refer_text},          # 上下文当风格提示，不会被念出来
    {"role": "assistant", "content": data_item.get('text')} # 目标文本
]
self.client = OpenAI(api_key=params.get("xiaomi_key",''), base_url=self.api_url)
completion = self.client.chat.completions.create(
    model=params.get("xiaomi_ttsmodel"),
    messages=post_message,
    audio={"format": "wav", "voice": data_item['role']}
)
message = completion.choices[0].message
audio_bytes = base64.b64decode(message.audio.data)
```

> 💡 值得抄的点：**把前后各 5 句对话拼成 `user.content` 当韵律上下文** —— 对 AI 陪伴场景（本项目）非常合适，
> 能让语音语调跟着剧情走，而且官方明确「user 消息内容不会出现在合成的语音中」。
> 也印证了：**用官方 OpenAI SDK（= `Authorization: Bearer`）同样能通**，不是只有 `api-key` 头。
> 错误处理值得抄：`NotFoundError/AuthenticationError/PermissionDeniedError → 直接终止`；
> `APIError` 且 `message` 匹配 `insufficient.*?balance` → 报「余额不足」并终止，不重试。

- **`videotrans/tts/_siliconflow.py`** — `https://github.com/jianchang512/pyvideotrans/blob/HEAD/videotrans/tts/_siliconflow.py`

```python
url = "https://api.siliconflow.cn/v1/audio/speech"
payload = {
    "model": self.model_name,                       # 默认 'FunAudioLLM/CosyVoice2-0.5B'
    "input": data_item['text'],
    "voice": f"{self.model_name}:{data_item['role']}",   # ← 预置音色必须拼模型名前缀
    "speed": self.speed,
    "response_format": "wav"
}
headers = {"Authorization": f"Bearer {params.get('siliconflow_key')}",
           "Content-Type": "application/json"}
response = requests.post(url, json=payload, headers=headers, verify=False)
response.raise_for_status()
with open(data_item['filename'] + "-44100.wav", 'wb') as f:   # ← 文件名点明默认采样率 44100
    f.write(response.content)                                  # ← 响应就是裸二进制
```

> 💡 值得抄的点：`voice = f"{model}:{role}"` 这个**前缀拼接公式**，以及「响应直接 `response.content` 写字节」。
> 重试策略：`tenacity` + `retry_if_not_exception_type(NO_RETRY_EXCEPT)` + 固定 2s 间隔 + 可配重试次数。

### 4.3 硅基流动音色克隆 · `yythl/siliconflow_tts`（上传/列表/删除全套，唯一带 `text` 的完整实现）

- 仓库：`https://github.com/yythl/siliconflow_tts`
- `src/client.py` — `https://github.com/yythl/siliconflow_tts/blob/HEAD/src/client.py`

```python
BASE_URL = "https://api.siliconflow.cn/v1"
DEFAULT_MODEL = "FunAudioLLM/CosyVoice2-0.5B"
VOICES = { "alex": "FunAudioLLM/CosyVoice2-0.5B:alex", "anna": "...:anna",
           "bella": "...:bella", "benjamin": "...:benjamin", "charles": "...:charles",
           "claire": "...:claire", "david": "...:david", "diana": "...:diana" }   # 8 个，与官方逐字一致

# ---- 合成：直接复用 openai SDK，gain 走 extra_body ----
extra_body = {}
if gain != 0.0:
    extra_body["gain"] = gain
response = self.client.audio.speech.create(
    model=model, voice=voice_id, input=text, speed=speed,
    response_format=response_format, extra_body=extra_body or None)
response.stream_to_file(output_path)

# ---- 克隆上传：multipart，file + model + customName + text ----
url = f"{Config.BASE_URL}/uploads/audio/voice"
with open(file_path, "rb") as f:
    files = {"file": (os.path.basename(file_path), f, "audio/mpeg")}
    data  = {"model": model, "customName": custom_name, "text": text}
    response = httpx.post(url, headers=self.headers, data=data, files=files, timeout=120.0)
result = response.json()          # → {"uri": "speech:..."}
if 'uri' in result: ...

# ---- 列表：注意它多带了 ?model= 查询参数（官方未记载）----
url = f"{Config.BASE_URL}/audio/voice/list"
response = httpx.get(url, headers=self.headers, params={"model": model}, timeout=30.0)

# ---- 删除：路径与官方一致 ----
url = f"{Config.BASE_URL}/audio/voice/deletions"
response = httpx.post(url, headers=self.headers, json={"uri": uri}, timeout=30.0)
```

- `src/voice_manager.py` — 同仓库。**值得抄的防御性解析**（列表响应键名三家说法不一）：

```python
if isinstance(response, dict):
    if   'result' in response: voices_list = response['result']
    elif 'data'   in response: voices_list = response['data']
    elif 'voice'  in response: voices_list = [response['voice']] if response.get('voice') else []
elif isinstance(response, list):
    voices_list = response
for v in voices_list:
    uri, name = v.get('uri'), v.get('customName')
    # 本地 voices.json 存 {name: {uri, model, text, raw}}
```

> 💡 **`VoiceManager` 的整体设计正是本项目 `data/VoiceCloner.kt` 该抄的骨架**：
> 上传成功 → 把 `{customName → uri, model, text}` **落到本地文件**（本项目应落 DataStore/Room），
> 之后合成时按名字查 `uri`；启动时可选 `sync_remote_voices()` 与云端对账。
> ⚠️ 它的本地键名解析里**没有官方文档写的 `results`** —— 三份证词（`results` / `result` / `data`）**全都要兜**。
> ⚠️ 上传超时给了 **120s**（音频文件较大），列表/删除 30s。这个梯度可以直接抄。

### 4.4 硅基流动音色克隆 · `WillChenNewFish/skill-soulsaying`（shell 版全流程 + 踩坑笔记）

- 仓库：`https://github.com/WillChenNewFish/skill-soulsaying`
- `scripts/clone_voice.sh`：

```bash
RESPONSE=$(curl -s -X POST "https://api.siliconflow.cn/v1/uploads/audio/voice" \
  -H "Authorization: Bearer ${SILICONFLOW_API_KEY}" \
  -F "file=@${AUDIO_FILE}" \
  -F "model=FunAudioLLM/CosyVoice2-0.5B" \
  -F "customName=${VOICE_NAME}")
VOICE_URI=$(echo "$RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('uri',''))")
```

- `scripts/tts.sh`：

```bash
JSON_BODY=$(python3 -c "import json,sys; print(json.dumps({
    'model': sys.argv[1], 'input': sys.argv[2],
    'voice': sys.argv[3], 'response_format': 'mp3'}))" "$MODEL" "$TEXT" "$VOICE_URI")
curl -s -X POST "https://api.siliconflow.cn/v1/audio/speech" \
  -H "Authorization: Bearer ${SILICONFLOW_API_KEY}" -H "Content-Type: application/json" \
  -d "${JSON_BODY}" -o "${OUTPUT}"
# 校验产物是否真的是音频：file "${OUTPUT}" | grep -q "Audio\|MPEG\|ID3"
```

- `references/api-notes.md`（**踩坑笔记，务必看**）：

> | Model | Cloned Voice Support | Built-in Voices |
> |-------|---------------------|-----------------|
> | `IndexTeam/IndexTTS-2` | ✅ Stable | Uses cloned only |
> | `FunAudioLLM/CosyVoice2-0.5B` | ⚠️ **May 500** | alex, bella, claire, anna |
>
> **Important**: Cloned voices uploaded via CosyVoice2 can be used with IndexTTS-2 for generation.
> Upload always uses CosyVoice2; generation can use either model.

> 💡 三条可直接落地的经验：
> ① **CosyVoice2 + 克隆音色可能直接 500**，`IndexTeam/IndexTTS-2` 更稳 → **model 必须可切换，且克隆失败要能换 model 重试**；
> ② 上传时 `model` 固定填 CosyVoice2，**合成时 model 可以是别的** → `uri` 与 model 解耦；
> ③ **产物要校验是不是音频**（`file` 魔术字节 / 开头是否 `ID3`、`0xFF 0xFB`、`RIFF`）—— 因为失败时同一条 URL 会返回 JSON/纯文本，
>    不校验就会把错误文本存成 `.mp3` 然后「播放失败」。
> ⚠️ 但这个仓库**没传 `text` 字段**，与官方 required 冲突；它的 delete 路径也写错了（`/v1/uploads/audio/voice/deletions`），列表键名写成 `result`。
> **凡与官方文档冲突处，一律以官方为准** —— 这个仓库只用来看踩坑经验，不用来抄字段。
> 📏 它还给了个体积经验值：样本 **> 2MB 就警告**，建议剪到 10-30s、**< 1MB**。

### 4.5 硅基流动 · `WhyMeta/bob-plugin-siliconflow-tts`（16★，Bob 翻译插件）

- `src/main.js` — `https://github.com/WhyMeta/bob-plugin-siliconflow-tts/blob/HEAD/src/main.js`

```javascript
$http.request({
  method: 'POST',
  url: `${$info.apiUrl}/v1/audio/speech`,
  header: { 'Authorization': `Bearer ${$option.apiKey}`, 'Content-Type': 'application/json' },
  body: {
    model: 'FunAudioLLM/CosyVoice2-0.5B',
    input: originText,
    voice: `fishaudio/fish-speech-1.4:${$option.voice}`,   // ← 换 model 时前缀必须跟着换！
    speed: parseFloat($option.speed),
    gain: parseFloat($option.gain),
    response_format: "mp3",
    stream: true
  },
  handler: function (resp) {
    if (resp.error) { /* ... */ }
    if (!resp.rawData) { completion({error:{type:'data', message:'未收到音频数据'}}); return; }
    completion({ result: { type: 'base64', value: resp.rawData.toBase64(), raw: {} } });
  }
});
```

- **API Key 校验用的是 `GET /v1/audio/voice/list`**（返回 200 即认为 key 有效）：

```javascript
const resp = await $http.request({ method: "GET", url: `${$info.apiUrl}/v1/audio/voice/list`,
  header: { 'Authorization': `Bearer ${$option.apiKey}` } });
if (resp.response.statusCode === 200) completion({ result: true });
```

> 💡 值得抄的两点：
> ① **`voice` 前缀必须与 `model` 一致** —— 它这里 `model` 是 CosyVoice2 而 `voice` 前缀写了 `fishaudio/fish-speech-1.4`，
>    **这其实是个 bug**（说明前缀写错不一定立刻报错，容易静默出问题）。**本项目必须用 `"$model:$alias"` 动态拼，禁止硬编码前缀。**
> ② `stream: true` 时它仍按「整块 rawData」消费 —— 再次印证**硅基的 `stream` 是 chunked 裸二进制、不是 SSE**。
> ③ **拿 `/v1/audio/voice/list` 当「测试连接」探针**很合适（轻量、GET、无副作用）—— 本项目「设置 → 语音合成 → 试听」之外的
>    「测试连接」按钮可以直接用它，比合成一句省钱省时。

### 4.6 通用基线 · 三个成熟网关

| 仓库 | 文件 | 链接 | 看点 |
|---|---|---|---|
| `openai/openai-python`（官方 SDK） | `src/openai/types/audio/speech_create_params.py` | `https://github.com/openai/openai-python/blob/HEAD/src/openai/types/audio/speech_create_params.py` | **协议本体**（由 OpenAPI spec 生成）：字段全集、`response_format` 六个 enum、`voice` 可为 `{"id":...}` 对象、`input` ≤ 4096 字符 |
| `openai/openai-python` | `src/openai/resources/audio/speech.py` | `https://github.com/openai/openai-python/blob/HEAD/src/openai/resources/audio/speech.py` | `self._post("/audio/speech", ...)` + **`extra_headers = {"Accept": "application/octet-stream"}`** + `security={"bearer_auth": True}` + `extra_body` 逃生舱 |
| `QuantumNous/new-api` | `relaykit/dto/audio.go` | `https://github.com/QuantumNous/new-api/blob/HEAD/relaykit/dto/audio.go` | 一个成熟中转网关认为值得转发的字段全集：`model/input/voice/instructions/response_format/speed/stream_format/metadata`（`speed` 是 `*float64` 指针 → **未填就不进 JSON**，正是本项目该做的）；`IsStream() = (stream_format == "sse")` |
| `QAbot-zh/VoiceHub`（46★） | `functions/speech.js` | `https://github.com/QAbot-zh/VoiceHub/blob/HEAD/functions/speech.js` | Cloudflare Worker 代理：**只透传 `{model, input, voice}`**；上游非 2xx 时 `err.error?.message \|\| err.error \|\| JSON.stringify(err)` 三级取错误文案；成功时 `Content-Type` 直接沿用上游、缺省兜 `audio/mpeg`，body 原样 stream 回去 |
| `lobehub/lobe-chat` | `packages/model-runtime/src/types/tts.ts`、`src/app/(backend)/webapi/tts/openai/route.ts` | `https://github.com/lobehub/lobe-chat/blob/HEAD/packages/model-runtime/src/types/tts.ts` | 最小公约数 payload `{input, model, voice}`；服务端把 SDK 的 bytes 包成 Response 的统一封装 `createSpeechResponse` |

### 4.7 MiMo voiceclone · `QingchenWait/astrbot_plugin_mimo_tts_voiceclone`（v1.2.4）

- `main.py` — `https://github.com/QingchenWait/astrbot_plugin_mimo_tts_voiceclone/blob/HEAD/main.py`

```python
url = f"{self.base_url.rstrip('/')}/chat/completions"
headers = {"api-key": self.api_key, "Content-Type": "application/json"}
voice_value = f"data:{reference_audio.get('mime_type','audio/mpeg')};base64,{reference_audio['base64']}"
payload = {
    "model": self.model_name,                     # 默认 'mimo-v2.5-tts-voiceclone'
    "messages": [{"role": "user", "content": ""},
                 {"role": "assistant", "content": truncated_text}],
    "audio": {"format": self.output_format, "voice": voice_value}
}
async with session.post(url, json=payload, headers=headers,
                        timeout=aiohttp.ClientTimeout(total=120)) as response:
    if response.status == 200:
        result = await response.json()
        message = result["choices"][0].get("message", {})
        audio_data = message.get("audio", {})
        if audio_data and "data" in audio_data:
            return base64.b64decode(audio_data["data"])
```

MIME 判定（可直接抄成 Kotlin 的 `when(ext)`）：

```python
{".mp3": "audio/mpeg", ".wav": "audio/wav", ".flac": "audio/flac", ".ogg": "audio/ogg"}
# 兜底 "audio/mpeg"
```

> 💡 值得抄：`base_url` **可配置**且做 `rstrip('/')` 归一（Token Plan 专属 Base URL 正好需要）；
> 参考音频**启动时一次性读入并 base64 缓存**（`reference_audio_base64`），避免每次合成都重读文件；
> 总超时 **120s**；支持**按 bot/persona 绑定不同参考音频**（`bot_reference_audios`）—— 正对应本项目「每个人设一个专属声音」。

---

<a id="5-未能确认汇总"></a>
## 5. 「未能确认」汇总

> 🔴 **以下每一条都没有官方文档的明确结论。写代码时按「建议做法」实现，并在代码注释里标 `// UNVERIFIED:`，
> 等有 Key 能实测时逐条销账。禁止把下面任何一条当成事实写进用户可见文案。**

| # | 事项 | 官方文档状态 | 旁证 | 最佳猜测 / 建议做法 | 置信度 |
|---|---|---|---|---|---|
| 1 | **`voice.gbkkov.cn` 是什么** | 无任何记载 | 只出现在爱语 dex 串 `https://voice.gbkkov.cn/`；本机 `nslookup` 报 **Non-existent domain**（NXDOMAIN），`gbkkov.cn` 同样不存在；公网搜索零结果 | **已死域名 / 或爱语作者自建的一次性中转**，与硅基流动**无官方关系**。**结论：不要对接，不要写进代码，当它不存在。** | 高（是死域名这点）／低（它当初干什么的） |
| 2 | 硅基 `/v1/audio/speech` 成功响应的 **Content-Type 逐字值** | 只说「随 `response_format` 变化」，未列表 | VoiceHub 代理兜底 `audio/mpeg` | `mp3→audio/mpeg`、`wav→audio/wav`、`pcm→audio/pcm`(或 `application/octet-stream`)、`opus→audio/opus`。**实现上不要依赖 Content-Type 判成败**，只看 HTTP 状态码 + body 魔术字节嗅探 | 中 |
| 3 | 硅基 `sample_rate` 的**默认值** | **官方自相矛盾**：用户指南正文 = 「wav/pcm/mp3 默认 44100」；API 参考的 `Default` 栏 = `32000` | pyvideotrans 把产物命名成 `-44100.wav`（即它按 44100 理解） | **不传 `sample_rate` 就是赌默认值 → 一律显式传**。本项目显式传 `44100`（mp3/wav），需要低延迟通话链路时传 `16000` 或 `24000` 并同步改播放器采样率 | 中（44100 更可能） |
| 4 | 硅基上传参考音频的**体积上限（MB）** | 只写了「时长 < 30s、建议 8~10s、格式 mp3/wav/pcm/opus、推荐 ≥192kbps mp3」，**没写 MB** | soulsaying 经验值：>2MB 警告、建议 <1MB；VoiceCafe/爱语的 `10MB` 都是别处的限制 | 客户端自设 **≤ 5MB** 且 **≤ 20s**，超限就先本地裁剪/降码率再上传。**不要把 10MB 当成硅基的限制写进 UI 文案** | 中 |
| 5 | 硅基上传时 `text` 传空串是否被接受 | 官方标 **required** | soulsaying 的脚本**完全不传** `text`，且作者称流程可用 | **必须传真实逐字稿**。用本项目已有的端侧 SenseVoice ASR 自动转写用户选的样本（`AsrModelManager` + `SherpaAsr.transcribeWav`），转写失败则让用户手填。**不要传空串赌** | 高（该传）／低（空串是否报错） |
| 6 | 硅基 `GET /v1/audio/voice/list` 响应的**数组键名** | 官方 = **`results`** | soulsaying = `result`；yythl = 兜 `result`/`data`/`voice`/裸数组 | **四路兜底**：`results` → `result` → `data` → 若整个 body 就是数组则直接用。取不到就报「音色列表解析失败」而非「无音色」 | 低（三家说法互斥，只能全兜） |
| 7 | 硅基删除音色的**路径** | 官方 = `POST /v1/audio/voice/deletions` | soulsaying = `POST /v1/uploads/audio/voice/deletions`；yythl = 与官方一致 | 用官方路径；**若返回 404 再回退试 soulsaying 那条**（一次重试，不循环） | 中高 |
| 8 | 硅基删除音色 **200 响应体** | 只标了 `response: string`，示例位是 `"string"` | soulsaying 按 `json.message == 'success'` 判成功 | **HTTP 200 即视为成功**，body 只用于日志；若要判文案就试 `message` 键，取不到不报错 | 中 |
| 9 | 硅基 TTS **可用 model 全集** | 明说「支持的 TTS 模型可能发生调整，请在**模型广场**筛选『语音』标签获得当前列表」；API 参考 enum 只列 2 个 | soulsaying 用 `IndexTeam/IndexTTS-2`；bob 插件注释提到 `fishaudio/fish-speech-1.4`、`GPT-SoVITS` 系列；文档另有 `fnlp/MOSS-TTSD-v0.5` | **model 做成自由文本输入 + 一份内置候选下拉**（`FunAudioLLM/CosyVoice2-0.5B` 默认、`IndexTeam/IndexTTS-2`、`fnlp/MOSS-TTSD-v0.5`）。**不要做客户端白名单校验**，会被服务端模型变更打死 | 高（会变动这点）／低（当前全集） |
| 10 | 硅基 **`stream: true` 的确切线上格式** | 只写「是否使用流式输出」 | 官方示例 5.4 与 bob 插件都按「整块二进制」消费 | **chunked 裸二进制，非 SSE**。**本期建议直接不开 stream**（`stream:false` 或干脆不传），等通话链路要低延迟时再单独实测 | 中 |
| 11 | 硅基**未实名认证**时调上传/list 的具体错误码与文案 | 只说「使用用户预置音色，需要进行实名认证」，没说违反时的返回 | 无 | 上传失败时把服务端 message **原文透传给用户**，并在 UI 附一句「硅基流动的音色克隆需先在官网完成实名认证」+ 官网链接。**不要自己编错误码判断** | 中 |
| 12 | MiMo **错误响应的 JSON body 形状** | 只给了状态码表（400/401/402/403/404/**421**/429/500/503），**没给 body** | 无（爱语的错误串看不出取值路径） | 猜 `{"error":{"message":...,"type":...,"code":...}}`（OpenAI 兼容惯例）。**取值三级兜底**：`error.message` → `message` → body 纯文本截断 300 字 | 中 |
| 13 | MiMo **是否需要 `modalities`** | **官方从未出现该字段**（OpenAI 兼容参考页全文 0 次） | 三个独立开源实现均不传 | **不传**。若某天真出现「返回里没有 audio」，第一嫌疑就是缺 `modalities`，可作为排障备选项试 `["text","audio"]`（🔴 未验证） | 高（不需要） |
| 14 | MiMo `voiceclone`/`voicedesign` 的**限流数值** | 模型列表页只给了 `mimo-v2.5-tts` 的 RPM 100 / TPM 10M，另两个模型同表未列 | 无 | 按 **RPM 100** 统一做客户端节流；429 一律指数退避（1s/2s/4s，最多 3 次）后放弃并提示 | 中 |
| 15 | MiMo `audio.data` 是否**带 `data:...;base64,` 前缀** | 官方示例直接 `b64decode(audio.data)` 写文件 → 暗示**纯 base64** | 爱语 dex 里有字面量 `data:audio/wav;base64,` 与正则 `data:[^"\s]+;base64,[A-Za-z0-9+/=]+` → 它**防御性剥前缀** | **解码前先剥前缀**（`if (s.contains(";base64,")) s = s.substringAfter(";base64,")`）+ 去掉所有 `\r\n` 空白。零成本防变体 | 高（当前无前缀）／剥前缀无风险 |
| 16 | MiMo `format: "wav"` 非流式返回的是**完整 WAV（带 RIFF 头）还是裸 PCM** | 官方说「Base64 编码音频，格式为请求中指定的格式」；示例直接把 decode 结果存成 `.wav` | `MiMoTTSReader` 非流式也是 decode 后直接当 wav 用；只有**流式 pcm16** 才手写 44 字节头 | 非流式 `wav` = **带头的完整 WAV**；流式 `pcm16` = **裸 PCM，需自己补头（24000Hz/mono/16bit）** | 中高 |
| 17 | MiMo **Token Plan 专属 Base URL** 的实际字符串 | 只说「以 Token Plan 页面提供的 Base URL 为准」，文档里没印 | 无 | **baseUrl 必须用户可填**，默认 `https://api.xiaomimimo.com/v1`；Key 以 `tp-` 开头时提示用户「Token Plan 需填专属 Base URL」 | 高（需可填这点） |
| 18 | MiMo `audio.format: "mp3"` 是否真的可用 | 官方 enum 里**有** `mp3`，但**所有示例都只用 `wav` / `pcm16`** | 无开源实现用 mp3 | **默认用 `wav`**（示例验证过）。想要 mp3 省体积可以试，但要能回退 wav。⚠️ 注意本项目 `Mp3Decoder` 只解 mp3，若走 wav 需另接 PCM 播放路径（`AudioTrack` 直喂即可） | 中 |
| 19 | 通用端点 `voice` 传**对象** `{"id":"..."}` 的兼容面 | OpenAI SDK 类型里明确支持 | new-api / VoiceHub / lobe-chat 的 DTO 都只按 `string` 处理 | **本期只发字符串 `voice`**；对象形态留作后续（若用户端点是 OpenAI 官方且用了自定义音色再加） | 中 |
| 20 | 硅基 `references[].audio` 传 **base64 时是否也要 `data:audio/mpeg;base64,` 前缀** | 只写「参考音频 url。**也支持 base64 格式**」；上传接口那节明确要求前缀 | 上传接口的 `audio` 字段要求带前缀 | 猜**同样要带前缀**（同一平台同一约定）。**本期不用动态音色**（走上传换 `uri`），真要用时先按带前缀实现 | 低 |

### 5.1 由上表直接推导出的**三条硬实现要求**

1. **成功/失败判据不能依赖 Content-Type**（第 2 条）。统一逻辑：
   `!isSuccessful` → 按文本/JSON 双路提取错误；`isSuccessful` → 读 bytes，再嗅探前 1 字节是否为 `{`，是则当错误 JSON 处理（防止 200 里塞错误体的网关）。
2. **所有厂商私有字段一律 `null` 即不出现在 JSON 里**（第 3、9 条 + new-api 的 `*float64` 指针做法）。
   Gson 默认就会跳过 null 字段；**别用 `JSONObject.put(k, null)`**（那会写出 `"k":null`，某些严格网关会 400）。
3. **`voice` 前缀必须运行时拼**：`val voice = if (voiceId.contains(":")) voiceId else "$model:$voiceId"`（第 §4.5 的 bug 教训）。
   已经带 `:` 的（克隆 `uri` 是 `speech:a:b:c`，也含 `:`）→ 更准确的判据是
   `if (voiceId.startsWith("speech:") || voiceId.startsWith("$model:")) voiceId else "$model:$voiceId"`。

---

<a id="6-附反编译参考-app爱语-295证据清单"></a>
## 6. 附：反编译参考 App（爱语 2.9.5）证据清单

> 全部标 🟡。**只能证明「有人这么实现且发布了」，不能当协议事实。**
> 证据文件：`C:\Users\16621\Desktop\爱语参考资料\aiyu_apk\classes.dex`（5.3MB，单 dex）、
> `aiyu_dex_strings.txt` / `aiyu_all_strings.txt` / `aiyu_cn_strings.txt` / `aiyu_res_strings.txt`。
> 该 App 包名 `com.shuoshuo.aiyu`，同样内置 sherpa-onnx（与本项目同源技术），并用 QuickJS 跑插件模块。

### 6.1 三家端点与模型（dex 串逐字）

```
https://api.siliconflow.cn/v1/audio/speech      ← 硅基 TTS
https://api.siliconflow.cn/v1/chat/completions  ← 硅基 LLM
https://api.siliconflow.cn/v1/                  ← 硅基 base
https://api.xiaomimimo.com/v1/chat/completions  ← MiMo（确认是 chat/completions，不是 /audio/speech）
https://api.xxx.com/v1/audio/speech             ← 自定义 TTS 的占位示例（UI 提示文案）
https://voice.gbkkov.cn/                        ← 死域名，见 §5 第 1 条
FunAudioLLM/CosyVoice2-0.5B                     ← 硅基默认模型
mimo-v2.5-tts / mimo-v2.5-tts-voiceclone / mimo-v2.5-tts-voicedesign  ← MiMo 三个模型 ID
Qwen/Qwen3-Omni-30B-A3B-Instruct                ← 另一个多模态模型（非 TTS 链路）
```

> ✅ **`api.xiaomimimo.com` 是真实主机**，与小米官方文档完全一致（官方 SDK 示例的 `base_url` 就是它）。
> ✅ **MiMo 走 chat/completions** 这一点由「dex 串 + 官方文档 + 3 个开源实现」三方交叉确认，**已无疑问**。

### 6.2 SharedPreferences 键名 → 反推出的请求字段

**硅基（`key_sf_*`）**：`api_key`、`tts_model`、`voice_id`、`speed`、`gain`、`sample_rate`、`use_global_key`
→ 印证硅基请求体 = `model` + `input` + `voice` + `speed` + `gain` + `sample_rate`(+`response_format`)。
**与官方 API 参考的字段集完全吻合**（🟢 交叉验证通过）。

**MiMo（`key_mimo_*`）**：`api_key`、`tts_model`、`voice_id`、`audio_format`、`clone_audio_path`、`clone_audio_name`、`style_desc`、`optimize_text`
→ 印证 MiMo = `model` + `audio.voice` + `audio.format` + voiceclone 的**本地音频路径**（= base64 内联，**不是上传接口**）
+ voicedesign 的**风格描述**（= `user.content`）+ `optimize_text_preview`。
**与官方 API 参考逐项吻合**，且 `optimize_text` 这个键名对应官方的 `optimize_text_preview`（🟢 交叉验证通过）。

**自定义 TTS（`key_custom_tts_*`）**：`url`、`api_key`、`model`、`voice_id`、`params`
另有 `KEY_VOICE_FIELD_NAME` / `voice_field_name` 与 `KEY_AUDIO_INPUT_FIELD_NAME` / `audio_input_field_name`
→ **它把「voice 字段名」「input 字段名」都做成了可配置**，并有「可添加任意 JSON 参数注入自定义 TTS 请求体」的 UI 文案。
> 💡 **这是对付「通用 OpenAI 兼容端点各家字段名不统一」的成熟做法**，值得本项目借鉴：
> 至少在高级设置里留一个「额外 JSON 参数」自由文本框（爱语的做法），比穷举字段名更耐用。

### 6.3 MiMo 响应解析路径（错误串逐字反推）

```
MiMo 响应体为空
MiMo 响应无 choices
MiMo 响应无 message.audio        ← 说明它取的是 message.audio
MiMo 响应 audio.data 为空        ← 说明它取的是 audio.data
MiMo 响应里取不到音频数据
解析 MiMo 响应失败
```
配合 dex 里的字段串 `message.audio`、`audio.data`、`data:audio/wav;base64,`、正则 `data:[^"\s]+;base64,[A-Za-z0-9+/=]+`、变量名 `$base64Audio`
→ **完整确认 MiMo 音频路径 = `choices[0].message.audio.data`，base64，且它会剥 data-URI 前缀**。
与 🟢 官方 API 参考**完全一致**。（这正是任务描述里那句错误串「MiMo 响应里取不到音频数据」的出处，假设成立。）

### 6.4 克隆流程的 UI 文案（逐字）

```
音频克隆 / 音频克隆 — 上传音频样本克隆音色
文本生成音色 / 文本生成音色 — 用自然语言描述生成自定义音色
预置音色 / 预置音色 — 9 个内置音色            ← ⚠️ 这条串没带厂商名，无法判定是硅基还是 MiMo 的。
                                                 MiMo 官方恰好 9 个预置音色、硅基官方恰好 8 个；
                                                 若是硅基侧，多的 1 个大概率是它自加的「自定义/克隆」占位项。
                                                 别拿它当任何一家的音色数量依据。
没有自定义音色 / 前往添加>>
自定义音色ID (Voice) / 音色 ID / TTS音色ID
设置 MiMo TTS 专属音色 / 设置专属语音音色 / 留空则使用全局默认音色
voiceclone 音频样本路径为空
voiceclone 音频样本文件不存在:
voiceclone 音频样本超过 10MB 限制:            ← 10MB = MiMo 的 base64 上限（🟢 官方一致）
可能 voiceclone 音频样本读不到
音频文件超过 10MB 限制
正在截取前15秒音频... / 用户选择(前15秒)      ← 15s 是它自定的裁剪值，非协议约束
支持 mp3/wav                                  ← 与 MiMo voiceclone「仅支持 mp3 和 wav」一致 🟢
音频Base64大小: / 音频数据大小: / 音频格式: / 采样率: / 语速: / 风格描述: / 可选风格描述
获取音频时长失败 / 音频截取失败 / ❌ 原生音频截取失败 可能是不支持的格式(如VBR MP3)
```

> **关键推论**：`voiceclone` 相关串**全部**与 `mimo-v2.5-tts-voiceclone` 的 base64 内联模型对得上（10MB / mp3+wav / base64 大小 / 15s 裁剪）。
> **dex 里找不到任何 `/v1/uploads` 或 `purpose` 字符串** → 爱语**没有实现硅基的音色克隆上传接口**。
> 它给硅基克隆的做法是：**让用户去硅基流动官网控制台自己导入音色，然后把得到的 voice ID 手填进「自定义音色ID (Voice)」**
> —— 这正是随包教程视频 `爱语下载跟设定/爱语使用教程/硅基流动语音音色导入教程.mp4` 的内容（该视频存在本身即证据）。
> 随包的 8 个 MP3 样本（`Cartethyia.mp3` / `Changli.mp3` / `LinWanXia*.mp3` / `Yinlin*.mp3` / `HuiLiYi*.mp3` / `古月方源1.mp3` / `-hnqinjqj16lqg920.mp3`）就是给用户拿去官网导入用的。
>
> 👉 **对本项目的含义**：`v0.50.0` 要做的「App 内直接调 `/v1/uploads/audio/voice` 完成克隆」
> **比爱语更进一步，没有现成同构实现可抄**，只有 §4.3 / §4.4 两个非 Kotlin 参考。
> 按 AGENTS.md 零.3 —— 这块**实现路线有歧义（尤其第 5 条 `text` 从哪来），动手前应先向用户确认方向**：
> - 方向 A：**App 内全自动**（选音频 → 端侧 SenseVoice 自动转写出 `text` → multipart 上传 → 存 `uri`）。体验最好，但依赖离线 ASR 已就绪 + 用户已实名认证。
> - 方向 B：**只填 voice ID**（照爱语，用户去官网导入后回填 `speech:...` 串）。零风险、一天能做完，但用户要跳出 App。
> - 方向 C：**A + B 并存**（默认 A，A 失败/未认证时露出 B 的手填入口）。工作量最大，兜底最稳。

### 6.5 其他值得注意的爱语实现细节

- `PRESET_VOICES`、`MODEL_VOICE_CLONE`、`MODEL_VOICE_DESIGN` 三个常量 → 它把「预置音色 / 音频克隆 / 文本生成音色」做成**三选一的合成方式**（UI 文案「合成方式:」），与 MiMo 三模型一一对应。**这个信息架构可以直接抄**，比「先选厂商再选模型」更好懂。
- `TTS_ACCELERATE` / `TTS请求加速` + 文案「若频繁报错429，请关闭该选项」→ 它做了**并发预合成**（`KEY_TTS_MAX_CONCURRENCY`、`ttsSemaphore`、`ttsCurrentConcurrency`）。**注意 MiMo RPM 上限只有 100**，本项目若做预合成必须带并发闸门 + 429 退避。
- `TTS_BRACKET_REMOVE` / `TTS_MARKDOWN_REMOVE` → 合成前**去括号内容 / 去 Markdown 标记**。本项目已有 `MarkdownText`，TTS 前需要一份纯文本清洗（去 `**`、去 `#`、去 `【语音条】` 标记本身）。
- `b64_json` 串存在 → 那是它生图链路用的，**与 TTS 无关**，别混淆。
- 音频格式串只有 `audio/mpeg`、`audio/wav`、`audio/`、`audio/*` → 它 TTS 产物只落 mp3 / wav 两种。

---

## 7. 一页速查（贴代码旁边用）

| | 硅基流动 | 小米 MiMo | 通用 OpenAI 兼容 |
|---|---|---|---|
| Method+URL | `POST https://api.siliconflow.cn/v1/audio/speech` | `POST https://api.xiaomimimo.com/v1/chat/completions` | `POST {baseUrl}/audio/speech` |
| 鉴权头 | `Authorization: Bearer K` | **`api-key: K` 与 `Authorization: Bearer K` 都发** | `Authorization: Bearer K` |
| 文本放哪 | `input` | `messages[role=assistant].content` | `input` |
| 风格指令放哪 | `input` 里 + `<\|endofprompt\|>` | `messages[role=user].content` | `instructions`（可选） |
| 音色字段 | `voice` = `"<model>:<alias>"` 或 `uri` | `audio.voice` = 预置ID / `data:<mime>;base64,<b64>` | `voice` = string |
| 格式字段 | `response_format`: mp3/opus/wav/pcm（默认 mp3） | `audio.format`: wav/mp3/pcm/pcm16（默认 wav；stream 时 pcm） | `response_format`: mp3/opus/aac/flac/wav/pcm |
| 语速 | `speed` 0.25~4.0 | **无字段** → 写进 user 指令 | `speed` 0.25~4.0 |
| 音量 | `gain` -10~10 dB | **无** | **无** |
| 采样率 | `sample_rate`（显式传，别靠默认） | **无**（流式固定 24000） | **无** |
| **响应** | **裸二进制字节** | **JSON → `choices[0].message.audio.data` (base64)** | **裸二进制字节** |
| 流式 | `stream:true` = chunked 二进制（非 SSE） | `stream:true` = SSE，`choices[0].delta.audio.data`，pcm16@24k 需自拼+补 WAV 头 | `stream_format: sse\|audio` |
| 克隆 | **两步**：`POST /v1/uploads/audio/voice`（multipart `file`+`model`+`customName`+`text`，或 JSON `audio` data-URI+`model`+`customName`+`text`）→ `{"uri":"speech:name:a:b"}` → 塞进 `voice`。需**实名认证** | **无上传接口**：每次请求把样本 base64（≤10MB 编码后、仅 mp3/wav、带 `data:<mime>;base64,` 前缀）内联进 `audio.voice`，model 换 `mimo-v2.5-tts-voiceclone` | 端点自定，无统一约定 |
| 音色管理 | `GET /v1/audio/voice/list`（键名 `results`/`result`/`data` 全兜）；`POST /v1/audio/voice/deletions` body `{"uri":...}` | **无** | 无 |
| 测试连接探针 | `GET /v1/audio/voice/list`（轻量、无副作用） | 发一句最短文本合成 | 同左 |
| 错误体 | 400/429/503 = JSON `{code,message,data}`；**401/403/404 = 裸字符串** | 🔴 未公开；三级兜底 `error.message`→`message`→纯文本；**421 = 内容审核拦截，别重试** | `{"error":{message,type,param,code}}` |
| 建议超时 | connect 30s / read **120s** | connect 30s / read **120s**（克隆请求体大，write 也要放宽） | 同左 |

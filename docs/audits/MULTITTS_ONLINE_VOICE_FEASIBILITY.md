# MultiTTS 在线音色脱离外部引擎的可行性审计

审计日期：2026-11-09。目标项目：`D:\project\legado_NG`；分支：`codex/migrate-legado-features`。本次为接入前审计，未迁入生产模块，未改动应用 TTS 执行代码。

## 1. 结论与适用范围

**对用户提供的这份讯飞备份，独立合成的关键路径已抽样验证成功，可以按“解析音色 → 生成 NG SCRIPT → 复用现有多角色”实施。** 不需要引入 LegadoTts，也不需要安装或启动 MultiTTS。仍需要讯飞在线服务；导入音色描述不等于把云端合成模型下载到本地。

最终审计原型使用 NG 仓库实际使用的 HtmlUnit JS JAR、JDK 标准 JCA 和缓存中的 Hutool/Gson，六个真实发音请求组合均返回可解析、非静音的 PCM WAV。覆盖普通数字参数、`@style` 默认值/其他候选，以及 16 kHz 和 24 kHz 输出。请求未使用账号凭据、登录 Cookie、外部本地转发服务或伪造转发 IP。

证据只覆盖本备份及抽样请求。**尚未证明 189 条全部在线可用，尚未完成 NG Android 应用内导入/试听/多角色播放。** 主机网络成功不能证明手机网络可用；有效音频与 vid 正确也不等于已人工听辨音色身份、自然度或情绪效果。

代码审计还发现签名请求取消上下文缺口，必须作为实施验收项，不能承诺“只新增 lib，宿主一行不改，全部原行为已验证”。当前角色识别/选角算法和播放框架无需因该提供方重建。

完整实施设计见 [主迁移方案第 4 节](../LEGADO_FEATURE_MODULE_MIGRATION_PLAN.md#4-multitts-在线音色导入与现有-ng-引擎适配)。

### 1.1 另外两类采用相同接入方案

根据源项目 `OnlineTtsBackendType`，除讯飞 XFPEIYIN 外的两类为 HUOSHAN 和 DIRECT_HTTP，均已纳入主方案第 4.5.2、4.5.3、4.8 节，沿用同一 lib、导入桥和 NG 现有 SCRIPT / 多角色流程。

| 类型 | 本轮核对内容 | 尚待验证 |
| --- | --- | --- |
| HUOSHAN | 源实现使用 POST language/speaker/text，检查 base_resp，解码 audio.data；NG 有 JSON/Base64 提取路径，但需补可选业务状态检查 | 实际备份、有效 speaker、接口当前可用性、返回字节、Android 播放/角色流程 |
| DIRECT_HTTP | 源实现使用受限 URL 占位符和 GET；已记录原始 code、参数中值、URL 编码及残留占位符处理 | 具体远端服务、模板和音色样本；该类不存在一个可代表所有模板的统一在线结果 |

两类目前是代码核对与接入设计，**没有套用讯飞六个样本的成功结论，也未新增在线实测**。三类模板分别负责协议和响应，共用目录及绑定机制；现有多角色一次选择一个 SCRIPT 引擎的约束继续保留。

## 2. 实际输入与解析结果

输入：`C:\Users\Administrator\Downloads\xfpeiyin(1).zip`。

ZIP SHA-256：`98e901f3e6c03fafc4791f0153e4946bc5437265747687835259adf605c9c2d7`。

通过 ZIP 流读取 YAML，未将备份整体解压或改写。审计使用 SnakeYAML SafeConstructor，剥离已知的 MultiTTS 数据标签，不实例化备份中指定的 Java 类。

| 项目 | 结果 |
| --- | --- |
| engines.yaml | 一个引擎：code=xfpeiyin，名称“讯飞配音”；未提供可直接使用的合成 URL/脚本 |
| config.yaml | 一个 xfpeiyin 分组，189 条 Speaker |
| 直接数字 param | 183 条 |
| `@style` 引用 | 6 条，全部解析出默认 vid |
| candidate 风格选项 | 15 项，编号均为数字 |
| 未解析编号 / 重复 speaker code | 0 / 0 |
| 性别 | gender=1：78；gender=0：111 |
| 声明采样率 | 16000：109；24000：80 |
| type | 189 条均为 1；不以此字段单独判定可发声 |
| 语速倍数 | 1.2：142；1.0：3；1.5：44 |
| 音量 / 音调 | 均为 1.0 |
| 头像引用 | 179 条；头像不参与合成可行性判断 |
| 重复显示名 | 31 组 |
| 默认 vid 去重后 | 167 个，22 组默认 vid 各出现两次 |
| 包含风格候选的不同 vid | 176 个 |

189 条是描述记录数量，不能称为 189 种互不相同的音色。名称相同可能参数/风格不同；默认 vid 相同也可能 speed 不同，不能简单按姓名或 vid 丢弃备份记录。

### 2.1 风格引用实况

| speaker code | 默认 vid | 可选 vid 与名称 |
| --- | --- | --- |
| tianming | 130097 | 130005 默认2；130097 聆天明 |
| lingfeihong | 130115 | 130115 纪录片；130098 叙述 |
| xinyao | 130272 | 130270 直播；130271 教培；130272 情感 |
| xinyue | 130218 | 130254 口播；130218 心灵鸡汤；130217 直播 |
| tianming_16 | 20079 | 65271 生气；65272 悲伤；20079 叙述 |
| xiaolu_16 | 130009 | 130009 可爱；20070 语音助手 |

备份既有 `extendUI: |-` 多行块，也有单引号包裹并折行的 JSON 字符串。按行扫描且忽略 YAML 折行，会漏掉实际风格。

## 3. 独立在线合成的实测

协议参考：[源项目 OnlineTtsBackendRegistry.kt](D:/project/legado/app/src/main/java/io/legado/app/service/OnlineTtsBackendRegistry.kt:223)。审计原型没有调用该 Kotlin 单例，只参考协议并在 JS 中重新表达请求。

```text
文本 → MD5 → JSON → AES/ECB/PKCS5Padding → Base64
    → POST works_synth_sign
    → 解密 body，取得 time_stamp / sign_text
    → GET synth，带 vid / speed / volume / content
    → 校验响应、读取 WAV 帧与非静音采样
```

最终原型做了一次签名通路预检，再做下表六次音频请求。签名及音频状态均为 HTTP 200；不是仅凭状态码判定成功，下载结果另用 Python 标准库 wave 解析帧数据，检查采样率、位深、声道、时长和采样能量。

| 备份音色 / 风格 | 最终 vid | 字节数 | 实际音频 | 时长 | RMS |
| --- | --- | --- | --- | --- | --- |
| 男童-宁宁 | 130166 | 195244 | 24 kHz，单声道，16-bit PCM WAV | 4.067 秒 | 4395.47 |
| 女童-小芳 | 130025 | 160684 | 24 kHz，单声道，16-bit PCM WAV | 3.347 秒 | 6731.27 |
| 男青年-聆天明，默认 | 130097 | 137644 | 24 kHz，单声道，16-bit PCM WAV | 2.867 秒 | 2679.88 |
| 聆天明，默认2 | 130005 | 128044 | 24 kHz，单声道，16-bit PCM WAV | 2.667 秒 | 3935.93 |
| 男青年-楚哥 | 523064535 | 85804 | 16 kHz，单声道，16-bit PCM WAV | 2.680 秒 | 3341.08 |
| 天明，生气 | 65271 | 73004 | 16 kHz，单声道，16-bit PCM WAV | 2.280 秒 | 2835.05 |

测试文本为“你好，这是一段在线音色验证。”。文件哈希、帧数及能量结果保存在本地忽略目录 `build/tts-multitts-audit/audio-summary.json`；音频不提交仓库。上表表明有有效音频和不同的风格请求结果，不作听感质量或服务稳定性的保证。

### 3.1 类型误标必须处理

六份响应头均标注 `audio/mpeg`，实际以 `RIFF...WAVE` 开头。NG [requireAudioStream](D:/project/legado_NG/app/src/main/java/io/legado/app/help/tts/TtsScriptEngineClient.kt:388) 根据配置正则检查响应类型；若照默认 `audio/x-wav` 配置，会在取得有效音频后仍被拒绝。

方案：生成脚本返回 `audioContentType: 'audio/(mpeg|wav|x-wav)'`，让既有检查接受本次观察到的头；播放器仍按真实内容解析。审计原型已补充此字段，Android Media3 的实际播放仍待验收，不能以桌面 wave 解析代替。

### 3.2 JS 执行与加密依赖范围

离线检查在实际 vendored JAR `third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.4-android21.1/htmlunit-core-js-5.3.0-legado.4-android21.1.jar` 中执行。覆盖 189 个默认请求及 15 个风格请求，共 204 个组合，签名响应在离线模式中使用可控密文，验证解析与请求构造，不将其记为 204 次在线验证。

为避免桌面加密提供器差异，最终原型用标准 `Cipher` 加显式 AES `SecretKeySpec`，不要求新增 BC 依赖；Base64 来自已有 Hutool。脚本先保存 NG `java` 宿主对象引用，再访问 `Packages.java` 下的标准类，避免裸 JS 环境中的全局名称碰撞。

类策略静态审查：[RhinoClassShutter.kt](D:/project/legado_NG/modules/rhino/src/main/java/com/script/rhino/RhinoClassShutter.kt:276)。本次使用的标准加密类不在当前拒绝清单内。**隔离审计未完整运行 Android 的 RhinoContext、WrapFactory、ScriptBindings、共享作用域与 AnalyzeUrl**，这些仍是集成验收范围；不会为通过测试而放宽策略。

## 4. 为什么能复用现有多角色

| 当前拥有者 | 观察到的契约 | 接入含义 |
| --- | --- | --- |
| [TtsEngineModels.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/help/tts/TtsEngineModels.kt:9) | 类型仅 SYSTEM / SCRIPT；TtsVoice 保存 extra 和风格数据 | 输出真正 SCRIPT；不新增 NATIVE 或原生伪脚本 |
| [TtsEngineStore.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/help/tts/TtsEngineStore.kt:655) | 引擎导入要求 SCRIPT 且 script 非空 | YAML 不能原样导入，模块必须生成脚本和配置 |
| [TtsHttpForwarderClient.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/help/tts/TtsHttpForwarderClient.kt:149) | 音色解析保留 gender、language、extra | 提供方编号和风格可进入现有目录；此处只是解析器复用，不表示调用外部转发器 |
| [BookTtsCastingCoordinator.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/help/tts/BookTtsCastingCoordinator.kt:1432) | 接受启用 SCRIPT，候选取该引擎 enabledVoices | 一个讯飞 SCRIPT 可进入手动/自动选音，无需重写人物算法 |
| [ReadAloudTtsRouter.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/help/tts/ReadAloudTtsRouter.kt:387) | 非旁白绑定必须匹配当前 multiRoleTtsEngineId | 当前单提供方符合；不能承诺各角色任意跨独立引擎混用 |
| [TtsScriptEngineClient.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/help/tts/TtsScriptEngineClient.kt:476) | synthesize 的 voice 参数带 extra、style_value | 可将 NG 选定风格落到实际 vid |
| [ReadAloud.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/model/ReadAloud.kt:50) | SCRIPT 进入现有 HttpReadAloudService | 沿现有缓存、预生成、队列和播放器执行 |

当前仓库的 [multitts_forwarder.js](D:/project/legado_NG/app/src/main/assets/defaultData/tts/multitts_forwarder.js:1) 请求本地 8774 服务，不能拿它满足“不依赖 MultiTTS”的要求。数字 vid 也不能改名冒充微软音色；需要本审计验证的讯飞协议。

## 5. 实施前确认的风险与最小处理

| 问题 | 源代码证据 / 原因 | 方案与验收 |
| --- | --- | --- |
| 签名请求取消未贯通 | TtsScriptEngineClient 收到 coroutineContext，但 prepare/callEngineFunction 不传；BaseSource.evalJS 不显式带上下文；JsExtensions.ajax 从 Rhino 取 context，缺失时为空 | TTS 求值边界传入当前 Job，复用已有 RhinoScriptEngine.eval 的 context 重载；分别测签名阶段/音频阶段取消、停止后不入队 |
| 有效音频被类型检查拒绝 | 响应声明 MPEG，实际 WAV | 生成配置使用兼容正则；验证 Preview/正文两条实际播放链 |
| 参数更新后旧缓存 | audioCacheKey 含 script/options，不直接含 voice.extra | 目录/参数哈希嵌入生成脚本版本；同 ID 改 vid、倍速、风格后应生成新缓存身份 |
| 风格仅显示未生效 | param=@style 不能直接作为 vid | 默认 value 与 styles 保留；script 读取 style_value，真实请求 vid 应改变 |
| 数字性别导致候选匹配错误 | NG 使用规范性别文本 | 映射 1→male、0→female；以现有角色性别筛选回归 |
| 更新目录删掉旧条目/绑定 | upsertVoiceList 内部调用 replaceForEngine | 导入层先定义合并/替换与别名保留，再一次性写目录；复用现有冲突操作，不覆盖内置引擎 |
| 新音色刷新/恢复后消失 | runtimeVoices 不进入配置保存 | 目录进入静态 voices / 本地 voices() 和可导出配置；验证清缓存及恢复 |
| 重复真实音色影响角色差异 | 189 条中仅 167 个默认 vid | 不按名称丢弃；保留参数变体，标注重复，完全等价项可用既有禁用机制管理 |
| 提供方不可用或协议变动 | 本次验证为当前网页协议、有限样本 | 区分导入成功、协议支持、待验证、上次成功/失败；未知提供方不进入“已支持”候选 |

取消缺口的直接证据：

- [executeSynthesis / prepareSynthesisRequest](D:/project/legado_NG/app/src/main/java/io/legado/app/help/tts/TtsScriptEngineClient.kt:160)。
- [BaseSource.evalJS](D:/project/legado_NG/app/src/main/java/io/legado/app/data/entities/BaseSource.kt:328)。
- [JsExtensions.context / ajax](D:/project/legado_NG/app/src/main/java/io/legado/app/help/JsExtensions.kt:115)。
- [RhinoScriptEngine.eval 的上下文入口](D:/project/legado_NG/modules/rhino/src/main/java/com/script/rhino/RhinoScriptEngine.kt:61)。

这不是认定 NG 所有脚本均无法取消，而是目前调用没有显式贯通同一个 Job，不能依赖偶然已有的线程上下文满足新协议的停止语义。

参数处理仅声明真实支持项：脚本头写 synthesis_speed、synthesis_volume、style_tags；不声明未实现的 synthesis_pitch 或任意连续情绪。NG 以 50 为参数中值，不能直接套旧引擎以 100 为中值的比例；本地播放倍速仍由 NG 原策略控制。

## 6. 已执行与未执行的验证

| 验证 | 状态 | 能证明什么 |
| --- | --- | --- |
| 用户 ZIP 完整解析 | 通过，189 条 / 6 个风格引用 / 15 个候选 | 本样本的字段和编号可解释；不代表未来实现已完成 |
| 实际 JS JAR + mock 签名 | 通过，204 个默认/风格请求组合 | 脚本和解析后的目录能构造对应请求 |
| 不使用外部应用的在线签名/合成 | 通过，最终六个音频组合 | 当前桌面网络与协议可独立取得真实音频 |
| WAV 解码/时长/采样能量 | 通过，六份 | 音频为有效 PCM WAV，非空且非静音 |
| 全部 176 个不同 vid 在线可用性 | 未执行 | 不把样本抽检扩大为全目录可用 |
| NG 导入/保存/目录刷新/导出恢复 | 尚未实施 | 只有契约与代码审查，需后续集成回归 |
| NG 手动/自动/AI 选音和多角色交替播放 | 未执行 | 入口满足结构条件，实际业务表现未验证 |
| 签名取消/Android 网络/Media3/音色听辨 | 未执行 | 必须纳入实施验收 |
| 既有 Android 单元测试 | 尝试但未跑到测试执行 | 不记为通过，见下文环境限制 |

尝试执行现有 TtsHttpForwarderClientTest、ReadAloudTtsRouterTest、BookTtsCastingCoordinatorTest、TtsSpeedPolicyTest：离线构建缺少 Robolectric、AndroidX 测试和部分 JUnit 依赖，卡在单测 Manifest 处理；另一次联网尝试因 app/build.gradle 第 24 行计算 Git 提交数取得空字符串而在配置阶段中止。没有为本次审计修改现有构建文件，也没有执行 APK 打包、安装或设备操作。

初次受限网络请求被沙箱拒绝，申请网络执行权限后重试成功。因此最初网络拒绝不被记为讯飞服务失败。第一次桌面加密实验缺少额外提供器时暴露 Hutool 构造器差异；最终原型改用显式 AES JCA 路径，并在不增加 BC 的情况下重新完成 204 个离线组合和六个在线样本。

## 7. 可复现材料与运行方式

- [XfBackupAudit.java](multitts/XfBackupAudit.java)：安全读取实际 YAML、生成审计目录、驱动实际 JS JAR、mock / 在线签名与少量样本请求。
- [XfProtocolProbe.js](multitts/XfProtocolProbe.js)：审计用 xfpeiyin 协议原型，未注册为应用引擎；协议常量运行时从本地源项目读取，不输出签名值。
- [Run-Audit.ps1](multitts/Run-Audit.ps1)：使用本机已发现 JDK/JAR，依赖路径固定以重现本次环境；换机器需调整路径，不自动安装依赖。
- [Verify-Audio.py](multitts/Verify-Audio.py)：解析本地样本的 WAV 帧、时长与采样能量，写入 audio-summary.json。
- 本地结果：`build/tts-multitts-audit/backup-summary.json`、`voice-catalog.json`、`audio-summary.json` 及六份 `.audio`。这些为本地忽略产物，原始备份未复制入仓库。

在 `D:\project\legado_NG` 执行：

```powershell
# 离线：只读取本地备份，用可控签名响应验证脚本请求
& .\docs\audits\multitts\Run-Audit.ps1 `
  -Backup 'C:\Users\Administrator\Downloads\xfpeiyin(1).zip'

# 联网：发送少量固定测试文本；不会遍历全部音色
& .\docs\audits\multitts\Run-Audit.ps1 `
  -Backup 'C:\Users\Administrator\Downloads\xfpeiyin(1).zip' -Live

python .\docs\audits\multitts\Verify-Audio.py
```

Java 原型的网络桥使用 JDK HttpClient 模拟 NG java.ajax 的请求规则；不是直接运行 Android AnalyzeUrl。因此脚本原型是可行性证据和后续固定样本，不应未经集成验证直接当成生产实现交付。

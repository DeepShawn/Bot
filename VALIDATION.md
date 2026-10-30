# 构建与验证记录

生成时间：2026-10-30T02:10:56+00:00

- 版本：轻答 1.2.0（debug）；包名：dev.qingreply
- 系统：Android 8.0 / API 26 及以上；target API 35
- 构建：assembleDebug 通过
- 自动测试：32 项通过，0 失败，0 错误
- Android Lint：0 错误，9 条提示（依赖更新 / KTX 写法建议）
- APK 签名：apksigner 校验通过，APK Signature Scheme v2

| 测试类 | 通过 |
| --- | ---: |
| AiClientTest | 10 |
| AiProvidersTest | 4 |
| ConversationMemoryTest | 5 |
| MentionMatcherTest | 5 |
| NotificationSourceTest | 4 |
| ReplyGateTest | 4 |

覆盖重点：提供商切换密钥隔离、DeepSeek 请求、模型获取、每次注入 system、双开来源与去重冷却隔离、同群上下文隔离 / 去重 / 过期 / 长度上限。

APK SHA-256：

`634cee6e75b04138991b3ea819eb506ffd915ee42677bb1cae5b4f79ea70e235`

API 测试使用本地 MockWebServer，无真实密钥或真实模型调用。
自动回复通过原 QQ 通知的 RemoteInput 直接提交，不需要用户复制。小米双开通知可见性、QQ 回复入口与实际送达尚未真机验证，须按 README 检测。

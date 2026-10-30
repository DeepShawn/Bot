# 构建与验证记录

生成时间：2026-10-30T02:54:55+00:00

- 版本：轻答 1.3.1（调试签名 APK）；Android 8.0 / API 26 及以上
- assembleDebug：通过
- 自动测试：44 项通过，0 失败，0 错误
- Android Lint：0 错误；9 条警告，1 条信息提示
- APK 签名：apksigner v2 校验通过

| 测试类 | 通过 |
| --- | ---: |
| AiClientTest | 10 |
| AiProvidersTest | 4 |
| ChatUiPolicyTest | 7 |
| ConversationMemoryTest | 5 |
| MentionMatcherTest | 5 |
| NotificationSourceTest | 4 |
| ReplyActionPolicyTest | 5 |
| ReplyGateTest | 4 |

新增覆盖：无标题自由文本回复选择、语义回复优先、歧义操作拒绝、群名必须匹配页面顶部、聊天气泡不能充当群名、保护草稿、排除密码 / 搜索输入、发送按钮位置与状态。

APK SHA-256：

`92e97a04b4041b33036f59663740bbf762d10bf6c1bd77fa09aca0b2e2f9d536`

API 测试使用本地 MockWebServer；UI 选择策略为 JVM 测试，未在用户 QQ / HyperOS 手机实际执行无障碍发送。
用户截图已证实可读取双开用户 999 通知，但没有通知快捷回复入口；v1.3.0 允许此来源绑定并提供需单独授权的无障碍备用发送。
需在真实手机验证小号原会话入口、聊天页群名、可访问输入框 / 发送按钮及后台弹出权限。

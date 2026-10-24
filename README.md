# 值不值 · 安卓版

一个简洁的记账 app，Claude 在里面帮你记账、查账、判断值不值。

- **记账**：点中间的 ＋，数字键盘输金额，选分类，记一笔
- **Claude 助手**：直接说"午饭 18，奶茶 15"，它会帮你把这两笔记进去；也可以问"这个月吃饭花了多少""299 的耳机值吗"
- **值不值**：记账时点「值不值？」，Claude 会结合你这个月的花销和预算盖章判断
- **付款后自动提醒**：开启后，微信支付、支付宝的付款通知一到，app 就弹提醒让你记一笔
- **统计**：分类环形图、每日支出、Claude 月度点评
- **导入账单**：微信 / 支付宝导出的 .csv 或 .xlsx
- **备份**：导出成 .json 文件，换手机后导入恢复

账本只存在手机上。只有用 Claude 功能时，相关的账目摘要会发给 Anthropic 的 API。

---

## 一、打包成 APK

1. 在电脑上安装 **Android Studio**（官网 developer.android.com/studio，国内也可以从 developer.android.google.cn/studio 下载）。
2. 解压这个文件夹，打开 Android Studio → **Open** → 选 `ZhiBuZhi` 文件夹。
3. 第一次打开会自动下载 Gradle 和依赖（几分钟到十几分钟）。等右下角进度条走完、没有红色报错。
   - 如果提示要升级 Android Gradle Plugin，可以点同意，也可以忽略。
   - 如果提示缺少 SDK，按提示点 Install 装上 **Android SDK 34**。
4. 菜单 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。
5. 完成后右下角弹出提示，点 **locate**，找到 `app-debug.apk`。
6. 把 apk 发到手机（数据线、微信文件传输助手都行），点开安装。手机会提示"允许安装未知来源应用"，允许即可。

也可以用数据线连手机、在手机上打开「开发者选项 → USB 调试」，然后在 Android Studio 里点绿色的 ▶ 直接装到手机上。

### Gradle 下载很慢或失败？

`settings.gradle.kts` 里已经把阿里云的镜像放在前面了。如果卡在下载 Gradle 本身，可以把
`gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成腾讯云镜像：

```
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
```

---

## 二、填 API key

1. 去 **console.anthropic.com** 注册，在 **API Keys** 里创建一个 key（以 `sk-ant-` 开头）。
2. 在 **Billing** 里充值。API 按用量付费，和 Claude 网页版的会员是分开的。
3. 打开 app →「我的」→ 粘贴 key → 保存。

模型可以在「我的」里切换：

| 模型 | 说明 |
| --- | --- |
| Haiku 5.5（默认） | 最快最省。记一笔、问一次值不值，大约不到 1 分钱人民币 |
| Sonnet 5.5 | 更聪明，价格约是 Haiku 的 20 倍 |

注意：Anthropic 的 API 只在它支持的国家和地区提供。如果一直提示"连不上 Claude 的服务器"，多半是网络或地区原因。

key 只存在这台手机的 app 私有空间里，不会备份、不会上传到别处。

---

## 三、开启"付款后自动提醒"

1. 「我的」→「付款后自动提醒」→ 去开启。
2. 在系统的「通知使用权 / 通知读取权限」页面里，找到 **值不值 · 付款识别**，打开。
3. 回到 app，按提示允许发送通知。

之后用微信或支付宝付款，收到付款通知时，值不值会弹一条"刚刚用微信付了 ¥xx"，点一下就能记账。明细页顶部也会列出待记的付款。

说明：
- 只读取 **微信** 和 **支付宝** 的通知，并且微信里只认"微信支付"发来的，聊天消息不会被当成付款。
- 识别靠通知里的文字，所以前提是你手机上能收到这两个 app 的付款通知。
- 部分国产系统会在后台杀掉通知服务。如果不灵了，在系统设置里把值不值设为「允许后台运行 / 不受电池优化限制」。
- 系统不允许任何 app 在你付款**之前**拦下来，所以这里做的是付款**之后**立刻提醒。想在买之前问，就在记账时点「值不值？」或者去 Claude 页问。

---

## 四、导入微信 / 支付宝账单

- **微信**：「我 → 服务 → 钱包 → 账单」→ 下载账单 → 用于个人对账，账单发到邮箱。
- **支付宝**：「我的 → 账单」右上角 → 开具交易流水证明 → 用于个人对账。
- 邮件里是压缩包，解压密码在微信 / 支付宝的消息里。解压后在 app「我的 → 导入」里选 .csv 或 .xlsx。

读 .xlsx 需要联网加载一个解析库。想离线使用，可以下载 SheetJS 的 `xlsx.full.min.js`（0.18.5 版），放进 `app/src/main/assets/` 再重新打包。

---

## 文件说明

```
app/src/main/
├── assets/index.html            界面和全部前端逻辑（记账、统计、Claude、导入）
├── java/com/zhibuzhi/app/
│   ├── MainActivity.java        WebView 外壳：存账本、存 key、调 Claude API、选文件、导出备份
│   └── PayListener.java         读取微信 / 支付宝付款通知，弹出记账提醒
├── res/                         图标、颜色、主题
└── AndroidManifest.xml
```

想改界面、分类、提示词，改 `assets/index.html` 就行，改完重新 Build APK。
调 API 的地址、超时在 `MainActivity.java` 顶部。识别付款的关键词在 `PayListener.java` 顶部。

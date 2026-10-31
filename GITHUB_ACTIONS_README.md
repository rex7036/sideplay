# GitHub Actions APK 构建 - 配置总结

## 📌 关键信息

你的项目 [.github/workflows/apk.yml](.github/workflows/apk.yml) 已配置好，需要以下两个 GitHub Secrets：

| Secret | 用途 | 内容 |
|--------|------|------|
| **STORE** | APK 签名密钥库 | Base64 编码的 `upload.jks` 文件 |
| **LOCAL** | Gradle 配置文件 | Base64 编码的 `local.properties` 文件 |

---

## 🚀 三步快速配置

### Step 1️⃣ : 本地配置（2分钟）

**Linux/macOS 用户**：
```bash
bash setup_github_secrets.sh
# 选择 "8) 完整设置流程" 自动完成所有步骤
```

**Windows 用户**：
```cmd
setup_github_secrets.bat
# 按菜单提示完成各步骤
```

### Step 2️⃣ : 上传 Secrets 到 GitHub（2分钟）

1. 打开: https://github.com/YOUR_USERNAME/sideplay/settings/secrets/actions

2. 点击 **New repository secret**

3. 添加 `STORE` secret:
   - Name: `STORE`
   - Value: 粘贴 `upload.jks_base64.txt` 的内容

4. 添加 `LOCAL` secret:
   - Name: `LOCAL`
   - Value: 粘贴 `local.properties_base64.txt` 的内容

### Step 3️⃣ : 触发构建（1分钟）

**方法 A - 推送 release 分支**：
```bash
git tag v1.0.0
git push origin v1.0.0
```

**方法 B - GitHub Web UI 手动触发**：
1. 访问: https://github.com/YOUR_USERNAME/sideplay/actions
2. 选择: **Generate APK**
3. 点击: **Run workflow**
4. 输入版本: `v1.0.0`

---

## 📚 详细文档

| 文档 | 用途 | 何时查看 |
|------|------|---------|
| [GITHUB_ACTIONS_QUICK_START.md](GITHUB_ACTIONS_QUICK_START.md) | 快速参考 | 需要快速上手 |
| [GITHUB_ACTIONS_SETUP.md](GITHUB_ACTIONS_SETUP.md) | 完整指南 | 需要详细说明或排查问题 |

---

## ⚙️ 配置脚本

| 脚本 | 平台 | 用途 |
|------|------|------|
| [setup_github_secrets.sh](setup_github_secrets.sh) | Linux/macOS | 交互式配置工具 |
| [setup_github_secrets.bat](setup_github_secrets.bat) | Windows | 交互式配置工具 |

---

## 🔑 所需的密钥信息

配置前需要准备：

```
Keystore 信息：
  - 文件: upload.jks（或你自己的密钥库）
  - Store Password: __________ (记录下来)
  - Key Alias: sideplay
  - Key Password: __________ (记录下来)

local.properties 内容：
  RELEASE_STORE_FILE=upload.jks
  RELEASE_STORE_PASSWORD=<上面的 Store Password>
  RELEASE_KEY_ALIAS=sideplay
  RELEASE_KEY_PASSWORD=<上面的 Key Password>
```

---

## ✅ 配置检查清单

在触发构建前，确认以下事项：

- [ ] ✅ 生成了 Android Keystore（upload.jks）
- [ ] ✅ 创建了 local.properties 文件（包含签名密钥密码）
- [ ] ✅ 编码了两个文件为 Base64（无换行符）
- [ ] ✅ 在 GitHub Secrets 中添加了 STORE secret
- [ ] ✅ 在 GitHub Secrets 中添加了 LOCAL secret
- [ ] ✅ 验证了 Secrets 显示在 GitHub Settings 页面
- [ ] ✅ .gitignore 包含了 upload.jks 和 local.properties
- [ ] ✅ 本地没有提交这两个文件到 Git

---

## 🏗️ 工作流触发条件

你的工作流会在以下情况自动触发：

1. **推送到 release 分支**：
   ```bash
   git push origin release/1.0.0
   ```

2. **创建 Git 标签**（标签格式 v*.*.*）：
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

3. **手动触发**（GitHub Web UI）：
   - Actions → Generate APK → Run workflow

---

## 📱 构建输出

构建成功后，GitHub 会自动创建 Release，包含：

```
Release: v1.0.0
├── app-release.apk      ← 可直接安装的 APK
└── app-release.aab      ← Google Play 应用包
```

下载链接：
```
https://github.com/YOUR_USERNAME/sideplay/releases/tag/v1.0.0
```

---

## ⏱️ 预计时间

| 步骤 | 时间 |
|------|------|
| 本地配置（运行脚本）| 2-5 分钟 |
| GitHub Secrets 配置 | 1-2 分钟 |
| 首次构建 | 10-15 分钟 |
| 后续构建 | 5-10 分钟 |
| **总计（首次）** | **15-25 分钟** |

---

## 🔍 监控构建

### 实时日志

1. 进入: https://github.com/YOUR_USERNAME/sideplay/actions
2. 点击最新的工作流运行
3. 展开各个步骤查看详细日志

### 常见步骤

```
✅ Checkout                    (几秒)
✅ Set TAG                     (几秒)
✅ Use JDK                     (1-2 分钟)
✅ Build                       (8-12 分钟)  ← 最耗时
✅ Publish (GitHub)           (1-2 分钟)
```

---

## 🆘 遇到问题？

### 问题排查步骤

1. **查看错误日志**
   - Actions 页面 → 选择失败的运行 → 展开 Build 步骤

2. **最常见的错误**
   - `Base64 decode error` → 重新编码，确保无换行符
   - `Keystore was tampered with` → 检查密码是否正确
   - `Build failed` → 本地运行 `./gradlew clean build` 测试

3. **查看完整文档**
   - [GITHUB_ACTIONS_SETUP.md](GITHUB_ACTIONS_SETUP.md) 有完整的故障排查

---

## 💡 最佳实践

1. **定期备份 Keystore**
   ```bash
   cp upload.jks ~/backups/upload.jks.bak
   ```

2. **使用有意义的版本号**
   ```bash
   git tag v1.0.0  # 首个正式版
   git tag v1.0.1  # Bug 修复
   git tag v1.1.0  # 功能更新
   ```

3. **保护敏感信息**
   - ✅ Keystore 在 .gitignore 中
   - ✅ 密码只在 GitHub Secrets 中
   - ✅ 从不在日志中打印密码

4. **测试构建**
   ```bash
   # 本地测试，确保能成功构建
   ./gradlew assembleRelease
   ```

---

## 📞 获取帮助

### 文档和脚本

```
项目根目录
├── GITHUB_ACTIONS_QUICK_START.md    ← 快速参考
├── GITHUB_ACTIONS_SETUP.md           ← 完整指南
├── setup_github_secrets.sh           ← Linux/macOS 脚本
├── setup_github_secrets.bat          ← Windows 脚本
└── .github/workflows/apk.yml         ← 工作流定义
```

### 有用的链接

- 🔗 [GitHub Actions 文档](https://docs.github.com/actions)
- 🔗 [Secrets 管理指南](https://docs.github.com/actions/security-guides/encrypted-secrets)
- 🔗 [Android 签名文档](https://developer.android.com/studio/publish/app-signing)

---

## 🎯 下一步

1. **现在就开始**：
   ```bash
   bash setup_github_secrets.sh  # 或 setup_github_secrets.bat (Windows)
   ```

2. **按步骤完成**：
   - 生成 Keystore
   - 生成 local.properties
   - 编码为 Base64
   - 在 GitHub 添加 Secrets

3. **触发第一个构建**：
   ```bash
   git tag v1.0.0 && git push origin v1.0.0
   ```

4. **监控进度**：
   - 访问 GitHub Actions 页面
   - 等待构建完成
   - 从 Release 页面下载 APK

---

**祝你构建顺利！** 🚀

如有任何问题，请参考 [GITHUB_ACTIONS_SETUP.md](GITHUB_ACTIONS_SETUP.md) 的故障排查部分。

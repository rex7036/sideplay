# GitHub Actions APK 构建配置 - 快速参考

## 📋 目录

| 配置难度 | 主要步骤 |
|---------|--------|
| 🟢 简单 | 1. 准备签名密钥库 |
| 🟡 中等 | 2. 编码为 Base64 |
| 🟢 简单 | 3. 配置 GitHub Secrets |
| 🟢 简单 | 4. 触发构建 |

---

## ⚡ 快速开始（5分钟）

### Linux/macOS 用户

```bash
# 1. 运行交互式配置脚本
bash setup_github_secrets.sh

# 脚本会引导你完成所有步骤：
#   - 生成 Keystore
#   - 生成 local.properties
#   - 编码为 Base64
#   - 显示 GitHub Secrets 配置
```

### Windows 用户

```cmd
# 1. 运行批处理脚本
setup_github_secrets.bat

# 脚本会提供交互式菜单选择各步骤
```

---

## 🔑 所需的 Secrets

### STORE Secret

**用途**: Android APK 签名密钥库

**生成方法**:
```bash
# 1. 生成 keystore（如果还没有）
keytool -genkey -v -keystore upload.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias sideplay -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD \
  -dname "CN=Dev, O=YourOrg, C=CN"

# 2. 编码为 Base64（Linux/macOS）
base64 -i upload.jks | tr -d '\n' > keystore.b64

# 3. 编码为 Base64（Windows PowerShell）
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("upload.jks"))
$b64 | Set-Clipboard
```

### LOCAL Secret

**用途**: Gradle 本地配置（签名密钥密码）

**内容示例**:
```properties
RELEASE_STORE_FILE=upload.jks
RELEASE_STORE_PASSWORD=YOUR_STORE_PASSWORD
RELEASE_KEY_ALIAS=sideplay
RELEASE_KEY_PASSWORD=YOUR_KEY_PASSWORD
```

**生成方法**:
```bash
# 1. 创建 local.properties 文件
cat > local.properties << EOF
RELEASE_STORE_FILE=upload.jks
RELEASE_STORE_PASSWORD=你的keystore密码
RELEASE_KEY_ALIAS=sideplay
RELEASE_KEY_PASSWORD=你的key密码
EOF

# 2. 编码为 Base64（Linux/macOS）
base64 -i local.properties | tr -d '\n' > local.b64

# 3. 编码为 Base64（Windows PowerShell）
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("local.properties"))
$b64 | Set-Clipboard
```

---

## 📌 GitHub Secrets 配置步骤

### 1️⃣ 访问 GitHub Settings

```
https://github.com/YOUR_USERNAME/sideplay/settings/secrets/actions
```

### 2️⃣ 添加 STORE Secret

| 字段 | 值 |
|-----|-----|
| Name | `STORE` |
| Value | upload.jks Base64 编码后的内容 |

### 3️⃣ 添加 LOCAL Secret

| 字段 | 值 |
|-----|-----|
| Name | `LOCAL` |
| Value | local.properties Base64 编码后的内容 |

### 4️⃣ 验证配置

在 Secrets 页面应该看到：
- ✅ STORE
- ✅ LOCAL
- ✅ GITHUB_TOKEN（自动）

---

## 🚀 触发 APK 构建

### 方法 1: 推送到 release 分支（推荐）

```bash
# 创建并推送 release 分支
git checkout -b release/1.0.0
git push origin release/1.0.0
```

### 方法 2: 创建 Git 标签

```bash
# 创建标签
git tag v1.0.0
git push origin v1.0.0
```

### 方法 3: 手动触发（GitHub Web）

1. 打开: `https://github.com/YOUR_USERNAME/sideplay/actions`
2. 选择: **Generate APK** workflow
3. 点击: **Run workflow**
4. 输入: 版本号（如 `v1.0.0`）
5. 确认: **Run workflow**

---

## ✅ 监控构建进度

### 查看构建日志

1. 进入 **Actions** 标签页
2. 点击最新的工作流运行
3. 查看构建步骤和日志

### 构建完成后

自动创建 GitHub Release 包含：
- `app-release.apk` - 应用包
- `app-release.aab` - Play Store 包

---

## 📱 安装 APK

### 方法 1: 从 GitHub Release 下载

```bash
# 从 Release 页面下载后
adb install app-release.apk
```

### 方法 2: 使用 Android Studio

1. Build → Analyze APK
2. 选择 `app-release.apk`
3. 查看详细信息

---

## ⚠️ 常见问题

### Q: Base64 编码后包含换行符怎么办？

**A**: 一定要移除换行符：
```bash
base64 -i file | tr -d '\n'  # Linux/macOS
```

### Q: Keystore 密码错误怎么办？

**A**: 
1. 检查 local.properties 中的密码是否正确
2. 使用 keytool 验证 keystore：
```bash
keytool -list -v -keystore upload.jks
```

### Q: 构建超时怎么办？

**A**: 通常是网络问题
1. 检查网络连接
2. 本地运行 `./gradlew clean build` 测试
3. 重新触发工作流

### Q: 如何更新签名密钥？

**A**: 如果需要更新密钥：
1. 生成新的 keystore
2. 重新编码为 Base64
3. 更新 GitHub Secrets
4. 重新构建

---

## 🔒 安全检查清单

- [ ] ✅ Keystore 文件不在 Git 中
- [ ] ✅ local.properties 不在 Git 中
- [ ] ✅ `.gitignore` 包含这两个文件
- [ ] ✅ Secrets 已正确配置在 GitHub
- [ ] ✅ Base64 编码正确（无换行符）
- [ ] ✅ 密码没有包含特殊字符或已正确转义
- [ ] ✅ Keystore 有备份副本
- [ ] ✅ Keystore 密码已安全保管

---

## 📞 需要帮助？

### 完整文档
- 详细指南: [GITHUB_ACTIONS_SETUP.md](GITHUB_ACTIONS_SETUP.md)
- 工作流定义: [.github/workflows/apk.yml](.github/workflows/apk.yml)

### 有用的命令

```bash
# 验证 Keystore
keytool -list -v -keystore upload.jks

# 检查 Base64 内容（前100字符）
head -c 100 keystore.b64

# 查看 .gitignore
cat .gitignore

# 查看 Git 状态
git status
```

### GitHub 相关链接

- Actions 文档: https://docs.github.com/actions
- Secrets 管理: https://docs.github.com/actions/security-guides/encrypted-secrets
- 发布 Release: https://docs.github.com/repositories/releasing-projects-on-github/about-releases

---

## 📊 工作流流程图

```
┌─────────────────────────────────────────┐
│  代码推送或手动触发                       │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│  GitHub Actions 工作流启动                │
│  (apk.yml)                              │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│  1. Checkout 代码（含子模块）             │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│  2. 解析版本号（从分支或标签）           │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│  3. 设置 Java SDK                        │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│  4. 解码 Secrets                         │
│     - STORE → upload.jks                │
│     - LOCAL → local.properties          │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│  5. Gradle 构建                          │
│     - assembleRelease (APK)             │
│     - bundleRelease (AAB)               │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│  6. 清理临时文件                         │
│     - 删除 upload.jks                   │
│     - 删除 local.properties             │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│  7. 创建 GitHub Release                  │
│     - 上传 APK 和 AAB                    │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│  ✅ 构建完成！                            │
│  Release 可供下载                        │
└─────────────────────────────────────────┘
```

---

**最后更新**: 2024
**状态**: 生产就绪 ✅

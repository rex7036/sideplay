# GitHub Actions APK 构建配置指南

## 📋 概览

当前 [apk.yml](.github/workflows/apk.yml) 工作流需要以下 secrets 和配置：

### 所需的 Secrets

| Secret 名称 | 类型 | 说明 |
|-----------|------|------|
| `STORE` | Base64 编码 | Android keystore 密钥库文件 |
| `LOCAL` | Base64 编码 | Gradle local.properties 配置文件 |
| `GITHUB_TOKEN` | Token | GitHub 官方 token（自动提供，通常不需要手动配置） |

---

## 🔐 Step 1: 准备签名密钥库 (Keystore)

### 1.1 生成密钥库文件（如果还没有）

```bash
# 在本地生成 keystore（使用 keytool）
keytool -genkey -v -keystore upload.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias sideplay \
  -storepass YOUR_KEYSTORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=Your Name, O=Your Organization, C=CN"
```

### 1.2 参数说明

- **keystore**: 输出的密钥库文件名 (`upload.jks`)
- **keyalg**: 密钥算法 (RSA)
- **keysize**: 密钥大小 (2048)
- **validity**: 有效期天数 (10000 = ~27年)
- **alias**: 密钥别名 (`sideplay`)
- **storepass**: 密钥库密码 ⚠️ **保管好！**
- **keypass**: 密钥密码 ⚠️ **保管好！**
- **dname**: 证书信息

### 1.3 保存密钥库

- 将 `upload.jks` 妥善保管（不要提交到 Git）
- 记录下 `storepass` 和 `keypass`

---

## 🔐 Step 2: 编码 Keystore 为 Base64

### 2.1 Linux/macOS 命令

```bash
base64 -i upload.jks | tr -d '\n' > keystore_base64.txt
cat keystore_base64.txt
```

### 2.2 Windows PowerShell 命令

```powershell
$fileContent = [System.IO.File]::ReadAllBytes("C:\path\to\upload.jks")
$base64String = [System.Convert]::ToBase64String($fileContent)
Set-Clipboard -Value $base64String
```

### 2.3 复制生成的 Base64 字符串

⚠️ **这个字符串很长（几百字符），全部复制！**

---

## 📝 Step 3: 准备 local.properties 文件

### 3.1 创建本地配置文件

在项目根目录创建 `local.properties`：

```properties
# local.properties
sdk.dir=/home/user/Android/Sdk

# Signing configuration
RELEASE_STORE_FILE=upload.jks
RELEASE_STORE_PASSWORD=YOUR_KEYSTORE_PASSWORD
RELEASE_KEY_ALIAS=sideplay
RELEASE_KEY_PASSWORD=YOUR_KEY_PASSWORD
```

### 3.2 关键配置项

- **RELEASE_STORE_FILE**: keystore 文件名 (upload.jks)
- **RELEASE_STORE_PASSWORD**: keystore 密码 ⚠️
- **RELEASE_KEY_ALIAS**: 密钥别名 (sideplay)
- **RELEASE_KEY_PASSWORD**: 密钥密码 ⚠️

### 3.3 验证 build.gradle 配置

确保 `app/build.gradle.kts` 或 `app/build.gradle` 中有类似配置：

```kotlin
// 示例 (Kotlin DSL)
signingConfigs {
    release {
        storeFile = file(System.getProperty("user.home") + "/release.jks")
        // 或者相对路径：
        storeFile = file(property("RELEASE_STORE_FILE") as String? ?: "upload.jks")
        storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
        keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
        keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.release
    }
}
```

---

## 🔐 Step 4: 编码 local.properties 为 Base64

### 4.1 Linux/macOS 命令

```bash
base64 -i local.properties | tr -d '\n' > local_base64.txt
cat local_base64.txt
```

### 4.2 Windows PowerShell 命令

```powershell
$fileContent = [System.IO.File]::ReadAllBytes("C:\path\to\local.properties")
$base64String = [System.Convert]::ToBase64String($fileContent)
Set-Clipboard -Value $base64String
```

---

## 🚀 Step 5: 在 GitHub 添加 Secrets

### 5.1 访问 GitHub Secrets 页面

1. 打开你的项目：`https://github.com/YOUR_USERNAME/sideplay`
2. 进入 **Settings** → **Secrets and variables** → **Actions**

### 5.2 添加 STORE Secret

1. 点击 **New repository secret**
2. **Name**: `STORE`
3. **Value**: 粘贴之前复制的 keystore Base64 字符串
4. 点击 **Add secret**

### 5.3 添加 LOCAL Secret

1. 点击 **New repository secret**
2. **Name**: `LOCAL`
3. **Value**: 粘贴 local.properties 的 Base64 字符串
4. 点击 **Add secret**

### 5.4 验证 Secrets

在 Settings 页面可以看到已添加的 secrets：
- ✅ STORE
- ✅ LOCAL
- ✅ GITHUB_TOKEN（GitHub 自动提供）

---

## 🏗️ Step 6: 触发构建

### 6.1 三种触发方式

#### 方式 1: 推送到 release 分支（自动触发）

```bash
# 创建并推送到 release 分支
git checkout -b release/1.0.0
git push origin release/1.0.0

# 或推送标签（也会触发）
git tag v1.0.0
git push origin v1.0.0
```

#### 方式 2: 推送 Git 标签（自动触发）

```bash
# 创建带版本号的标签
git tag v1.0.0
git push origin v1.0.0
```

#### 方式 3: 手动触发（Workflow Dispatch）

1. 进入 GitHub 项目首页
2. 点击 **Actions** 标签
3. 选择 **Generate APK** 工作流
4. 点击 **Run workflow**
5. 输入版本号（例如：v1.0.0）
6. 点击 **Run workflow**

### 6.2 监控构建进度

1. 进入 **Actions** 标签
2. 查看最新的工作流运行
3. 点击查看详细日志
4. 等待构建完成（通常 5-15 分钟）

---

## ✅ 构建成功后

### 构建产物位置

构建完成后，GitHub 会自动创建 Release，包含：

- **app-release.apk** - 可直接安装的 APK 文件
- **app-release.aab** - Google Play 应用包格式

### 下载 APK

在 Release 页面下载 `app-release.apk`：

```
https://github.com/YOUR_USERNAME/sideplay/releases/tag/v1.0.0
```

---

## 🔍 故障排查

### 问题 1: Base64 解码错误

```
Error: Invalid base64 string
```

**解决方案**：
- 确保 Base64 字符串没有换行符
- 重新生成：`base64 -i file | tr -d '\n'`

### 问题 2: 签名密钥密码错误

```
Error: Keystore was tampered with, or password was incorrect
```

**解决方案**：
- 检查 local.properties 中的密码是否正确
- 确保密码没有包含特殊字符导致转义问题
- 如需更改密码，使用 keytool 更新

### 问题 3: 找不到 keystore 文件

```
Error: upload.jks (No such file or directory)
```

**解决方案**：
- 检查 local.properties 中的 RELEASE_STORE_FILE 路径
- 确保 Base64 解码正确

### 问题 4: Gradle build 失败

```
Build failed: Unable to resolve dependency
```

**解决方案**：
- 检查网络连接
- 查看完整错误日志
- 本地运行 `./gradlew clean build` 测试

---

## 🔒 安全建议

### 密钥库管理

1. ✅ **不要在 Git 中提交** `upload.jks` 和 `local.properties`
2. ✅ 添加到 `.gitignore`：
   ```
   upload.jks
   local.properties
   ```
3. ✅ 将 keystore 文件备份到安全的地方
4. ⚠️ 不要在日志或控制台输出密码

### Secrets 最佳实践

1. ✅ 定期检查 Secrets 是否泄露
2. ✅ GitHub 会自动脱敏日志中的 secrets 值
3. ✅ 如果 Secrets 泄露，立即更新 keystore 密码
4. ⚠️ 不要在工作流日志中打印 secrets

---

## 📱 安装和测试 APK

### 通过 adb 安装

```bash
# 从 Release 页面下载 app-release.apk 后
adb install app-release.apk
```

### 通过 Android Studio

1. 打开 Android Studio
2. Build → Analyze APK
3. 选择 `app-release.apk`
4. 查看详细信息

---

## 相关文件

- 主工作流：[.github/workflows/apk.yml](.github/workflows/apk.yml)
- 构建脚本：`app/build.gradle.kts`
- 项目配置：`build.gradle.kts` (root)

---

## 常见版本号格式

推荐使用语义化版本号：

- `v1.0.0` - 首个正式版本
- `v1.0.1` - Bug 修复版本
- `v1.1.0` - 功能更新版本
- `v2.0.0` - 主要版本变化

所有版本号应以 `v` 开头，使用 `git tag` 创建。

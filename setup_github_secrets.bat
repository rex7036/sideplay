@echo off
REM GitHub Actions APK 构建配置快速脚本 (Windows 版)
REM 使用方法: setup_github_secrets.bat

setlocal enabledelayedexpansion

:menu
cls
echo ==========================================
echo GitHub Actions APK 构建配置助手 (Windows)
echo ==========================================
echo.
echo 请选择操作:
echo 1) 生成 Android Keystore
echo 2) 生成 local.properties
echo 3) 编码文件为 Base64 (需要 certutil)
echo 4) 显示 GitHub Secrets 配置说明
echo 5) 打开 GitHub Secrets 页面
echo 0) 退出
echo.
set /p choice=请选择 (0-5):

if "%choice%"=="1" goto generate_keystore
if "%choice%"=="2" goto generate_properties
if "%choice%"=="3" goto encode_base64
if "%choice%"=="4" goto show_setup
if "%choice%"=="5" goto open_github
if "%choice%"=="0" exit /b 0
echo 无效的选择
pause
goto menu

:generate_keystore
cls
echo 生成 Android Keystore...
echo.
set /p store_pass=输入 keystore 密码:
set /p key_pass=输入 key 密码:
set /p org_name=输入组织名 (默认: Sideplay):
if "%org_name%"=="" set org_name=Sideplay

REM 检查 keytool 位置
for /f %%i in ('where keytool') do set keytool_path=%%i
if "%keytool_path%"=="" (
    echo ❌ 错误: 找不到 keytool
    echo 请确保 Java SDK 已安装并配置到 PATH
    pause
    goto menu
)

echo 正在生成...（输入 yes 确认证书信息）
keytool -genkey -v -keystore upload.jks ^
    -keyalg RSA ^
    -keysize 2048 ^
    -validity 10000 ^
    -alias sideplay ^
    -storepass %store_pass% ^
    -keypass %key_pass% ^
    -dname "CN=Developer, O=%org_name%, C=CN"

if errorlevel 1 (
    echo ❌ 生成失败
) else (
    echo ✅ Keystore 生成成功: upload.jks
    echo ⚠️  请妥善保管此文件和密码
)
pause
goto menu

:generate_properties
cls
echo 生成 local.properties...
echo.
set /p store_pass=输入 keystore 密码:
set /p key_pass=输入 key 密码:

(
    echo # Android SDK 路径（如果需要）
    echo # sdk.dir=C:\Android\sdk
    echo.
    echo # APK 签名配置
    echo RELEASE_STORE_FILE=upload.jks
    echo RELEASE_STORE_PASSWORD=%store_pass%
    echo RELEASE_KEY_ALIAS=sideplay
    echo RELEASE_KEY_PASSWORD=%key_pass%
) > local.properties

echo ✅ local.properties 生成成功
echo ⚠️  请勿提交此文件到 Git
pause
goto menu

:encode_base64
cls
echo 编码文件为 Base64...
echo.
set /p filepath=输入文件路径:
if not exist "%filepath%" (
    echo ❌ 文件不存在: %filepath%
    pause
    goto menu
)

certutil -encode "%filepath%" "%filepath%_base64.txt" >nul
if errorlevel 1 (
    echo ❌ 编码失败
) else (
    echo ✅ Base64 编码完成: %filepath%_base64.txt
    echo 💾 请用文本编辑器打开查看内容
)
pause
goto menu

:show_setup
cls
echo ==========================================
echo GitHub Secrets 配置说明
echo ==========================================
echo.
echo 1. 访问 GitHub 项目:
echo    https://github.com/YOUR_USERNAME/sideplay
echo.
echo 2. 进入 Settings 页面:
echo    Settings → Secrets and variables → Actions
echo.
echo 3. 添加以下两个 Secrets:
echo.
echo    Secret 1:
echo    - Name: STORE
echo    - Value: 粘贴 upload.jks_base64.txt 的内容
echo           (不要包含任何换行符，复制整个内容！)
echo.
echo    Secret 2:
echo    - Name: LOCAL
echo    - Value: 粘贴 local.properties_base64.txt 的内容
echo           (不要包含任何换行符，复制整个内容！)
echo.
echo 4. 点击 'Add secret' 保存
echo.
echo 5. 触发构建:
echo    a) 推送到 release 分支: git push origin release/1.0.0
echo    b) 创建标签: git tag v1.0.0 && git push origin v1.0.0
echo    c) 手动触发: GitHub Actions 页面
echo.
pause
goto menu

:open_github
start https://github.com/YOUR_USERNAME/sideplay/settings/secrets/actions
echo 已打开 GitHub Secrets 页面
echo 请将 YOUR_USERNAME 替换为你的 GitHub 用户名
pause
goto menu

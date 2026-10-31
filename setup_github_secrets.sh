#!/bin/bash

# GitHub Actions APK 构建配置快速脚本
# 使用方法: bash setup_github_secrets.sh

set -e

echo "=========================================="
echo "GitHub Actions APK 构建配置助手"
echo "=========================================="
echo ""

# 检查依赖工具
check_tools() {
    local tools=("base64" "keytool" "git")
    for tool in "${tools[@]}"; do
        if ! command -v "$tool" &> /dev/null; then
            echo "❌ 缺少工具: $tool"
            echo "请先安装 $tool"
            return 1
        fi
    done
    echo "✅ 所有依赖工具已安装"
}

# 生成 keystore（如果不存在）
generate_keystore() {
    local keystore_file="upload.jks"

    if [ -f "$keystore_file" ]; then
        echo "⚠️  $keystore_file 已存在，跳过生成"
        return 0
    fi

    echo ""
    echo "生成 Android Keystore..."
    read -p "输入 keystore 密码: " store_pass
    read -p "输入 key 密码: " key_pass
    read -p "输入组织名 (默认: Sideplay): " org_name
    org_name=${org_name:-Sideplay}

    keytool -genkey -v -keystore "$keystore_file" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -alias sideplay \
        -storepass "$store_pass" \
        -keypass "$key_pass" \
        -dname "CN=Developer, O=$org_name, C=CN" \
        -noprompt

    echo "✅ Keystore 生成成功: $keystore_file"
    echo "⚠️  请妥善保管此文件和密码"
}

# 生成 local.properties
generate_local_properties() {
    local config_file="local.properties"

    echo ""
    echo "生成 local.properties..."
    read -p "输入 keystore 密码: " store_pass
    read -p "输入 key 密码: " key_pass

    cat > "$config_file" << EOF
# Android SDK 路径（如果需要）
# sdk.dir=/path/to/android/sdk

# APK 签名配置
RELEASE_STORE_FILE=upload.jks
RELEASE_STORE_PASSWORD=$store_pass
RELEASE_KEY_ALIAS=sideplay
RELEASE_KEY_PASSWORD=$key_pass
EOF

    echo "✅ local.properties 生成成功"
    echo "⚠️  请勿提交此文件到 Git"
}

# 编码文件为 Base64
encode_to_base64() {
    local file=$1
    local output="${file}_base64.txt"

    if [ ! -f "$file" ]; then
        echo "❌ 文件不存在: $file"
        return 1
    fi

    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS
        base64 -i "$file" > "$output"
    else
        # Linux
        base64 "$file" | tr -d '\n' > "$output"
    fi

    echo "✅ Base64 编码完成: $output"
    echo "📋 内容预览（前100字符）:"
    head -c 100 "$output"
    echo "..."
    echo ""
    echo "💾 完整内容已保存到 $output"
}

# 显示 GitHub Secrets 配置说明
show_github_setup() {
    echo ""
    echo "=========================================="
    echo "GitHub Secrets 配置说明"
    echo "=========================================="
    echo ""
    echo "1. 访问: https://github.com/YOUR_USERNAME/sideplay"
    echo "2. 进入: Settings → Secrets and variables → Actions"
    echo "3. 添加以下两个 Secrets:"
    echo ""
    echo "   Secret 1:"
    echo "   - Name: STORE"
    echo "   - Value: 粘贴 upload.jks_base64.txt 的内容"
    echo ""
    echo "   Secret 2:"
    echo "   - Name: LOCAL"
    echo "   - Value: 粘贴 local.properties_base64.txt 的内容"
    echo ""
    echo "4. 点击 'Add secret' 保存"
    echo ""
}

# 触发 GitHub Actions 构建
trigger_workflow() {
    echo ""
    echo "=========================================="
    echo "触发 GitHub Actions 构建"
    echo "=========================================="
    echo ""
    echo "选择触发方式:"
    echo "1) 推送 release 分支（自动触发）"
    echo "2) 创建 Git 标签（自动触发）"
    echo "3) 手动触发（通过 GitHub Web 界面）"
    echo ""
    read -p "请选择 (1-3): " choice

    case $choice in
        1)
            read -p "输入 release 分支名 (如: 1.0.0): " branch_name
            git checkout -b "release/$branch_name" 2>/dev/null || git checkout "release/$branch_name"
            git push origin "release/$branch_name"
            echo "✅ 已推送到 release/$branch_name，Actions 应该自动触发"
            ;;
        2)
            read -p "输入版本号 (如: v1.0.0): " version
            git tag "$version"
            git push origin "$version"
            echo "✅ 已创建标签 $version，Actions 应该自动触发"
            ;;
        3)
            echo "请访问以下链接手动触发:"
            echo "https://github.com/YOUR_USERNAME/sideplay/actions/workflows/apk.yml"
            echo "点击 'Run workflow' 按钮"
            ;;
    esac
}

# 主菜单
main_menu() {
    echo ""
    echo "请选择操作:"
    echo "1) 检查依赖工具"
    echo "2) 生成 Android Keystore"
    echo "3) 生成 local.properties"
    echo "4) 编码 Keystore 为 Base64"
    echo "5) 编码 local.properties 为 Base64"
    echo "6) 显示 GitHub Secrets 配置说明"
    echo "7) 触发 GitHub Actions 构建"
    echo "8) 完整设置流程（依次执行所有步骤）"
    echo "0) 退出"
    echo ""
    read -p "请选择 (0-8): " choice

    case $choice in
        1)
            check_tools
            main_menu
            ;;
        2)
            generate_keystore
            main_menu
            ;;
        3)
            generate_local_properties
            main_menu
            ;;
        4)
            encode_to_base64 "upload.jks"
            main_menu
            ;;
        5)
            encode_to_base64 "local.properties"
            main_menu
            ;;
        6)
            show_github_setup
            main_menu
            ;;
        7)
            trigger_workflow
            main_menu
            ;;
        8)
            echo "执行完整设置流程..."
            check_tools && \
            generate_keystore && \
            generate_local_properties && \
            encode_to_base64 "upload.jks" && \
            encode_to_base64 "local.properties" && \
            show_github_setup && \
            echo "✅ 设置完成！"
            ;;
        0)
            echo "退出"
            exit 0
            ;;
        *)
            echo "❌ 无效的选择"
            main_menu
            ;;
    esac
}

# 启动
check_tools && main_menu

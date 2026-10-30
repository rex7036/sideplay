# 音视频同步问题修复 - 变更摘要

## 修改日期
$(date +%Y-%m-%d)

## 问题描述
1. **声音延迟不固定**：音频比视频延迟，且延迟时间在不同播放会话中变化
2. **开头卡顿**：播放开始时的几秒钟内声音卡顿或完全无声

## 根本原因分析
1. 自适应音频缓冲的目标延迟过高（95百分位，最大1000ms）
2. 预缓冲等待时间过长（需要填满100%目标缓冲才开始播放）
3. 延迟恢复机制响应过慢（trim throttle 2秒）

## 实施的修改

### 1. 降低默认音频延迟百分位
**文件**: `app/src/main/kotlin/io/github/jqssun/airplay/Prefs.kt`
- 修改 `DEF_AUDIO_ADAPTIVE_STEP` 从 3 (95%) 改为 1 (85%)
- **影响**: 默认目标延迟降低约30-50ms

### 2. 减小最大缓冲上限
**文件**: `app/src/main/cpp/timeline_buffer.h`
- 修改 `MAX_CUSHION_MS` 从 1000ms 改为 300ms
- **影响**: 即使在最差网络条件下，音频延迟也不会超过300ms

### 3. 优化预缓冲快速启动
**文件**: `app/src/main/cpp/timeline_buffer.h`
- 预缓冲启动阈值从 100% 改为 50% 目标缓冲
- 无声等待时间从 2x 改为 1.5x 缓冲大小
- **影响**: 音频启动速度提升约50%

### 4. 加快延迟恢复响应
**文件**: `app/src/main/cpp/timeline_buffer.h`
- `TRIM_FLOOR_MS`: 30ms → 20ms（基础延迟下限）
- `TRIM_THROTTLE_MS`: 2000ms → 1500ms（trim操作间隔）
- `TRIM_SUSTAIN_MS`: 2000ms → 1000ms（触发trim前的等待时间）
- **影响**: 当网络条件改善时，能更快地降低累积的延迟

## 预期效果

### 改进方面
1. **音频延迟降低**: 典型场景下从 100-200ms 降低到 50-100ms
2. **延迟更稳定**: 固定在较低水平，不会在不同会话间大幅波动
3. **启动更快**: 从无声到有声的时间从 1-2秒 降低到 0.3-0.5秒
4. **更好的音视频同步**: 延迟降低使音画更同步

### 可能的权衡
1. 在网络抖动较大的环境下，可能会有轻微的偶发卡顿
2. 如果用户的网络条件特别差，可以在设置中手动提高 AUDIO_ADAPTIVE_STEP

## 测试建议

### 基础测试
1. **本地WiFi测试**: 验证低延迟表现和音画同步
2. **播放启动**: 多次启动播放，确认声音快速开始
3. **长时间播放**: 播放30分钟以上，验证稳定性

### 网络条件测试
1. **良好网络**: 验证最佳性能
2. **中等网络**: 验证自适应调整
3. **较差网络**: 验证是否有过多卡顿（如有，可能需要微调）

### 回归测试
1. 暂停/恢复功能
2. 多次连接/断开
3. 切换不同内容源

## 回滚方案

如果新版本出现问题，可以恢复以下值：

```kotlin
// Prefs.kt
const val DEF_AUDIO_ADAPTIVE_STEP = 3  // 恢复到95%

// timeline_buffer.h
static constexpr int MAX_CUSHION_MS = 1000;
static constexpr int TRIM_FLOOR_MS = 30;
static constexpr int TRIM_THROTTLE_MS = 2000;
static constexpr int TRIM_SUSTAIN_MS = 2000;

// 预缓冲逻辑恢复为 100% tuned 和 2x starveFrames
```

## 后续优化方向

1. **添加A/V延迟监控**: 在调试界面显示实时音视频延迟差
2. **用户可调延迟补偿**: 允许用户手动微调音频延迟 ±200ms
3. **统一的AV同步框架**: 实现视频跟随音频主时钟的同步机制
4. **自适应算法改进**: 基于实际A/V同步差异动态调整缓冲策略

## 相关文件

- [AUDIO_VIDEO_SYNC_FIX.md](./AUDIO_VIDEO_SYNC_FIX.md) - 详细技术分析
- `Prefs.kt` - 配置默认值
- `timeline_buffer.h` - 音频缓冲核心算法
- `AudioConfig.kt` - 音频配置数据类
- `VideoRenderer.kt` - 视频渲染器

## 构建说明

修改后需要重新编译 native 代码：

```bash
./gradlew clean
./gradlew assembleDebug
```

或使用 Android Studio 的 Build → Rebuild Project

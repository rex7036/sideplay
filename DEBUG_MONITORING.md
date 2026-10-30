# 音视频同步调试和监控增强

## 当前调试能力

项目已有的调试基础设施：

### 音频调试数据（AudioDebugData）
- **backlogMs**: 当前音频缓冲积压（毫秒）
- **tunedCushionMs**: 自适应调谐器计算的目标缓冲（毫秒）
- **trims**: 缓冲裁剪次数（延迟过高时的主动降低）
- **drops**: 丢弃的延迟数据包数量
- **silences**: 插入的静音段数量（网络间隙）
- **underruns**: 缓冲下溢次数（卡顿）
- **xrun**: Oboe 音频流的 xrun 计数
- **decodeErrors**: 解码错误累计

### 视频调试数据
- **fps**: 当前帧率
- **bitrateBps**: 视频比特率
- **frameCount**: 总帧数
- **droppedFrames**: 丢帧数
- **framePacingJitterUs**: 帧间隔抖动（微秒）
- **codecName**: 使用的编解码器

## 建议的增强功能

### 1. 音视频时间戳同步监控

**目标**: 实时监控音视频的实际播放时间差

**实现方案**:
```kotlin
// 在 AirPlayService 或 MirrorSession 中添加
class AVSyncMonitor {
    private var lastAudioPts = 0L
    private var lastVideoPts = 0L
    private var lastCheckTime = 0L
    
    fun updateAudioPts(ptsNs: Long) {
        lastAudioPts = ptsNs
        checkSync()
    }
    
    fun updateVideoPts(ptsUs: Long) {
        lastVideoPts = ptsUs * 1000L
        checkSync()
    }
    
    private fun checkSync() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastCheckTime < 500_000_000L) return // 每500ms检查一次
        lastCheckTime = now
        
        val diffMs = (lastVideoPts - lastAudioPts) / 1_000_000L
        if (abs(diffMs) > 100) {
            Log.w(TAG, "A/V sync diff: ${diffMs}ms (video ${if (diffMs > 0) "ahead" else "behind"})")
        }
    }
}
```

### 2. 启动时间监控

**目标**: 测量从连接到声音/画面出现的时间

**关键时间点**:
- T0: 接收到 /play 或镜像开始请求
- T1: 音频引擎启动完成
- T2: 视频解码器启动完成
- T3: 第一个音频帧播放
- T4: 第一个视频帧渲染

```kotlin
object StartupMetrics {
    var connectionTime = 0L
    var audioStartTime = 0L
    var videoStartTime = 0L
    var firstAudioFrameTime = 0L
    var firstVideoFrameTime = 0L
    
    fun logMetrics() {
        Log.i(TAG, """
            Startup metrics:
            - Audio engine: ${audioStartTime - connectionTime}ms
            - First audio: ${firstAudioFrameTime - connectionTime}ms
            - Video decoder: ${videoStartTime - connectionTime}ms
            - First video: ${firstVideoFrameTime - connectionTime}ms
            - A/V diff: ${firstVideoFrameTime - firstAudioFrameTime}ms
        """.trimIndent())
    }
}
```

### 3. 网络抖动分析

**目标**: 记录和可视化网络延迟变化

Native 层已有 DelayTracker 的直方图，可以暴露给 Java 层：

```cpp
// 在 audio_engine.h 添加
struct JitterStats {
    int64_t minLatencyMs;
    int64_t maxLatencyMs;
    int64_t currentCushionMs;
    int32_t histogram[10]; // 简化的10档直方图
};

JitterStats getJitterStats();
```

### 4. 开发者选项界面增强

在设置中的开发者选项添加实时监控：

```kotlin
// 调试覆盖层显示的内容
A/V Sync Monitor
├─ Audio Buffer: 45ms / 85ms (target)
├─ Video Buffer: 3 frames
├─ A/V Diff: -12ms (audio ahead)
├─ Network Jitter: 5-45ms (p85: 32ms)
├─ Underruns: 2, Trims: 5, Drops: 0
└─ Last 10s avg FPS: 59.8
```

### 5. 事件日志记录

**关键事件应该记录**:
- 音频缓冲 underrun（及触发原因）
- 音频缓冲 trim（及累积延迟）
- 视频丢帧（及原因：解码慢/队列满）
- A/V 同步漂移超过阈值
- 网络抖动突变
- 编解码器重启

```kotlin
sealed class AVEvent {
    data class AudioUnderrun(val backlogMs: Int) : AVEvent()
    data class AudioTrim(val excessMs: Int) : AVEvent()
    data class VideoFrameDrop(val reason: String) : AVEvent()
    data class AVSyncDrift(val driftMs: Long) : AVEvent()
}

object AVEventLogger {
    private val events = ArrayDeque<Pair<Long, AVEvent>>(100)
    
    fun log(event: AVEvent) {
        events.addLast(SystemClock.elapsedRealtime() to event)
        if (events.size > 100) events.removeFirst()
        
        // 可选：写入文件或上报
    }
    
    fun export(): String {
        return events.joinToString("\n") { (time, event) ->
            "${time}: $event"
        }
    }
}
```

## 使用 benchmark_log 模式

代码中已有 `benchmarkLog` 开关，启用后会定期输出性能数据：

```kotlin
// 在 Prefs.kt 中
const val BENCHMARK_LOG = "benchmark_log"
const val DEF_BENCHMARK_LOG = false

// 启用后会在 logcat 看到：
// BENCHMARK: fps=60 bitrate=8520kbps jitter=1234us frames=3600 dropped=0 codec=H.264 (OMX.google.h264.decoder) res=1920x1080
```

## 推荐的调试流程

### 问题复现
1. 启用 `benchmark_log` 和 `debug_enabled`
2. 使用 `adb logcat -s BENCHMARK AudioEngine TimelineBuffer VideoRenderer` 过滤日志
3. 复现问题（延迟、卡顿等）
4. 导出日志分析

### 分析指标

**音频延迟问题**:
- 查看 `tunedCushionMs` - 如果持续很高（>150ms），说明网络抖动大
- 查看 `backlogMs` - 如果远超 `tunedCushionMs`，说明 trim 不够及时
- 查看 `trims` 和 `underruns` 的比例

**音频卡顿问题**:
- `underruns` 增加 → 缓冲太小或网络太差
- `drops` 增加 → 数据包到达太晚
- `silences` 增加 → 网络有间隙

**音视频不同步**:
- 比较音频 `backlogMs` 和视频缓冲（难以直接测量）
- 查看 `framePacingJitterUs` - 视频渲染不稳定会加剧不同步感
- 查看 `droppedFrames` - 视频丢帧会导致音频超前

## 性能测试建议

### 测试用例

1. **本地WiFi基线测试**
   - 1080p60 视频播放
   - 测量：启动时间、平均延迟、A/V同步误差

2. **网络压力测试**
   - 同时下载大文件或其他WiFi流量
   - 观察自适应缓冲的表现

3. **长时间稳定性**
   - 播放1小时
   - 检查是否有时钟漂移累积

4. **快速开始/停止**
   - 连续10次启动和停止
   - 验证状态清理和重新初始化

### 性能基准

修改后的目标性能（良好WiFi环境）：
- **启动到有声音**: < 500ms
- **音频延迟**: 50-100ms（自适应）
- **A/V同步误差**: < ±30ms
- **Underrun率**: < 1次/小时
- **帧率**: 稳定60fps（1080p60输入）

## 工具推荐

1. **systrace / Perfetto**: 查看音视频线程调度
2. **adb logcat**: 实时日志监控
3. **Wireshark**: 抓包分析网络延迟
4. **scrcpy**: 对比原始设备和接收端的延迟

## 下一步

1. 实现 AVSyncMonitor 类
2. 暴露 jitter 统计到 Java 层
3. 创建调试覆盖层 UI
4. 添加事件日志导出功能

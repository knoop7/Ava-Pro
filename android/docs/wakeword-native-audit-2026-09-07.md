# 唤醒链路原生代码排查与验证

## 结论范围

本轮修复的是可定位的实现错误，不能据此宣称 openWakeWord 或 microWakeWord 已经没有误触发。没有替换模型、修改用户阈值或部署到设备。micro 的模型推理由 Kotlin 调用 TFLite，C++ 负责音频特征，因此检查和修复包含 JNI 两侧。

## 已修复的问题

| 位置 | 原有问题 | 修复 |
| --- | --- | --- |
| `microfeatures/src/main/cpp/openwakeword/oww_engine.cpp` 救援路径 | 主路径和补偿路径执行二次验证，救援路径却绕过验证器 | 所有救援候选执行同一验证器；拒绝时不扣冷却时间 |
| 同文件，active 列表 | 显式设置空列表反而启用所有关键词 | 空列表禁用全部；添加模型仍默认启用该模型 |
| 同文件，VAD 恢复 | setter 内部触发，下一次处理 PCM 时结果被清空 | 在能返回事件的 `process_chunk()` 内回放缓存 |
| 同文件，批量回放 | 批次可能从 B 相位开始，却总按 A、B 交错处理 | 以 40 ms 调度单位恢复真实顺序 |
| `oww_engine.h/.cpp`，长时间 VAD 暂停 | 840 ms 缓存仅覆盖嵌入网络，未覆盖关键词的多帧历史 | 缓存覆盖最长上下文，再加 240 ms 事件尾部；更旧的回放只恢复状态，不产生事件 |
| `microfeatures/src/main/cpp/openwakeword/oww_gate.h` | NaN 的比较结果使其绕过低分拒绝，Infinity 也可能触发 | 非有限分数和阈值拒绝，清除连续命中状态；验证器非有限输出同样拒绝 |
| `app/src/main/java/com/example/ava/microwakeword/TensorBuffer.kt` | INT8/UINT8 越界直接转字节造成回绕 | 按实际类型进行饱和截断 |
| 同文件，FLOAT32 输入 | 浮点特征仍除以量化 scale；未量化模型的 scale 通常为 0 | 浮点输入直接写入 |
| `MicroWakeWord.kt`、`MicroVad.kt` | 输出固定按 UINT8 解码 | 根据张量类型解码 UINT8、INT8、FLOAT32，并检查单个概率输出 |
| `microfeatures/src/main/cpp/MicroFrontend.cpp` | C++ 对象销毁没有释放 frontend 内部分配 | 添加析构释放、初始化失败清理及 JNI 输入缓冲区检查 |

## 验证证据

- micro 输入转换的 3 个回归用例在修复前全部失败；修复后输入和输出类型共 6 个用例通过。
- 原生最小复现中，明确拒绝所有候选的验证器仍被救援路径绕过，空 active 列表仍触发，VAD 恢复则丢失唤醒；修复后分别为拒绝、禁用和正确返回一次事件。
- 新增长暂停回归：旧唤醒不延迟上报，最近的真实唤醒仍能恢复。
- 应用侧 open/micro 相关单测：7 个测试类，62 项用例通过。
- 原生 CTest 包括触发门控、调度、负信号、嵌入一致性、离线验证和新增引擎回归。
- 实际 microfrontend 连续创建、处理、重置、销毁 1000 次，macOS 分配器保留量增长 10,816 字节，低于测试上限 256 KiB；特征输出数量及重置行为检查通过。
- Android `arm64-v8a` 和 `armeabi-v7a` 原生构建通过。

Gradle 在仓库配置的 Java 24 下无法创建单测任务；本轮使用本机已有 JBR 17 的命令行覆盖运行，没有改项目配置。

```sh
./gradlew -Dorg.gradle.java.home=/Users/knoopnu/Library/Java/JavaVirtualMachines/jbr-17.0.9/Contents/Home :app:testLiteDebugUnitTest --tests 'com.example.ava.microwakeword.*' --tests 'com.example.ava.openwakeword.*'
cmake --build .oww-hosttest/build -j 4
ctest --test-dir .oww-hosttest/build --output-on-failure -j 2
```

## 模型误触发仍然存在

Jarvis 分类器对照使用实际 C++ 导出的同一批特征窗口，而不是重新实现一份音频前端。5 条代表性音频包括 Travis、Elvis、室内混响近音词、中文 Java 句子和远场正样本，共 533 个窗口。原生推理与 ONNX Runtime 最大绝对差为 0.00000685，阈值判定差异为 0。这能排除这些窗口上分类器算子错误，不能证明所有模型、音频前端或设备条件完全一致。

现有 v3 合成压力集含 333 条负样本、31 条正样本，仍沿用原来的文件名标签：

| 试验 | 负样本触发条数 | 正样本唤醒条数 |
| --- | ---: | ---: |
| open Jarvis，0.68、单次命中、旁路开启，前一轮校正 A/B 后基线 | 60 | 28 |
| open Jarvis，0.68、两次命中、旁路关闭，前一轮候选 | 37 | 27 |
| micro Jarvis，内置推荐 0.97、窗口 5，量化修复前 | 48 | 23 |
| micro Jarvis，相同配置，量化修复后 | 48 | 23 |

micro 364 条中有 2 条非语音样本出现超过 INT8 特征范围的值，但修正截断未改变这批样本的最终触发结论。内置模型输出实际为 UINT8；INT8/FLOAT32 输出修复保护的是其他兼容模型。

这些是模型级压力测试，未串入完整应用 VAD、AEC、回声筛查及设备上的学习验证器。合成近音词比例很高，不能换算为每小时日常误触率，也不能以不同召回率直接宣布某个引擎更好。

复现工具：

- `.oww-hosttest/check_classifier_parity.py`：C++ 特征窗口与 ONNX Runtime 分类器逐窗口比较。
- `.oww-hosttest/micro_quantization_ab.py`：同一特征输入比较回绕与截断，输出 `fp-jarvis-v3/micro_quantization_ab.csv`。
- `.oww-hosttest/engine_regression.cc`：验证器、active 集合、VAD 暂停恢复。
- `.oww-hosttest/micro_frontend_lifecycle.cc`：实际 microfrontend 生命周期检查，需要 JNI 头和 host frontend 对象文件。

## 进一步减少模型误触所需的验证

下一步需要独立的设备录音验证集，同时包含真实误触、正常唤醒、远场、小声、连读命令和长时间背景音。训练数据与验收数据应按说话人及原始录音划分，不能把同一句的不同噪声版本分别放进训练和测试。

应在固定召回率下比较每小时误触及唤醒延迟，再决定是否部署新的验证器或重新训练模型。重复运行同一个饱和高分模型、只提高阈值、或在当前测试集上拟合后直接报告准确率，都不能证明已解决近音词问题。长暂停回放的真机耗时及完整 VAD/AEC 联动也需要设备测量。

参考项目的官方说明：[openWakeWord](https://github.com/dscripka/openWakeWord)、[microWakeWord](https://github.com/kahrendt/microWakeWord)。

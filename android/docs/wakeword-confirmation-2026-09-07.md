# 两套引擎的候选确认

## 通用实现

目标是 openWakeWord 和 microWakeWord 的引擎能力，适用于各个模型。Jarvis 是用于暴露近音词误触的开发回归集，不是针对特定设备的参数调整。

两套引擎统一遵循：连续处理音频 → 生成候选 → VAD/验证器确认 → 正式触发 → 冷却与重新就绪。后续召回审查进一步修正了拒绝行为：保留连续滑动窗口，让下一帧更新后的完整证据重试；只有接受才清空窗口并进入冷却。详见 `wakeword-recall-2026-09-07.md`。

- micro：`MicroWakeWord.processAudioFeatures` 增加候选确认回调，仅在完整概率窗口过线时调用。`WakeWordDetector` 把原有 VAD 和学习验证器检查放进回调，接受后才执行 `rearmGate.disarm()`。无验证器的调用仍使用原来行为。
- open：C++ 新增独立的最终触发许可 `set_trigger_gates(normal, strict)`，与节省计算的 `set_voice_gate` 分开。Kotlin 把普通、严格 VAD 判定通过一次 JNI 调用传入；各关键词选择自己的 VAD 规则，主路径、补偿路径和救援路径都在提交触发之前检查。VAD 规则改变时同步重建原生配置。
- 两者均不引入固定额外等待。候选当下通过即可触发；被拒绝后，后续完整证据可以再次确认。

新增 micro 回归覆盖：弱证据不运行二次确认、拒绝不扣冷却、重新确认使用最新的完整滑动窗口、接受后防止重复唤醒、非有限输出拒绝。C++ 增加普通及严格 VAD 拒绝后仍能接受后续真唤醒的回归。

这属于确认顺序和状态管理的修复，不代表所有模型的识别能力已经达到 HomePod 水平。

## 二次模型实验

`second_pass_benchmark.py` 复用项目已有的标准化特征与线性验证器格式，分别对 open 的 1536 维嵌入窗口和 micro 的 2400 维时序特征窗口训练候选验证器。

- 标签从原始生成脚本的句子、说话人信息建立，检查了 364 条样本、31 条正样本。
- 每一折排除测试说话人的全部训练样本，包括其语速和房间等变体。多人重叠音频对应的所有说话人一起排除。
- 参数预设为 `C=0.02`、验证阈值 `0.5`，没有在报告的测试折上搜索阈值。
- open 的确认结果通过实际 C++ 引擎运行得到；micro 使用真实原生前端和 LiteRT 推理分数，按修改后的门控规则回放。两者均不包括完整设备 AEC/VAD/回声筛查。
- 补充试验仅增加训练侧 294 条变体；仍严格排除测试说话人的变体。第二次试验是在看过第一次结果后进行的开发迭代，因此不是最终独立验收集。

下表记录当时“拒绝后清空窗口”策略的历史实验，不代表后续保留滑动窗口后的全部结果。

| 开发实验 | open 误触 / 333 | open 正常唤醒 / 31 | micro 误触 / 333 | micro 正常唤醒 / 31 |
| --- | ---: | ---: | ---: | ---: |
| 基线 | 60 | 28 | 48 | 23 |
| 按说话人隔离的线性验证器 | 12 | 23 | 22 | 19 |
| 增加训练侧音频变化 | 9 | 21 | 25 | 19 |

增加训练变化后，成功保留的正样本新增音频等待 P95 为 open 120 ms、micro 315 ms。该指标不包含推理计算耗时，不等于端到端响应延迟；漏掉的正样本另外计入失败，不能以其缺席降低延迟统计。

以上候选全部不满足本次开发门槛：原基线能唤醒的正样本一个也不能新增遗漏、误触至少减少一半、正样本新增音频等待 P95 不超过 120 ms。所有训练出的权重仅保存在 `.oww-hosttest/second-pass-experiment`，没有复制进应用资源或修改默认模型。

## 后续技术方向

轻量线性头在这批跨说话人近音词上存在明显取舍，不能用“更高阈值”或更多次重复相同模型评分来证明精准又快速。下一阶段的候选应是具有独立语音判别能力的短音频 checker，并使用新的独立数据验收；共同的引擎确认接口可以继续复用。

Apple 公开的系统采用多阶段检测，其高精度 checker 使用 Conformer 和语音/判别联合训练，另有整句误触抑制。参考的是这一技术方向，并非已经复现其模型或获得同等效果：[Voice Trigger System for Siri](https://machinelearning.apple.com/research/voice-trigger)。清晰候选快速接受、边界候选使用更多上下文的取舍另见 [Progressive Voice Trigger Detection](https://machinelearning.apple.com/research/progressive-voice-trigger)。

```sh
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 python3 .oww-hosttest/second_pass_benchmark.py \
  --open-model /Users/knoopnu/Downloads/bb/openwakeword/models/hey_jarvis/hey_jarvis.onnx
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 python3 .oww-hosttest/second_pass_benchmark.py \
  --open-model /Users/knoopnu/Downloads/bb/openwakeword/models/hey_jarvis/hey_jarvis.onnx \
  --augment-training
```

# 设备遥测 · 设备影子 · 阈值告警（本地一体化服务）

基于 Spring Boot 3 / Java 21 的**纯进程内**设备接入、设备影子与告警判定服务。
用内置的设备与遥测模拟器替代真实物联网数据链路，**不依赖** MQTT/Kafka/数据库/真实设备或外部凭据，
`mvn spring-boot:run` 即可运行。

---

## 1. 架构与数据链路

```
            ┌───────────────────────┐
            │ DeviceSimulator 模拟器 │ (定时/手动, 进程内方法调用)
            └──────────┬────────────┘
                       ▼
 REST /api/telemetry/reports
                       ▼
 ┌─────────────────────────────────────────────┐
 │ TelemetryService（设备锁内串行处理每台设备）    │
 │  设备校验 → 载荷校验 → 未来时间戳 → 重复判定     │
 │  → 乱序/迟到判定 → 指标范围清洗 → 影子原子更新   │
 │  → AlertService 规则评估（同一临界区）          │
 └───────┬───────────────────────────┬───────────┘
         ▼                           ▼
  ShadowStore（reported/        AlertStore（规则版本/
  desired + 乐观版本）            告警状态机/只增历史）
         ▲                           ▲
 REST /api/shadows/**   REST /api/alerts/**
```

- **按设备互斥**：`DeviceLocks` 为每台设备分配一把 `ReentrantLock`。同一设备的遥测写入、
  desired 写入、抑制、读取全部串行；不同设备互不阻塞。
- **影子更新与告警判定在同一临界区**：杜绝“更新了影子但漏判告警”这类中间态。
- 所有对外返回的影子/告警均为**不可变快照（防御性拷贝）**，读取永远自洽。

---

## 2. 遥测处理与状态一致性规则

每条上报返回 `IngestResult{status, reason, deviceId, seq, shadowVersion}`，
**任何输入都有可区分的结论，绝不静默丢弃**。

| status | 触发条件 | 是否写影子 | 判定依据（reason 前缀） |
|---|---|---|---|
| `ACCEPTED` | 合法新事件，全部指标合法 | 是，reportedVersion +1 | — |
| `DEGRADED` | 部分指标超出注册区间，被清洗 | 是（仅合法指标），版本 +1 | 列出被清洗指标及区间 |
| `DUPLICATE` | `seq` 等于该设备已接受的最大 seq（重传） | 否，版本不变 | `序列号…完全重复` |
| `STALE` | `seq` 小于最大 seq（乱序）；或 seq 更大但事件时间早于最新事件超过迟到窗口（默认 5000ms） | 否，版本不变 | 区分“乱序”与“迟到”文案 |
| `REJECTED` | 设备未注册 / 空载荷 / NaN、无穷值或空指标名 / 事件时间超前接收时间超过容忍（默认 60000ms）/ 全部指标越界 | 否，version=-1 | `UNKNOWN_DEVICE`、`EMPTY_METRICS`、`INVALID_METRICS`、`FUTURE_TIMESTAMP`、`OUT_OF_RANGE` |

关键规则：

1. **乱序/重复以设备序列号 `seq` 为准**；迟到以事件时间戳与容忍窗口辅助判定。
2. **过期或重复数据绝不覆盖较新状态**：`reported`、`lastSeq`、版本号均保持不变。
3. **范围清洗**：指标合法区间在设备注册信息中声明（`metricBounds`）。部分越界 → `DEGRADED`
   （越界指标既不进影子也不参与告警），全部越界 → `REJECTED`。
4. **平台分配序号**：`seq < 0` 时由服务端在设备锁内分配 `maxSeq+1`。
   多接入线程并发时即使抢锁顺序不确定，也不会被误判为乱序。
5. 判定参数可调：`telemetry.late-tolerance-millis`、`telemetry.future-skew-millis`。

---

## 3. 设备影子一致性

影子快照 `DeviceShadow`：

- `reported`：最近一次**被接受**（含降级）上报合并后的指标，`reportedVersion` 每次接受 +1（首版 1）；
- `desired`：期望状态，`desiredVersion` 每次成功写入 +1（0 表示从未设置）；
- `lastSeq / lastEventTs`：最新已接受上报的序号与事件时间。

desired 写入返回 `ShadowUpdateResult{status, reason, desiredVersion}`：

| status | 含义 |
|---|---|
| `OK` | 写入成功 |
| `VERSION_CONFLICT` | 乐观锁冲突：携带的 `expectedVersion` 与当前版本不一致，返回当前版本供重试 |
| `UNKNOWN_DEVICE` | 设备未注册 |
| `INVALID_STATE` | 空状态、空指标名、`NaN`/`Infinity` 等非法值 |

- `expectedVersion` 传 -1（或缺省）表示不校验版本；标准多写者用法是“读快照 → 带版本写 → 冲突重读重试”。
- 并发保证：单设备读写全部经同一把锁串行化，快照中的 reported/desired/版本号始终彼此自洽；
  相同版本的并发写**恰好一个成功**，其余明确得到 `VERSION_CONFLICT`（见 `ShadowConsistencyTest`）。

---

## 4. 告警语义

### 规则（`AlertRule`）

- 维度：`deviceId + metric`，算子 `GT/GTE/LT/LTE/EQ` + 阈值；可 `enabled=false` 停用。
- **版本化**：相同 `ruleId` 每次下发（含停用/启用）都生成 `ruleVersion+1` 的新不可变规则。
- 历史评估记录与既有告警**保留其判定时的规则版本/算子/阈值**，规则变更不追溯改写，
  因此可随时用历史记录中的字段重新计算 `violated`，复现历史判定。

### 生命周期（单设备锁内的同一状态机）

```
                 越限且无 open 告警
   (无) ───────────────────────────────► ACTIVE ◄─────┐
    ▲                                     │  ▲         │ 抑制窗口到期仍越限
    │ STILL_RESOLVED                      │  │ STILL_  │ (自动 reactivate)
    │                                     ▼  │ ACTIVE  │
    │                              SUPPRESSED┘         │
    │ 回到阈值内                            │ 窗口内越限  │
    └──────────── RESOLVED ◄───────────────┘ (SUPPRESSED,不升级)
```

每次“遥测 × 规则”输出 `AlertVerdict` 并追加一条只增 `EvaluationRecord`：

| verdict | 含义 |
|---|---|
| `TRIGGERED` | 首次越限，创建新告警 |
| `STILL_ACTIVE` | 活动告警下持续越限，**幂等**，只累加 `triggerCount`，不产生新告警 |
| `SUPPRESSED` | 抑制窗口内越限，保持抑制、不升级、不产生新告警 |
| `RESOLVED` | 回到阈值内，告警恢复（记录恢复值与时间），open 索引关闭 |
| `STILL_RESOLVED` | 阈值内且无活动告警，无动作 |
| `RULE_DISABLED` | 规则已停用：不驱动状态机、不产生告警，但仍留一条判定记录，停用期历史可追溯 |
| `METRIC_ABSENT` | 本次上报不含规则所监控指标 |

- 同一规则同时**至多一条 open 告警**；恢复后再次越限会产生新 `alertId`，老告警保留为 `RESOLVED`。
- **抑制**：`POST /api/alerts/rules/{ruleId}/suppress?durationMillis=...`，
  只对 `ACTIVE` 告警生效；无活动告警/重复抑制都会返回 `applied=false` 与原因，不静默成功。
  抑制窗口结束后若仍越限，自动恢复为 `ACTIVE`（复用同一告警，不新建）。
- 并发上报由设备锁保证：**不重复告警**（只有一个 `TRIGGERED`）、**不漏报**
  （每次越限都体现在 `triggerCount` 与历史记录中），见 `AlertConcurrencyTest`。

---

## 4b. 规则上线前的历史试算（What-if）

改阈值 / 换算子 / 加规则之前，先用**最近的历史遥测**把候选规则试跑一遍，
并与**当前生效规则**在同一时间窗口的结果对照，提前看清这次调整会多报还是少报。
试算**完全只读**，绝不触碰线上影子、告警与判定历史。

### 适用场景

- 阈值调整（如 `temperature > 30` 改成 `> 40`）前评估告警量变化；
- 新增规则、更换算子（`GT`→`GTE` 等）前看会在哪些时刻新触发；
- 评估触发时刻、恢复时刻（持续时长）是否因规则调整而提前/推迟。

### 历史留存范围（有界、确定）

- 仅留存**被接入管道接受**的遥测（`ACCEPTED` / `DEGRADED`），且是**范围清洗之后**的指标载荷，
  因此试算口径与线上判定完全一致；被拒绝、重复（`DUPLICATE`）、迟到（`STALE`）的上报**不留存**。
- 容量上限可配（内存占用有明确上限），超限后按固定 **FIFO** 规则淘汰最旧数据：
  - 每设备 `trial.history.max-points-per-device`（默认 20000）；
  - 全局合计 `trial.history.max-total-points`（默认 200000），超限时在各设备最旧点中淘汰事件时间最早者。
- 每台设备记录“已淘汰数据的最大事件时间”高水位，查询/试算用 `coverage` 明确区分三种情况：

| coverage | 含义 |
|---|---|
| `COMPLETE` | 留存完整覆盖请求窗口（窗口里有数据，且不与淘汰区相交） |
| `PARTIAL_EVICTED` | 窗口与已淘汰时间区间相交，部分历史已被淘汰，结果可能不完整 |
| `NO_DATA` | 留存是完整的，但窗口内确实没有任何已接受遥测（自然也没有告警） |

### 规则版本与兼容

- 试算入参是一份**完整候选规则集**（`TrialRule`，含尚未生效的阈值/算子/启用状态）：
  与当前规则 `ruleId` 相同视为“修改”，否则视为“新增”；候选集里没有的当前规则视为“删除”。
- 候选规则**不会**写入线上规则库，也不占用线上版本号。试算内部为其分配确定版本：
  修改的规则取“当前版本 + 1”，新增规则取 `0`（试算初版），仅用于结果标注与差异复核。
- 同一批历史 + 同一份候选规则，重复试算**结果逐字节一致**（不使用当前时钟、随机数、锁顺序）。

### 试算结果与差异

- `baseline`：当前生效规则在同一窗口的确定性回放；`candidate`：候选规则回放。
  每侧都给出 `episodes`（完整告警段：触发时刻、触发输入值、段内峰值、恢复时刻、持续时长）。
- `diffs` 三类：
  - `NEW`：候选下新多出来的告警段；
  - `REMOVED`：当前会报、候选下不报了；
  - `CHANGED`：两侧都报，但触发时刻或恢复时刻/持续时长变了。
- 每条差异都带 `baselineVersion / candidateVersion` 与两侧告警段（含**输入值与时刻**），可直接复核
  “是基于哪版规则、由哪些输入值算出来的”。
- 说明：试算从“窗口起点无未决告警”的固定初始状态回放，回答的是“**规则本身**在这段历史上会报什么”；
  线上的人工**抑制（suppress）**是运维动作，不纳入试算状态机。

---

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| PUT | `/api/devices` | 注册设备（含指标合法区间） |
| GET | `/api/devices`, `/api/devices/{id}` | 查询设备 |
| POST | `/api/telemetry/reports` | 遥测接入 |
| GET | `/api/shadows/{deviceId}` | 影子快照 |
| PUT | `/api/shadows/{deviceId}/desired` | desired 乐观写入 |
| PUT | `/api/alerts/rules` | 创建/变更规则（自动版本递增） |
| GET | `/api/alerts/rules/{deviceId}` | 查设备的规则 |
| POST | `/api/alerts/rules/{ruleId}/suppress?durationMillis=` | 抑制活动告警 |
| GET | `/api/alerts/devices/{deviceId}` | 查设备全部告警 |
| GET | `/api/alerts/history/devices/{deviceId}` | 设备判定历史 |
| GET | `/api/alerts/history/rules/{ruleId}` | 规则判定历史（可复现） |
| POST | `/api/trials/what-if` | 规则试算：候选规则在历史窗口回放并与当前规则对照（只读） |
| GET | `/api/trials/history/{deviceId}?fromTs=&toTs=` | 查询留存的原始遥测与覆盖/淘汰边界（只读） |
| POST | `/api/simulator/tick` | 推进一次温度振荡周期 |
| POST | `/api/simulator/produce` | 产生一条指定温度上报 |
| POST | `/api/simulator/arbitrary` | 产生任意 seq/ts 上报（乱序/迟到演示） |
| POST | `/api/simulator/duplicate` | 产生完全相同的重传 |

应用启动时已播种两台设备（`sim-thermo-001/002`，指标 `temperature[-40,85]`、
`humidity[0,100]`）以及规则 `rule-temp-high`（`temperature > 30`，设备 001）。

---

## 6. 本地验证

### 6.1 运行测试（乱序/重复/并发/规则变更/历史试算全覆盖）

```bash
mvn test
```

25 个用例，测试日志会打印**输入与判定依据**，例如：

```
[输入] seq=11 ts偏移=10ms metrics={temperature=21.0} -> [结论] status=ACCEPTED version=2 ...
[输入] seq=9  ts偏移=-100ms ...                        -> [结论] status=STALE version=2 ...
[并发越限结果] accepted=16 TRIGGERED=1 STILL_ACTIVE=15 告警实体数=1
```

测试类与场景对应：

| 测试类 | 覆盖场景 |
|---|---|
| `TelemetryOrderingTest` | 乱序、重复、迟到、降级、全越界拒绝、未知设备/空载荷/NaN/未来时间戳 |
| `ShadowConsistencyTest` | desired 版本冲突、非法状态、20 线程并发写（恰好一成）、reported/desired 并发交错一致性 |
| `AlertLifecycleTest` | 触发/持续/恢复、抑制/窗口到期自动恢复、停用、指标缺失 |
| `AlertConcurrencyTest` | 16 线程并发越限：单告警、无重复、无漏报、历史自洽 |
| `RuleChangeReproducibilityTest` | 阈值 30→40 版本化变更、历史按 v1/v2 复现、停用后重启用 |
| `RuleTrialDiffTest` | 试算差异 NEW/REMOVED/CHANGED 正确、重复试算逐字段一致（可复现）、试算只读不改动线上 |
| `TelemetryRetentionTest` | 有界留存 FIFO 淘汰、`COMPLETE/PARTIAL_EVICTED/NO_DATA` 区分、被拒/重复/迟到不留存 |
| `TrialConcurrencyTest` | 8 上报线程×2 设备 + 1000 规则反复改版 + 4 试算线程并发：不报错、不丢不重、版本递增 |
| `TrialApiIntegrationTest` | 经 MockMvc 验证试算与历史查询 HTTP 链路、差异内容、非法入参/未知设备 400 |
| `EndToEndIntegrationTest` | 经 MockMvc HTTP 走完整链路并断言各入口结论一致 |

### 6.2 启动服务并手工演练

```bash
mvn spring-boot:run
# 想让模拟器自动周期上报：
# mvn spring-boot:run -Dspring-boot.run.arguments=--simulator.enabled=true
```

```bash
# 1) 正常上报（越限，触发告警）
curl -s -X POST localhost:8080/api/telemetry/reports -H 'Content-Type: application/json' -d '{
  "deviceId":"sim-thermo-001","seq":1,"ts":'$(date +%s%N | cut -c1-13)',
  "metrics":{"temperature":36.5,"humidity":55}}'

# 2) 重传（seq 相同）→ DUPLICATE
curl -s -X POST localhost:8080/api/simulator/duplicate -H 'Content-Type: application/json' -d '{
  "deviceId":"sim-thermo-001","seq":1,"ts":1,"metrics":{"temperature":36.5,"humidity":55}}'

# 3) 影子快照
curl -s localhost:8080/api/shadows/sim-thermo-001

# 4) desired 乐观写入：先无版本写一次（得到 desiredVersion=1），
#    再带过期版本 0 写入即得到 VERSION_CONFLICT（响应中带回当前版本便于重试）
curl -s -X PUT localhost:8080/api/shadows/sim-thermo-001/desired -H 'Content-Type: application/json' \
  -d '{"desired":{"targetTemp":25.0}}'
curl -s -X PUT localhost:8080/api/shadows/sim-thermo-001/desired -H 'Content-Type: application/json' \
  -d '{"expectedVersion":0,"desired":{"targetTemp":26.0}}'

# 5) 抑制活动告警 10 秒
curl -s -X POST 'localhost:8080/api/alerts/rules/rule-temp-high/suppress?durationMillis=10000'

# 6) 改阈值（产生 ruleVersion=2），再查历史复现
curl -s -X PUT localhost:8080/api/alerts/rules -H 'Content-Type: application/json' -d '{
  "ruleId":"rule-temp-high","deviceId":"sim-thermo-001",
  "metric":"temperature","operator":"GT","threshold":40.0,"enabled":true}'
curl -s localhost:8080/api/alerts/history/rules/rule-temp-high

# 7) 规则上线前历史试算（不改线上）：候选把阈值放宽到 40，
#    返回 baseline/candidate 两侧告警段与 diffs（NEW/REMOVED/CHANGED）及 coverage
curl -s -X POST localhost:8080/api/trials/what-if -H 'Content-Type: application/json' -d '{
  "deviceId":"sim-thermo-001","fromTs":1700000000000,"toTs":1700000099000,
  "rules":[{"ruleId":"rule-temp-high","deviceId":"sim-thermo-001",
            "metric":"temperature","operator":"GT","threshold":40.0,"enabled":true}]}'

# 8) 只查留存的原始遥测（含 coverage 与 evictedHighTs 淘汰边界）
curl -s 'localhost:8080/api/trials/history/sim-thermo-001?fromTs=1700000000000&toTs=1700000099000'
```

---

## 7. 代码结构

```
domain/    领域类型：上报、影子快照、规则、告警实体、评估记录、各类结论枚举
           试算类型：TrialRule（候选规则）、TrialEpisode（试算告警段）、TrialRun、
                     EpisodeDiff（差异）、WhatIfResult、TrialCoverage、TelemetryHistoryWindow
store/     DeviceRegistry、ShadowStore、AlertStore、DeviceLocks（全部进程内、线程安全）
           TelemetryHistoryStore：有界原始遥测留存（每设备 + 全局容量、FIFO 淘汰、淘汰高水位）
service/   TelemetryService（接入管道，接受后在设备锁内留存历史）、ShadowService（desired/读取）、
           AlertService（线上状态机）、TrialService（试算编排：快照→双侧回放→差异对照）、
           TrialEngine（确定性、无副作用的本地回放状态机）
simulator/ DeviceSimulator（数据模拟）、BootSeeder（启动播种）
web/       REST 控制器与全局异常处理（含 TrialController 试算入口）
```

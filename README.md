# 设备遥测、设备影子与告警判定服务（本地可运行）

基于 Spring Boot 3.4 / Java 21 的进程内物联网数据链路演示：**设备遥测接入 → 设备影子（期望/上报状态）→ 阈值告警生命周期**。
全部状态保存在内存中，设备数据由进程内模拟器或 HTTP 请求产生，**不依赖外部中间件、真实设备或外部凭据**。

## 1. 构建与运行

```bash
# 运行全部测试（12 个用例，覆盖乱序/重复/并发/规则变更）
mvn test

# 启动服务（默认端口 8080）
mvn spring-boot:run

# 启动时自动跑一遍内置演示脚本（默认关闭）
mvn spring-boot:run -Dspring-boot.run.arguments=--simulator.demo-on-start=true
```

启动后访问一键演示接口（自动注册演示设备与规则，并投递乱序/重复/迟到数据）：

```bash
curl -s http://localhost:8080/api/demo
```

关键配置（`src/main/resources/application.properties`）：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `telemetry.late-millis` | 3000 | 迟到判定迟滞窗口（毫秒），见 §2 |
| `simulator.demo-on-start` | false | 启动时是否自动执行演示脚本 |

## 2. 遥测接入处理规则

每条上报形如：

```json
{
  "deviceId": "dev-1",
  "metrics": {"temp": 95.0},
  "timestamp": 1700000000000,
  "seq": 12,
  "messageId": "客户端生成的唯一幂等键"
}
```

- `seq`：设备内单调递增序列号，代表写入版本；
- `timestamp`：设备侧事件时间；
- `messageId`：报文唯一标识，**重发必须复用同一 messageId**，是幂等去重的依据。

接入检查按固定顺序执行，每条报文都得到**互斥且可区分**的结论，绝不静默丢弃：

| 结论 (`IngestionStatus`) | 含义 | 是否写影子/驱动告警 | HTTP 状态码 |
| --- | --- | --- | --- |
| `ACCEPTED` | 正常接入 | 是 | 200 |
| `ACCEPTED_GAP` | 正常接入，但检测到序列号跳号（可能丢报） | 是 | 200 |
| `ACCEPTED_LATE` | **迟到降级**：事件时间 < 已接受最大事件时间 − `late-millis`。仅留痕（记录 messageId 防重放），不推进位点 | 否 | 200 |
| `REJECTED_DUPLICATE` | 重复：messageId 已处理且负载一致（或同 seq 同负载） | 否 | 409 |
| `REJECTED_STALE` | 过期：seq 落后于已接受最大 seq，旧数据不覆盖新状态 | 否 | 409 |
| `REJECTED_CONFLICT` | 版本冲突：seq 相同但负载不一致 | 否 | 409 |
| `REJECTED_INVALID` | 非法：字段缺失、值为 NaN/Infinity、指标不在设备白名单等 | 否 | 422 |
| `REJECTED_UNKNOWN_DEVICE` | 设备未注册 | 否 | 422 |

判定优先级：结构合法性 → 设备注册 → 指标白名单 → messageId 幂等 → seq 乱序/过期/冲突 → 迟到水位 → 接受（含跳号检测）。
事件时间水位只随**已接受**的报文推进，重复、过期、迟到数据都不会移动水位。

## 3. 设备影子与状态一致性

每个设备维护一份影子（`ShadowState`）：

- `reported` / `reportedVersion`：最近一次有效遥测的指标与其 seq；更旧 seq 一律 `STALE` 拒绝；
- `desired` / `desiredVersion`：期望状态，初始版本为 `0`，每次成功下发 +1；
- `version`：影子整体版本，任一写入成功后 +1，用于解释状态演进顺序。

期望状态下发采用**乐观并发**：`POST /api/devices/{id}/shadow/desired?expectedVersion=<读取时的desiredVersion>`。

- 版本匹配 → 成功（`OK`，200）；
- 版本不匹配 → `VERSION_CONFLICT`（409），与 `STALE`（seq 过期）和 `INVALID_STATE`（指标非法/不在白名单/数值非有限数，422）明确区分；
- `expectedVersion=-1` 表示盲写，不做版本校验。

**多入口并发一致性**：系统按 `deviceId` 提供分段可重入锁（`StripedDeviceLocks`）。HTTP 接入、模拟器接入、期望状态下发都在同一把设备锁内执行「接入检查 → 影子写入 → 告警判定」，因此：

- 同一设备的所有写操作全局串行，跨设备互不阻塞；
- 影子 `reportedVersion` 单调不降，并发乱序报文中的旧 seq 被 `STALE` 拒绝，最终状态一定等于最大 seq；
- 每条上报都有明确的接受/拒绝计数（见并发测试：接受数 + STALE 数 = 提交总数）。

## 4. 告警语义与规则版本化

规则形如「某设备的某指标 `op` 阈值」，带抑制窗口 `suppressMillis`：`GT/GTE/LT/LTE`。
每次有效遥测（不含被拒绝与迟到降级数据）对设备的全部启用规则做一次判定，维护 `(device, rule)` 维度的单一告警状态：

| 当前状态 | 条件 | 结果 |
| --- | --- | --- |
| 无活动告警 | 条件成立 | `FIRE`，进入 ACTIVE |
| ACTIVE | 条件成立，距上次 FIRE 的事件时间差 **≤ 抑制窗口** | `SUPPRESSED`（留痕，不产生新 FIRE） |
| ACTIVE | 条件成立且**超过**抑制窗口 | 再次 `FIRE`（周期性提醒） |
| ACTIVE | 条件不成立 | `RECOVER`，回到 RECOVERED |
| 无活动告警 | 条件不成立 | 无事件（不制造噪声） |

规则停用后不再产生新事件，但历史事件全部保留。

**为什么并发不会重复告警或漏报**：判定与接入共用同一设备锁，同一时刻只有一条报文在评估某设备的告警状态；同一时刻并发越限的报文，只有第一条产生 `FIRE`，其余全部 `SUPPRESSED`。

**规则变更后历史判定可复现**：

- 规则以 `(ruleId, revision)` **不可变版本链**保存，任何修改都追加新 revision，不就地覆盖；
- 每个告警事件固化产生时的 `revision`、指标快照、seq、事件时间与文字判定依据（`basis`）；
- `AlarmService.reevaluate(ruleId, revision, history)` 可用任意历史 revision 对同一批历史遥测离线复算，结果与当时实判一致；用新 revision 复算则得到新规则下的结论，两者可对比。

## 5. HTTP 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/devices` | 注册设备（body：`{deviceId, allowedMetrics}`） |
| POST | `/api/telemetry` | 上报遥测，返回接入结论 + 本次告警事件 |
| GET | `/api/devices/{id}/shadow` | 读取影子快照 |
| POST | `/api/devices/{id}/shadow/desired?expectedVersion=` | 下发期望状态（乐观锁） |
| POST | `/api/rules` | 注册规则 |
| PUT | `/api/rules/{ruleId}?threshold=&op=&suppressMillis=&enabled=` | 修改规则（产生新 revision，参数可选） |
| GET | `/api/rules/{ruleId}` / `/api/rules/{ruleId}/revisions/{n}` | 当前规则 / 历史版本 |
| GET | `/api/alarms/devices/{id}/rules/{ruleId}/state` | 当前告警状态 |
| GET | `/api/alarms/devices/{id}/rules/{ruleId}/events` | 告警事件历史（复现依据） |
| GET | `/api/demo` | 一键演示脚本 |

快速手动验证：

```bash
curl -s -X POST http://localhost:8080/api/devices -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-1","allowedMetrics":["temp"]}'
curl -s -X POST http://localhost:8080/api/rules -H 'Content-Type: application/json' \
  -d '{"id":"r1","deviceId":"dev-1","metric":"temp","op":"GT","threshold":90,"suppressMillis":1000}'
curl -s -X POST http://localhost:8080/api/telemetry -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-1","metrics":{"temp":95},"timestamp":1700000001000,"seq":1,"messageId":"m1"}'
curl -s http://localhost:8080/api/devices/dev-1/shadow
curl -s http://localhost:8080/api/alarms/devices/dev-1/rules/r1/events
```

## 6. 测试覆盖

测试均使用 Spring 上下文，日志会打印**测试输入与判定依据**（`[测试输入]` / `[接入结论]` / `[告警判定]`）：

| 测试类 | 覆盖场景 |
| --- | --- |
| `TelemetryOrderingTest` | 乱序 STALE、跳号 GAP、messageId/seq 重复、同 seq 冲突、迟到 LATE 不覆盖影子、非法/未知设备 |
| `DeviceShadowConcurrencyTest` | 乐观锁 VERSION_CONFLICT、盲写、非法 desired 状态、16 线程并发写影子不回退、每条结论可计数 |
| `AlarmLifecycleTest` | FIRE→SUPPRESSED→再 FIRE→RECOVER→再 FIRE 自洽；重复/过期报文不产生告警；32 并发越限恰好 1 个 FIRE |
| `RuleChangeReproducibilityTest` | 阈值变更产生新 revision、历史事件 revision 固化、用历史/新 revision 复算结论可对比、停用规则不留新事件 |
| `HttpEntryIntegrationTest` | HTTP 全链路、200/409/422 状态码与响应结论一致、影子与告警查询 |

```bash
mvn test                                  # 全部
mvn test -Dtest=TelemetryOrderingTest     # 单个类
```

## 7. 代码结构

```
common/      IngestionStatus（接入结论）、StripedDeviceLocks（设备级分段锁）
device/      DeviceProfile、DeviceRegistry
telemetry/   TelemetryMessage/Result、TelemetryIngestionService（校验/去重/乱序/迟到）
shadow/      ShadowState、DeviceShadowService（版本与乐观并发）
rule/        AlertRule（不可变版本）、ComparisonOp、RuleService（版本链）
alarm/       AlarmEvent/State/Decision、AlarmService（触发/抑制/恢复、复算）
pipeline/    IngestionPipeline（设备锁内编排三阶段）
simulator/   DeviceTelemetrySimulator（进程内数据源）、SimulatorRunner（可选启动演示）
web/         各 HTTP 入口与 /api/demo
```

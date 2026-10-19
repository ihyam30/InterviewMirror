# 面试口径：为什么用 PostgreSQL 存 checkpoint 和任务

**契约版本：** `interviewmirror.persistence-qa.v1.0.0`
**回答依据：** 本文所有数字和代码锚点都来自当前仓库，可直接对照源码复述。

---

## 0. 一句话答案（30 秒版）

> 因为我需要的不是"一个更快的队列"，而是**跨状态的原子提交和一个能查询、能审计的事实来源**。这个项目里一次回答的落地是：锁 session → 幂等写回答 → 写 `PENDING` 转移 → 写持久化事件，**这四步必须在同一个事务里**；worker 领取用的是 `UPDATE … WHERE status='PENDING'` 的条件更新，靠数据库行锁保证单消费者；进度靠租约 + 过期回收，而不是靠消息中间件的重投。这些语义 PostgreSQL 本来就给我了，Redis 或 MQ 反而要把它们重新拼一遍。checkpoint 必须和业务数据同库同事务，所以它也只能在 PostgreSQL。

---

## 1. 这个项目里 PostgreSQL 到底承担了什么

不是"有一个队列正好用了 PG"，而是**三张表 + 一个 saver**，全是同一套机制：

| 用途 | 落地形式 | 关键机制 |
|---|---|---|
| 资料解析任务 | `document_parse_tasks` | 条件更新领取 + 3 分钟租约 |
| 报告生成任务 | `report_tasks` | 条件更新领取 + 5 分钟租约 |
| 回答推进（图推进） | interview transition 表 | `PENDING → PROCESSING` 单活跃消费者 |
| 图控制状态 | LangGraph4j `PostgresSaver` | `threadId = interviewId`，每节点完成后落库 |
| SSE 事件流 | 持久化事件表 | `Last-Event-ID` 从库里回放 |

三个 worker 都是同一个模式：`@Scheduled(fixedDelayString = …poll-interval:PT2S)`，每 2 秒 `recoverExpired(...)` + `processOne()`。也就是说**"队列"在这套设计里就是一张有状态、有租约、可查询的表**。

---

## 2. 五个不能让步的理由（按重要性排序）

### 2.1 一致性边界：回答和图状态必须一起提交（最重要）

一次 `POST answers` 的顺序是（见 [INTERVIEW-STATE-MACHINE.md](../phase3/INTERVIEW-STATE-MACHINE.md) 第 24–30 行）：

```text
短事务：锁 session → 校验 owner/status/active turn
        → 按 clientRequestId 幂等写回答
        → 写 PENDING transition
        → 写持久化 SSE 事件
        → COMMIT
worker：条件更新 PENDING → PROCESSING（只有一个能成功）
        → GraphInput.resume() 推进 threadId=interviewId 的 checkpoint
        → 新事务写下一 turn / 完成状态 + 事件
```

这套顺序的价值是：**提交了回答就一定有对应的待推进任务；崩溃了也没有"回答在库里、任务不见了"的中间态**。如果 checkpoint 放 Redis、业务数据放 PostgreSQL，这四步就变成跨两个系统的双写：要么引入分布式事务，要么接受不一致再写补偿。这里的一致性不是性能问题，是业务正确性问题——丢一步图状态，这场面试就恢复不了。

### 2.2 幂等与单消费者：条件更新天然给我，不用引入分布式锁

领取任务就是一个条件更新（`backend/.../documents/DocumentRepository.java:86-93`）：

```sql
UPDATE document_parse_tasks
SET status = 'PROCESSING', attempt_count = attempt_count + 1,
    started_at = ?, worker_id = ?, lease_until = ?
WHERE id = ? AND owner_id = ? AND status = 'PENDING'
```

`taskUpdated != 1` 就 `continue` 试下一条。这一条语句同时给了三件事：**单消费者**（行锁 + 状态谓词）、**可重试**（`attempt_count`）、**完成时可判定"我是不是还在位"**（写回时复核 `workerId + attemptCount`，过期结果只记 `STALE_RESULT_IGNORED`，绝不覆盖新尝试）。

Redis 要做到同样的事，得自己用 Lua 脚本保证「查状态 + 抢锁 + 写 owner」的原子性，再自己实现 fencing token 防旧 worker 覆盖，最后还是要落库才能查。**我相当于用一条 SQL 替掉了分布式锁 + fencing 的一大坨代码。**

### 2.3 可查询 / 可审计：任务本身是产品功能，不只是内部管道

API 直接暴露任务的 `queuedAt`、`startedAt`、`completedAt`、`durationMs`、`retryCount`、`attemptCount`、错误码和安全错误信息（[PARSING-PIPELINE.md](../phase2/PARSING-PIPELINE.md) 第 18 行）。报告性能验收也是直接读 `report_tasks` 的 `started_at / completed_at / duration_ms`（`record-report-performance.ps1`）。

如果任务只活在 Redis list 或 MQ 里，要回答"这个用户的简历为什么还没解析好"，就得再建一张状态表或者去翻日志——那等于**把权威状态又搬回数据库**，中间件只剩下"通知"的功能。

### 2.4 checkpoint 语义本身要求持久事务存储 + 恢复时做 owner 鉴权

- 恢复路径固定是：先按 `owner_id` 查业务 interview，**没过 owner 校验就不调用 `lastStateOf(threadId)`**（[INTERVIEW-GRAPH.md](../phase3/INTERVIEW-GRAPH.md) 第 33 行）。
- `threadId` 就是 interview UUID，checkpoint 是**用户数据的可恢复副本**，所以删除面试时必须同时删业务记录和 thread checkpoint（合规清单里已列为待办）。
- checkpoint 里存的是**图控制状态**：问题计划、`mainQuestionIndex`、`followupCount`、`replaceCount`、当前问题、最近回答、已消费 turn ID；**完整 transcript 以 `interview_turns` 为准，不把简历/题库复制进每个 checkpoint**（[INTERVIEW-GRAPH.md](../phase3/INTERVIEW-GRAPH.md) 第 31 行）。

所以单场数据量很小：6 个主问题 + 每主问题最多 2 次追问 ≈ 24 次交互，每个节点完成写一次 ≈ 几十行快照。**这个量级 PostgreSQL 一点压力都没有，而"可恢复 + 可删除 + 可鉴权"是硬要求。**

### 2.5 运维复杂度：少一个中间件就少一类故障

这是个本地演示/开发定位的项目，Compose 只有 PostgreSQL、MinIO、Backend、Frontend 四个服务（`docker-compose.yml`）。引入 Redis 意味着：持久化策略（AOF/RDB）选型、内存上限与淘汰策略、主从/哨兵、"重启后数据还在不在"要单独验收。而我现在的验收标准很直白：**双 JVM 硬退出后恢复必须通过**——`InterviewPostgresCheckpointRecoveryTest` 和 `InterviewBackendRestartRecoveryTest` 就是在证明这件事，不是靠"中间件应该是可靠的"这种假设。

---

## 3. 如果面试官问"Redis 不是更快吗"

快，但**快错了地方**。这个项目的延迟大头是模型调用：报告任务 p95 门槛是 **60 秒**，模型 TTFT p95 门槛是 **5 秒**；而任务表领取是一条带主键谓词的 `UPDATE`，亚毫秒级；轮询间隔 2 秒。**用 Redis 把 2 秒轮询降到 5 毫秒，在一分钟级的任务里完全不可感知，却换来了跨系统一致性和一个新中间件的持久化风险。**

反过来问一个更有杀伤力的问题：**"如果 Redis 挂了会怎样？"** 业务数据还在，但图控制状态丢了 → 面试无法恢复 → 这是业务正确性问题；而 PostgreSQL 挂了我的服务本来就整体不可用，**故障域是重合的**，没有新增风险面。

---

## 4. 为什么"checkpoint 更不能放 Redis"

| 维度 | PostgreSQL checkpoint | Redis checkpoint |
|---|---|---|
| 与业务表的一致性 | 同一数据源、同一事务边界 | 跨系统双写，必须补偿 |
| 恢复判定 | `owner` 校验 + 事务内一致读 | 还要额外校验两边的版本对齐 |
| 数据安全 | 默认持久、可备份、可审计 | 内存为主，需显式配置 AOF/RDB 并单独验收 |
| 快照版本 | 每节点一版，天然留历史 | 要自己管内存与 TTL |
| 删除合规 | 与业务记录同库级联删除 | 还要单独清 Redis keyspace |

一句话：**checkpoint 不是缓存，是状态。放缓存里的状态，就是迟早要还的技术债。**

---

## 5. 如果一定要用 Redis / 消息队列，正确做法是什么

先说结论：**不是"把 PostgreSQL 换成 Redis"，而是"PostgreSQL 仍然是权威，Redis/MQ 只做唤醒信号"**。这个原则必须在前 10 秒说出来，否则后面全错。

### 5.1 架构：权威层 + 加速层

```text
生产者（API）
  ① 同一 PostgreSQL 事务：业务数据 + 任务行 (PENDING)   ← 权威，不依赖 Redis
  ② 事务提交后发布 wakeup 信号（MQ / Redis Stream）      ← 只是"有活了"
消费者（worker）
  ③ 收到消息 → 回库条件更新抢租约
     UPDATE … SET status='PROCESSING', worker_id=?, lease_until=?
     WHERE id=? AND status='PENDING'
     · 抢到 → 干活 → 完成后回库条件写回（复核 worker+attempt）
     · 没抢到 / 已完成 → ACK 丢弃（重复投递天然幂等）
  ④ ACK
兜底
  ⑤ 低频 sweep（30 秒）扫 PENDING 与租约过期       ← 丢消息、消费者全挂也能自愈
```

为什么这么设计：

1. **不在数据库事务里发消息**。数据库事务和 MQ 是两个系统，没有 XA 就不要假装有。正确顺序是"先提交数据库，再发信号"，并用 **outbox**（同一事务写 outbox 表，由发布器扫表投递）保证"提交了就一定发得出去"。
2. **消息只是提示，不是事实**。所以消费端必须回库抢租约，重复投递、乱序投递都不会造成重复执行——**幂等性由数据库谓词保证，不由消息中间件的投递语义保证**。
3. **永远保留兜底轮询**。MQ 能做到 at-least-once，但做不到"消费失败一定被感知"；`recoverExpired` 这类扫表就是最后一道保险。

### 5.2 各中间件的具体做法

| 方案 | 领取/消费 | 需要注意 |
|---|---|---|
| Redis List | `BRPOP` 阻塞取任务 | 消费中进程崩溃 → 消息已出队丢失；需要 `BRPOPLPUSH` 到 processing list + 定时清理，等于手写租约 |
| Redis Stream | consumer group + `XREADGROUP` / `XACK`，`XPENDING` 看未 ACK | 自带 pending 列表，比 List 靠谱；内存与 `MAXLEN` 截断要规划 |
| Redis 锁 | `SET key val NX PX ttl` + Lua 校验释放 | 只能做互斥，**不能替代状态机**；仍要回库复核，否则锁过期后旧 worker 会覆盖新结果 |
| RabbitMQ | 手动 ACK + `prefetch` 限流 + 死信队列 DLX | 崩溃时 unacked 会重投，语义接近"至少一次"；要设 `x-message-ttl`、DLQ 兜底 |
| Kafka | 分区内有序 + 消费者组 + offset 提交 | 适合高吞吐流式；单任务场景过重，仍要防重复消费 |

### 5.3 落地时必须写出来的四件事

1. **幂等**：唯一约束 + 条件更新。这个项目已有的 `(owner_id, client_request_id)`、`(interview_id, answer_request_id)` 唯一键和 `replace_request_id` 单活跃 claim 就是同一套思路，换队列不需要改。
2. **可见性超时 ≥ 任务最坏耗时**：资料解析租约 3 分钟、报告 5 分钟。消息的 ack timeout 必须对齐这两个数，否则会出现"消费者还在跑，消息已被重投给第二个消费者"。
3. **有序性**：同一场面试的推进必须串行。Kafka 按 `interviewId` 做 partition key，或者每条消息带 `version/attempt` 让旧消息被条件更新拒绝。
4. **可观测性**：拿 **PENDING 任务的最老年龄**当核心 SLI（我现在的表能直接 `SELECT` 出来，用 MQ 就要额外埋点）；再配队列深度、重投次数、DLQ 数量告警。

### 5.4 什么时候我会真的换过去（决策指标，别说"以后量大再说"）

- 任务创建速率超过 **数百/秒**，或 PENDING 最大年龄的 p95 稳态超过**秒级**（当前 2 秒轮询根本达不到）；
- 消费者需要**独立伸缩**、任务类型需要**按优先级/资源隔离**（比如 MinerU 解析和报告生成要分开扩容）；
- 需要**削峰**：模型服务限流下要把请求排队而不是堆积在数据库里。

在这些指标出现之前，多加一个中间件只会增加"消费者没 ACK 卡住""消息和库不一致"这类**我原本不需要处理**的故障模式。这就是我选 PostgreSQL 的全部理由——**不是它更快，而是它在当前规模下用更少的部件给了我更强的保证。**

---

## 6. 现场演示锚点（说到哪里就点哪里）

| 我要证明的点 | 代码/文档位置 |
|---|---|
| 领取 = 条件更新，单消费者 | `backend/src/main/java/local/interviewmirror/backend/documents/DocumentRepository.java:86-93` |
| 过期结果不许覆盖新尝试 | 同文件 `complete/fail` 里的 `lockCurrentClaim`；worker 日志 `STALE_RESULT_IGNORED` |
| checkpoint 用真 PostgresSaver | `backend/src/main/java/local/interviewmirror/backend/interviews/InterviewCheckpointConfiguration.java:21-26` |
| 每节点落库 + 中断恢复 | 同文件 `:135-136`；`docs/phase3/INTERVIEW-GRAPH.md:29-33` |
| 持久化顺序与幂等 | `docs/phase3/INTERVIEW-STATE-MACHINE.md:24-40` |
| 跨 JVM 恢复有实测 | `docs/phase3/EVALUATION.md:57-63`；`scripts/phase3/run-backend-restart-smoke.ps1` |
| 任务状态是产品功能 | `docs/phase2/PARSING-PIPELINE.md:18,47-51` |

---

## 7. 高频追问与答法

**Q：轮询不是浪费资源吗？**
一条带主键谓词的 `UPDATE`，2 秒一次；相比分钟级的模型调用可以忽略。而且它换来的是"没有任何中间件也能自愈"。真要省，可以把 sweep 间隔按队列空置情况退避。

**Q：数据库会不会成为瓶颈？**
当前瓶颈是模型服务，不是数据库。真到瓶颈时第一优化是**提高轮询效率**（加状态索引、按 owner 分片、缩短事务），而不是换存储。表结构本身已经给了分片键（`owner_id`）。

**Q：为什么不用 `SELECT … FOR UPDATE SKIP LOCKED`？**
这在 PostgreSQL 里是很好的取任务写法（我这里是先 `LIMIT 10` + 条件更新重试，效果等价，且状态迁移和文档状态在一个事务里一起改）。两者都比 Redis 方案强的地方是：**领取结果和状态变更在同一个事务里可见**。

**Q：这样是不是把消息队列的事自己写了一遍？**
是，但只写了这个场景需要的那 20%：领取、租约、重试、过期回收。消息队列剩下的 80%（路由、分区、削峰、多语言客户端）我当前用不上，用了反而要额外运维。

**Q：如果面试官坚持"生产环境就该上 MQ"呢？**
可以顺着答：生产上我会把它做成 5.1 的混合架构——**PostgreSQL 权威 + MQ 唤醒 + 扫表兜底**，这样既拿到低延迟和削峰，又不放弃单消费者与幂等这两条底线。然后给出 5.4 的切换指标。

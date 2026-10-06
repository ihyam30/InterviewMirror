-- Local-only deterministic fixtures. IDs are stable so repeated seeding is idempotent.
WITH demo_owner AS (SELECT id FROM app_users WHERE username = 'demo1')
INSERT INTO managed_documents (
    id, owner_id, file_id, document_type, title, status,
    parsed_content, content, content_version, confirmed_version, confirmed_at
)
SELECT 'a5500000-0000-4000-8000-000000000001'::uuid, id, NULL, 'RESUME', '演示简历 · Java 后端工程师', 'CONFIRMED',
       '{"schemaVersion":"interviewmirror.resume-content.v1","personalInfo":{"name":"林知远","targetRole":"Java 后端工程师","location":"杭州"},"education":[{"school":"浙江理工大学","major":"计算机科学与技术","degree":"本科","startDate":"2022-09","endDate":"2026-06"}],"experiences":[{"company":"示例科技","role":"后端开发实习生","startDate":"2025-06","endDate":"2025-09","description":"参与内部知识检索服务开发，负责接口、索引更新与监控。"}],"projects":[{"name":"校园知识问答平台","role":"后端开发","technologies":["Java","Spring Boot","PostgreSQL","Redis"],"description":"实现文档上传、分段检索和带引用的问答接口。"}],"skills":["Java 基础与集合","Spring Boot 与 REST API","PostgreSQL 索引与事务","Redis 缓存与幂等设计"],"awards":[],"metrics":[]}'::text,
       '{"schemaVersion":"interviewmirror.resume-content.v1","personalInfo":{"name":"林知远","targetRole":"Java 后端工程师","location":"杭州"},"education":[{"school":"浙江理工大学","major":"计算机科学与技术","degree":"本科","startDate":"2022-09","endDate":"2026-06"}],"experiences":[{"company":"示例科技","role":"后端开发实习生","startDate":"2025-06","endDate":"2025-09","description":"参与内部知识检索服务开发，负责接口、索引更新与监控。"}],"projects":[{"name":"校园知识问答平台","role":"后端开发","technologies":["Java","Spring Boot","PostgreSQL","Redis"],"description":"实现文档上传、分段检索和带引用的问答接口。"}],"skills":["Java 基础与集合","Spring Boot 与 REST API","PostgreSQL 索引与事务","Redis 缓存与幂等设计"],"awards":[],"metrics":[]}'::text,
       1, 1, CURRENT_TIMESTAMP
FROM demo_owner
ON CONFLICT (id) DO UPDATE SET
    title = EXCLUDED.title, status = 'CONFIRMED', parsed_content = EXCLUDED.parsed_content,
    content = EXCLUDED.content, content_version = 1, confirmed_version = 1,
    confirmed_at = COALESCE(managed_documents.confirmed_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
WHERE managed_documents.owner_id = EXCLUDED.owner_id AND managed_documents.document_type = 'RESUME';

WITH demo_owner AS (SELECT id FROM app_users WHERE username = 'demo1')
INSERT INTO managed_documents (
    id, owner_id, file_id, document_type, title, status,
    parsed_content, content, content_version, confirmed_version, confirmed_at
)
SELECT 'a5500000-0000-4000-8000-000000000002'::uuid, id, NULL, 'QUESTION_BANK', '演示题库 · Java 后端基础', 'CONFIRMED',
       '{"schemaVersion":"interviewmirror.question-bank-content.v1","questions":[{"id":"demo-q1","position":1,"stem":"请说明 Java HashMap 在 JDK 8 中的底层结构与扩容过程。","answer":"结合数组、链表与红黑树说明 put 流程；达到阈值时会扩容并重新分配桶位置。","category":"集合"},{"id":"demo-q2","position":2,"stem":"什么是 Java 内存模型？volatile 解决了什么问题？","answer":"说明主内存与工作内存、可见性与有序性；volatile 提供可见性和有限的有序性保证，不提供复合操作原子性。","category":"并发"},{"id":"demo-q3","position":3,"stem":"数据库事务的隔离级别有哪些？如何处理不可重复读？","answer":"列出读未提交、读已提交、可重复读和串行化，并结合 MVCC 或锁解释实现取舍。","category":"数据库"},{"id":"demo-q4","position":4,"stem":"Redis 缓存穿透、击穿和雪崩分别是什么，如何应对？","answer":"分别说明不存在键、热点键过期和大量键同时失效；可用空值/布隆过滤器、互斥重建、随机过期与限流降级。","category":"缓存"},{"id":"demo-q5","position":5,"stem":"如何设计一个安全且可重试的 REST API？","answer":"说明认证鉴权、输入校验、幂等键、稳定错误码、超时与重试边界，并通过测试验证。","category":"后端设计"},{"id":"demo-q6","position":6,"stem":"请介绍一次你定位并修复线上或测试环境故障的过程。","answer":"按现象、影响范围、假设、证据、修复、回归与复盘的顺序说明；只使用演示经历，不代表真实生产经验。","category":"故障排查"},{"id":"demo-q7","position":7,"stem":"线程池的核心参数有哪些，任务队列满时会发生什么？","answer":"解释核心线程数、最大线程数、存活时间、队列和拒绝策略，并结合任务类型设置容量与监控。","category":"并发"},{"id":"demo-q8","position":8,"stem":"为什么数据库索引会影响写入性能？","answer":"索引需要维护额外数据结构，增加存储与写放大；应依据查询模式和执行计划选择索引。","category":"数据库"}]}'::text,
       '{"schemaVersion":"interviewmirror.question-bank-content.v1","questions":[{"id":"demo-q1","position":1,"stem":"请说明 Java HashMap 在 JDK 8 中的底层结构与扩容过程。","answer":"结合数组、链表与红黑树说明 put 流程；达到阈值时会扩容并重新分配桶位置。","category":"集合"},{"id":"demo-q2","position":2,"stem":"什么是 Java 内存模型？volatile 解决了什么问题？","answer":"说明主内存与工作内存、可见性与有序性；volatile 提供可见性和有限的有序性保证，不提供复合操作原子性。","category":"并发"},{"id":"demo-q3","position":3,"stem":"数据库事务的隔离级别有哪些？如何处理不可重复读？","answer":"列出读未提交、读已提交、可重复读和串行化，并结合 MVCC 或锁解释实现取舍。","category":"数据库"},{"id":"demo-q4","position":4,"stem":"Redis 缓存穿透、击穿和雪崩分别是什么，如何应对？","answer":"分别说明不存在键、热点键过期和大量键同时失效；可用空值/布隆过滤器、互斥重建、随机过期与限流降级。","category":"缓存"},{"id":"demo-q5","position":5,"stem":"如何设计一个安全且可重试的 REST API？","answer":"说明认证鉴权、输入校验、幂等键、稳定错误码、超时与重试边界，并通过测试验证。","category":"后端设计"},{"id":"demo-q6","position":6,"stem":"请介绍一次你定位并修复线上或测试环境故障的过程。","answer":"按现象、影响范围、假设、证据、修复、回归与复盘的顺序说明；只使用演示经历，不代表真实生产经验。","category":"故障排查"},{"id":"demo-q7","position":7,"stem":"线程池的核心参数有哪些，任务队列满时会发生什么？","answer":"解释核心线程数、最大线程数、存活时间、队列和拒绝策略，并结合任务类型设置容量与监控。","category":"并发"},{"id":"demo-q8","position":8,"stem":"为什么数据库索引会影响写入性能？","answer":"索引需要维护额外数据结构，增加存储与写放大；应依据查询模式和执行计划选择索引。","category":"数据库"}]}'::text,
       1, 1, CURRENT_TIMESTAMP
FROM demo_owner
ON CONFLICT (id) DO UPDATE SET
    title = EXCLUDED.title, status = 'CONFIRMED', parsed_content = EXCLUDED.parsed_content,
    content = EXCLUDED.content, content_version = 1, confirmed_version = 1,
    confirmed_at = COALESCE(managed_documents.confirmed_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
WHERE managed_documents.owner_id = EXCLUDED.owner_id AND managed_documents.document_type = 'QUESTION_BANK';

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM managed_documents WHERE id IN (
        'a5500000-0000-4000-8000-000000000001'::uuid,
        'a5500000-0000-4000-8000-000000000002'::uuid
    ) AND owner_id = (SELECT id FROM app_users WHERE username = 'demo1') AND status = 'CONFIRMED') <> 2 THEN
        RAISE EXCEPTION 'Phase 5 demo fixtures were not seeded for demo1';
    END IF;
END $$;

import csv

rows = []
with open('test/monitor_perf.csv', encoding='utf-8') as f:
    for r in csv.DictReader(f):
        rows.append(r)

def stat(col):
    vals = [float(r[col]) for r in rows if r.get(col) not in ('', '-1', None)]
    vals = [v for v in vals if v >= 0]
    if not vals:
        return 0, 0
    return max(vals), sum(vals)/len(vals)

print("=" * 60)
print("性能压测监控指标分析（22:53 - 23:20，覆盖 21 轮压测）")
print("=" * 60)
for col, name in [
    ("mq_publish_rate", "MQ 生产速率(接口受理=成功下单速率, 条/s)"),
    ("mq_deliver_rate", "MQ 消费速率(落库 TPS, 条/s)"),
    ("mq_ready",        "MQ 积压消息数(ready)"),
    ("redis_ops",       "Redis 每秒操作数"),
    ("conn_active",     "DB 活跃连接数"),
    ("conn_pending",    "DB 等待连接数"),
]:
    mx, avg = stat(col)
    print(f"  {name:>42}  峰值={mx:>8.1f}  平均={avg:>8.1f}")

# 分段：按 publish_rate 找压测活跃区间（publish>0 表示正在压测）
active = [r for r in rows if float(r.get('mq_publish_rate', 0) or 0) > 0]
if active:
    print("-" * 60)
    print(f"压测活跃区间样本数: {len(active)}")
    for col, name in [("mq_publish_rate", "生产速率"), ("mq_deliver_rate", "消费速率")]:
        mx, avg = stat_r = None, None
        vals = [float(r[col]) for r in active]
        print(f"  {name}  峰值={max(vals):.1f}  平均={sum(vals)/len(vals):.1f}  中位={sorted(vals)[len(vals)//2]:.1f}")

# 消费速率在压测期间的峰值（找最大）
d = [float(r['mq_deliver_rate']) for r in rows if float(r.get('mq_deliver_rate',0) or 0) > 0]
if d:
    d.sort()
    print("-" * 60)
    print(f"消费速率(落库TPS) 分布: 峰值={max(d):.1f} P50={d[len(d)//2]:.1f} P95={d[int(len(d)*0.95)]:.1f}")

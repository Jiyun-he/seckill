"""
压测后一致性核验：DB / Redis / MQ 一键检查，输出 PASS/FAIL 结论。

用法：
    python test/verify_pressure.py --initial-stock 100 --goods-id 1
"""

import argparse
import sys
import time

import pymysql
import redis
import requests

MYSQL = dict(host="localhost", port=3306, user="root", password="123456", database="seckill_db")
REDIS_HOST = "localhost"
REDIS_PORT = 6379
RABBITMQ_API = "http://localhost:15672/api"
RABBITMQ_USER = "seckill"
RABBITMQ_PASS = "seckill123"


def query(sql):
    conn = pymysql.connect(**MYSQL)
    try:
        with conn.cursor() as cur:
            cur.execute(sql)
            return cur.fetchall()
    finally:
        conn.close()


def wait_mq_empty(timeout=120):
    """等主队列与死信队列积压归零，返回是否清空。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            q = requests.get(f"{RABBITMQ_API}/queues/%2F/seckill.queue",
                             auth=(RABBITMQ_USER, RABBITMQ_PASS), timeout=3).json()
            dq = requests.get(f"{RABBITMQ_API}/queues/%2F/seckill.queue.dlq",
                              auth=(RABBITMQ_USER, RABBITMQ_PASS), timeout=3).json()
            if q.get("messages", 0) == 0 and dq.get("messages", 0) == 0:
                return True
        except requests.RequestException:
            pass
        time.sleep(1)
    return False


def wait_no_hanging(r, timeout=90):
    """等高并发后订单状态收敛：无 PENDING/CONFIRMED/RETRY 中间态（对账 Scanner 30s 周期兜底）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        hanging = [k for k in r.scan_iter(match="seckill:order:*", count=500)
                   if r.hget(k, "status") in ("PENDING", "CONFIRMED", "RETRY")]
        if not hanging:
            return True
        time.sleep(2)
    return False


def main():
    p = argparse.ArgumentParser(description="压测一致性核验")
    p.add_argument("--initial-stock", type=int, required=True, help="压测前的初始库存")
    p.add_argument("--goods-id", type=int, default=1, help="秒杀商品 ID")
    args = p.parse_args()

    results = []  # (name, ok, detail)

    def record(name, ok, detail=""):
        results.append((name, ok, detail))
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {name} {detail}")

    print("=" * 60)
    print(f"压测一致性核验：商品 {args.goods_id}，初始库存 {args.initial_stock}")
    print("=" * 60)

    # 先等 MQ 清空（最终收敛）
    print("[0] 等待 MQ 积压清空...")
    mq_ok = wait_mq_empty()
    record("MQ backlog 清空", mq_ok, "" if mq_ok else "（有消息未消费完，后续检查可能受 in-flight 影响）")

    # 等订单状态收敛（对账 Scanner 30s 周期兜底 CONFIRMED/PENDING 悬挂）
    print("[0.5] 等待订单状态收敛（对账兜底）...")
    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
    reconcile_ok = wait_no_hanging(r)
    record("订单状态收敛（无中间态）", reconcile_ok, "" if reconcile_ok else "（仍有悬挂中间态）")

    # 1. DB 检查
    print("[1] 数据库检查...")
    db_stock = query(f"SELECT seckill_stock FROM seckill_goods WHERE id = {args.goods_id}")[0][0]
    order_count = query(f"SELECT COUNT(*) FROM `order` WHERE seckill_goods_id = {args.goods_id}")[0][0]
    record("DB stock >= 0", db_stock >= 0, f"（当前 DB stock={db_stock}）")
    record("订单数 <= 初始库存", order_count <= args.initial_stock,
           f"（订单数={order_count}，初始库存={args.initial_stock}）")
    record("库存扣减一致 (初始-终态=订单数)", args.initial_stock - db_stock == order_count,
           f"（{args.initial_stock} - {db_stock} = {args.initial_stock - db_stock}，订单数={order_count}）")

    dup_user = query(f"""SELECT user_id, COUNT(*) c FROM `order`
                         WHERE seckill_goods_id = {args.goods_id}
                         GROUP BY user_id, seckill_goods_id HAVING c > 1""")
    record("无重复有效订单 (每用户每商品<=1)", len(dup_user) == 0,
           "" if not dup_user else f"（发现 {len(dup_user)} 个重复：{dup_user[:5]}）")

    dup_order = query("SELECT order_no, COUNT(*) c FROM `order` GROUP BY order_no HAVING c > 1")
    record("无重复 orderNo", len(dup_order) == 0, "" if not dup_order else f"（{dup_order[:5]}）")

    # 2. Redis 检查
    print("[2] Redis 检查...")
    # 中间态悬挂（已在 [0.5] 等待对账收敛后再查）
    hanging = []
    for key in r.scan_iter(match="seckill:order:*", count=500):
        status = r.hget(key, "status")
        if status in ("PENDING", "CONFIRMED", "RETRY"):
            hanging.append((key, status))
    record("无悬挂中间态 (PENDING/CONFIRMED/RETRY)", len(hanging) == 0,
           "" if not hanging else f"（发现 {len(hanging)} 个：{hanging[:5]}）")

    # 假库存：Redis 库存 vs DB 库存
    stock_key = f"seckill:stock:{args.goods_id}:20260101000000"
    redis_stock = r.get(stock_key)
    fake_stock = False
    if redis_stock is not None:
        fake_stock = int(redis_stock) != db_stock
        record("无假库存 (Redis stock == DB stock)", not fake_stock,
               f"（Redis={redis_stock}，DB={db_stock}）")
    else:
        record("无假库存 (Redis stock == DB stock)", True, f"（Redis 无库存 key，DB={db_stock}）")

    # 3. MQ 死信
    print("[3] MQ 死信检查...")
    try:
        dq = requests.get(f"{RABBITMQ_API}/queues/%2F/seckill.queue.dlq",
                          auth=(RABBITMQ_USER, RABBITMQ_PASS), timeout=3).json()
        dlq_msgs = dq.get("messages", 0)
        record("死信队列为空", dlq_msgs == 0, f"（DLQ 消息数={dlq_msgs}）")
    except requests.RequestException:
        record("死信队列为空", False, "（无法查询 DLQ）")

    # 汇总
    print("=" * 60)
    failed = [r for r in results if not r[1]]
    if failed:
        print(f"❌ 核验未通过：{len(failed)} 项 FAIL")
        for name, _, detail in failed:
            print(f"   - {name} {detail}")
        sys.exit(1)
    else:
        print(f"✅ 全部 {len(results)} 项检查通过：零超卖 / 零重复有效订单 / 零悬挂 / 零错误补偿")
    print("=" * 60)


if __name__ == "__main__":
    main()

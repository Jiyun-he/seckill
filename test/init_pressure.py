"""
压测数据初始化脚本：清订单 / 重置库存 / 清 Redis 秒杀 key / 清 MQ / 重启 app 预热，可选批量造用户。

用法：
    python test/init_pressure.py --stock 100000 --users 100000 --clear-users   # 首次完整初始化
    python test/init_pressure.py --stock 100000                                # 每轮重置（保留用户与 token）
"""

import argparse
import subprocess
import sys
import time
from pathlib import Path

import pymysql
import redis
import requests

BASE_URL = "http://localhost:8080"
MYSQL = dict(host="localhost", port=3306, user="root", password="123456", database="seckill_db")
REDIS_HOST = "localhost"
REDIS_PORT = 6379
RABBITMQ_API = "http://localhost:15672/api"
RABBITMQ_USER = "seckill"
RABBITMQ_PASS = "seckill123"
APP_CONTAINER = "seckill-app-1"
GOODS_ID = 1
TEST_DIR = Path(__file__).parent


def sql(sql: str):
    conn = pymysql.connect(**MYSQL)
    try:
        with conn.cursor() as cur:
            cur.execute(sql)
        conn.commit()
    finally:
        conn.close()


def wait_healthy(timeout: int = 120) -> bool:
    for _ in range(timeout):
        try:
            if requests.get(f"{BASE_URL}/hello", timeout=3).status_code == 200:
                return True
        except requests.RequestException:
            pass
        time.sleep(1)
    return False


def purge_queue(name: str):
    try:
        requests.delete(f"{RABBITMQ_API}/queues/%2F/{name}/contents",
                        auth=(RABBITMQ_USER, RABBITMQ_PASS), timeout=5)
        print(f"  MQ purge {name} ✓")
    except requests.RequestException as e:
        print(f"  MQ purge {name} ✗ ({e})")


def main():
    p = argparse.ArgumentParser(description="秒杀压测数据初始化")
    p.add_argument("--stock", type=int, default=100000, help=f"商品 {GOODS_ID} 的目标库存")
    p.add_argument("--users", type=int, default=0, help="注册用户数（0=跳过）")
    p.add_argument("--clear-users", action="store_true", help="同时清空 user 表（首次初始化用）")
    p.add_argument("--all-goods", action="store_true", help="对所有秒杀商品设置库存（性能压测用），否则只改商品 1")
    p.add_argument("--prefix", default="perf_user", help="用户名前缀")
    args = p.parse_args()

    print("=" * 60)
    print(f"压测初始化：库存={args.stock} 用户数={args.users} 清用户={args.clear_users}")
    print("=" * 60)

    # 1. DB：清订单 + 重置库存（可选清 user）
    print("[1/5] 清理数据库...")
    sql("TRUNCATE TABLE `order`")
    if args.clear_users:
        sql("TRUNCATE TABLE `user`")
    if args.all_goods:
        sql(f"UPDATE seckill_goods SET seckill_stock = {args.stock}")
        print(f"  order 清空，所有 seckill_goods.seckill_stock = {args.stock} ✓")
    else:
        sql(f"UPDATE seckill_goods SET seckill_stock = {args.stock} WHERE id = {GOODS_ID}")
        print(f"  order 清空，seckill_goods[{GOODS_ID}].seckill_stock = {args.stock} ✓")

    # 2. Redis：删除 seckill:* key（保留 token:* 登录态）
    print("[2/5] 清理 Redis 秒杀 key...")
    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
    keys = list(r.scan_iter(match="seckill:*", count=500))
    if keys:
        r.delete(*keys)
    print(f"  删除 {len(keys)} 个 seckill:* key ✓")

    # 3. MQ：清空主队列与死信队列
    print("[3/5] 清理 MQ 队列...")
    purge_queue("seckill.queue")
    purge_queue("seckill.queue.dlq")

    # 4. 重启 app 触发 @PostConstruct 重新预热库存与活动时间
    print("[4/5] 重启 app 预热...")
    subprocess.run(["docker", "restart", APP_CONTAINER], check=True, capture_output=True)
    if not wait_healthy():
        print("  ✗ app 启动超时")
        sys.exit(1)
    print("  app 健康，库存已预热 ✓")

    # 5. 批量注册用户
    if args.users > 0:
        print(f"[5/5] 注册 {args.users} 个用户...")
        subprocess.run([sys.executable, str(TEST_DIR / "register_users.py"),
                        "--count", str(args.users), "--base-url", BASE_URL,
                        "--prefix", args.prefix], check=True)
    else:
        print("[5/5] 跳过用户注册")

    print("=" * 60)
    print("✓ 初始化完成")
    print("=" * 60)


if __name__ == "__main__":
    main()

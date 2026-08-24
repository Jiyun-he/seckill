"""
故障注入测试复位脚本：清空订单 / 重置库存 / purge MQ，并重启 app 触发库存预热。

用法:
    python test/failpoint/reset.py                 # 复位并重启 app
    python test/failpoint/reset.py --no-restart    # 仅清数据，不重启 app
"""

import argparse
import subprocess
import time
from pathlib import Path

import pymysql
import redis
import requests

PROJECT_ROOT = Path(__file__).resolve().parents[2]

# 连接信息（docker compose 端口映射到 localhost）
REDIS_HOST = "localhost"
REDIS_PORT = 6379
MYSQL_HOST = "localhost"
MYSQL_PORT = 3306
MYSQL_USER = "root"
MYSQL_PASSWORD = "123456"
MYSQL_DB = "seckill_db"

RABBITMQ_API = "http://localhost:15672/api"
RABBITMQ_USER = "seckill"
RABBITMQ_PASS = "seckill123"

# init.sql 中 seckill_goods 的初始库存
INITIAL_STOCK = {1: 50, 2: 29, 3: 100, 4: 20, 5: 75}

# 需要清理的 Redis key 模式（含带活动版本的 key）
REDIS_PATTERNS = ["seckill:stock:*", "seckill:ordered:*", "seckill:order:*",
                  "seckill:goods:*", "seckill:activity:*"]

MQ_QUEUES = ["seckill.queue", "seckill.queue.dlq"]


def reset_mysql():
    conn = pymysql.connect(host=MYSQL_HOST, port=MYSQL_PORT, user=MYSQL_USER,
                           password=MYSQL_PASSWORD, database=MYSQL_DB, charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM `order`")
            for gid, stock in INITIAL_STOCK.items():
                cur.execute("UPDATE seckill_goods SET seckill_stock=%s WHERE id=%s", (stock, gid))
        conn.commit()
        print("[mysql] order 已清空，seckill_stock 已重置为初值")
    finally:
        conn.close()


def reset_redis():
    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
    keys = []
    for pattern in REDIS_PATTERNS:
        keys.extend(r.keys(pattern))
    if keys:
        r.delete(*keys)
    print(f"[redis] 已删除 {len(keys)} 个 seckill key")


def purge_mq():
    for queue in MQ_QUEUES:
        resp = requests.delete(f"{RABBITMQ_API}/queues/%2F/{queue}/contents",
                               auth=(RABBITMQ_USER, RABBITMQ_PASS), timeout=5)
        status = "成功" if resp.status_code in (200, 204) else f"失败 HTTP {resp.status_code}"
        print(f"[mq] purge {queue}: {status}")


def restart_app():
    cmd = ["docker", "compose", "-f", "compose.yaml", "-f", "compose.fault-test.yaml", "restart", "app"]
    print(f"[docker] {' '.join(cmd)}")
    subprocess.run(cmd, cwd=PROJECT_ROOT, check=True)
    # 等待 app 就绪（/hello 无需 token）
    for _ in range(90):
        try:
            if requests.get("http://localhost:8080/hello", timeout=2).status_code == 200:
                print("[app] 已就绪")
                return
        except Exception:
            pass
        time.sleep(1)
    print("[app] 等待就绪超时（90s）")


def main():
    parser = argparse.ArgumentParser(description="复位秒杀故障注入测试数据")
    parser.add_argument("--no-restart", action="store_true", help="不重启 app（仅清数据）")
    args = parser.parse_args()

    print("=" * 50)
    print("复位秒杀故障注入测试数据")
    print("=" * 50)
    reset_mysql()
    reset_redis()
    purge_mq()
    if not args.no_restart:
        restart_app()
    print("复位完成")


if __name__ == "__main__":
    main()

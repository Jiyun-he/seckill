"""
故障注入观测脚本：打印 Redis / MySQL / RabbitMQ 关键状态快照。

用法:
    python test/failpoint/observe.py
"""

import pymysql
import redis
import requests

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

REDIS_PATTERNS = ["seckill:stock:*", "seckill:ordered:*", "seckill:order:*", "seckill:activity:*"]
MQ_QUEUES = ["seckill.queue", "seckill.queue.dlq"]


def observe_redis():
    print("== Redis ==")
    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
    for pattern in REDIS_PATTERNS:
        keys = sorted(r.keys(pattern))
        if not keys:
            print(f"  (无 {pattern})")
            continue
        for k in keys:
            t = r.type(k)
            if t == "set":
                val = sorted(r.smembers(k))
            else:
                val = r.get(k)
                ttl = r.ttl(k)
                val = f"{val} (ttl={ttl}s)"
            print(f"  {k} = {val}")


def observe_mysql():
    print("== MySQL ==")
    conn = pymysql.connect(host=MYSQL_HOST, port=MYSQL_PORT, user=MYSQL_USER,
                           password=MYSQL_PASSWORD, database=MYSQL_DB, charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM `order`")
            order_count = cur.fetchone()[0]
            print(f"  order 表行数: {order_count}")
            cur.execute("SELECT order_no, user_id, seckill_goods_id FROM `order` ORDER BY order_no DESC LIMIT 10")
            for row in cur.fetchall():
                print(f"    order_no={row[0]} user={row[1]} seckill_goods={row[2]}")
            cur.execute("SELECT id, seckill_stock FROM seckill_goods ORDER BY id")
            for row in cur.fetchall():
                print(f"    seckill_goods id={row[0]} seckill_stock={row[1]}")
    finally:
        conn.close()


def observe_mq():
    print("== RabbitMQ ==")
    for queue in MQ_QUEUES:
        resp = requests.get(f"{RABBITMQ_API}/queues/%2F/{queue}",
                            auth=(RABBITMQ_USER, RABBITMQ_PASS), timeout=5)
        if resp.status_code == 200:
            d = resp.json()
            print(f"  {queue}: ready={d.get('messages_ready')} unacked={d.get('messages_unacknowledged')} total={d.get('messages')}")
        else:
            print(f"  {queue}: 查询失败 HTTP {resp.status_code}")


def main():
    observe_redis()
    observe_mysql()
    observe_mq()


if __name__ == "__main__":
    main()

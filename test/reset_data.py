#!/usr/bin/env python3
"""
压测前重置 Redis 库存和 MySQL 秒杀库存。
用法:
    python test/reset_data.py
"""

import redis
import pymysql
import argparse

# 默认连接信息（对应 docker-compose 中的服务名映射到 localhost 端口）
REDIS_HOST = "localhost"
REDIS_PORT = 6379

MYSQL_HOST = "localhost"
MYSQL_PORT = 3306
MYSQL_USER = "root"
MYSQL_PASSWORD = "123456"
MYSQL_DB = "seckill_db"


# 原始库存（来自 init.sql），重置时直接写入 Redis，无需重启应用
ORIGINAL_STOCK = {
    1: 20,
    2: 40,
    3: 60,
    4: 80,
    5: 100,
}


def reset_redis(r: redis.Redis):
    """清除 Redis 中所有秒杀相关 key 并重新写入库存"""
    keys_to_delete = []

    # 扫描所有秒杀相关 key
    for pattern in ["seckill:stock:*", "seckill:ordered:*", "seckill:goods:*"]:
        keys = r.keys(pattern)
        keys_to_delete.extend(keys)
        print(f"  {pattern} → 找到 {len(keys)} 个 key")

    if keys_to_delete:
        r.delete(*keys_to_delete)
        print(f"  已删除 {len(keys_to_delete)} 个旧 key")

    # 直接写入库存值到 Redis，不需要重启应用
    for goods_id, stock in ORIGINAL_STOCK.items():
        r.set(f"seckill:stock:{goods_id}", stock)
        print(f"  Redis seckill:stock:{goods_id} = {stock}")

    print()


def reset_mysql(conn):
    """重置 MySQL 秒杀库存到初始值"""
    # 原始库存（来自 init.sql）
    original_stock = {
        1: 20,
        2: 40,
        3: 60,
        4: 80,
        5: 100,
    }

    cursor = conn.cursor()
    for goods_id, stock in original_stock.items():
        cursor.execute(
            "UPDATE seckill_goods SET seckill_stock = %s WHERE id = %s",
            (stock, goods_id),
        )
        print(f"  seckill_goods id={goods_id} 库存重置为 {stock}")

    # 清空订单表（可选，加 --clear-orders 参数才执行）
    conn.commit()
    cursor.close()
    print()


def clear_orders(conn):
    """清空订单表"""
    cursor = conn.cursor()
    cursor.execute("SELECT COUNT(*) FROM `order`")
    count = cursor.fetchone()[0]
    if count > 0:
        cursor.execute("DELETE FROM `order`")
        conn.commit()
        print(f"  已清空 order 表（删除了 {count} 条记录）\n")
    else:
        print(f"  order 表为空，无需清空\n")
    cursor.close()


def main():
    parser = argparse.ArgumentParser(description="重置秒杀测试数据")
    parser.add_argument("--clear-orders", action="store_true", help="同时清空订单表")
    args = parser.parse_args()

    print("=" * 50)
    print("重置秒杀测试数据")
    print("=" * 50)

    # 1. 重置 Redis
    print("\n[1/3] 连接 Redis...")
    try:
        r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
        r.ping()
        print("  Redis 连接成功")
        print("\n[2/3] 清除 Redis 秒杀缓存...")
        reset_redis(r)
    except Exception as e:
        print(f"  Redis 连接失败: {e}")
        print("  跳过 Redis 重置\n")
        r = None

    # 2. 重置 MySQL
    print("[3/3] 重置 MySQL 库存...")
    try:
        conn = pymysql.connect(
            host=MYSQL_HOST,
            port=MYSQL_PORT,
            user=MYSQL_USER,
            password=MYSQL_PASSWORD,
            database=MYSQL_DB,
            charset="utf8mb4",
        )
        reset_mysql(conn)
        if args.clear_orders:
            clear_orders(conn)
        conn.close()
    except Exception as e:
        print(f"  MySQL 连接失败: {e}")

    print("=" * 50)
    print("重置完成！Redis 库存已恢复，无需重启应用即可开始新一轮压测。")
    print("=" * 50)


if __name__ == "__main__":
    main()

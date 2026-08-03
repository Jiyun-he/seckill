#!/usr/bin/env python3
"""
压测期间自动监控脚本 — 每秒采集 DB连接池 / RabbitMQ队列 / Redis 指标。

用法:
    python test/monitor.py                          # 一直跑，Ctrl+C 停止
    python test/monitor.py --output monitor_50t.csv  # 指定输出文件

在另一个终端同时跑 JMeter 压测。
"""

import time
import csv
import json
import argparse
import sys
from datetime import datetime
from pathlib import Path

import requests
import redis

# ============ 配置 ============
ACTUATOR_URL = "http://localhost:8080/actuator"
RABBITMQ_API = "http://localhost:15672/api"
RABBITMQ_USER = "seckill"
RABBITMQ_PASS = "seckill123"
REDIS_HOST = "localhost"
REDIS_PORT = 6379

MONITOR_INTERVAL = 1  # 采集间隔（秒）
# =============================

OUTPUT_DIR = Path(__file__).parent


def fmt_ts() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def get_hikari_metrics() -> dict:
    """从 Actuator 获取 HikariCP 连接池指标"""
    metrics = {}
    keys = [
        "hikaricp.connections.active",     # 当前活跃连接
        "hikaricp.connections.idle",       # 空闲连接
        "hikaricp.connections.pending",    # 等待获取连接的请求数（>0 = 池不够用）
        "hikaricp.connections.max",        # 最大连接数
        "hikaricp.connections.creation",   # 累计创建连接数
    ]
    for key in keys:
        try:
            resp = requests.get(
                f"{ACTUATOR_URL}/metrics/{key}",
                timeout=3,
            )
            if resp.status_code == 200:
                data = resp.json()
                # 返回最近一次测量值
                measurements = data.get("measurements", [])
                if measurements:
                    metrics[key.split(".")[-1]] = measurements[0].get("value", 0)
        except Exception:
            metrics[key.split(".")[-1]] = -1
    return metrics


def get_rabbitmq_queue_stats(vhost: str = "%2F", queue: str = "seckill.queue") -> dict:
    """从 RabbitMQ Management API 获取队列消息数"""
    try:
        resp = requests.get(
            f"{RABBITMQ_API}/queues/{vhost}/{queue}",
            auth=(RABBITMQ_USER, RABBITMQ_PASS),
            timeout=3,
        )
        if resp.status_code == 200:
            data = resp.json()
            return {
                "mq_ready": data.get("messages_ready", -1),              # 待消费
                "mq_unacked": data.get("messages_unacknowledged", -1),   # 消费中未确认
                "mq_total": data.get("messages", -1),                     # 总消息数
                "mq_consumers": data.get("consumers", -1),               # 消费者数量
                "mq_publish_rate": data.get("message_stats", {}).get("publish_details", {}).get("rate", 0),
                "mq_deliver_rate": data.get("message_stats", {}).get("deliver_details", {}).get("rate", 0),
            }
    except Exception:
        pass
    return {"mq_ready": -1, "mq_unacked": -1, "mq_total": -1, "mq_consumers": -1, "mq_publish_rate": -1, "mq_deliver_rate": -1}


def get_redis_stats(r: redis.Redis) -> dict:
    """获取 Redis 瞬时统计"""
    try:
        info = r.info("stats")
        return {
            "redis_ops": info.get("instantaneous_ops_per_sec", -1),       # 每秒操作数
            "redis_connected": info.get("connected_clients", -1),          # 当前连接数
            "redis_rejected": info.get("rejected_connections", -1),        # 被拒绝的连接（>0 = 瓶颈）
            "redis_hit_rate": info.get("keyspace_hits", 0) / max(info.get("keyspace_hits", 0) + info.get("keyspace_misses", 1), 1) * 100,
        }
    except Exception:
        pass
    return {"redis_ops": -1, "redis_connected": -1, "redis_rejected": -1, "redis_hit_rate": -1}


def main():
    parser = argparse.ArgumentParser(description="秒杀压测监控")
    parser.add_argument("--output", default=None, help="输出 CSV 文件名（默认自动生成）")
    parser.add_argument("--interval", type=float, default=MONITOR_INTERVAL, help="采集间隔秒数")
    args = parser.parse_args()

    output_file = args.output or f"monitor_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
    output_path = OUTPUT_DIR / output_file

    print("=" * 60)
    print("秒杀压测监控器")
    print(f"采集间隔: {args.interval}s | 输出: {output_path}")
    print("按 Ctrl+C 停止")
    print("=" * 60)

    # 初始化 Redis 连接
    try:
        r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
        r.ping()
        print("  Redis ✓")
    except Exception as e:
        print(f"  Redis ✗ ({e})，将跳过 Redis 指标")
        r = None

    # 检查 Actuator
    try:
        resp = requests.get(f"{ACTUATOR_URL}/health", timeout=3)
        if resp.status_code == 200:
            print("  Actuator ✓")
        else:
            print(f"  Actuator ✗ (HTTP {resp.status_code})")
    except Exception as e:
        print(f"  Actuator ✗ ({e})")
        print("  请确认应用已启动！")
        sys.exit(1)

    # 检查 RabbitMQ API
    try:
        resp = requests.get(
            f"{RABBITMQ_API}/overview",
            auth=(RABBITMQ_USER, RABBITMQ_PASS),
            timeout=3,
        )
        if resp.status_code == 200:
            print("  RabbitMQ API ✓")
        else:
            print(f"  RabbitMQ API ✗ (HTTP {resp.status_code})")
    except Exception as e:
        print(f"  RabbitMQ API ✗ ({e})")

    print("-" * 60)
    print(f"{'时间':<20} {'活跃连接':>8} {'等待连接':>8} {'MQ Ready':>10} {'MQ Unacked':>10} {'Redis ops':>10}")

    # CSV 表头
    fieldnames = [
        "timestamp",
        "conn_active", "conn_idle", "conn_pending", "conn_max",
        "mq_ready", "mq_unacked", "mq_total", "mq_consumers", "mq_publish_rate", "mq_deliver_rate",
        "redis_ops", "redis_connected", "redis_rejected", "redis_hit_rate",
    ]

    with open(output_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()

        try:
            while True:
                ts = fmt_ts()
                row = {"timestamp": ts}

                # HikariCP
                hikari = get_hikari_metrics()
                row["conn_active"] = hikari.get("active", -1)
                row["conn_idle"] = hikari.get("idle", -1)
                row["conn_pending"] = hikari.get("pending", -1)
                row["conn_max"] = hikari.get("max", -1)

                # RabbitMQ
                mq = get_rabbitmq_queue_stats()
                row.update(mq)

                # Redis
                if r:
                    redis_stats = get_redis_stats(r)
                    row.update(redis_stats)

                writer.writerow(row)
                f.flush()

                # 终端实时输出关键指标
                pending_str = f"{row['conn_pending']:>8}" if row['conn_pending'] > 0 else f"{row['conn_pending']:>8}"
                print(
                    f"{ts:<20} "
                    f"{row['conn_active']:>8} "
                    f"{pending_str} "
                    f"{row['mq_ready']:>10} "
                    f"{row['mq_unacked']:>10} "
                    f"{row['redis_ops']:>10}"
                )

                time.sleep(args.interval)

        except KeyboardInterrupt:
            print("\n" + "-" * 60)
            print(f"监控已停止。数据已保存到: {output_path}")
            print("-" * 60)


if __name__ == "__main__":
    main()

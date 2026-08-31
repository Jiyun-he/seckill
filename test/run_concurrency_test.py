"""
Consumer 并发扩展实验：2/4/8/12/16 档，每档固定 500 并发压测，记录落库 TPS / publish 速率 / MQ 积压。

用法：python test/run_concurrency_test.py
"""

import os
import subprocess
import sys
import time

import requests

JMETER = r"F:\someSoftwares\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat"
TEST_DIR = os.path.dirname(os.path.abspath(__file__))
RABBITMQ_API = "http://localhost:15672/api"
RABBITMQ_USER = "seckill"
RABBITMQ_PASS = "seckill123"

LEVELS = [24, 32]
THREADS = 500
DURATION = 30
STOCK = 20000


def restart_app(level):
    env = dict(os.environ, SEKKILL_CONSUMER_CONCURRENCY=str(level))
    subprocess.run(["docker", "compose", "up", "-d", "app"], env=env,
                   cwd=os.path.dirname(TEST_DIR), capture_output=True, text=True)
    for _ in range(120):
        try:
            if requests.get("http://localhost:8080/hello", timeout=2).status_code == 200:
                return True
        except requests.RequestException:
            pass
        time.sleep(1)
    return False


def mq_stats():
    try:
        r = requests.get(f"{RABBITMQ_API}/queues/%2F/seckill.queue",
                         auth=(RABBITMQ_USER, RABBITMQ_PASS), timeout=2).json()
        stats = r.get("message_stats", {})
        return {
            "deliver": stats.get("deliver_details", {}).get("rate", 0.0),
            "publish": stats.get("publish_details", {}).get("rate", 0.0),
            "ready": r.get("messages_ready", 0),
        }
    except requests.RequestException:
        return {"deliver": 0.0, "publish": 0.0, "ready": -1}


def conn_metrics():
    try:
        r = requests.get("http://localhost:8080/actuator/metrics/hikaricp.connections.active", timeout=2).json()
        active = r["measurements"][0]["value"]
        r = requests.get("http://localhost:8080/actuator/metrics/hikaricp.connections.pending", timeout=2).json()
        pending = r["measurements"][0]["value"]
        return {"active": active, "pending": pending}
    except Exception:
        return {"active": -1, "pending": -1}


def init():
    return subprocess.run([sys.executable, os.path.join(TEST_DIR, "init_pressure.py"),
                           "--stock", str(STOCK), "--all-goods"],
                          cwd=TEST_DIR, capture_output=True, text=True).returncode == 0


def main():
    print("=" * 70)
    print(f"Consumer 并发扩展实验：{LEVELS} 档，每档固定 {THREADS} 并发 × {DURATION}s，库存 {STOCK}/商品")
    print("=" * 70)
    print(f"{'并发':>4} {'落库TPS峰值':>10} {'落库TPS平均':>10} {'publish峰值':>10} {'MQ积压峰值':>10} {'DB活跃':>6} {'DB等待':>6}")
    for level in LEVELS:
        print(f"\n>>> Consumer 并发 = {level}")
        if not restart_app(level):
            print("  ✗ app 重启失败")
            continue
        if not init():
            print("  ✗ 初始化失败")
            continue
        jtl = f"result_conc_{level}.jtl"
        proc = subprocess.Popen(
            [JMETER, "-n", "-t", os.path.join(TEST_DIR, "seckill_test.jmx"),
             "-Jthreads", str(THREADS), "-Jduration", str(DURATION),
             "-l", os.path.join(TEST_DIR, jtl)],
            cwd=TEST_DIR, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        delivers, publishes, readies, actives, pendings = [], [], [], [], []
        while proc.poll() is None:
            s = mq_stats()
            delivers.append(s["deliver"])
            publishes.append(s["publish"])
            readies.append(s["ready"])
            c = conn_metrics()
            actives.append(c["active"])
            pendings.append(c["pending"])
            time.sleep(1)
        proc.wait()
        peak_d = max(delivers) if delivers else 0
        avg_d = sum(delivers) / len(delivers) if delivers else 0
        peak_p = max(publishes) if publishes else 0
        peak_r = max(readies) if readies else 0
        peak_a = max(actives) if actives else 0
        peak_w = max(pendings) if pendings else 0
        print(f"  -> {level:>4}  {peak_d:>10.0f}  {avg_d:>10.0f}  {peak_p:>10.0f}  {peak_r:>10.0f}  {peak_a:>6.0f}  {peak_w:>6.0f}")
    print("\n" + "=" * 70)


if __name__ == "__main__":
    main()

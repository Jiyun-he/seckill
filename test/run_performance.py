"""
性能阶梯压测：大库存 + 阶梯并发，每梯度从 jtl 计算 QPS/P99/错误率。

用法：
    python test/run_performance.py --gradients 20,50,100,200,500,800,1000 --runs 3
"""

import argparse
import csv
import os
import subprocess
import sys
import time

JMETER = r"F:\someSoftwares\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat"
TEST_DIR = os.path.dirname(os.path.abspath(__file__))


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def init_large_stock(stock=20000):
    r = run([sys.executable, os.path.join(TEST_DIR, "init_pressure.py"),
             "--stock", str(stock), "--all-goods"], cwd=TEST_DIR)
    return r.returncode == 0


def run_jmeter(threads, duration, jtl):
    r = run([JMETER, "-n", "-t", os.path.join(TEST_DIR, "seckill_test.jmx"),
             "-Jthreads", str(threads), "-Jduration", str(duration),
             "-l", os.path.join(TEST_DIR, jtl)], cwd=TEST_DIR)
    return r.returncode == 0


def analyze_jtl(jtl, duration):
    """从 jtl 计算 QPS / 平均RT / P99 / 5xx错误率 / 业务拒绝(409)率。"""
    elapsed = []
    total = 0
    err_5xx = 0
    reject_409 = 0
    with open(os.path.join(TEST_DIR, jtl), "r", encoding="utf-8", errors="ignore") as f:
        reader = csv.DictReader(f)
        for row in reader:
            total += 1
            try:
                e = int(float(row.get("elapsed", 0)))
                elapsed.append(e)
            except (ValueError, TypeError):
                pass
            code = row.get("responseCode", "")
            if code.startswith("5"):
                err_5xx += 1
            elif code == "409":
                reject_409 += 1
    if not elapsed:
        return {"total": total, "qps": 0, "avg": 0, "p99": 0, "err_5xx": 0, "reject_409": 0}
    elapsed.sort()
    p99 = elapsed[int(len(elapsed) * 0.99)] if len(elapsed) > 1 else elapsed[0]
    qps = total / duration if duration > 0 else 0
    avg = sum(elapsed) / len(elapsed)
    return {"total": total, "qps": round(qps, 1), "avg": round(avg, 1),
            "p99": p99, "err_5xx": err_5xx, "reject_409": reject_409}


def main():
    p = argparse.ArgumentParser(description="性能阶梯压测")
    p.add_argument("--gradients", default="20,50,100,200,500,800,1000", help="并发梯度逗号分隔")
    p.add_argument("--runs", type=int, default=3, help="每梯度重复次数")
    p.add_argument("--duration", type=int, default=60, help="每轮持续时间（秒）")
    p.add_argument("--stock", type=int, default=20000, help="每个商品库存（--all-goods）")
    args = p.parse_args()

    gradients = [int(x) for x in args.gradients.split(",") if x.strip()]
    print("=" * 70)
    print(f"性能阶梯压测：梯度={gradients} 每梯度{args.runs}次 时长={args.duration}s 库存={args.stock}/商品")
    print("=" * 70)

    all_results = []
    for g in gradients:
        grad_results = []
        for r in range(1, args.runs + 1):
            print(f"\n>>> 并发 {g} 第{r}次")
            t0 = time.time()
            if not init_large_stock(args.stock):
                print("  ✗ 初始化失败")
                grad_results.append(None)
                continue
            jtl = f"result_perf_{g}t_r{r}.jtl"
            if not run_jmeter(g, args.duration, jtl):
                print("  ✗ JMeter 失败")
                grad_results.append(None)
                continue
            stats = analyze_jtl(jtl, args.duration)
            stats["threads"] = g
            grad_results.append(stats)
            print(f"  -> QPS={stats['qps']} 平均={stats['avg']}ms P99={stats['p99']}ms "
                  f"总请求={stats['total']} 5xx={stats['err_5xx']} 409={stats['reject_409']} "
                  f"（耗时{time.time()-t0:.1f}s）")
        all_results.append((g, grad_results))

    # 汇总（每梯度取中位数 QPS）
    print("\n" + "=" * 70)
    print("性能阶梯压测汇总（每梯度 3 次，取 QPS 中位数）：")
    print(f"{'并发':>6} {'QPS中位':>8} {'平均RT':>8} {'P99':>8} {'5xx率':>8}")
    for g, res in all_results:
        qps_list = sorted([x['qps'] for x in res if x])
        if not qps_list:
            continue
        mid = qps_list[len(qps_list)//2]
        avg_rt = sorted([x['avg'] for x in res if x])[len(qps_list)//2]
        p99 = sorted([x['p99'] for x in res if x])[len(qps_list)//2]
        err5 = res[len(qps_list)//2]['err_5xx']
        total = res[len(qps_list)//2]['total']
        err_rate = round(err5/total*100, 2) if total else 0
        print(f"{g:>6} {mid:>8} {avg_rt:>8} {p99:>8} {err_rate:>7}%")
    print("=" * 70)


if __name__ == "__main__":
    main()

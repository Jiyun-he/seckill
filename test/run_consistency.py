"""
一致性压测自动化：3 场景 × 3 次，每轮 初始化库存 -> JMeter 压测 -> 一致性核验。

用法：
    python test/run_consistency.py
"""

import os
import subprocess
import sys
import time

JMETER = r"F:\someSoftwares\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat"
TEST_DIR = os.path.dirname(os.path.abspath(__file__))

# (场景名, jmx 文件, 并发线程数, 初始库存)
SCENES = [
    ("场景1-多人抢少量库存", "scene1_many_users_few_stock.jmx", 1000, 100),
    ("场景2-单用户疯狂重复", "scene2_single_user_repeat.jmx", 500, 100),
    ("场景3-混合重复请求", "scene3_mixed_repeat.jmx", 500, 200),
]
RUNS = 3


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def init_stock(stock):
    r = run([sys.executable, os.path.join(TEST_DIR, "init_pressure.py"),
             "--stock", str(stock)], cwd=TEST_DIR)
    return r.returncode == 0


def run_jmeter(jmx, threads, jtl):
    r = run([JMETER, "-n", "-t", os.path.join(TEST_DIR, jmx),
             "-Jthreads", str(threads),
             "-l", os.path.join(TEST_DIR, jtl)], cwd=TEST_DIR)
    return r.returncode == 0


def verify(stock):
    r = run([sys.executable, os.path.join(TEST_DIR, "verify_pressure.py"),
             "--initial-stock", str(stock)], cwd=TEST_DIR)
    return r.returncode == 0


def main():
    print("=" * 70)
    print("一致性压测：3 场景 × 3 次")
    print("=" * 70)
    summary = []
    for name, jmx, threads, stock in SCENES:
        for run_idx in range(1, RUNS + 1):
            tag = f"{name} 第{run_idx}次"
            print(f"\n>>> {tag}（线程={threads}，库存={stock}）")
            t0 = time.time()
            ok_init = init_stock(stock)
            if not ok_init:
                print(f"  ✗ 初始化失败")
                summary.append((tag, "FAIL-初始化"))
                continue
            jtl = f"result_consistency_{jmx.split('.')[0]}_r{run_idx}.jtl"
            ok_jmeter = run_jmeter(jmx, threads, jtl)
            ok_verify = verify(stock)
            status = "PASS" if (ok_jmeter and ok_verify) else "FAIL"
            print(f"  -> {status}（耗时 {time.time()-t0:.1f}s，JMeter={'OK' if ok_jmeter else 'ERR'}，核验={'OK' if ok_verify else 'FAIL'}）")
            summary.append((tag, status))
    print("\n" + "=" * 70)
    print("一致性压测汇总：")
    for tag, status in summary:
        print(f"  [{status}] {tag}")
    failed = [s for s in summary if not s[1].startswith("PASS")]
    print(f"\n{'❌ 有失败' if failed else '✅ 全部通过'}（共 {len(summary)} 轮）")
    print("=" * 70)


if __name__ == "__main__":
    main()

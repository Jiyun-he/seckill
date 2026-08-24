"""
故障注入 failpoint 控制脚本（需应用以 fault-test profile 运行）。

用法:
    python test/failpoint/fault.py block commit_after      # 启用 BLOCK
    python test/failpoint/fault.py throw preoccupy_after   # 启用 THROW
    python test/failpoint/fault.py release commit_after    # 释放 BLOCK
    python test/failpoint/fault.py disable commit_after    # 禁用
    python test/failpoint/fault.py status commit_after     # 查单个状态
    python test/failpoint/fault.py status commit_after --wait  # 轮询直到 blockedThreads>=1
    python test/failpoint/fault.py status-all              # 查全部状态
"""

import argparse
import sys
import time

import requests

BASE_URL = "http://localhost:8080"


def call(method, path):
    return requests.request(method, f"{BASE_URL}{path}", timeout=5)


def do_status(fid, wait):
    while True:
        resp = call("GET", f"/fault/{fid}")
        if resp.status_code != 200:
            print(f"HTTP {resp.status_code}: {resp.text}")
            sys.exit(1)
        data = resp.json().get("data", {})
        mode = data.get("mode", "NONE")
        blocked = data.get("blockedThreads", 0)
        print(f"{fid}: mode={mode} blockedThreads={blocked}")
        if not wait or blocked >= 1:
            if wait and blocked >= 1:
                print(f">>> 已 BLOCK {blocked} 个线程，可执行 kill / 断连接")
            return
        time.sleep(0.5)


def main():
    parser = argparse.ArgumentParser(description="failpoint 控制")
    parser.add_argument("action", choices=["block", "throw", "release", "disable", "status", "status-all"])
    parser.add_argument("id", nargs="?", help="failpoint id")
    parser.add_argument("--wait", action="store_true", help="status 时轮询直到 blockedThreads>=1")
    args = parser.parse_args()

    if args.action == "status-all":
        resp = call("GET", "/fault")
        print(resp.json())
        return

    if not args.id:
        parser.error("该操作需要 failpoint id")

    if args.action == "block":
        resp = call("POST", f"/fault/{args.id}/block")
    elif args.action == "throw":
        resp = call("POST", f"/fault/{args.id}/throw")
    elif args.action == "release":
        resp = call("POST", f"/fault/{args.id}/release")
    elif args.action == "disable":
        resp = call("POST", f"/fault/{args.id}/disable")
    elif args.action == "status":
        do_status(args.id, args.wait)
        return
    else:
        parser.error("未知操作")

    print(resp.json())


if __name__ == "__main__":
    main()

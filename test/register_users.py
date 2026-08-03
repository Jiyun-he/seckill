#!/usr/bin/env python3
"""
批量注册秒杀测试用户，导出 username + token 到 CSV 文件。
用于 JMeter CSV Data Set Config 读取。

用法:
    python test/register_users.py                    # 默认 2000 个用户
    python test/register_users.py --count 5000       # 自定义数量
    python test/register_users.py --base-url http://192.168.1.100:8080  # 指定地址
"""

import requests
import csv
import time
import sys
import argparse
from pathlib import Path

BASE_URL = "http://localhost:8080"
REGISTER_URL = f"{BASE_URL}/user/register"
OUTPUT_DIR = Path(__file__).parent  # test 目录
OUTPUT_FILE = OUTPUT_DIR / "users.csv"
DEFAULT_COUNT = 2000
BATCH_PRINT_EVERY = 50  # 每 50 个打印一次进度


def register_user(username: str, password: str) -> str | None:
    """注册单个用户，成功返回 token，失败返回 None"""
    try:
        resp = requests.post(
            REGISTER_URL,
            json={"username": username, "password": password},
            headers={"Content-Type": "application/json"},
            timeout=10,
        )
        data = resp.json()
        if data.get("code") == 200:
            return data["data"]  # token
        else:
            print(f"  注册 {username} 失败: {data.get('msg', 'unknown')}")
            return None
    except requests.RequestException as e:
        print(f"  注册 {username} 网络错误: {e}")
        return None


def main():
    parser = argparse.ArgumentParser(description="批量注册秒杀测试用户")
    parser.add_argument("--count", type=int, default=DEFAULT_COUNT, help="注册用户数量")
    parser.add_argument("--base-url", default="http://localhost:8080", help="应用地址")
    parser.add_argument("--prefix", default="jmeter_user", help="用户名前缀")
    args = parser.parse_args()

    global BASE_URL, REGISTER_URL
    BASE_URL = args.base_url.rstrip("/")
    REGISTER_URL = f"{BASE_URL}/user/register"

    print(f"目标地址: {BASE_URL}")
    print(f"准备注册 {args.count} 个用户...")
    print(f"输出文件: {OUTPUT_FILE}")
    print("-" * 50)

    # 先检查服务是否可达
    try:
        resp = requests.get(f"{BASE_URL}/hello", timeout=5)
        if resp.status_code != 200:
            print(f"⚠ 服务不可达，请确认应用已启动: {BASE_URL}")
            sys.exit(1)
        print("✓ 服务连接正常\n")
    except requests.RequestException as e:
        print(f"⚠ 无法连接到 {BASE_URL}: {e}")
        sys.exit(1)

    success_count = 0
    fail_count = 0
    start_time = time.time()

    # 生成 username 固定宽度，方便排序
    width = len(str(args.count))

    with open(OUTPUT_FILE, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        # CSV 表头，JMeter CSV Data Set Config 用第一行做变量名
        writer.writerow(["username", "token"])

        for i in range(1, args.count + 1):
            username = f"{args.prefix}_{i:0{width}d}"
            password = "123456"  # 统一密码

            token = register_user(username, password)

            if token:
                writer.writerow([username, token])
                success_count += 1
            else:
                fail_count += 1

            # 进度输出
            if i % BATCH_PRINT_EVERY == 0 or i == args.count:
                elapsed = time.time() - start_time
                rate = i / elapsed if elapsed > 0 else 0
                print(f"  进度: {i}/{args.count} | 成功: {success_count} | 失败: {fail_count} | 速度: {rate:.1f} req/s")

            # 写文件稍微频繁一点 flush 防丢
            if i % 500 == 0:
                f.flush()

    elapsed = time.time() - start_time
    print("-" * 50)
    print(f"✓ 完成! 耗时 {elapsed:.1f} 秒")
    print(f"  总共: {args.count} | 成功: {success_count} | 失败: {fail_count}")
    print(f"  CSV 文件: {OUTPUT_FILE} ({OUTPUT_FILE.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()

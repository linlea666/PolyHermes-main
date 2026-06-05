#!/usr/bin/env python3
"""
尾盘价差（TAIL_DIFF）σ / modelProb 标定核对脚本（离线诊断，P0-3）。

目的
----
回答两个决定策略命题的问题：
  1. 生产用的障碍模型 modelProb = Phi(diffSigma) 是否「校准」？
     —— 即 diffSigma=z 的样本里，领先方向真实结算胜率是否 ≈ Phi(z)。
  2. 若不校准，σ 是系统性偏高还是偏低？给出建议的 σ 缩放系数。

方法（忠实复刻后端实现，便于直接对照）
  - σ 估计 = BarrierSigmaEstimator，支持两种口径（--sigma-method）：
      · MAD：采样周期边界价 p(t_k), t_k = periodStart - k*interval, k=0..lookbackPeriods，
             位移 = |p_i - p_{i+1}|，IQR(1.5) 去极值后取均值 = baseSpread。
             baseSpread 为平均绝对位移，sigmaScale 默认须 √(π/2)≈1.2533 修正为标准差。
      · GARMAN_KLASS（生产配置在用）：拉取 periodStart 之前 lookback 根 interval 级 K 线 OHLC，
             每根 v = 0.5·ln(H/L)² − (2ln2−1)·ln(C/O)²，IQR 均值后开方得对数波动 σ_log，
             × 期初参考价换算为价格量纲 baseSpread。baseSpread 已是标准差量纲，sigmaScale=1.0 即正确。
      两法统一：sigmaPerSqrtS = baseSpread * sigmaScale / sqrt(interval)。
  - 障碍模型 = BarrierProbability.winProbTerminal：
      z = |close-open| / (sigmaPerSqrtS * sqrt(remaining)); modelProb = Phi(z)
  - 价源用币安 K 线作为 Chainlink/RTDS 的代理（仅校准 σ 方法与
    barrier 模型本身的校准度；实盘价源差异另由 P0 可观测性定位）。

注意
  - 已忠实复刻 MAD 与 GARMAN_KLASS 两种口径（与后端 BarrierSigmaEstimator 一致）；EWMA 未复刻。
  - 校准务必用与你策略 sigmaMethod 一致的口径：GK 路径传 --sigma-method GARMAN_KLASS --sigma-scale 1.0。
  - 币安 K 线 close 与 Chainlink 结算价有微小差异，但不影响「Phi 是否校准」的方向性结论。

用法
  # 生产 GK 口径（推荐，与策略 sigmaMethod=GARMAN_KLASS、sigmaScale=1.0 一致）：
  python3 scripts/tail_diff_sigma_calibration.py --sigma-method GARMAN_KLASS --sigma-scale 1.0 \
      --symbol BTCUSDT --interval-seconds 300 --window-start 240 --window-end 50 --days 14
  # MAD 口径（须带 √(π/2) 修正）：
  python3 scripts/tail_diff_sigma_calibration.py --sigma-method MAD --sigma-scale 1.2533 --days 7
"""
import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BINANCE_BASE = "https://api.binance.com"


def fetch_1m(symbol, start_ms, end_ms):
    """分页拉取 1m K 线，返回 {minute_unix_sec: close_price}。"""
    out = {}
    cur = start_ms
    while cur < end_ms:
        params = {"symbol": symbol, "interval": "1m", "limit": 1000, "startTime": cur, "endTime": end_ms}
        url = f"{BINANCE_BASE}/api/v3/klines?{urllib.parse.urlencode(params)}"
        req = urllib.request.Request(url, headers={"User-Agent": "PolymarketBot/1.0 (calib)"})
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                rows = json.load(resp)
        except urllib.error.HTTPError as e:
            print(f"HTTP {e.code}: {e.read().decode()[:200]}", file=sys.stderr)
            break
        except Exception as e:
            print(f"request error: {e}", file=sys.stderr)
            break
        if not rows:
            break
        for k in rows:
            open_sec = k[0] // 1000
            out[open_sec] = float(k[4])  # close
        last_open = rows[-1][0]
        nxt = last_open + 60_000
        if nxt <= cur:
            break
        cur = nxt
        time.sleep(0.15)  # 轻微限速，避免触发币安风控
    return out


def price_at(prices, ts_sec):
    """floor 取该秒或之前最近的 1m close（与生产 floorEntry 同语义）。"""
    t = ts_sec - (ts_sec % 60)
    for _ in range(180):  # 最多回看 180 分钟
        if t in prices:
            return prices[t]
        t -= 60
    return None


def iqr_mean(values):
    if not values:
        return None
    s = sorted(values)
    n = len(s)
    q1 = s[min(int(n * 0.25), n - 1)]
    q3 = s[min(int(n * 0.75), n - 1)]
    iqr = q3 - q1
    lo, hi = q1 - 1.5 * iqr, q3 + 1.5 * iqr
    filt = [v for v in s if lo <= v <= hi]
    use = filt if len(filt) >= 3 else s
    return sum(use) / len(use)


def sigma_per_sqrt_s(prices, period_start, interval, lookback, sigma_scale):
    """复刻 BarrierSigmaEstimator MAD 口径。"""
    pts = []
    for k in range(0, lookback + 1):
        p = price_at(prices, period_start - k * interval)
        if p and p > 0:
            pts.append(p)
    if len(pts) < 4:  # minSamples(3)+1
        return None
    disp = [abs(pts[i] - pts[i + 1]) for i in range(len(pts) - 1)]
    base = iqr_mean(disp)
    if not base or base <= 0:
        return None
    return base * sigma_scale / math.sqrt(interval)


def phi(x):
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


_TWO_LN2_MINUS_1 = 2.0 * math.log(2.0) - 1.0
BINANCE_INTERVAL = {60: "1m", 300: "5m", 900: "15m"}


def fetch_ohlc(symbol, interval_str, interval_sec, start_ms, end_ms):
    """分页拉取 interval 级 K 线，返回 {open_unix_sec: (open, high, low, close)}（GK σ 用）。"""
    out = {}
    cur = start_ms
    step_ms = interval_sec * 1000
    while cur < end_ms:
        params = {"symbol": symbol, "interval": interval_str, "limit": 1000, "startTime": cur, "endTime": end_ms}
        url = f"{BINANCE_BASE}/api/v3/klines?{urllib.parse.urlencode(params)}"
        req = urllib.request.Request(url, headers={"User-Agent": "PolymarketBot/1.0 (calib)"})
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                rows = json.load(resp)
        except urllib.error.HTTPError as e:
            print(f"HTTP {e.code}: {e.read().decode()[:200]}", file=sys.stderr)
            break
        except Exception as e:
            print(f"request error: {e}", file=sys.stderr)
            break
        if not rows:
            break
        for k in rows:
            open_sec = k[0] // 1000
            out[open_sec] = (float(k[1]), float(k[2]), float(k[3]), float(k[4]))  # O,H,L,C
        nxt = rows[-1][0] + step_ms
        if nxt <= cur:
            break
        cur = nxt
        time.sleep(0.15)  # 轻微限速，避免触发币安风控
    return out


def gk_sigma_per_sqrt_s(ohlc, prices, period_start, interval, lookback, sigma_scale):
    """复刻 BarrierSigmaEstimator GARMAN_KLASS 口径：periodStart 之前 lookback 根已收盘 K 线。"""
    vlist = []
    for k in range(1, lookback + 1):  # k=0(当前周期)不计，仅用已收盘历史，无前视
        c = ohlc.get(period_start - k * interval)
        if not c:
            continue
        o, h, low, cl = c
        if o <= 0 or h <= 0 or low <= 0 or cl <= 0:
            continue
        hl = math.log(h / low)
        co = math.log(cl / o)
        v = 0.5 * hl * hl - _TWO_LN2_MINUS_1 * co * co
        if math.isfinite(v) and v > 0:
            vlist.append(v)
    if len(vlist) < 3:  # minSamples
        return None
    avg_var_log = iqr_mean(vlist)
    if not avg_var_log or avg_var_log <= 0:
        return None
    sigma_log = math.sqrt(avg_var_log)
    ref = price_at(prices, period_start)  # 参考价=期初价（缺失则跳过该周期）
    if not ref or ref <= 0:
        return None
    base = sigma_log * ref  # 对数波动 × 参考价 → 价格量纲 baseSpread（标准差量纲）
    return base * sigma_scale / math.sqrt(interval)


def main():
    ap = argparse.ArgumentParser(description="TAIL_DIFF σ/modelProb 标定核对")
    ap.add_argument("--symbol", default="BTCUSDT")
    ap.add_argument("--days", type=int, default=7, help="回看天数（1m 数据量）")
    ap.add_argument("--interval-seconds", type=int, default=300, help="市场周期秒（5m=300, 15m=900）")
    ap.add_argument("--sigma-method", default="GARMAN_KLASS", choices=["MAD", "GARMAN_KLASS"],
                    help="σ 口径，须与策略 sigmaMethod 一致（生产=GARMAN_KLASS）")
    ap.add_argument("--sigma-scale", type=float, default=1.0,
                    help="生产 sigmaScale（GK 取 1.0；MAD 须传 √(π/2)≈1.2533 修正为标准差）")
    ap.add_argument("--lookback-periods", type=int, default=20)
    ap.add_argument("--window-start", type=int, default=150, help="入场窗口上界（剩余秒）")
    ap.add_argument("--window-end", type=int, default=60, help="入场窗口下界（剩余秒）")
    args = ap.parse_args()

    interval = args.interval_seconds
    now = int(time.time())
    # 多拉 lookback 跨度用于 σ 边界采样
    span_sec = args.days * 86400 + (args.lookback_periods + 2) * interval
    start_ms = (now - span_sec) * 1000
    end_ms = now * 1000

    method = args.sigma_method.upper()
    print(f"=== TAIL_DIFF σ/modelProb 标定 ({args.symbol}, interval={interval}s, days={args.days}) ===")
    print(f"sigmaMethod={method}, sigmaScale={args.sigma_scale}, lookbackPeriods={args.lookback_periods}, "
          f"window=[{args.window_end},{args.window_start}]s\n拉取币安 1m K 线 ...")
    prices = fetch_1m(args.symbol, start_ms, end_ms)
    if not prices:
        print("无 K 线数据", file=sys.stderr)
        sys.exit(1)
    print(f"已获取 {len(prices)} 根 1m K 线\n")

    # GK 口径需额外拉取 interval 级 OHLC（与后端 fetchRecentOhlc 同口径）
    ohlc = {}
    if method == "GARMAN_KLASS":
        interval_str = BINANCE_INTERVAL.get(interval)
        if not interval_str:
            print(f"GK 口径不支持的 interval={interval}s（仅 60/300/900）", file=sys.stderr)
            sys.exit(1)
        ohlc = fetch_ohlc(args.symbol, interval_str, interval, start_ms, end_ms)
        if not ohlc:
            print("无 interval 级 K 线数据（GK σ 不可用）", file=sys.stderr)
            sys.exit(1)
        print(f"已获取 {len(ohlc)} 根 {interval_str} K 线（GK σ 用）\n")

    # diffSigma 分桶（与 TailDiffBuckets 对齐）
    edges = [(0.0, 1.0), (1.0, 1.5), (1.5, 2.0), (2.0, 2.5), (2.5, 3.0), (3.0, 99.0)]
    buckets = {e: {"n": 0, "z_sum": 0.0, "phi_sum": 0.0, "win": 0} for e in edges}
    # 用于 σ 缩放系数拟合的原始 (z, won) 对
    pairs = []

    first_period = (now - args.days * 86400)
    first_period -= first_period % interval
    p = first_period
    total_obs = 0
    while p + interval <= now:
        open_p = price_at(prices, p)
        final_close = price_at(prices, p + interval)
        if open_p and final_close:
            settle_side = 0 if (final_close - open_p) >= 0 else 1
            sigma = (gk_sigma_per_sqrt_s(ohlc, prices, p, interval, args.lookback_periods, args.sigma_scale)
                     if method == "GARMAN_KLASS"
                     else sigma_per_sqrt_s(prices, p, interval, args.lookback_periods, args.sigma_scale))
            if sigma and sigma > 0:
                # 在入场窗口内按 1m 网格取观测点
                rem = args.window_start
                while rem >= args.window_end:
                    obs_ts = p + interval - rem
                    close_p = price_at(prices, obs_ts)
                    if close_p:
                        gap = close_p - open_p
                        lead_side = 0 if gap >= 0 else 1
                        z = abs(gap) / (sigma * math.sqrt(rem))
                        won = 1 if lead_side == settle_side else 0
                        pairs.append((z, won))
                        for e in edges:
                            if e[0] <= z < e[1]:
                                b = buckets[e]
                                b["n"] += 1
                                b["z_sum"] += z
                                b["phi_sum"] += phi(z)
                                b["win"] += won
                                break
                        total_obs += 1
                    rem -= 60
        p += interval

    if total_obs == 0:
        print("无有效观测点", file=sys.stderr)
        sys.exit(1)

    print(f"有效观测点: {total_obs}\n")
    print(f"{'diffSigma桶':>12} | {'n':>5} | {'均z':>6} | {'均Phi':>7} | {'实证胜率':>8} | {'gap(实-Phi)':>11}")
    print("-" * 66)
    for e in edges:
        b = buckets[e]
        if b["n"] == 0:
            continue
        mean_z = b["z_sum"] / b["n"]
        mean_phi = b["phi_sum"] / b["n"]
        emp = b["win"] / b["n"]
        label = f"{e[0]:.1f}-{e[1]:.1f}" if e[1] < 90 else f"{e[0]:.1f}+"
        print(f"{label:>12} | {b['n']:>5} | {mean_z:>6.3f} | {mean_phi:>7.4f} | "
              f"{emp:>8.4f} | {emp-mean_phi:>+11.4f}")

    # 拟合 σ 缩放系数 c：令 Phi(z*c) 最贴近实证胜率（粗网格搜索）
    best_c, best_err = 1.0, 1e9
    c = 0.40
    while c <= 2.50:
        err = sum((phi(z * c) - won) ** 2 for z, won in pairs) / len(pairs)
        if err < best_err:
            best_err, best_c = err, c
        c += 0.01
    # 基线误差（c=1，不改）
    base_err = sum((phi(z) - won) ** 2 for z, won in pairs) / len(pairs)

    print("\n=== σ 标定结论 ===")
    print(f"当前(c=1) Brier 误差: {base_err:.5f}")
    print(f"最优缩放 c={best_c:.2f} 时 Brier 误差: {best_err:.5f}")
    if best_c < 0.95:
        print(f"→ Phi 过度自信（实证胜率 < Phi）。等价于 σ 偏低：建议把 sigmaScale 调大约 ×{1/best_c:.2f}"
              f"（当前 {args.sigma_scale} → 建议 sigmaScale ≈ {args.sigma_scale / best_c:.3f}）")
        print("  含义：可买区间(0.80-0.94)看到的『正 edge』多为模型高估，放宽阈值有 -EV 风险。")
    elif best_c > 1.05:
        print(f"→ Phi 过度保守（实证胜率 > Phi）。等价于 σ 偏高：建议把 sigmaScale 调小约 ×{1/best_c:.2f}"
              f"（当前 {args.sigma_scale} → 建议 sigmaScale ≈ {args.sigma_scale / best_c:.3f}）")
        print("  含义：diffSigma 被系统性压低，真实机会评分偏低，是 0 成交的诱因之一。")
    else:
        print("→ Phi 基本校准（c≈1）。说明市场高效，可买区间难有持续静态 edge；")
        print("  keep_lag 路线只能靠『新鲜价 + 动态滞后』捕捉秒级时间差，务必先修 P0 再灰度。")


if __name__ == "__main__":
    main()

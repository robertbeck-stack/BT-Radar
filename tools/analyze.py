#!/usr/bin/env python3
"""
Greinir CSV úr BLE Radar 3D (Downloads/BLE3D/ble3d_*.csv) og metur 3D staðsetningu tækja.

    python analyze.py ble3d_20261028_021500.csv                # sterkasta tækið
    python analyze.py ble3d_...csv --address AA:BB:CC:DD:EE:FF
    python analyze.py --simulate                               # prófa á hermdum gögnum

Þarf: numpy, scipy, matplotlib  (pip install numpy scipy matplotlib)

Hnit eru ARCore heimshnit: y = upp, núllpunktur = staðsetning símans þegar appið ræstist.
"""
import argparse
import csv
import sys
from collections import defaultdict

import numpy as np
from scipy.optimize import least_squares

BIN = 0.3
N_PATHLOSS = 2.2
C = 10 * N_PATHLOSS / np.log(10)
SIGMA_INFLATION = 3.0  # leifar eru fylgnar í rúmi → hrátt σ of bjartsýnt (sama og í appinu)


def load(path):
    rows = defaultdict(list)
    names = {}
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            # Hver ARCore lota (frame) hefur eigið hnitakerfi; ekki blanda þeim saman
            key = r["address"] if r.get("frame", "0") in ("", "0") else f'{r["address"]}#{r["frame"]}'
            rows[key].append((float(r["x"]), float(r["y"]), float(r["z"]), int(r["rssi"])))
            if r["name"]:
                names[key] = r["name"]
    return {a: np.array(v) for a, v in rows.items()}, names


def bin_samples(s):
    """Miðgildi RSSI í hverjum 30 cm teningi — dregur úr hávaða og jafnar vægi staða."""
    keys = np.floor(s[:, :3] / BIN).astype(int)
    groups = defaultdict(list)
    for k, row in zip(map(tuple, keys), s):
        groups[k].append(row)
    pts = []
    for g in groups.values():
        g = np.array(g)
        pts.append([*g[:, :3].mean(0), np.median(g[:, 3]), len(g)])
    return np.array(pts)


def fit(pts):
    P, r, cnt = pts[:, :3], pts[:, 3], pts[:, 4]
    w = np.sqrt(np.minimum(cnt, 6) / 6)

    def resid(q):
        d = np.maximum(np.linalg.norm(P - q[:3], axis=1), 0.2)
        return w * (r - (q[3] - C * np.log(d)))

    strongest = P[np.argmax(r)]
    d0 = np.clip(10 ** ((-59 - r.max()) / (10 * N_PATHLOSS)), 0.5, 8)
    starts = [strongest + d0 * e for e in np.vstack([np.eye(3), -np.eye(3)])]
    lin = 10 ** (r / 10)
    starts.append((P * lin[:, None]).sum(0) / lin.sum())

    sols = []
    for s0 in starts:
        res = least_squares(resid, [*s0, -60.0], loss="huber", f_scale=5.0)
        sols.append(res)
    best = min(sols, key=lambda s: s.cost)
    # óvissa úr Jacobian
    J = best.jac
    dof = max(1, len(r) - 4)
    s2 = (best.fun ** 2).sum() / dof * SIGMA_INFLATION ** 2
    try:
        cov = np.linalg.inv(J.T @ J) * s2
        sigma = np.sqrt(np.clip(np.diag(cov)[:3], 0, None))
    except np.linalg.LinAlgError:
        sigma = np.full(3, np.inf)
    rivals = [s for s in sols if s.cost <= best.cost * 1.05 and np.linalg.norm(s.x[:3] - best.x[:3]) > 1.0]
    return best.x, sigma, rivals


def report(addr, name, samples, truth=None, plot=True):
    pts = bin_samples(samples)
    spread = pts[:, :3].std(0)
    print(f"\n== {name or addr} ==")
    print(f"mælingar: {len(samples)}, hólf: {len(pts)}")
    print(f"hreyfisvið (std): x {spread[0]:.2f}  y(hæð) {spread[1]:.2f}  z {spread[2]:.2f} m")
    if len(pts) < 8:
        print("of fá hólf — gakktu meira um")
        return
    q, sigma, rivals = fit(pts)
    print(f"staðsetning: x {q[0]:.2f}  y {q[1]:.2f}  z {q[2]:.2f} m   (A = {q[3]:.1f} dBm @1m)")
    print(f"± σ:         x {sigma[0]:.2f}  y {sigma[1]:.2f}  z {sigma[2]:.2f} m")
    if truth is not None:
        print(f"raunveruleg: x {truth[0]:.2f}  y {truth[1]:.2f}  z {truth[2]:.2f}  → skekkja {np.linalg.norm(q[:3]-truth):.2f} m")
    if rivals:
        r = max(rivals, key=lambda s: np.linalg.norm(s.x[:3] - q[:3]))
        print(f"⚠ tvíræð: önnur lausn jafngóð í ({r.x[0]:.2f}, {r.x[1]:.2f}, {r.x[2]:.2f})")
    if spread[1] < 0.3:
        print("⚠ lítil lóðrétt hreyfing — hæðin er óáreiðanleg")

    if plot:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig = plt.figure(figsize=(8, 7))
        ax = fig.add_subplot(projection="3d")
        # ARCore y=upp → teiknum sem z-ás
        sc = ax.scatter(pts[:, 0], pts[:, 2], pts[:, 1], c=pts[:, 3], cmap="coolwarm", s=12)
        ax.scatter([q[0]], [q[2]], [q[1]], c="k", s=120, marker="*", label="mat")
        if truth is not None:
            ax.scatter([truth[0]], [truth[2]], [truth[1]], c="g", s=80, marker="X", label="raun")
        for r in rivals:
            ax.scatter([r.x[0]], [r.x[2]], [r.x[1]], facecolors="none", edgecolors="k", s=120, label="tvíræð")
        ax.set_xlabel("x (m)"); ax.set_ylabel("z (m)"); ax.set_zlabel("hæð y (m)")
        fig.colorbar(sc, label="RSSI (dBm)", shrink=0.6)
        ax.legend()
        out = f"ble3d_{addr.replace(':', '')}.png"
        fig.savefig(out, dpi=120, bbox_inches="tight")
        print(f"mynd: {out}")


def simulate(seed=0):
    """
    Raunsærri hermun en einingaprófin: rúmfræðilega fylgnandi skuggadeyfing (shadow fading,
    σ=5 dB, fylgnilengd ~1 m), líkamsskuggi (−8 dB þegar líkaminn er milli síma og tækis)
    og hraður hávaði (σ=3 dB).
    """
    rng = np.random.default_rng(seed)
    truth = np.array([3.0, 0.3, -2.5])
    # fylgnandi deyfing: summa af slembnum sínusbylgjum í rúmi
    K = rng.normal(size=(40, 3)) * (2 * np.pi / 1.0) / np.sqrt(3)
    ph = rng.uniform(0, 2 * np.pi, 40)

    def shadow(p):
        return 5.0 * np.sqrt(2 / 40) * np.cos(p @ K.T + ph).sum(-1)

    n = 4000
    t = np.linspace(0, 1, n)
    s = t * 2 * np.pi * 3
    x = 2.0 * np.cos(s) + 0.4 * np.sin(5 * s)
    z = 1.5 * np.sin(s) - 1.0
    y = 1.1 + 0.9 * np.sin(t * 2 * np.pi * 17)
    P = np.stack([x, y, z], 1)
    heading = np.stack([np.gradient(x), np.gradient(z)], 1)
    heading /= np.linalg.norm(heading, axis=1, keepdims=True)
    to_dev = (truth - P)[:, [0, 2]]
    to_dev /= np.linalg.norm(to_dev, axis=1, keepdims=True)
    body = np.where((heading * to_dev).sum(1) < -0.3, -8.0, 0.0)  # síminn fyrir framan, tækið fyrir aftan
    d = np.maximum(np.linalg.norm(truth - P, axis=1), 0.1)
    rssi = -62 - 10 * N_PATHLOSS * np.log10(d) + shadow(P) + body + rng.normal(0, 3, n)
    return np.column_stack([P, np.round(rssi)]), truth


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", nargs="?")
    ap.add_argument("--address")
    ap.add_argument("--simulate", action="store_true")
    ap.add_argument("--no-plot", action="store_true")
    a = ap.parse_args()

    if a.simulate:
        errs = []
        for seed in range(10):
            samples, truth = simulate(seed)
            pts = bin_samples(samples)
            q, sigma, _ = fit(pts)
            errs.append(np.abs(q[:3] - truth))
            if seed == 0:
                report("SIM", "Hermt tæki (seed 0)", samples, truth, plot=not a.no_plot)
        errs = np.array(errs)
        print(f"\n10 hermanir — miðgildi skekkju: x {np.median(errs[:,0]):.2f}  hæð {np.median(errs[:,1]):.2f}  z {np.median(errs[:,2]):.2f} m;"
              f"  3D: {np.median(np.linalg.norm(errs,axis=1)):.2f} m (verst {np.linalg.norm(errs,axis=1).max():.2f})")
        return

    if not a.csv:
        ap.error("gefðu upp CSV skrá eða --simulate")
    data, names = load(a.csv)
    if a.address:
        addrs = [a.address]
    else:
        addrs = sorted(data, key=lambda k: -len(data[k]))[:1]
        print("Tæki eftir fjölda mælinga:")
        for k in sorted(data, key=lambda k: -len(data[k]))[:10]:
            print(f"  {k}  {names.get(k, ''):24s} {len(data[k])}")
    for addr in addrs:
        if addr not in data:
            sys.exit(f"{addr} finnst ekki")
        report(addr, names.get(addr), data[addr], plot=not a.no_plot)


if __name__ == "__main__":
    main()

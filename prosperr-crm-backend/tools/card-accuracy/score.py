"""Run every card in set/ through the CRM's /api/ai/card endpoint and score each field against truth.json."""
import json, os, re, subprocess, sys, time

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "set")
B, TOK = sys.argv[1], sys.argv[2]
truth = json.load(open(os.path.join(D, "truth.json")))

def norm(s): return re.sub(r"[^a-z0-9]+", " ", (s or "").lower().replace("&", " and ")).strip()
def company(s): return re.sub(r"\b(pvt|private|ltd|limited|llp|inc)\b", "", norm(s)).strip()
def domain(s): return re.sub(r"^(https?://)?(www\.)?", "", (s or "").lower()).split("/")[0].strip()
def ten(p): d = re.sub(r"\D", "", p); return d[-10:] if len(d) >= 10 else d
def li(s): return re.sub(r"^(https?://)?(www\.)?", "", (s or "").lower()).rstrip("/")

CHECK = {
    "fullName": lambda t, g: norm(t) == norm(g),
    "designation": lambda t, g: norm(t) == norm(g),
    "company": lambda t, g: company(t) == company(g),
    "emails": lambda t, g: set(t) == set(x.lower() for x in g),
    "phones": lambda t, g: set(map(ten, t)) == set(map(ten, g)),
    "website": lambda t, g: domain(t) == domain(g),
    "city": lambda t, g: norm(t) == norm(g),
    "linkedin": lambda t, g: li(t) == li(g),
}
rows, per, CHECKS = [], {k: [0, 0] for k in CHECK}, {}
for cid in sorted(truth):
    t0 = time.time()
    out = subprocess.run(["curl", "-s", "-m", "90", "-X", "POST", f"{B}/api/ai/card", "-H", f"Authorization: Bearer {TOK}",
                          "-F", f"image=@{os.path.join(D, cid + '.png')};type=image/png"], capture_output=True, text=True).stdout
    secs = time.time() - t0
    try: got = json.loads(out)["card"]; conf = json.loads(out)["confidence"]; CHECKS[cid] = json.loads(out).get("checks", [])
    except Exception: rows.append((cid, secs, None, out[:200])); continue
    misses = []
    for k, ok in CHECK.items():
        if k not in truth[cid]: continue
        per[k][1] += 1
        if ok(truth[cid][k], got.get(k) if got.get(k) is not None else ([] if k in ("emails", "phones") else "")): per[k][0] += 1
        else: misses.append(f"{k}: expected {truth[cid][k]!r} got {got.get(k)!r} (conf {conf.get(k)})")
    rows.append((cid, secs, misses, None))
fields = sum(v[1] for v in per.values()); right = sum(v[0] for v in per.values())
perfect = sum(1 for r in rows if r[2] == [])
print(f"Cards: {len(rows)} · fully correct: {perfect}/{len(rows)} · fields correct: {right}/{fields} ({100*right/fields:.1f}%) · avg time {sum(r[1] for r in rows)/len(rows):.1f}s")
for k, (a, n) in per.items(): print(f"  {k:12} {a}/{n}  ({100*a/n:.0f}%)")
for cid, secs, misses, err in rows:
    print(f"- {cid} ({secs:.1f}s): " + ("ERROR " + err if err else "all correct" if not misses else "; ".join(misses)))
    for c in CHECKS.get(cid, []): print(f"      check: {c}")

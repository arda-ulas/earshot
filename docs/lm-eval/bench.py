#!/usr/bin/env python3
"""Scores a prompt/model variant with the host mirror of the app's llama.cpp path.

  bench.py VARIANT.json [dev|test]

VARIANT: {"name", "model", "format": "command"|"intent", "system", "examples": [[user, assistant], ...],
          "grammar", "assistant_prefix" (optional)}
Prints a score line and per-case rows; writes results/<name>-<split>.json.
Labels: warmer, cooler, set_temp_high (>=24), set_temp_low (<=18), set_temp_mid, ac_on, ac_off,
fan_up (>=3), fan_down, defrost_front_on, defrost_rear_on, defrost_off, query_speed, query_gear,
query_cabin, ood, invalid.
"""
import json, os, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
LMHOST = os.environ.get("LMHOST", os.path.join(HERE, "lmhost", "build", "lmhost"))

INTENTS = {  # intent format: label is the intent itself, mapped the same way the app will map it
    "warmer": "warmer", "cooler": "cooler", "ac_on": "ac_on", "ac_off": "ac_off",
    "defrost_front_on": "defrost_front_on", "defrost_rear_on": "defrost_rear_on",
    "query_speed": "query_speed", "query_gear": "query_gear", "query_cabin": "query_cabin",
    "out_of_domain": "ood",
}

def label(fmt, out):
    try:
        j = json.loads(out)
    except Exception:
        return "invalid"
    if fmt == "intent":
        return INTENTS.get(j.get("intent"), "invalid")
    c = j.get("cmd")
    if c == "adjust_temp":
        return "warmer" if j["delta"] > 0 else "cooler"
    if c == "set_temp":
        v = j["celsius"]
        return "set_temp_high" if v >= 24 else "set_temp_low" if v <= 18 else "set_temp_mid"
    if c == "set_ac":
        return "ac_on" if j["on"] else "ac_off"
    if c == "set_fan":
        return "fan_up" if j["level"] >= 3 else "fan_down"
    if c == "set_defrost":
        return f"defrost_{j['window']}_on" if j["on"] else "defrost_off"
    if c == "out_of_domain":
        return "ood"
    if c in ("query_speed", "query_gear", "query_cabin"):
        return c
    return "invalid"

def run(v, split):
    cases = json.load(open(os.path.join(HERE, f"{split}.json")))
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    spec = os.path.join(HERE, "results", f"{v['name']}.spec")
    with open(spec, "w") as f:
        f.write("system\t" + v["system"].replace("\n", "\\n") + "\n")
        for u, a in v.get("examples", []):
            f.write("user\t" + u.replace("\n", "\\n") + "\n")
            f.write("assistant\t" + a.replace("\n", "\\n") + "\n")
        if v.get("assistant_prefix"):
            f.write("assistant_prefix\t" + v["assistant_prefix"].replace("\n", "\\n") + "\n")
    gfile = os.path.join(HERE, "results", f"{v['name']}.gbnf")
    open(gfile, "w").write(v["grammar"])
    t0 = time.time()
    p = subprocess.run([LMHOST, v["model"], spec, gfile], input="\n".join(u for u, _ in cases) + "\n",
                       capture_output=True, text=True, timeout=900)
    outs = p.stdout.splitlines()
    prefix_tokens = next((l.split(":")[1].strip() for l in p.stderr.splitlines() if l.startswith("prefix tokens")), "?")
    if p.returncode != 0 or len(outs) != len(cases):
        print("RUN FAILED", p.returncode, p.stderr[-500:]); sys.exit(1)
    rows, ok, ood_total, ood_ok, false_ood, wrong_dir = [], 0, 0, 0, 0, 0
    for (u, good), o in zip(cases, outs):
        l = label(v["format"], o.strip())
        hit = l in good
        ok += hit
        if good == ["ood"]:
            ood_total += 1; ood_ok += hit
        elif l == "ood":
            false_ood += 1
        if ("warmer" in good and l in ("cooler", "set_temp_low", "ac_on")) or ("cooler" in good and l in ("warmer", "set_temp_high")):
            wrong_dir += 1
        rows.append({"utterance": u, "expected": good, "output": o.strip(), "label": l, "ok": hit})
    res = {"name": v["name"], "split": split, "score": ok, "total": len(cases), "ood_correct": ood_ok, "ood_total": ood_total,
           "in_domain_marked_ood": false_ood, "wrong_direction": wrong_dir, "prefix_tokens": prefix_tokens,
           "seconds": round(time.time() - t0, 1), "rows": rows}
    json.dump(res, open(os.path.join(HERE, "results", f"{v['name']}-{split}.json"), "w"), indent=1)
    return res

if __name__ == "__main__":
    v = json.load(open(sys.argv[1]))
    split = sys.argv[2] if len(sys.argv) > 2 else "dev"
    r = run(v, split)
    print(f"{r['name']} [{split}] {r['score']}/{r['total']}  ood {r['ood_correct']}/{r['ood_total']}  "
          f"in-domain->ood {r['in_domain_marked_ood']}  wrong-direction {r['wrong_direction']}  prefix {r['prefix_tokens']} tok  {r['seconds']}s")
    for row in r["rows"]:
        print(("  ok  " if row["ok"] else "  BAD ") + f"{row['utterance']!r:48} {row['output']}  -> {row['label']}")

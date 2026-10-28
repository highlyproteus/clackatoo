#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Evaluate the built-in swipe decoder on the HeliBoard Gesture Data Project.

The dataset (CC BY-SA 4.0, https://codeberg.org/eclexic/heliboard-gesture-data) is downloaded into
--cache and never committed. Each record holds a real swipe, the keyboard geometry it was made on,
the word the user meant and the suggestions Google's decoder produced for it.

    cmake -S tools/gesture-eval -B build/gesture-eval -DCMAKE_BUILD_TYPE=Release
    cmake --build build/gesture-eval -j
    python3 tools/gesture-eval/run_eval.py --binary build/gesture-eval/gesture_eval \\
        --dict app/src/main/assets/dicts/main_en-US.dict --split dev

Prints one JSON summary; the first 200 misses go to <cache>/misses-<split>.tsv.
Standard library only; needs the `zstd` command line tool.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import subprocess
import sys
import urllib.request

DATASET_URL = ("https://codeberg.org/eclexic/heliboard-gesture-data/raw/commit/"
               "7d39bc9831d088192e133dae9ea6cd2a6127bae4/compressed/batch_1/en-US/"
               "en-US-1.gestures.json.zst")
DATASET_NAME = "en-US-1.gestures.json.zst"
DATASET_SHA256 = "3b87b66f75af220495169f06bd4d37a51dc31489e9d209636e5e33ef93e033c3"
MAX_MISSES = 200


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_dataset(cache):
    os.makedirs(cache, exist_ok=True)
    path = os.path.join(cache, DATASET_NAME)
    if not os.path.exists(path):
        print(f"downloading {DATASET_URL}", file=sys.stderr)
        tmp = path + ".part"
        with urllib.request.urlopen(DATASET_URL) as response, open(tmp, "wb") as out:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
        os.replace(tmp, path)
        print(f"dataset sha256: {sha256_of(path)}", file=sys.stderr)
    actual = sha256_of(path)
    if actual != DATASET_SHA256:
        sys.exit(f"dataset checksum mismatch: {actual} (expected {DATASET_SHA256}); "
                 f"delete {path} and retry")
    raw = subprocess.run(["zstd", "-dc", path], check=True, capture_output=True).stdout
    return json.loads(raw)


class Engine:
    """One gesture_eval process speaking the line protocol."""

    def __init__(self, binary, dictionary, params):
        cmd = [binary, "--dict", dictionary]
        for param in params:
            cmd += ["--param", param]
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     text=True, encoding="utf-8", bufsize=1)
        self.next_id = 0

    def _roundtrip(self, text):
        self.proc.stdin.write(text)
        self.proc.stdin.flush()
        line = self.proc.stdout.readline()
        if not line:
            raise RuntimeError(f"gesture_eval exited with status {self.proc.wait()}")
        return line.split()

    def contains(self, word):
        self.next_id += 1
        reply = self._roundtrip(f"Q {self.next_id} {word}\n")
        return reply[0] == "IN" and reply[2] == "1"

    def decode(self, record):
        """Returns (suggestions best first, microseconds)."""
        self.next_id += 1
        info = record["keyboardInfo"]
        keys = record["_keys"]
        points = record["gesture"]
        lines = [f"G {self.next_id} {info['width']} {info['height']} {len(keys)} {len(points)}"]
        lines += [f"K {k['left']} {k['top']} {k['width']} {k['height']} {ord(k['value'].lower())}"
                  for k in keys]
        lines += [f"P {p['x']} {p['y']} {p['millis']} 0" for p in points]
        reply = self._roundtrip("\n".join(lines) + "\n")
        if reply[0] != "R":
            raise RuntimeError(f"unexpected reply: {reply}")
        micros, count = int(reply[2]), int(reply[3])
        words = [reply[4 + 2 * i] for i in range(count)]
        return words, micros

    def close(self):
        self.proc.stdin.close()
        self.proc.wait()


def usable_keys(record):
    """Keys of the record that ProximityInfo would see: one code point, not a control code."""
    keys = []
    for key in record["keyboardInfo"]["keys"]:
        value = key.get("value") or ""
        if len(value) != 1:
            continue
        lowered = value.lower()
        if len(lowered) != 1 or ord(lowered) < 32:
            continue
        keys.append(key)
    return keys


def percentile(sorted_values, fraction):
    if not sorted_values:
        return None
    index = min(len(sorted_values) - 1, int(round(fraction * (len(sorted_values) - 1))))
    return sorted_values[index]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--binary", required=True, help="path of gesture_eval")
    parser.add_argument("--dict", required=True, help="binary dictionary (.dict)")
    parser.add_argument("--split", choices=["dev", "test"], default="dev")
    parser.add_argument("--param", action="append", default=[], metavar="NAME=VALUE",
                        help="GestureParams override, passed through (repeatable)")
    parser.add_argument("--limit", type=int, default=0, help="evaluate at most N records")
    parser.add_argument("--cache", default=os.path.expanduser("~/.cache/clackatoo-gesture-data"))
    parser.add_argument("--jobs", type=int, default=1,
                        help="gesture_eval processes to run in parallel (timings get noisier)")
    args = parser.parse_args()

    records = fetch_dataset(args.cache)
    exclusions = {"wrong split": 0, "not active mode": 0, "unknown library": 0,
                  "target shorter than 2": 0, "several pointers": 0, "no usable keys": 0,
                  "target not in dictionary": 0}
    candidates = []
    split_total = 0
    for record in records:
        # The split is a property of the record's uuid, so dev and test never overlap.
        is_test = int(record["uuid"][0], 16) % 2 == 1
        if is_test != (args.split == "test"):
            exclusions["wrong split"] += 1
            continue
        split_total += 1
        target = record.get("targetWord") or ""
        if not record.get("activeMode"):
            exclusions["not active mode"] += 1
        elif not record.get("knownLibrary"):
            exclusions["unknown library"] += 1
        elif len(target) < 2:
            exclusions["target shorter than 2"] += 1
        elif len({p["id"] for p in record["gesture"]}) != 1 or not record["gesture"]:
            exclusions["several pointers"] += 1
        else:
            record["_keys"] = usable_keys(record)
            if not record["_keys"]:
                exclusions["no usable keys"] += 1
            else:
                candidates.append(record)

    checker = Engine(args.binary, args.dict, args.param)
    kept = []
    for record in candidates:
        if checker.contains(record["targetWord"]):
            kept.append(record)
        else:
            exclusions["target not in dictionary"] += 1
    checker.close()
    if args.limit:
        kept = kept[:args.limit]

    def run_shard(shard):
        engine = Engine(args.binary, args.dict, args.param)
        try:
            return [engine.decode(record) for record in shard]
        finally:
            engine.close()

    jobs = max(1, args.jobs)
    shards = [kept[i::jobs] for i in range(jobs)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        shard_results = list(pool.map(run_shard, shards))
    outcomes = [None] * len(kept)
    for i, results in enumerate(shard_results):
        for j, result in enumerate(results):
            outcomes[i + j * jobs] = result

    top1 = top3 = google_top1 = google_top3 = 0
    micros = []
    misses = []
    for record, (words, elapsed) in zip(kept, outcomes):
        target = record["targetWord"].lower()
        ours = [w.lower() for w in words]
        google = [s["word"].lower() for s in record.get("suggestions", [])]
        micros.append(elapsed)
        hit1 = ours[:1] == [target]
        hit3 = target in ours[:3]
        top1 += hit1
        top3 += hit3
        google_top1 += google[:1] == [target]
        google_top3 += target in google[:3]
        if not hit1 and len(misses) < MAX_MISSES:
            misses.append((record["targetWord"], ",".join(words[:3]), google[0] if google else ""))

    n = len(kept)
    micros.sort()
    summary = {
        "split": args.split,
        "params": args.param,
        "records_in_split": split_total,
        "evaluated": n,
        "excluded": {k: v for k, v in exclusions.items() if k != "wrong split"},
        "top1": top1 / n if n else None,
        "top3": top3 / n if n else None,
        "google_top1": google_top1 / n if n else None,
        "google_top3": google_top3 / n if n else None,
        "p50_ms": percentile(micros, 0.50) / 1000 if micros else None,
        "p95_ms": percentile(micros, 0.95) / 1000 if micros else None,
    }
    os.makedirs(args.cache, exist_ok=True)
    with open(os.path.join(args.cache, f"misses-{args.split}.tsv"), "w", encoding="utf-8") as f:
        f.write("target\tours_top3\tgoogle_top1\n")
        for row in misses:
            f.write("\t".join(row) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()

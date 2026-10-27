#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Compares grammar-check models on grammar-cases.json with the host test tool (same native code as the app).

usage: run_grammar_eval.py <clackatoo-ml-host> <models-dir> [threads]
The prompts mirror GrammarPrompt.kt (chat template per model family, empty think block, greedy decoding).
"""
import difflib, json, subprocess, sys, os, re

HOST, MODELS = sys.argv[1], sys.argv[2]
THREADS = sys.argv[3] if len(sys.argv) > 3 else "4"
CASES = json.load(open(os.path.join(os.path.dirname(__file__), "grammar-cases.json")))

SYSTEM = ("You are a proofreader. Correct the spelling, grammar and punctuation of the text the user sends. "
          "Keep the meaning, the wording, the tone and the language. Do not translate, do not explain, "
          "do not add anything. Reply with only the corrected text.")
SHOTS = {
    "en": [("me and her goes to school on mondey", "She and I go to school on Monday."),
           ("The weather is nice today.", "The weather is nice today.")],
    "es": [("yo y ella vamos a la escuela los luness", "Ella y yo vamos a la escuela los lunes."),
           ("El tiempo está muy bonito hoy.", "El tiempo está muy bonito hoy.")],
}

def chatml(turns):
    p = "".join(f"<|im_start|>{r}\n{t}<|im_end|>\n" for r, t in turns)
    return p + "<|im_start|>assistant\n<think>\n\n</think>\n\n"

def granite(turns):
    p = "".join(f"<|start_of_role|>{r}<|end_of_role|>{t}<|end_of_text|>\n" for r, t in turns)
    return p + "<|start_of_role|>assistant<|end_of_role|>"

MODELS_UNDER_TEST = [
    ("Qwen3-0.6B Q4_K_M", "Qwen3-0.6B-Q4_K_M.gguf", chatml),
    ("granite-4.0-350m Q4_K_M", "granite-4.0-350m-Q4_K_M.gguf", granite),
    ("Qwen3.5-0.8B Q4_K_M", "Qwen3.5-0.8B-Q4_K_M.gguf", chatml),
    ("Qwen3-1.7B Q4_K_M", "Qwen3-1.7B-Q4_K_M.gguf", chatml),
]

def norm(s): return re.sub(r"\s+", " ", s.strip())

def prompt(fmt, case, shots):
    turns = [("system", SYSTEM)]
    if shots:
        for a, b in SHOTS[case["lang"]]:
            turns += [("user", a), ("assistant", b)]
    turns.append(("user", case["input"]))
    if fmt is chatml:
        # past assistant turns carry no think block in Qwen3 templates
        p = "".join(f"<|im_start|>{r}\n{t}<|im_end|>\n" for r, t in turns)
        return p + "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    return fmt(turns)

def run(model_file, fmt, shots):
    prompts = "\x1e".join(prompt(fmt, c, shots) for c in CASES)
    r = subprocess.run([HOST, "llm", os.path.join(MODELS, model_file), THREADS, "160"], input=prompts.encode(),
                       capture_output=True)
    recs = [x for x in r.stdout.decode().split("\x1e") if x != ""]
    out = []
    for rec in recs:
        ms, _, text = rec.partition("\n")
        out.append((float(ms), text.strip()))
    return out

results = {}
for name, f, fmt in MODELS_UNDER_TEST:
    for shots in (False, True):
        outs = run(f, fmt, shots)
        stats = {}
        for lang in ("en", "es"):
            idx = [i for i, c in enumerate(CASES) if c["lang"] == lang]
            exact = sum(norm(outs[i][1]) == norm(CASES[i]["expected"]) for i in idx)
            sim = sum(difflib.SequenceMatcher(None, norm(outs[i][1]), norm(CASES[i]["expected"])).ratio() for i in idx) / len(idx)
            ms = sum(outs[i][0] for i in idx) / len(idx)
            stats[lang] = (exact, len(idx), sim, ms)
        label = f"{name} {'few-shot' if shots else 'zero-shot'}"
        results[label] = (stats, outs)
        print(f"{label:45s}", " | ".join(f"{l}: exact {s[0]}/{s[1]} sim {s[2]:.3f} {s[3]:.0f} ms" for l, s in stats.items()), flush=True)

json.dump({k: v[1] for k, v in results.items()}, open("/tmp/grammar-eval-outputs.json", "w"), ensure_ascii=False, indent=1)

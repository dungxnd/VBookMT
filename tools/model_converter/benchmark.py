"""
Benchmark candidate models for VBookMT:
- Quality comparison on web novel/xianxia sentences.
- Pronoun consistency and dialogue registers.
- Inference latency measurement.
"""

import time
from transformers import AutoTokenizer, MarianMTModel
import torch

TEST_SENTENCES = [
    # 1. Xianxia action & mountain gate
    "他抬头看向远处的山门，山门前已有一众年轻弟子排队等候。",
    # 2. Dialogue with vocative / pronoun stability (ta / nguoi)
    "“你休要胡言乱语！”师姐冷哼一声，“我与师尊之事，岂容你随意置喙？”",
    # 3. Subject-role royal titles (tram / ban vuong)
    "本王不要看。朕且听你说。",
    # 4. Modern/slang web novel line
    "恰好我室友疯狂为你打call，我便随手点进来看了一眼。"
]

MODELS = [
    ("HachimiMT-60-QT", "ngocdang83/HachimiMT-60-QT"),
    ("HachimiMT-40-QT", "ngocdang83/HachimiMT-40-QT"),
    ("MoxhiMT-30-QT", "DanVP/MoxhiMT-30-QT")
]

def run_benchmark():
    print("================================================================")
    print("           VBookMT Model Quality & Speed Benchmark              ")
    print("================================================================\n")

    for name, repo_id in MODELS:
        print(f"\n--- Testing Model: {name} ({repo_id}) ---")
        try:
            tok = AutoTokenizer.from_pretrained(repo_id)
            model = MarianMTModel.from_pretrained(repo_id).eval()

            for i, src in enumerate(TEST_SENTENCES, 1):
                start = time.perf_counter()
                inputs = tok(src, return_tensors="pt", truncation=True, max_length=256)
                with torch.inference_mode():
                    outputs = model.generate(**inputs, max_new_tokens=256, num_beams=4)
                elapsed = (time.perf_counter() - start) * 1000
                trans = tok.decode(outputs[0], skip_special_tokens=True)

                print(f"[{i}] Trung: {src}")
                print(f"    Việt : {trans}  ({elapsed:.1f}ms)")
        except Exception as e:
            print(f"Lỗi khi nạp/dịch với {name}: {e}")

if __name__ == "__main__":
    run_benchmark()

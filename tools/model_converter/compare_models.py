import sys
import time
import re
from pathlib import Path
import ctranslate2
import sentencepiece as spm

sys.stdout.reconfigure(encoding='utf-8')

OPENERS = "“‘«「『【《([{（［｛"
_OPENER_SPACE = re.compile("([" + re.escape(OPENERS) + r"]) (?=[^\W\d_])")
_TRAILING_NUMS = re.compile(r"(\D)\s*\d+$")

def clean_source_line(text: str) -> str:
    text = text.strip()
    # Strip web novel crawler comment counts at the end of paragraphs (e.g. '打你屁股了哦！”15' -> '打你屁股了哦！”')
    text = _TRAILING_NUMS.sub(r"\1", text)
    return text

def postprocess(text: str) -> str:
    return _OPENER_SPACE.sub(r"\1", text)

def load_engine(model_dir: str):
    sp_src = spm.SentencePieceProcessor(model_file=f"{model_dir}/source.spm")
    sp_tgt = spm.SentencePieceProcessor(model_file=f"{model_dir}/target.spm")
    translator = ctranslate2.Translator(model_dir, device="cpu", compute_type="int8_float32", intra_threads=4)
    return sp_src, sp_tgt, translator

def translate_paragraphs(paragraphs, sp_src, sp_tgt, translator):
    results = []
    valid_indices = []
    batch_tokens = []
    
    for idx, p in enumerate(paragraphs):
        p_clean = clean_source_line(p)
        if not p_clean:
            results.append("")
        else:
            # MUST append </s> as required by MarianMT tokenizer contract
            tokens = sp_src.encode(p_clean, out_type=str) + ["</s>"]
            batch_tokens.append(tokens)
            valid_indices.append(idx)
            results.append(None)
            
    if batch_tokens:
        ct2_res = translator.translate_batch(batch_tokens, beam_size=1, max_decoding_length=256)
        for i, res in zip(valid_indices, ct2_res):
            trans_str = postprocess(sp_tgt.decode(res.hypotheses[0]))
            results[i] = trans_str
            
    return results

def main():
    input_file = Path("test_input.txt")
    if not input_file.exists():
        print("Missing test_input.txt")
        return
        
    raw_text = input_file.read_text(encoding="utf-8")
    paragraphs = raw_text.splitlines()
    non_empty = [clean_source_line(p) for p in paragraphs if clean_source_line(p)]
    total_chars = sum(len(p) for p in non_empty)
    print(f"Loaded {len(non_empty)} non-empty paragraphs ({total_chars} Chinese characters).\n")

    models = [
        ("HachimiMT-60-QT", "./models/hachimi60_ct2/ct2-int8_float32"),
        ("HachimiMT-40-QT", "./models/hachimi40_ct2/ct2-int8_float32"),
        ("MoxhiMT-30-QT", "./models/moxhi30_ct2/ct2-int8_float32")
    ]

    outputs = {}

    for name, path in models:
        t0 = time.perf_counter()
        sp_src, sp_tgt, translator = load_engine(path)
        t_load = (time.perf_counter() - t0) * 1000
        
        t1 = time.perf_counter()
        trans_paras = translate_paragraphs(paragraphs, sp_src, sp_tgt, translator)
        t_trans = (time.perf_counter() - t1) * 1000
        
        speed = total_chars / (t_trans / 1000.0)
        outputs[name] = {
            "paras": trans_paras,
            "load_ms": t_load,
            "trans_ms": t_trans,
            "speed": speed
        }
        print(f"[+] {name}: Loaded in {t_load:.1f}ms | Translated {len(non_empty)} lines in {t_trans:.1f}ms ({speed:.1f} chars/sec)")

        # Save to output file
        fname = f"output_{name}.txt"
        with open(fname, "w", encoding="utf-8") as f:
            f.write("\n".join(trans_paras))

if __name__ == "__main__":
    main()

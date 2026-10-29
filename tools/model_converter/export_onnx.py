"""
Utility to export Hugging Face MarianMT models (HachimiMT / MoxhiMT) to ONNX INT8
for Mobile (Android ONNX Runtime) and Desktop (Kotlin/Native).
"""

import argparse
from pathlib import Path
from optimum.onnxruntime import ORTModelForSeq2SeqLM
from optimum.onnxruntime.configuration import AutoQuantizationConfig
from optimum.onnxruntime import ORTQuantizer
from transformers import AutoTokenizer

SUPPORTED_MODELS = {
    "hachimi-60-qt": "ngocdang83/HachimiMT-60-QT",
    "hachimi-40-qt": "ngocdang83/HachimiMT-40-QT",
    "moxhi-30-qt": "DanVP/MoxhiMT-30-QT",
    "moxhi-60": "DanVP/MoxhiMT-60",
    "vp2vi": "DanVP/vp2vi"
}

def export_model(model_name: str, output_dir: str, quantize_int8: bool = True):
    repo_id = SUPPORTED_MODELS.get(model_name, model_name)
    out_path = Path(output_dir) / model_name
    out_path.mkdir(parents=True, exist_ok=True)

    print(f"[*] Exporting {repo_id} to ONNX at {out_path} ...")
    
    # 1. Export FP32 ONNX model
    model = ORTModelForSeq2SeqLM.from_pretrained(repo_id, export=True)
    tokenizer = AutoTokenizer.from_pretrained(repo_id)

    model.save_pretrained(out_path)
    tokenizer.save_pretrained(out_path)
    print(f"[+] FP32 ONNX export completed at {out_path}")

    # 2. Dynamic INT8 Quantization
    if quantize_int8:
        print("[*] Quantizing model to INT8 (Dynamic Quantization for CPU/Mobile) ...")
        q_dir = out_path / "int8"
        q_dir.mkdir(exist_ok=True)
        
        qconfig = AutoQuantizationConfig.arm64(is_static=False, per_channel=False)
        quantizer = ORTQuantizer.from_pretrained(out_path)
        quantizer.quantize(save_dir=q_dir, quantization_config=qconfig)
        
        tokenizer.save_pretrained(q_dir)
        print(f"[+] Quantized INT8 model saved at {q_dir}")

def main():
    parser = argparse.ArgumentParser(description="Export MarianMT models to ONNX for VBookMT")
    parser.add_argument("--model", type=str, default="hachimi-40-qt", choices=list(SUPPORTED_MODELS.keys()))
    parser.add_argument("--output", type=str, default="./exported_models")
    parser.add_argument("--no-quantize", action="store_true")
    args = parser.parse_args()

    export_model(args.model, args.output, quantize_int8=not args.no_quantize)

if __name__ == "__main__":
    main()

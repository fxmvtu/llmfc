#!/usr/bin/env python3
"""
download_model.py — HuggingFace GGUF 模型下载工具

用法:
    python scripts/download_model.py Qwen/Qwen2.5-0.5B-Instruct-GGUF ./models/
    python scripts/download_model.py microsoft/Phi-3-mini-4k-instruct-GGUF ./models/
"""

import sys
import os
import argparse
from pathlib import Path

try:
    from huggingface_hub import hf_hub_download, list_repo_files, snapshot_download
except ImportError:
    print("ERROR: huggingface-hub not installed.")
    print("  Install with: pip install huggingface-hub")
    sys.exit(1)


def list_available_gguf_files(model_id: str):
    """列出指定 HuggingFace repo 下所有 .gguf 文件"""
    try:
        files = list_repo_files(model_id, repo_type="model")
        gguf_files = [f for f in files if f.endswith(".gguf")]
        return gguf_files
    except Exception as e:
        print(f"ERROR listing files for {model_id}: {e}")
        return []


def download_model(model_id: str, out_dir: str, filename: str = None, model_size_gb: float = None):
    """下载指定 model_id 的 GGUF 文件到 out_dir"""
    os.makedirs(out_dir, exist_ok=True)

    if filename:
        # 下载指定文件
        files = [filename]
    else:
        # 列出并让用户选择
        files = list_available_gguf_files(model_id)
        if not files:
            print(f"No .gguf files found for {model_id}")
            return

    for gguf in files:
        print(f"\nDownloading: {gguf}")
        if model_size_gb:
            print(f"  Estimated size: {model_size_gb:.1f} GB")

        try:
            path = hf_hub_download(
                repo_id=model_id,
                filename=gguf,
                local_dir=out_dir,
                local_dir_use_symlinks=False,
            )
            size_mb = os.path.getsize(path) / 1024 / 1024
            print(f"  Saved: {path} ({size_mb:.1f} MB)")
        except Exception as e:
            print(f"  FAILED: {e}")


def main():
    parser = argparse.ArgumentParser(description="Download GGUF models from HuggingFace")
    parser.add_argument("model_id", help="HuggingFace model ID, e.g. Qwen/Qwen2.5-0.5B-Instruct-GGUF")
    parser.add_argument("out_dir", nargs="?", default="./models", help="Output directory (default: ./models)")
    parser.add_argument("--filename", "-f", help="Specific .gguf filename to download")
    parser.add_argument("--list", "-l", action="store_true", help="List available GGUF files and exit")
    parser.add_argument("--size-gb", type=float, help="Expected model size in GB (for display only)")

    args = parser.parse_args()

    if args.list:
        files = list_available_gguf_files(args.model_id)
        print(f"Available GGUF files for {args.model_id}:")
        for f in files:
            print(f"  {f}")
        return

    if not args.filename:
        files = list_available_gguf_files(args.model_id)
        if not files:
            print(f"No .gguf files found for {args.model_id}")
            sys.exit(1)
        print(f"Found {len(files)} GGUF file(s). Downloading all:")
        for f in files:
            print(f"  {f}")
        print()

    download_model(args.model_id, args.out_dir, args.filename, args.size_gb)


if __name__ == "__main__":
    main()

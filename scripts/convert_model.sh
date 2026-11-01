#!/bin/bash
# LiteRT LM 模型转换脚本
# 从 HuggingFace / modelscope 下载模型 → 转换为 .litertlm 格式
#
# 依赖: pip install litert-torch-nightly modelscope
#
# 用法:
#   bash scripts/convert_model.sh qwen-0.5b
#   bash scripts/convert_model.sh qwen-1.5b
#   bash scripts/convert_model.sh qwen-3b
#   bash scripts/convert_model.sh gemma-3-1b

set -e

MODEL_NAME="${1:-qwen-1.5b}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODEL_OUT_DIR="$PROJECT_DIR/models/$MODEL_NAME"
MODEL_CACHE_DIR="$PROJECT_DIR/models/cache"

mkdir -p "$MODEL_OUT_DIR" "$MODEL_CACHE_DIR"

case "$MODEL_NAME" in
  qwen-0.5b)
    HF_REPO="Qwen/Qwen2.5-0.5B-Instruct"
    CACHE_LENGTH=1024
    ;;
  qwen-1.5b)
    HF_REPO="Qwen/Qwen2.5-1.5B-Instruct"
    CACHE_LENGTH=2048
    ;;
  qwen-3b)
    HF_REPO="Qwen/Qwen2.5-3B-Instruct"
    CACHE_LENGTH=2048
    ;;
  gemma-3-1b)
    HF_REPO="google/gemma-3-1b-it"
    CACHE_LENGTH=1024
    ;;
  *)
    echo "未知模型: $MODEL_NAME"
    echo "可用: qwen-0.5b | qwen-1.5b | qwen-3b | gemma-3-1b"
    exit 1
    ;;
esac

# 允许用 SXJ_CACHE_LENGTH 覆盖默认 cache_length（0.5B 小模型建议 8192，覆盖 4.5K 字符切片）
CACHE_LENGTH="${SXJ_CACHE_LENGTH:-$CACHE_LENGTH}"

echo "=== Step 1: 从 modelscope 下载 $HF_REPO ==="
python3 -c "
from modelscope import snapshot_download
model_dir = snapshot_download('$HF_REPO', cache_dir='$MODEL_CACHE_DIR')
print(f'模型路径: {model_dir}')
# 输出路径供下一步使用
import os
with open('$MODEL_OUT_DIR/.model_path', 'w') as f:
    f.write(model_dir)
"

MODEL_PATH=$(cat "$MODEL_OUT_DIR/.model_path")
echo "=== Step 2: litert-torch 导出 (cache_length=$CACHE_LENGTH) ==="
litert-torch export_hf \
  "$MODEL_PATH" \
  "$MODEL_OUT_DIR" \
  --task text_generation \
  --cache_length "$CACHE_LENGTH"

echo "=== 完成 ==="
echo "输出目录: $MODEL_OUT_DIR"
ls -lh "$MODEL_OUT_DIR"

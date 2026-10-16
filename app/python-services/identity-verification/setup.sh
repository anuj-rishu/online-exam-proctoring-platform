#!/usr/bin/env bash
# One-time setup. Run this once (or again after changing requirements.txt).
# After this, `java -jar ...` starts the service automatically — no manual
# `python app.py` needed on every boot.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON_BIN="${PYTHON_BIN:-python3}"

echo "Using $($PYTHON_BIN --version)"
$PYTHON_BIN -m venv .venv
.venv/bin/python -m pip install --quiet --upgrade pip
.venv/bin/python -m pip install --quiet -r requirements.txt

mkdir -p models

if [ ! -f models/face_detection_yunet.onnx ]; then
  echo "Downloading face detection model..."
  curl -sL -o models/face_detection_yunet.onnx \
    "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx"
fi

if [ ! -f models/face_recognition_sface.onnx ]; then
  echo "Downloading face recognition model..."
  curl -sL -o models/face_recognition_sface.onnx \
    "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx"
fi

echo "Verifying models load..."
.venv/bin/python -c "import face_utils; print('OK')"

echo "Setup complete. Java will auto-start this service from .venv/bin/python from now on."

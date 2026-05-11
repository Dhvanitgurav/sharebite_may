"""
Load ``freshness_fixed.h5``, preprocess a PIL image to 224×224, run inference,
and apply business rules (expiry hours + user-facing message).

Camera capture and file upload both arrive as image bytes / a file object — the same
preprocessing path is used (see ``predict_from_upload`` in ``main.py``).
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass
from typing import Any

import numpy as np
import tensorflow as tf
from PIL import Image
from tensorflow import keras

from model import IMG_SIZE

logger = logging.getLogger(__name__)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
# Primary artifact name for production; override with env FOOD_MODEL_PATH if needed.
DEFAULT_MODEL_PATH = os.path.join(BASE_DIR, "freshness_fixed.h5")
DEFAULT_CLASS_INDICES_PATH = os.path.join(BASE_DIR, "class_indices.json")

# Label names aligned with training folders (lowercase keys in class_indices).
FRESH_KEY = "fresh"
ROTTEN_KEY = "rotten"


@dataclass
class FreshnessResult:
    status: str  # "FRESH" | "ROTTEN"
    confidence: float
    estimated_expiry_hours: int
    message: str
    # Per-class softmax from Keras, keys lowercased to match ``class_indices.json`` folder names.
    class_probabilities: dict[str, float]
    predicted_class_index: int


class FreshnessPredictor:
    """Thread-safe enough for FastAPI: load once at startup."""

    def __init__(
        self,
        model_path: str | None = None,
        class_indices_path: str | None = None,
    ) -> None:
        self._model_path = model_path or os.environ.get("FOOD_MODEL_PATH", DEFAULT_MODEL_PATH)
        self._class_indices_path = class_indices_path or os.environ.get(
            "FOOD_CLASS_INDICES_PATH", DEFAULT_CLASS_INDICES_PATH
        )
        self._model: keras.Model | None = None
        self._index_to_label: dict[int, str] = {}

    def load(self) -> None:
        path = self._model_path
        if not os.path.isfile(path):
            raise FileNotFoundError(
                f"Model not found at {self._model_path}. "
                "Add freshness_fixed.h5 beside predict.py or run: python train.py"
            )
        self._model = keras.models.load_model(path)
        self._model_path = path
        self._index_to_label = self._read_class_indices()
        logger.info("Loaded model from %s; classes: %s", self._model_path, self._index_to_label)

    def _read_class_indices(self) -> dict[int, str]:
        if os.path.isfile(self._class_indices_path):
            with open(self._class_indices_path, encoding="utf-8") as f:
                name_to_idx: dict[str, int] = json.load(f)
            return {int(v): k for k, v in name_to_idx.items()}
        # Keras alphabetical default for directory flow: fresh=0, rotten=1
        logger.warning("class_indices.json missing; assuming fresh=0, rotten=1")
        return {0: FRESH_KEY, 1: ROTTEN_KEY}

    def preprocess_pil(self, image: Image.Image) -> np.ndarray:
        if image.mode != "RGB":
            image = image.convert("RGB")
        image = image.resize((IMG_SIZE, IMG_SIZE), Image.Resampling.LANCZOS)
        arr = np.asarray(image, dtype=np.float32) / 255.0
        return np.expand_dims(arr, axis=0)

    def predict_array(self, batch: np.ndarray) -> tuple[str, float, dict[str, float], int, np.ndarray]:
        if self._model is None:
            raise RuntimeError("Model not loaded; call load() at startup.")
        probs = self._model.predict(batch, verbose=0)[0]
        idx = int(np.argmax(probs))
        confidence = float(probs[idx])
        label = self._index_to_label.get(idx, FRESH_KEY if idx == 0 else ROTTEN_KEY)
        status = "FRESH" if label.lower() == FRESH_KEY else "ROTTEN"
        probs_by_label: dict[str, float] = {}
        for i in range(len(probs)):
            raw = self._index_to_label.get(i, f"class_{i}")
            probs_by_label[str(raw).lower()] = float(probs[i])
        return status, confidence, probs_by_label, idx, probs

    def _margin(self, probs: np.ndarray) -> float:
        if len(probs) < 2:
            return float(probs[0])
        top = sorted(float(p) for p in probs)
        return top[-1] - top[-2]

    def predict_with_rules(self, image: Image.Image) -> FreshnessResult:
        batch = self.preprocess_pil(image)
        status, confidence, probs_by_label, idx, probs_vec = self.predict_array(batch)
        margin = self._margin(probs_vec)

        if status == "ROTTEN":
            return FreshnessResult(
                status="ROTTEN",
                confidence=confidence,
                estimated_expiry_hours=0,
                message="Unsafe → send to composting",
                class_probabilities=probs_by_label,
                predicted_class_index=idx,
            )

        # Low argmax score or small margin between classes → do not imply strong "fresh" shelf life.
        if confidence < 0.6 or margin < 0.12:
            return FreshnessResult(
                status="FRESH",
                confidence=confidence,
                estimated_expiry_hours=0,
                message="Uncertain freshness — verify visually; model scores are ambiguous.",
                class_probabilities=probs_by_label,
                predicted_class_index=idx,
            )

        return FreshnessResult(
            status="FRESH",
            confidence=confidence,
            estimated_expiry_hours=4,
            message="Food is fresh and safe",
            class_probabilities=probs_by_label,
            predicted_class_index=idx,
        )

    def to_api_dict(self, result: FreshnessResult) -> dict[str, Any]:
        probs_rounded = {k: round(v, 4) for k, v in result.class_probabilities.items()}
        return {
            "status": result.status,
            "confidence": round(result.confidence, 4),
            "estimated_expiry_hours": result.estimated_expiry_hours,
            "message": result.message,
            "class_probabilities": probs_rounded,
            "predicted_class_index": result.predicted_class_index,
        }

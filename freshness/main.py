"""
================================================================================
END-TO-END FLOW (ShareBite + Food Freshness) — read this before integrating
================================================================================

1) **Image from the browser (two equivalent paths)**  
   - **Camera**: the React app captures a frame (e.g. ``<video>`` + canvas) and sends it
     as ``multipart/form-data`` with a file field — same as upload.  
   - **File upload**: user picks an image; frontend posts the file the same way.  
   Spring Boot does **not** need different Python endpoints for camera vs upload.

2) **Frontend → Spring Boot**  
   The hotel / donor UI posts the image (and donation metadata) to your Spring API
   (e.g. ``POST /api/donations`` with ``multipart/form-data``).

3) **Spring Boot → Python (this service)**  
   Spring receives the file, then forwards bytes to ``POST /predict`` here using
   ``multipart/form-data`` with part name ``file`` (see ``RUN.txt`` for a curl example).
   In Java, use ``RestTemplate`` or ``WebClient`` with ``MultipartBodyBuilder`` and
   ``MediaType.MULTIPART_FORM_DATA``.

4) **CNN inference**  
   ``predict.py`` loads ``freshness_fixed.h5`` (or legacy ``food_model.h5``), resizes to 224×224, scales to [0,1],
   runs softmax → **FRESH** vs **ROTTEN** + **confidence**.

5) **JSON back to Spring**  
   Response: ``status``, ``confidence``, ``estimated_expiry_hours``, ``message``.  
   Spring applies **donation vs composting** rules (e.g. ``ROTTEN`` or low confidence
   → composting pipeline; ``FRESH`` with high confidence → donation / NGO flow).

6) **Persistence**  
   Optionally store the prediction on ``Donation`` / audit tables in MySQL; the Python
   service is stateless and safe to scale behind a reverse proxy.

================================================================================
"""

from __future__ import annotations

import io
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from PIL import Image
from pydantic import BaseModel, Field

from predict import FreshnessPredictor

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

_predictor: FreshnessPredictor | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _predictor
    _predictor = FreshnessPredictor()
    try:
        _predictor.load()
        logger.info("Freshness model ready.")
    except FileNotFoundError as e:
        logger.error("Startup warning: %s — /predict will return 503 until model exists.", e)
    yield
    _predictor = None


app = FastAPI(
    title="Food Freshness API",
    description="CNN-based fresh vs rotten classification for ShareBite / Spring Boot.",
    version="1.0.0",
    lifespan=lifespan,
)

_origins = [
    o.strip()
    for o in os.environ.get(
        "CORS_ORIGINS",
        "http://localhost:3000,http://127.0.0.1:3000,http://localhost:8080,http://127.0.0.1:8080",
    ).split(",")
    if o.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class PredictResponse(BaseModel):
    status: str = Field(..., description="FRESH or ROTTEN (argmax label)")
    confidence: float = Field(..., ge=0.0, le=1.0)
    estimated_expiry_hours: int = Field(..., ge=0)
    message: str
    class_probabilities: dict[str, float] = Field(
        default_factory=dict,
        description="Softmax per class name (keys from class_indices.json, lowercased).",
    )
    predicted_class_index: int = Field(0, description="Argmax index after training class order.")


@app.get("/health")
def health():
    ok = _predictor is not None and getattr(_predictor, "_model", None) is not None
    return {"status": "ok" if ok else "degraded", "model_loaded": bool(ok)}


@app.post("/predict", response_model=PredictResponse)
async def predict(file: UploadFile = File(..., description="Image file (camera frame or upload)")):
    """
    Accepts any image part the client sends (camera capture or gallery upload).
    Spring should forward the same multipart field name: ``file``.
    """
    if _predictor is None or getattr(_predictor, "_model", None) is None:
        raise HTTPException(
            status_code=503,
            detail="Model not loaded. Add freshness_fixed.h5 beside predict.py or train with `python train.py`.",
        )
    if not file.content_type or not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="Expected an image/* content type.")

    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="Empty file.")

    try:
        image = Image.open(io.BytesIO(raw))
        image.load()
    except Exception as exc:  # noqa: BLE001 — user may send corrupt bytes
        raise HTTPException(status_code=400, detail=f"Invalid image: {exc}") from exc

    result = _predictor.predict_with_rules(image)
    return PredictResponse(**_predictor.to_api_dict(result))

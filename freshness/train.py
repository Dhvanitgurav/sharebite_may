"""
Train the CNN on ``dataset/train`` and ``dataset/validation`` using ImageDataGenerator,
then save ``food_model.h5`` and ``class_indices.json`` next to this file.

**Data should reflect human-edible foods** (fruits, vegetables, meals you donate).
Populate folders via ``python download_dataset.py`` (HF fruit fresh/rotten set) and/or
add your own images under ``fresh`` / ``rotten``.

Expected layout::

    dataset/
      train/
        fresh/
        rotten/
      validation/
        fresh/
        rotten/
"""

from __future__ import annotations

import json
import os

import tensorflow as tf
from tensorflow import keras
from tensorflow.keras.preprocessing.image import ImageDataGenerator

from model import IMG_SIZE, build_freshness_cnn

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATASET_DIR = os.path.join(BASE_DIR, "dataset")
TRAIN_DIR = os.path.join(DATASET_DIR, "train")
VAL_DIR = os.path.join(DATASET_DIR, "validation")
MODEL_OUT = os.path.join(BASE_DIR, "food_model.h5")
CLASS_INDICES_OUT = os.path.join(BASE_DIR, "class_indices.json")

BATCH_SIZE = 32
EPOCHS = 25


def main() -> None:
    if not os.path.isdir(TRAIN_DIR) or not os.path.isdir(VAL_DIR):
        raise FileNotFoundError(
            f"Missing dataset folders. Create:\n  {TRAIN_DIR}\n  {VAL_DIR}\n"
            "with subfolders fresh/ and rotten/ containing images."
        )

    train_datagen = ImageDataGenerator(
        rescale=1.0 / 255.0,
        rotation_range=15,
        width_shift_range=0.1,
        height_shift_range=0.1,
        horizontal_flip=True,
        zoom_range=0.1,
        fill_mode="nearest",
    )
    val_datagen = ImageDataGenerator(rescale=1.0 / 255.0)

    train_gen = train_datagen.flow_from_directory(
        TRAIN_DIR,
        target_size=(IMG_SIZE, IMG_SIZE),
        batch_size=BATCH_SIZE,
        class_mode="categorical",
        shuffle=True,
    )
    val_gen = val_datagen.flow_from_directory(
        VAL_DIR,
        target_size=(IMG_SIZE, IMG_SIZE),
        batch_size=BATCH_SIZE,
        class_mode="categorical",
        shuffle=False,
    )

    class_indices = {k: int(v) for k, v in train_gen.class_indices.items()}
    with open(CLASS_INDICES_OUT, "w", encoding="utf-8") as f:
        json.dump(class_indices, f, indent=2)

    model = build_freshness_cnn()
    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=1e-4),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )

    callbacks = [
        keras.callbacks.EarlyStopping(
            monitor="val_loss",
            patience=5,
            restore_best_weights=True,
        ),
        keras.callbacks.ModelCheckpoint(
            MODEL_OUT,
            monitor="val_accuracy",
            save_best_only=True,
            verbose=1,
        ),
    ]

    model.fit(
        train_gen,
        validation_data=val_gen,
        epochs=EPOCHS,
        callbacks=callbacks,
        verbose=1,
    )

    # ModelCheckpoint may skip save if metric never improves; always persist final weights.
    if not os.path.isfile(MODEL_OUT):
        model.save(MODEL_OUT)

    print(f"Saved model: {MODEL_OUT}")
    print(f"Saved class map: {CLASS_INDICES_OUT}")
    print("Class indices:", class_indices)


if __name__ == "__main__":
    tf.keras.utils.set_random_seed(42)
    main()

"""
CNN architecture for binary food freshness (fresh vs rotten).

Input: 224x224 RGB images, normalized to [0, 1] in the training / prediction pipeline.
Output: 2-way softmax — class order must match ``class_indices`` saved at training time
(typically fresh=0, rotten=1 when using folder names ``fresh`` and ``rotten``).
"""

from __future__ import annotations

import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers


IMG_SIZE = 224
NUM_CLASSES = 2


def build_freshness_cnn(input_shape: tuple[int, int, int] = (IMG_SIZE, IMG_SIZE, 3)) -> keras.Model:
    """
    Stack requested layer types: Conv2D → MaxPooling → … with ReLU and Dropout,
    ending in Dense(2, softmax) for Fresh vs Rotten.

    Optimizer / loss are applied in ``train.py`` (Adam, categorical_crossentropy).
    """
    inputs = keras.Input(shape=input_shape, name="image_input")
    x = inputs

    x = layers.Conv2D(32, (3, 3), activation="relu", padding="same", name="conv2d_1")(x)
    x = layers.MaxPooling2D((2, 2), name="pool_1")(x)

    x = layers.Conv2D(64, (3, 3), activation="relu", padding="same", name="conv2d_2")(x)
    x = layers.MaxPooling2D((2, 2), name="pool_2")(x)

    x = layers.Conv2D(128, (3, 3), activation="relu", padding="same", name="conv2d_3")(x)
    x = layers.MaxPooling2D((2, 2), name="pool_3")(x)

    x = layers.Dropout(0.25, name="dropout_conv")(x)
    x = layers.Flatten(name="flatten")(x)

    x = layers.Dense(128, activation="relu", name="dense_hidden")(x)
    x = layers.Dropout(0.5, name="dropout_dense")(x)
    outputs = layers.Dense(NUM_CLASSES, activation="softmax", name="freshness_softmax")(x)

    return keras.Model(inputs=inputs, outputs=outputs, name="food_freshness_cnn")

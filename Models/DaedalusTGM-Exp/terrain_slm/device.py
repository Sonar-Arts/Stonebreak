"""Default CUDA device for training, data builds and evaluation.

Two or more GPUs: cuda:1, keeping cuda:0 free for the game's rendering (the development box).
One GPU: cuda:0. Every script's --device flag still overrides this.
"""
import torch


def default_device() -> str:
    return "cuda:1" if torch.cuda.device_count() > 1 else "cuda:0"

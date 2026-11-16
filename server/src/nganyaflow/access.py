"""Access codes for enumerators and crew. Riders never need one."""

import hashlib
import re
import secrets

ROLES = ("enumerator", "crew")
# No 0/O/1/I/L so a code read aloud or from a screen is hard to mistype.
ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"


def generate_code():
    raw = "".join(secrets.choice(ALPHABET) for _ in range(8))
    return f"NF-{raw[:4]}-{raw[4:]}"


def normalize(code):
    return re.sub(r"[^A-Z0-9]", "", code.upper())


def hash_code(code):
    return hashlib.sha256(("nganyaflow-code:" + normalize(code)).encode()).hexdigest()


def hash_token(token):
    return hashlib.sha256(("nganyaflow-token:" + token).encode()).hexdigest()


def new_token():
    return secrets.token_urlsafe(32)

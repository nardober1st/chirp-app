package com.bernardooechsler.chirp.domain.exception

class SamePasswordException: RuntimeException(
    "The new password can't be equal to the old one."
)
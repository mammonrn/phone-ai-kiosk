"""Kiosk broker — the only thing the kiosk phone is allowed to talk to.

Phase 2: it takes Thai text over HTTPS, asks Claude Haiku, and hands back a
short spoken-style answer. It has no tools, touches no other service, and
returns `action: null` on every single response.
"""

__version__ = "0.1.0"

# Mihon source adapter

Owns plugin catalog/detail/chapter/page operations, per-request filter instances and per-source/book update serialization. Public results use feature-api. The runtime discovers and loads extensions; this module contains no NG database or UI code.

Image references retain page URLs and image URLs across process recreation. Images are fetched through the extension's own client/getImage implementation, with caller-owned streaming responses and cancellation.

Tests use deterministic in-memory sources. Compatibility with arbitrary installed extension APK versions and actual sites is not established by these tests.

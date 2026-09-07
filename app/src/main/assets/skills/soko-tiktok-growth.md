# Soko TikTok Growth

Use the governed `TIKTOK_POST_PUBLISH` work item. Never publish directly from a worker or generic tap plan.

1. Read active Soko products and require a usable product image.
2. Exclude product titles attempted during the last 30 days. If the catalog is exhausted, avoid the newest third and randomize the older pool.
3. Prefer a model-written factual caption; use the bounded Soko caption fallback when the model is unavailable.
4. Download and normalize the source image to JPEG, share it to TikTok, and choose Photo—not Message.
5. Require the exact caption to be visibly present in TikTok's editor before tapping Post. If it is absent, stop without publishing.
6. Execute through the side-effect transaction and target-bound verifier. Never retry an uncertain publication automatically.
7. Record the attempted product even when verification fails so it is not selected again immediately.

Learning is evidence-driven: record successes and failures, propose selector changes after repeated UI mismatches, and retain the owner-controlled publishing policy and caps.

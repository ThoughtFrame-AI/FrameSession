# FrameSession Caching – Prior Art Disclosure and Reference Implementation

This repository documents and demonstrates **FrameSession Caching**, a method for persisting and reusing transformer inference state to enable efficient, deterministic semantic retrieval over large corpora.

It serves as both a **prior art disclosure** and a **reference implementation**, covering:

- Persistent serialization of key/value attention state and RNG metadata (".framesession" files)
- Hierarchical multi-pass query orchestration (HDM reasoning)
- Benchmark results across quantized transformer models
- Integration with a content management pipeline (ThoughtFrame.AI / eMediaDB)
- Experimental logs and corpus samples

---

## 🔍 Overview

**FrameSession Caching** enables transformer models to:
- Ingest documents once and reuse latent state across future queries
- Resume generation deterministically without re-prefill
- Perform hierarchical drill-down and coarse-to-fine relevance scoring
- Support low-latency semantic access to large, slow-changing corpora

Example domains:
- Legal documents, standards, textbooks
- DAM systems, enterprise knowledge bases
- Education and compliance workflows

---

## 📄 Included

- `framesession-disclosure.md` – Full technical disclosure (Markdown source)
- `framesession-disclosure.pdf` – Exported PDF version
- `framesession-disclosure.sha256` – SHA-256 hash for verification
- `src/` – Reference code modules (Java)
- `logs/` – Semantic relevance test logs, model response timing
- `corpus/physics_reference_chunk.pdf` – Sample document used in evaluation

---

## 📜 License and Use

This work is released to the public domain as prior art. You are free to use, modify, and build on this work **with attribution**.

Please reference the original as:
> Ian Miller, “FrameSession Caching for Efficient Semantic Retrieval with Quantized Transformers,” ThoughtFrame.AI, October 2025

No patents are being pursued. This is a contribution to the open AI community to prevent enclosure of foundational techniques in persistent transformer orchestration.

---

## 📫 Contact

**Ian Miller, B.Eng (Computer), P.Eng, M.Sc (Technology Management)**  
Founder & Principal Architect  
**ThoughtFrame.AI – The Workflow Engine for Adaptive Intelligence**  
📧 ian@thoughtframe.ai  
🌐 [https://thoughtframe.ai](https://thoughtframe.ai)


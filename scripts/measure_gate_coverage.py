#!/usr/bin/env python3
"""
게이트를 붙이기 전에: 청크 단위 결정 modality 커버리지를 실측한다.

물어보는 것 — 청크를 modality로 걸러내면 진짜 결정이 있는 청크까지 버리게 되는가?
버리면 게이트는 하드 필터로 쓸 수 없고, 신호(프롬프트 힌트)나 사후 검증으로만 써야 한다.

사용: python measure_gate_coverage.py --corpus corpus/val_only.jsonl --chunk-chars 1000
"""
import argparse
import json

import korean_syntax as ks
from exaone_extract_chunked import chunk_paragraphs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--chunk-chars", type=int, default=1000)
    args = ap.parse_args()

    rows = [json.loads(l) for l in open(args.corpus)]

    total_chunks = core_hit = ext_hit = 0
    docs_all_dropped_core = docs_all_dropped_ext = 0
    docs_with_decisions = 0
    marker_counts = {}

    for row in rows:
        chunks = chunk_paragraphs(row["input"], args.chunk_chars)
        total_chunks += len(chunks)
        doc_core = doc_ext = 0
        for chunk in chunks:
            hit_core = hit_ext = False
            for sentence in ks.sentences(chunk):
                m = ks.modality(sentence, extended=True)
                if m is None:
                    continue
                marker_counts[m] = marker_counts.get(m, 0) + 1
                hit_ext = True
                if m in ks.CORE:
                    hit_core = True
            core_hit += hit_core
            ext_hit += hit_ext
            doc_core += hit_core
            doc_ext += hit_ext

        # 교사가 결정을 찾은 문서인데 게이트가 청크를 전부 버리면 = 치명적 손실
        if row["target"]["decisionPoints"]:
            docs_with_decisions += 1
            if doc_core == 0:
                docs_all_dropped_core += 1
            if doc_ext == 0:
                docs_all_dropped_ext += 1

    print(f"청크 총 {total_chunks}개 (문서 {len(rows)}, {args.chunk_chars}자 기준)")
    print(f"  핵심3표지 통과: {core_hit} ({core_hit/total_chunks:.0%})")
    print(f"  확장표지 통과: {ext_hit} ({ext_hit/total_chunks:.0%})")
    print()
    print(f"교사가 결정을 찾은 문서 {docs_with_decisions}개 중, 게이트가 전 청크를 버리는 문서:")
    print(f"  핵심3표지: {docs_all_dropped_core}개")
    print(f"  확장표지: {docs_all_dropped_ext}개")
    print()
    print("표지별 문장 수:", dict(sorted(marker_counts.items(), key=lambda x: -x[1])))


if __name__ == "__main__":
    main()

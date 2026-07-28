#!/usr/bin/env python3
"""
슬랙 스레드를 로컬 추출용 코퍼스로 내보낸다 — Java 추출 잡과 같은 묶음 규칙으로.

왜 Java를 안 쓰나: 제품 경로는 Gemini를 호출한다. 백필로 스레드가 25개→187개로 늘어난
지금 그대로 돌리면 API 비용이 그만큼 든다. 같은 입력을 만들어 GPU의 로컬 학생 모델로
추출하면 비용 0이다.

묶음 규칙은 DecisionExtractionJob과 일치시킨다(SlackArchiveMessageRepository.findQuietThreads):
  channel_id + thread_ts 로 묶고, 메시지 수 >= min_messages 인 스레드만.
프롬프트의 스레드 표기도 JsonDecisionExtractPort.userPrompt와 같은 형식이어야 한다 —
학생 모델이 그 분포로 학습됐기 때문이다.

--only-new: 이미 추출된 스레드(slack_decision_log)는 건너뛴다.

사용: venv/bin/python export_slack_threads.py --out slack_corpus.jsonl --only-new
"""
import argparse
import json
import os

import psycopg2

DSN = dict(host="localhost", dbname="mydoc", user="mydoc", password=os.environ["MYDOC_DB_PASSWORD"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--only-new", action="store_true")
    args = ap.parse_args()

    conn = psycopg2.connect(**DSN)
    cur = conn.cursor()
    cur.execute("SELECT min_messages FROM knowledge_setting LIMIT 1")
    row = cur.fetchone()
    min_messages = row[0] if row else 2

    cur.execute("""
        SELECT channel_id, thread_ts
        FROM slack_archive_message
        GROUP BY channel_id, thread_ts
        HAVING count(*) >= %s
        ORDER BY channel_id, thread_ts
    """, (min_messages,))
    threads = cur.fetchall()

    done = set()
    if args.only_new:
        cur.execute("SELECT channel_id, thread_ts FROM slack_decision_log")
        done = set(cur.fetchall())

    written = skipped = 0
    with open(args.out, "w") as out:
        for channel_id, thread_ts in threads:
            if (channel_id, thread_ts) in done:
                skipped += 1
                continue
            cur.execute("""
                SELECT user_id, text, ts
                FROM slack_archive_message
                WHERE channel_id = %s AND thread_ts = %s
                ORDER BY ts
            """, (channel_id, thread_ts))
            msgs = cur.fetchall()
            # Java userPrompt와 같은 표기 — "이름: 본문" 한 줄씩.
            lines = []
            for uid, text, ts in msgs:
                speaker = uid or "알 수 없음"
                lines.append(f"{speaker}: {(text or '').strip()}")
            thread_text = "\n".join(lines)
            if len(thread_text) < 40:
                skipped += 1
                continue
            out.write(json.dumps({
                "channel_id": channel_id,
                "thread_ts": thread_ts,
                "message_count": len(msgs),
                "input": thread_text[:24000],
            }, ensure_ascii=False) + "\n")
            written += 1
    conn.close()
    print(f"[export] 대상 스레드 {len(threads)} · 내보냄 {written} · 건너뜀 {skipped}"
          + (f" (기추출 {len(done)})" if args.only_new else ""))


if __name__ == "__main__":
    main()

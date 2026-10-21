#!/usr/bin/env python3
"""PostToolUse(Skill) 훅: /gf-run 단계 결과를 기록하고, QA가 끝나면 요약 보고서를 만든다.

- gf-run-implement / gf-run-review / gf-run-qa 의 반환 블록을 .gf-run/<브랜치>.jsonl 에 쌓는다.
- gf-run-qa 가 끝나면 이번 실행(마지막 implement 이후, 없으면 전체)의 단계 결과, 커밋, 변경 파일,
  명세의 리뷰·QA 기록, 확인이 필요한 이슈를 모아 .gf-run/reports/<브랜치>-<시각>.md 를 만든다.
- 어떤 오류가 나도 파이프라인을 막지 않는다(항상 exit 0). 오류는 .gf-run/hook-error.log 에 남긴다.
- .gf-run/ 은 .gitignore 대상이다(로컬 보고용).
"""
import datetime
import glob
import json
import os
import re
import subprocess
import sys
import traceback

STAGES = ("implement", "review", "qa")
FIELDS = ("STAGE", "STATUS", "HEAD", "SUMMARY", "TESTS", "NEXT")


def out(obj=None):
    print(json.dumps(obj or {}, ensure_ascii=False))
    sys.exit(0)


def git(root, *args):
    try:
        return subprocess.run(["git", "-C", root, *args], capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:
        return ""


def parse_block(text):
    """반환 블록에서 단일 필드와 목록 필드(DECISIONS_NEEDED, CONCERNS)를 뽑는다."""
    fields, lists, current = {}, {"DECISIONS_NEEDED": [], "CONCERNS": []}, None
    for line in (text or "").splitlines():
        s = line.strip().strip("`")
        m = re.match(r"^([A-Z_]+):\s*(.*)$", s)
        if m and (m.group(1) in FIELDS or m.group(1) in lists):
            key, val = m.group(1), m.group(2).strip()
            current = key if key in lists else None
            if key in FIELDS:
                fields[key] = val
            elif val and val not in ("없음", "- 없음"):
                lists[key].append(val.lstrip("- ").strip())
        elif current and s.startswith("-"):
            item = s.lstrip("- ").strip()
            if item and item != "없음":
                lists[current].append(item)
    return fields, lists


def spec_section(spec_path, title, n=3):
    if not spec_path or not os.path.exists(spec_path):
        return []
    text = open(spec_path, encoding="utf-8").read()
    m = re.search(r"^## " + re.escape(title) + r".*?$(.*?)(?=^## |\Z)", text, re.S | re.M)
    if not m:
        return []
    lines = [l.strip() for l in m.group(1).splitlines()
             if l.strip() and not l.strip().startswith("<!--")]
    return lines[-n:]


def main():
    payload = json.load(sys.stdin)
    if payload.get("tool_name") != "Skill":
        out()
    skill = (payload.get("tool_input") or {}).get("skill", "")
    if not skill.startswith("gf-run-"):
        out()
    stage = skill[len("gf-run-"):]
    if stage not in STAGES:
        out()

    resp = payload.get("tool_response")
    result = resp.get("result", "") if isinstance(resp, dict) else str(resp or "")

    cwd = payload.get("cwd") or os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    root = git(cwd, "rev-parse", "--show-toplevel") or cwd
    branch = git(root, "rev-parse", "--abbrev-ref", "HEAD") or "unknown"
    safe = re.sub(r"[^A-Za-z0-9._-]", "-", branch)
    run_dir = os.path.join(root, ".gf-run")
    os.makedirs(os.path.join(run_dir, "reports"), exist_ok=True)
    log_path = os.path.join(run_dir, safe + ".jsonl")

    now = datetime.datetime.now()
    with open(log_path, "a", encoding="utf-8") as f:
        f.write(json.dumps({"ts": now.isoformat(timespec="seconds"), "stage": stage,
                            "head": git(root, "rev-parse", "--short", "HEAD"), "result": result},
                           ensure_ascii=False) + "\n")

    if stage != "qa":
        out()

    # ---- QA 완료: 보고서 생성 ----
    entries = [json.loads(l) for l in open(log_path, encoding="utf-8") if l.strip()]
    start = max((i for i, e in enumerate(entries) if e["stage"] == "implement"), default=0)
    run = entries[start:]

    spec_path = None
    m = re.match(r"^(?:feat|fix)/(ml-\d+|\d+[a-z]?)-", branch)
    if m:
        found = sorted(glob.glob(os.path.join(root, "docs", "specs", m.group(1) + "-*.md")))
        spec_path = found[0] if found else None
    spec_rel = os.path.relpath(spec_path, root) if spec_path else "(브랜치 이름으로 명세를 찾지 못함)"

    base = "origin/main" if git(root, "rev-parse", "--verify", "-q", "origin/main") else "main"
    commits = git(root, "log", "--oneline", f"{base}..HEAD")
    diffstat = git(root, "diff", "--stat", f"{base}...HEAD")
    dirty = git(root, "status", "--short")

    rows, issues, raw = [], [], []
    for e in run:
        fields, lists = parse_block(e["result"])
        status = fields.get("STATUS", "형식 없음")
        rows.append(f"| {e['stage']} | {status} | {fields.get('HEAD') or e.get('head', '')} | {fields.get('SUMMARY', '')} | {fields.get('TESTS', '')} |")
        if not status.startswith("DONE"):
            issues.append(f"[{e['stage']}] 상태 {status}")
        issues += [f"[{e['stage']}] 결정 필요: {d}" for d in lists["DECISIONS_NEEDED"]]
        issues += [f"[{e['stage']}] 우려: {c}" for c in lists["CONCERNS"]]
        raw.append(f"<details><summary>{e['stage']} · {e['ts']}</summary>\n\n```\n{e['result'].strip()}\n```\n</details>")
    qa_issue_ids = sorted(set(re.findall(r"ISSUE-\d+", run[-1]["result"] if run else "")))

    stamp = now.strftime("%Y%m%d-%H%M")
    report_rel = os.path.join(".gf-run", "reports", f"{safe}-{stamp}.md")
    nl = "\n"
    report = f"""# gf-run 보고서: {branch}

- 생성: {now.strftime('%Y-%m-%d %H:%M')}
- 명세: `{spec_rel}`
- HEAD: `{git(root, 'rev-parse', '--short', 'HEAD')}` (비교 기준 `{base}`)

## 단계 결과
| 단계 | 상태 | HEAD | 요약 | 테스트 |
|---|---|---|---|---|
{nl.join(rows)}

## 확인이 필요한 이슈 ({len(issues)}건)
{nl.join('- ' + i for i in issues) if issues else '- 단계 보고에 남은 결정, 우려 없음'}

QA에서 다룬 이슈 ID: {', '.join(qa_issue_ids) if qa_issue_ids else '없음'} (처리 결과는 아래 QA 기록 참고)

## 실행한 작업
### 커밋 ({len(commits.splitlines()) if commits else 0}개)
```
{commits or '(없음)'}
```
### 변경 파일
```
{diffstat or '(없음)'}
```
### 커밋되지 않은 변경
```
{dirty or '(없음)'}
```

## 명세에 남은 기록
**리뷰 기록**
{nl.join('- ' + l for l in spec_section(spec_path, '리뷰 기록')) or '- (없음)'}

**QA 기록**
{nl.join('- ' + l for l in spec_section(spec_path, 'QA 기록')) or '- (없음)'}

## 다음 할 일
1. 위 이슈를 확인한다. 결정이 필요한 항목이 있으면 정한 뒤 `/gf-run <NN> --from <단계>`로 재개한다.
2. 문제가 없으면 `/gf-ship`으로 PR을 만든다.
3. 팀원 승인 후 Rebase and merge.

## 단계별 원본 결과
{nl.join(raw)}
"""
    with open(os.path.join(root, report_rel), "w", encoding="utf-8") as f:
        f.write(report)

    issue_count = len(issues)
    msg = f"gf-run QA 완료: 요약 보고서 {report_rel} (확인 필요 이슈 {issue_count}건)"
    try:
        subprocess.run(["osascript", "-e", f'display notification "{msg}" with title "graffiti gf-run"'],
                       capture_output=True, timeout=5)
    except Exception:
        pass
    out({"systemMessage": msg,
         "hookSpecificOutput": {"hookEventName": "PostToolUse",
                                "additionalContext": f"요약 보고서가 {report_rel}에 생성됨. 사용자에게 경로를 알려줄 것. 보고서를 다시 쓰지 말 것."}})


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        try:
            root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
            os.makedirs(os.path.join(root, ".gf-run"), exist_ok=True)
            with open(os.path.join(root, ".gf-run", "hook-error.log"), "a", encoding="utf-8") as f:
                f.write(datetime.datetime.now().isoformat() + "\n" + traceback.format_exc() + "\n")
        except Exception:
            pass
        out()

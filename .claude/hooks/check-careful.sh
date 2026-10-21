#!/usr/bin/env bash
# check-careful.sh — PreToolUse(Bash) 훅. gstack careful/bin/check-careful.sh를 축약.
# 위험한 명령을 감지하면 실행 전에 사용자 확인("ask")을 요청한다.
# 문자열 매칭 기반의 안전망일 뿐 보안 경계가 아니다.
# 결정은 반드시 hookSpecificOutput 아래에 넣어야 Claude Code가 인식한다.
set -uo pipefail

INPUT=$(cat)

ask() {
  # $1: 사유 (JSON 문자열 안전하게 큰따옴표와 역슬래시 제거)
  local reason=${1//\\/}
  reason=${reason//\"/\'}
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"ask","permissionDecisionReason":"[careful] %s"}}\n' "$reason"
  exit 0
}

# tool_input.command 추출: jq → python3 순서. 둘 다 없거나 파싱 실패하면 fail-closed(ask).
CMD=""
if command -v jq >/dev/null 2>&1; then
  CMD=$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty' 2>/dev/null) || ask "훅 입력을 해석하지 못했습니다. 명령을 직접 확인하고 승인하세요."
elif command -v python3 >/dev/null 2>&1; then
  CMD=$(printf '%s' "$INPUT" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("command",""))' 2>/dev/null) || ask "훅 입력을 해석하지 못했습니다. 명령을 직접 확인하고 승인하세요."
else
  ask "jq나 python3가 없어 명령을 검사할 수 없습니다. 명령을 직접 확인하고 승인하세요."
fi

[ -z "$CMD" ] && { echo '{}'; exit 0; }

LOWER=$(printf '%s' "$CMD" | tr '[:upper:]' '[:lower:]')

match() { grep -qE -e "$1" <<< "$LOWER"; }

# 난독화: ${IFS} 단어 분리, base64 → 셸
match '\$\{?ifs\}?|base64[[:space:]]+(-d|--decode).*\|[[:space:]]*(ba)?sh' \
  && ask "셸 난독화 패턴이 있습니다(IFS 또는 base64→셸). 명령을 꼼꼼히 확인하세요."

# 재귀 삭제
match '(^|[;&|[:space:]])(sudo[[:space:]]+)?rm[[:space:]]+([^;&|]*[[:space:]])?-[a-z]*r' \
  && ask "재귀 삭제(rm -r)입니다. 대상 경로를 확인하세요."

# SQL 파괴 명령
match '(drop[[:space:]]+(table|database|schema)|truncate[[:space:]]+(table[[:space:]]+)?[a-z_`])' \
  && ask "DROP/TRUNCATE SQL입니다. 운영 DB가 아닌지, 백업이 있는지 확인하세요 (INV-07)."
if match 'delete[[:space:]]+from' && ! match 'where'; then
  ask "WHERE 없는 DELETE입니다. 전체 행이 지워집니다 (INV-07)."
fi

# git 되돌릴 수 없는 작업
match 'git[[:space:]]+push([^;&|]*)[[:space:]](-f|--force|--force-with-lease)([[:space:]=]|$)' \
  && ask "force push는 금지입니다 (CLAUDE.md 커밋 규칙). 정말 필요한지 확인하세요."
match 'git[[:space:]]+push[^;&|]*[[:space:]](origin[[:space:]]+)?main([[:space:]]|$)' \
  && ask "main 브랜치로 직접 push하려 합니다. PR로 진행해야 합니다."
match 'git[[:space:]]+reset[[:space:]]+[^;&|]*--hard' \
  && ask "git reset --hard는 커밋되지 않은 변경을 지웁니다."
match 'git[[:space:]]+clean[[:space:]]+[^;&|]*-[a-z]*f' \
  && ask "git clean -f는 추적되지 않은 파일을 지웁니다."
match 'git[[:space:]]+(checkout|restore)[[:space:]]+[^;&|]*(--[[:space:]]+)?\.([[:space:]]|$)' \
  && ask "작업 트리 전체 변경을 되돌리는 명령입니다."
grep -qE 'git[[:space:]]+branch[[:space:]]+([^;&|]*[[:space:]])?(-D|--delete[[:space:]]+--force)([[:space:]]|$)' <<< "$CMD" \
  && ask "브랜치 강제 삭제(git branch -D)입니다."
match 'git[[:space:]]+add[[:space:]]+[^;&|]*\.(pt|onnx|safetensors|ckpt|pth)([[:space:]]|$)' \
  && ask "모델 가중치 파일을 git에 추가하려 합니다. 가중치는 S3와 ml/models.lock으로 관리합니다 (SEC-17)."
match 'git[[:space:]]+add[[:space:]]+(-a|--all|\.)([[:space:]]|$)' \
  && ask "git add -A / git add . 는 금지입니다. 파일을 지정해서 stage하세요 (빌드 산출물, .env 혼입 방지)."
match '--no-verify' \
  && ask "--no-verify는 훅과 검사를 건너뜁니다 (Gate Integrity RH-14). 사람 승인이 필요합니다."

# Docker 데이터 삭제
match 'docker[[:space:]]+(compose[[:space:]]+)?[^;&|]*down[^;&|]*(-v|--volumes)' \
  && ask "볼륨까지 삭제합니다. MySQL 데이터가 사라집니다."
match 'docker[[:space:]]+(volume[[:space:]]+(rm|prune)|system[[:space:]]+prune)' \
  && ask "Docker 볼륨이나 데이터를 삭제하는 명령입니다."

echo '{}'
exit 0

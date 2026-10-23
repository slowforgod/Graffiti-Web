# ChatKHU API

ChatKHU는 OpenAI, Anthropic, Google Gemini, xAI, Perplexity 등의 모델을 **API 키 하나와 Base URL 하나**로 쓰는 LLM 게이트웨이입니다(mindlogic FactChat 기반). 이 프로젝트에서는 텍스트 검사(D-19, 07-moderation)에 씁니다. 기존 OpenAI SDK나 Anthropic SDK를 그대로 쓰고 Base URL만 바꾸면 됩니다.

키는 환경변수 `CHATKHU_API_KEY`로만 받습니다(SEC-06). 이 문서와 코드에 키 값을 쓰지 않습니다.

## Base URL

| API 형식 | Base URL |
|---|---|
| OpenAI 호환 (Chat Completions) | `https://factchat-cloud.mindlogic.ai/v1/gateway` |
| Anthropic 네이티브 (Messages API) | `https://factchat-cloud.mindlogic.ai/v1/gateway/claude` |
| OpenAI Responses API | `https://factchat-cloud.mindlogic.ai/v1/gateway` |
| Google Gemini 네이티브 | `https://factchat-cloud.mindlogic.ai/v1/gateway/gemini` |

## 인증

모든 엔드포인트에 API 키가 필요합니다. 두 헤더 방식을 모두 지원하며, 백엔드는 OpenAI 방식(`Authorization: Bearer`)을 씁니다.

```http
Authorization: Bearer <CHATKHU_API_KEY>    # OpenAI SDK 기본
x-api-key: <CHATKHU_API_KEY>               # Anthropic SDK 기본
```

키는 ChatKHU 웹사이트 왼쪽 아래 **API Gateway** → **API 키 생성**에서 발급합니다. 발급한 키는 다시 조회할 수 없으므로 바로 안전한 곳에 보관합니다. 호출 크레딧은 **키를 만든 계정**에서 차감됩니다.

## 보안

- 키는 환경변수 `CHATKHU_API_KEY`에만 두고 소스 코드나 문서에 넣지 않습니다(SEC-06).
- HTTPS 주소만 씁니다. HTTP는 지원되지 않습니다.
- 키가 노출되면 바로 API Gateway 페이지에서 해지하고 새로 발급한 뒤, 로컬 `.env`, GitHub Secrets, 서버 `/opt/graffiti/.env`를 함께 바꿉니다. API로 해지하려면 `POST /v1/gateway/keys/revoke/`를 씁니다.
- 키는 조직 단위로 범위가 정해져 다른 조직의 모델에는 접근할 수 없습니다.

## 전체 엔드포인트

경로 앞에는 `https://factchat-cloud.mindlogic.ai`가 붙습니다.

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/gateway/models/` | 모델 목록 (`?type=`으로 종류별) |
| GET | `/v1/gateway/models/{model}/` | 모델 하나 상세 |
| POST | `/v1/gateway/chat/completions/` | OpenAI 호환 채팅 |
| POST | `/v1/gateway/responses/` | OpenAI Responses API |
| POST | `/v1/gateway/claude/v1/messages/` | Anthropic Messages API |
| POST | `/v1/gateway/claude/v1/messages/count_tokens/` | Anthropic 토큰 수 계산 |
| GET | `/v1/gateway/claude/v1/models/` | Anthropic 형식 모델 목록 |
| GET · POST | `/v1/gateway/gemini/v1beta/models/…` | Gemini 네이티브 (목록 · generateContent · 스트리밍) |
| POST | `/v1/gateway/embeddings/` | 임베딩 |
| POST | `/v1/gateway/rerank/` | 검색 결과 재정렬 |
| POST | `/v1/gateway/exa/search/` · `…/contents/` · `…/answer/` | 웹 검색 (Exa) |
| POST | `/v1/gateway/guard/` | 개인정보 탐지·마스킹 |
| POST | `/v1/gateway/decisions/` | 판정 모델 (Jev · OpenAI) |
| GET · POST | `/v1/gateway/mcp/` (GET) · `/v1/gateway/mcp/{suite}/` (POST) | MCP 도구 묶음 |
| POST | `/v1/gateway/audio/speech/` | 음성 합성 (TTS) |
| POST · GET | `/v1/gateway/audio/transcriptions/` · `…/{operation_id}/` | 받아쓰기 (STT) 시작 · 상태 |
| POST | `/v1/gateway/audio/music/` | 음악·효과음 생성 |
| POST · GET | `/v1/gateway/images/generate/` · `…/{operation_id}/` | 이미지 생성 · 지연된 작업 상태 |
| POST · GET | `/v1/gateway/video/generation/` · `…/{operation_id}/` · `…/download/` | 비디오 생성 · 상태 · 다운로드 |
| POST · WS | `/v1/gateway/realtime/sessions/` · 실시간 소켓 | 실시간 음성 대화·받아쓰기 |
| POST | `/v1/gateway/chatbots/{chatbot_id}/chat/completions/` | 스튜디오 챗봇 호출 |
| POST | `/v1/gateway/estimate/` | 호출 전 크레딧 견적 |
| GET | `/v1/gateway/credits/` | 크레딧 잔액 |
| GET | `/v1/gateway/usage/` | 사용량 요약 |
| POST | `/v1/gateway/keys/revoke/` | 지금 쓰는 키 해지 |

게이트웨이는 위 경로만 지원합니다. 제공업체의 네이티브 리소스 API(예: Gemini File Search, 파일 스토어, 벡터 검색)는 게이트웨이로 호출되지 않습니다.

## 호출 제한

| 제한 | 값 | 초과 시 |
|---|---|---|
| 속도 제한 | 회원당 60초에 120건 | `429` |
| 챗봇 채팅 라우트 | 분당 30건 | `429` |
| 작업 상태 폴링 (비디오·이미지·받아쓰기) | 회원당 60초에 300건 (속도 제한과 별도) | `429` |
| 크레딧 예약 공용 한도 | 회원당 60초에 크레딧 예약 300건 (크레딧을 예약하는 모든 호출 합산) | `429`, `Retry-After` 없음 |
| 요청 본문 크기 | 25MB | `413` |
| TTS `input` 길이 | 4,000자 | `400` |

크레딧은 월 단위로 배정되고 매월 갱신됩니다. 잔액과 갱신일은 `GET /v1/gateway/credits/`로 확인합니다.

검사 요청이 `429`나 시간 초과로 실패하면 D-18대로 그 단계는 `UNCHECKED`로 처리합니다. 속도 제한은 회원(키) 단위이므로 게시가 몰리면 걸릴 수 있습니다. 07 명세에서 재시도 여부와 타임아웃을 정합니다.

## 예시 (OpenAI 호환)

```python
import os

from openai import OpenAI

client = OpenAI(
    api_key=os.environ["CHATKHU_API_KEY"],
    base_url="https://factchat-cloud.mindlogic.ai/v1/gateway",
)

response = client.chat.completions.create(
    model="gpt-5.6-luna",
    messages=[{"role": "user", "content": "Hello!"}],
)
print(response.choices[0].message.content)
```

백엔드(Java)에서는 같은 형식으로 `POST /v1/gateway/chat/completions/`에 JSON을 보냅니다. 사용자 텍스트는 구분자로 감싸고, 응답은 스키마로 엄격히 파싱합니다(SEC-05).

## 모델

이 프로젝트는 ChatKHU에서 **OpenAI 모델만** 씁니다. 쓸 모델은 07 명세에서 정합니다.

### 모델 목록 조회

```http
GET https://factchat-cloud.mindlogic.ai/v1/gateway/models/?type=llm
Authorization: Bearer <CHATKHU_API_KEY>
```

- `?type=llm`이면 채팅 모델만 옵니다. 생략하면 오디오, 이미지, 비디오 모델도 함께 옵니다.
- 응답은 `{"object": "list", "data": [...]}`이고, 각 항목의 `id`를 요청의 `model`에 그대로 넣습니다. OpenAI 모델은 `owned_by`가 `openai`입니다.
- 모델 하나는 `GET /v1/gateway/models/{model}/`로 조회합니다.
- 조직에 활성화된 모델만 목록에 나오고, 구독 플랜에 따라 달라집니다.

### 엔드포인트

OpenAI 채팅 모델은 `POST /v1/gateway/chat/completions/`로 호출합니다. o-시리즈 추론 모델과 Codex는 `POST /v1/gateway/responses/`를 씁니다.

### API로 호출할 수 없는 모델

무료 모델(`gpt-5-nano` 등)과 앱 전용 기능(`mindlogic-super-agent`)은 게이트웨이 API로 호출하면 `403`이 옵니다. API에서는 크레딧을 쓰는 일반 모델(예: `gpt-5.6-luna`, `gpt-5.6-terra`)을 씁니다.

## 공식 제공업체 문서

요청·응답 형식은 각 제공업체의 문서를 따릅니다.

| API | 공식 문서 |
|---|---|
| OpenAI Chat Completions | <https://platform.openai.com/docs/api-reference/chat> |
| OpenAI Responses API | <https://platform.openai.com/docs/api-reference/responses> |

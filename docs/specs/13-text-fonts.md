# 13-text-fonts: 텍스트 낙서에 여러 글꼴 제공

- **상태**: IN_PROGRESS
- **승인자**: 현 (추천안대로 진행, "결정할 게 없으면 작업해") / 승인일: 2026-10-29
- **브랜치**: `feat/13-text-fonts`
- **선행 명세**: 05, 06, 07-moderation-frame

## 1. 왜 (Why)
지금 텍스트 낙서는 모두 Arial 한 가지로만 그려져 그림 낙서에 비해 표현 폭이 좁다. 사용자가 글꼴을 골라 쓸 수 있으면 텍스트 낙서가 "낙서다운" 느낌을 갖는다. 단, 공개 서비스에 들어가는 글꼴은 라이선스상 웹 배포가 허용된 것만 쓸 수 있다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-31 | 텍스트 bbox 상한(너비 ≤ 크기 × 글자 수, 높이 ≤ 크기 × 1.4 × 줄 수)을 글꼴이 넘지 않아야 함 |
| 결정 | D-33 | 텍스트 초안은 박스만 중계. 글꼴이 바뀌어도 그대로 |
| 결정 | D-44 (신규) | 글꼴 라이선스 기준과 자체 호스팅 (§5.1) |
| 결정 | D-45 (신규) | D-31 너비 상한을 글자 크기의 125%로 (§5.2) |
| 불변 조건 | INV-10 | 글꼴 목록은 `GraffitiProperties`에 두고 클라이언트는 서버 값을 씀 |
| 보안 | SEC-12 | 글자는 계속 Konva 캔버스로만 그림 |
| 보안 | CSP | `default-src 'self'`이므로 외부 CDN(Google Fonts) 불가, 자체 호스팅만 |
| API | `API.md` 낙서 게시 | TEXT payload에 `f`(글꼴 id) 추가, `/config`에 `fonts` 추가 |
| DB | `DB.md` §3 | payload JSON 안의 필드 추가뿐, 마이그레이션 없음 |

## 3. 범위
**포함**
- 서버 설정의 글꼴 목록(`graffiti.doodle.fonts`)과 `/config` 응답
- TEXT payload의 선택 필드 `f`와 서버 검증
- 프런트 자체 호스팅 글꼴(@font-face), 로딩 대기, 툴바 글꼴 선택
- 글꼴 추가 절차(라이선스·크기·폭 검사)와 첫 글꼴 세트

**제외 (NOT in scope)**
- 사용자가 글꼴 파일을 올리는 기능: 라이선스·악성 파일 검사 부담이 큼
- 굵기·기울임·자간 조절: 이번엔 글꼴 선택만
- 개인 사용 전용(PERSONAL USE ONLY) 또는 라이선스 불명 글꼴: 공개 서비스 배포 불가

## 4. 현재 상태 (검증된 사실)
- 글꼴은 `frontend/src/tools/textMeasure.ts`의 `TEXT_FONT = 'Arial'` 하나. 편집기 `ui/TextDraftEditor.tsx`가 이를 쓰고, 게시된 낙서 `canvas/DoodleShape.tsx`는 `fontFamily` 없이 Konva 기본값(Arial)으로 그림
- 크기는 서버 설정 `font-sizes`(application.yml)에서 내려옴
- 서버 `DoodleValidator`는 TEXT의 bbox를 D-31 상한으로만 검사하고, 클라이언트 측 `measureText`는 너비를 `크기 × 글자 수`로 잘라서 보냄
- **텍스트 검사는 글꼴과 무관**: `ModerationService`는 payload의 `text` 문자열을 그대로 금칙어·개인정보 검사에 넣음. ML(이미지·OCR)은 `MlAnalysis.enforces`에서 DRAWING에만 돔
- `frontend/designs/fonts/English_font/`는 만들어졌지만 비어 있음(복사 실패)

## 5. 설계

### 5.1 라이선스 기준 (가장 먼저)
| 허용 | 불가 |
|---|---|
| SIL Open Font License(OFL) 1.1, Apache 2.0, 웹폰트 임베딩·상업 이용을 명시적으로 허용하는 라이선스 | "Personal use only", "Demo", "Free for personal use", 라이선스 파일 없음, 상업 이용·웹 임베딩 금지 |

- 개인 사용 전용·데모·라이선스 불명 파일은 **저장소에도 넣지 않는다**(공개 저장소면 그것만으로 재배포가 됨). 지금 `frontend/designs/fonts/`는 커밋되지 않은 상태이므로 `.gitignore`에 넣고, 허용된 글꼴만 §5.3 위치로 옮긴다
- 라이선스와 근거는 `frontend/src/fonts/LICENSES.md`, OFL 원문과 저작권 표기는 `frontend/src/fonts/OFL.txt`에 둔다
- 웹용 변환(woff2, 글자 범위별 분할)은 글꼴 파일을 "수정"하는 일이다. 수정 금지 조항이 있는 글꼴은 웹폰트 사용이 명시적으로 허용된 경우만 쓴다

**2026-10-29 판정** (글꼴 파일 안의 저작권·라이선스 정보로 확인. 한글 글꼴은 모두 눈누(상업적 이용 허용 글꼴만 올림)에서 받았다는 현의 확인으로 "가능". 추가할 때 각 글꼴의 눈누 페이지 링크와 사용 범위 표의 "웹사이트"·"임베딩" 항목을 `frontend/src/fonts/<id>/LICENSE.md`에 남긴다. 원본 파일 단독 재배포는 대부분 금지이므로 원본 TTF·OTF는 커밋하지 않는다)

| 파일 | 만든 곳 | 파일 안 라이선스 정보 | 판정 |
|---|---|---|---|
| PretendardVariable.ttf (+ woff-subset) | 길형진 | SIL OFL 1.1 | **가능** |
| Korean/GowunDodum-Regular.ttf | 류양희 | SIL OFL 1.1 | **가능** |
| Korean/Galmuri11-Condensed.ttf | 이민서 | SIL OFL 1.1 | **가능** |
| Korean/Jalnan2TTF.ttf, JalnanGothic.otf | GC COMPANY(여기어때) | 개인·기업 무료, 수정·재배포 자유라고 명시 | **가능** |
| Korean/NanumSquareNeo-Variable.ttf | NAVER | 파일엔 없음. 눈누에서 받음(현) | **가능** (눈누) |
| Korean/Cafe24 Classictype·Shiningstar·Ssurround | 카페24 | 파일엔 없음. 눈누에서 받음(현) | **가능** (눈누) |
| English/GodoB.ttf | 고도몰·윤디자인 | 파일엔 없음, fsType 4(미리보기·인쇄만) | 조건부 (고도체 약관 확인) |
| Korean/KyoboHandwriting2025lyb.ttf | 교보문고 | 파일엔 없음. 눈누에서 받음(현) | **가능** (눈누) |
| Korean/KERISKEDU_B·Line·R | 한국교육학술정보원 | "KERIS"만 적힘. 눈누에서 받음(현) | **가능** (눈누) |
| Korean/MemomentKkukkukk.otf | memoment(WISEFONT) | 눈누에서 받음(현). 26MB라 분할 필수 | **가능** (눈누) |
| Korean/RixInooAriDuri Regular.ttf | FONTRIX | 눈누에서 받음(현) | **가능** (눈누) |
| Korean/YoonChildfundkoreaMinGuk.ttf | 윤디자인 | 파일엔 "복제·배포 금지" 문구, 눈누에서 받음(현) | **가능** (눈누, 단 파일 단독 재배포 금지 → 원본은 저장소에 넣지 않음) |
| Korean/Griun_PolSensibility-Rg.ttf | TypeE | 눈누에서 받음(현). 단 파일에 "수정은 사전 서면 허가 필요", 한글 2,780자뿐 | 비추천 (woff2 분할이 수정에 해당, 없는 글자는 다른 글꼴로 보임) |
| Korean/NalSun-Regular.ttf | 정보 없음 | 눈누에서 받음(현). 한글 2,780자·영문 6자뿐 | 비추천 (글자 부족) |
| English/BigLarianCustoms_PERSONAL_USE_ONLY.otf, FindMeThere_PERSONAL_USE_ONLY.otf | Mans Greback | 상업 라이선스 별도 구매 필요 | **불가** |
| English/Blacklock PERSONAL USE ONLY!.ttf | Kong Font | 파일명에 개인 사용 전용 | **불가** |
| English/Just Break Demo, Strasbourg Demo | FallenGraphic | 데모, fsType 2(임베딩 금지) | **불가** |
| English/Mistical Spring DEMO VERSION.ttf | TimelessType | 데모 | **불가** |
| English/Daustley, Ghosilla, Im Joking, Orange Lovely, Zerowax, Hopping Mappy Neue, Super Waffles, Super Whales | 각 개인 디자이너 | All rights reserved 또는 정보 없음 | 불가 (상업 라이선스 증빙 전) |

### 5.2 글꼴 세트 (현 승인)
| id | 글꼴 | 느낌 | 원본 파일 |
|---|---|---|---|
| `pretendard` | Pretendard (기본, `default-font`) | 깔끔한 고딕. 글꼴 이전 낙서도 이것으로 그림 | English_font/PretendardVariable.ttf (굵기 400으로 고정) |
| `gowun-dodum` | 고운돋움 | 부드러운 고딕 | Korean_font/GowunDodum-Regular.ttf |
| `galmuri` | 갈무리 | 픽셀(도트) | Korean_font/Galmuri11-Condensed.ttf |
| `jalnan` | 잘난체 | 굵은 제목체 | Korean_font/Jalnan2TTF.ttf |
| `kyobo-hand` | 교보 손글씨 | 손글씨 | Korean_font/KyoboHandwriting2025lyb.ttf |
| `cafe24-ssurround` | 카페24 써라운드 | 둥근 굵은체 | Korean_font/Cafe24Ssurround-v2.0.ttf |

영문 장식 글꼴은 현이 고른 파일이 모두 개인용·데모·불명이라 넣지 않는다. 나머지 "가능" 글꼴은 §5.6 절차로 추가.

**폭**: D-31은 너비를 `크기 × 글자 수`(1em)로 막는데, 잘난체(W 1.05em), 카페24 써라운드(% 1.08em) 등은 이를 넘는다. 그래서 너비 계수를 설정값 `text-width-percent: 125`로 둔다(D-45). 서버는 `크기 × 글자 수 × 125%`를 **올림**한 값까지 받는다(D-43 축소 후 올림한 bbox가 상한을 넘지 않게). 높이(1.4em 상한)는 모든 글꼴의 실제 글자 모양(ink)이 통과.

### 5.3 프런트 로딩
```
원본 TTF (현의 컴퓨터, git 밖)
  → frontend/scripts/build_fonts.py (fontTools)
      D-31 폭·높이 검사 → 영문·기호 1조각 + 한글 40조각(코드 순서로 279자씩) woff2
      → src/fonts/files/<id>-<n>.woff2, previews/<id>.png, manifest.ts(조각별 unicode-range)
  → Vite가 해시 이름으로 번들 (dist/assets, 장기 캐시 가능)
  → 처음 쓰일 때 FontFace(unicodeRange)로 등록, document.fonts.load(font, text)로 필요한 조각만 받음
```
- CSP가 `'self'`라 외부 CDN은 못 씀. 원본 TTF·OTF는 커밋하지 않고 woff2 조각만 커밋(6종 합계 약 4.4MB, 브라우저가 받는 건 쓰인 글자 조각뿐)
- 한글을 코드 순서로 나눈 것은 조각마다 unicode-range가 한 줄이라 manifest가 작기 때문(빈도순은 범위가 수천 개로 쪼개져 JS가 200KB 늘었음). "경희대 낙서장 WWW"를 그리면 글꼴당 약 7조각
- 한 조각의 1em보다 넓은 기호(‱ 등)는 넣지 않아 브라우저 기본 글꼴로 그려짐
- **캔버스는 글꼴이 로드되기 전 대체 글꼴로 그려 버림**. 그래서:
  - 편집기(`useDraft`): 글꼴·글자·크기가 바뀔 때마다 그 글꼴로 측정하고, 조각이 로드되면 다시 측정해 박스를 고침(`refit`). 게시 직전에도 한 번 기다림(`settle`)
  - 게시된 낙서(`DoodleShape`): `useFontFamily`로 처음엔 sans-serif로 그리고, 로드되면 글꼴 이름을 바꿔 Konva가 다시 그리게 함
  - 내 낙서 PNG 저장: 그리기 전에 글꼴을 모두 기다림
  - 로드 실패(네트워크·차단)는 대체 글꼴로 계속 보여 주고 오류는 내지 않음
- 글꼴 선택 칸(`ui/FontPicker.tsx`): 글자 크기 옆. `<select>`의 option에는 그림을 넣을 수 없어서 버튼 + listbox로 만듦. 각 항목은 그 글꼴로 그린 "낙서 Graffiti" 그림과 이름. 방향키·Enter·Esc 지원, 위로 펼쳐짐(툴바가 화면 아래)

### 5.4 데이터와 서버
- 설정: `graffiti.doodle.fonts`(id 목록, 선택 칸 순서), `default-font: pretendard`, `text-width-percent: 125` (INV-10). `/api/pages/current`의 `config`로 내려감. 표시 이름·파일·그림은 프런트 `src/fonts/`에 있고 서버는 id만 앎. 서버가 주는 id 중 프런트에 파일이 없는 것은 선택 칸에서 뺌
- payload: `{ "v": 1, "text", "c", "size", "x", "y", "f": "jalnan" }`. **`f`는 선택**이라 이전 클라이언트와 기존 낙서가 그대로 동작하고 `v`도 1 유지. `f`가 없으면 그대로 `f` 없이 저장(`@JsonInclude(NON_NULL)`), 프런트는 첫 글꼴로 그림
- 서버 검증: `f`가 있으면 설정 목록에 있어야 함(`invalid("f", "not_allowed")`). D-43 축소는 `f`를 유지
- 글꼴을 목록에서 빼면 새 게시는 거부되고, 이미 게시된 낙서는 프런트가 모르는 id를 첫 글꼴로 그림

### 5.5 검사(07/07b)와의 관계
- 텍스트 낙서는 글자 문자열로 검사하므로 글꼴이 바뀌어도 검사 결과는 같음. OCR을 거칠 필요 없음
- **위험 하나**: 기호·딩뱃 글꼴처럼 일반 글자 자리에 그림(예: 혐오 상징)을 넣은 글꼴이면, 문자열 검사는 통과하는데 화면엔 다른 것이 보임. 그래서 §5.6의 "글자 모양 확인"을 추가 절차에 넣고, 기호·딩뱃·아이콘 글꼴은 받지 않음
- 관리자 검토 화면은 텍스트를 문자열로 보여 주므로 글꼴 id를 같이 표시(어떤 모양으로 보였는지 알 수 있게)

### 5.6 글꼴 추가 절차 (`frontend/scripts/build_fonts.py`)
1. 라이선스 확인 → §5.1 표에 기록. 눈누 글꼴은 그 페이지 링크와 "웹사이트" 항목을 PR에 남김
2. `build_fonts.py`의 `FONTS`에 id·이름·원본 경로 추가 → `python -I scripts/build_fonts.py --src <원본 폴더>` (요구 패키지 `scripts/requirements-fonts.txt`). 영문·숫자·한글 중 `text-width-percent`보다 넓은 글자가 있거나 실제 글자 높이가 1.4em을 넘거나 한글 11,172자가 다 없으면 멈춤
3. 글자 모양 확인: 생성된 `previews/<id>.png`와 캔버스에서 일반 글자인지 사람이 확인
4. `application.yml`의 `fonts`에 id 추가

| 파일 | 역할 |
|---|---|
| `backend/.../config/GraffitiProperties.java` | `fonts`, `defaultFont`, `textWidthPercent` + 검증 |
| `backend/src/main/resources/application.yml` | 글꼴 id 목록, 기본 글꼴, 너비 계수 |
| `backend/.../doodle/DoodleValidator.java`, `TextShrinker.java` | `f` 검증·저장·유지, 너비 상한 |
| `backend/.../page/PageController.java` | `config`에 `fonts`, `defaultFont`, `textWidthPercent` |
| `frontend/scripts/build_fonts.py`, `requirements-fonts.txt` | 조각·미리보기·manifest 생성, D-31 검사 |
| `frontend/src/fonts/` | 생성물(`files/`, `previews/`, `manifest.ts`), `fonts.ts`(등록·로딩), `useFontReady.ts` |
| `frontend/src/ui/FontPicker.tsx`, `Toolbar.tsx`, `app.css` | 그림이 보이는 글꼴 선택 칸 |
| `frontend/src/tools/textMeasure.ts`, `useDraft.ts`, `draft.ts`, `publish.ts` | 글꼴로 측정, 로드 후 재측정, `f` 전송 |
| `frontend/src/canvas/DoodleShape.tsx`, `ui/TextDraftEditor.tsx`, `mine/png.ts` | 글꼴로 그리기 |
| `docs/API.md`, `DB.md` §3, `DECISIONS.md` D-44·D-45, `ARCHITECTURE.md` §6 | 문서 |

## 6. 실패 모드
| 경로 | 실패 상황 | 처리 (사용자에게 보이는 결과) | 테스트 |
|---|---|---|---|
| 게시 | 목록에 없는 `f` | 400 `f not_allowed`, 토스트 | 서버 단위 |
| 편집 | 글꼴 로드 전에 측정 | 로드 완료까지 게시 비활성, 그 뒤 측정 | 프런트 단위(가짜 FontFaceSet) |
| 표시 | 글꼴 로드 실패 | 대체 글꼴로 계속 표시, 오류 없음 | `fonts.test.ts` |
| 표시 | 저장된 `f`가 목록에서 빠짐 | `default`로 표시 | 프런트 단위 |
| 배포 | 넓은 글꼴이 D-31 상한 초과 | `build_fonts.py`가 멈추고 이유 출력 | 수동(생성 시 실행) |
| 배포 | 라이선스 불명 글꼴이 섞임 | `src/fonts/LICENSES.md`에 항목이 없으면 테스트 실패 | `fonts.test.ts` |

## 7. 수용 기준
- [ ] AC-1: Given 글꼴 `jalnan`을 고른 사용자, When "안녕"을 게시하면, Then 다른 클라이언트에도 잘난체로 보이고 payload에 `"f":"jalnan"`이 저장된다
- [ ] AC-2: Given `f` 없는 기존 낙서, When 페이지를 열면, Then 지금과 같은 글꼴로 보인다
- [ ] AC-3: Given 목록에 없는 `f`, When 게시하면, Then 400으로 거부된다
- [ ] AC-4: Given 느린 네트워크, When 글꼴을 고르고 바로 게시하면, Then bbox가 실제 글꼴 기준으로 계산된다(대체 글꼴 기준이 아님)
- [ ] AC-5: 번들된 모든 글꼴 폴더에 허용 라이선스 원문이 있고, 개인 사용 전용 파일은 저장소에 없다
- [ ] AC-6: 같은 글자에 대해 글꼴만 바꿔도 금칙어·개인정보 검사 결과가 같다
- [ ] 보안: 외부 글꼴 CDN 요청이 없다(CSP 위반 0), 글자는 Konva로만 그린다(SEC-12)

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 | `f` 검증, 설정 검증 | `DoodleValidatorTest`, `GraffitiPropertiesTest` |
| 단위 | 로드 대기·대체·모르는 id | `useFontReady.test.ts`, `textMeasure.test.ts`, `DoodleShape.test.tsx` |
| 통합 | 게시 → 조회 시 `f` 왕복 | `DoodlePublishIT` |
| 스크립트 | 폭·높이 검사 | `build_fonts.py` 실행 결과(6종 통과, Pretendard ‱ 등 넓은 기호는 조각에서 제외) |
| QA 시나리오 | 두 브라우저에서 글꼴별 게시, 느린 3G에서 첫 표시, 한글·영문 섞인 글 | 수동 |

## 9. 구현 에이전트 규칙 (이 명세 전용)
- §5.1 표에서 "허용"으로 판정된 글꼴만 저장소에 넣는다. 판정이 비어 있으면 멈추고 질문
- 외부 CDN 링크(`fonts.googleapis.com` 등)를 넣지 않는다
- D-31 상한은 바꾸지 않는다. 글꼴이 상한에 맞춰야 한다

## 10. 롤백
설정 `fonts`를 `[default]`로 줄이면 새 글꼴 게시가 막히고, 이미 게시된 낙서는 `default`로 보인다. 코드 되돌림은 프런트·백엔드 커밋 revert. DB 마이그레이션 없음.

## 11. 결정 기록 (2026-10-29, 현 "결정할 게 없으면 작업해" → 추천안)
1. 첫 세트: §5.2의 6종
2. 고도체: 출처(눈누) 확인 전이라 제외
3. 폭 초과 글꼴: B, `text-width-percent: 125` (D-45)
4. 영문 장식 글꼴: 이번엔 없음
5. 기본 글꼴: Arial → Pretendard (기존 낙서도 Pretendard로 보임)

---

## 리뷰 기록 (/gf-review가 추가)

## QA 기록 (/gf-qa가 추가)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)

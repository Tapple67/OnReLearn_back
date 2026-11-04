## 2026-11-04 사용자 문구 용어 통일

- [x] 화면에 표시되는 서버 문구를 프론트 용어에 맞췄다(파일→자료, 노트→메모). 대상은 통계 안내, 메모 입력 오류, 미완료 자료 삭제 안내다. API·내부 이름·코드 주석은 바꾸지 않았다. 재시작 후 반영된다.

## 2026-11-04 메모가 있는 페이지 조회

- [x] 소유자 전용 `GET /api/notes/pages?materialId=`를 추가했다. 응답은 `{pages:[3,4]}`이고 `no-store`다. 내용을 지워 빈 문자열이나 공백만 남은 행은 메모로 치지 않는다. 기존 노트 조회·저장과 스키마는 바꾸지 않았다.
- [x] 시험 NotePagesTests 4건(공백 메모 제외, 타인 403, 빈 자료 ID 400, 매개변수 바인딩·no-store) 통과. 실제 HTTP 401·403·400을 보는 검사를 NoteReadMysqlTests에 추가했으나 DB 환경변수가 없어 mysqlTest는 미실행이다.

## 2026-11-04 메모 기반 쉬운 설명과 프롬프트 정리

사용자 결정: 기본 요약은 원문 검증을 유지하고, 메모에 어려움이나 쉬운 설명 요청이 있을 때만 AI가 쓴 새 설명을 표시한다. 메모가 없으면 쉬운 설명을 넣지 않는다(summary.v17-memo-explanations, quiz_v12_memo_requests).

- [x] 요약 JSON Lines에 선택 필드 easy를 추가했다. 서버는 quote를 기존대로 검증한 뒤, 어려움 메모가 해당 개념을 가리키거나 개념 없는 페이지 전체 메모가 같은 페이지에 있을 때만 "내 메모"와 "쉽게 풀어보면"을 항목 아래에 붙인다. 메모 하나당 설명 하나, 600자 이내이며 줄바꿈은 공백으로 합친다. easy의 내용은 원문과 대조하지 않으며 화면에 "AI 설명"으로 표시한다.
- [x] 어려움 표현을 "어려운", "복잡", "이해가 안", "무슨 말", "풀어줘"까지 넓혔다.
- [x] 프롬프트 정리: 퀴즈 규칙을 Java 문자열에서 템플릿으로 모으고 근거·문항·유형·메모·출력 필드로 나눴다. 어긋난 "메모는 주제 선택에만" 문구와 실제 내용과 다른 입력 제목을 고쳤다. 메모 요청별로 묻는 방식을 지정했다. 요약 프롬프트도 같은 방식으로 다시 썼다. 켜져 있어도 메모를 받지 못하던 선택 검수에 메모를 전달한다.
- [x] 전체 비DB 180건·bootJar 통과(실패 0), JAR 금지 설정 0건. 프론트 Node 77건·ESLint·Vite build 통과. 신규 MemoEasyExplanationTests 11건, summaryContent.test.js 2건.
- [ ] 모델이 메모가 있어도 easy를 쓰지 않으면 기존 "추가 설명" 발췌만 표시된다. 별도 재요청은 넣지 않았다. 실제 출력에서 확인이 필요하다.
- [ ] 다음 시작점: IDE 백엔드 재시작 후 "어려워" 메모를 적은 자료로 요약을 생성해 설명 표시를 확인하고, 메모 없는 페이지에 설명이 없는지 본다. 실제 AI·MySQL·브라우저는 실행하지 않았다.

## 2026-11-04 메모를 AI 입력과 원문 연결에 반영

진단 결과 메모와 페이지의 매핑은 정상이었지만 요약·퀴즈 AI는 메모를 받지 못했고, 합성 메모 11개 중 원문에 연결된 메모는 문장형 PDF 2개, 슬라이드형 PDF 0개였다. 사용자 결정에 따라 메모를 AI에 전달한다(summary.v16-memo-aware, quiz_v11_memo_aware).

- [x] 페이지별 메모를 요약·퀴즈 프롬프트에 데이터로 넣는다. 명령처럼 보이는 줄은 표시와 전달에서 모두 제외한다. 메모만으로는 인용·정답 근거가 되지 않으며 서버 검증은 그대로다. 메모는 설정된 외부 AI 제공자로 전송된다.
- [x] 원문 연결: 조사가 붙어도 앞부분이 같으면 같은 용어로 보고, 3글자 용어 하나로도 연결한다. 문장부호 없는 긴 슬라이드는 줄 단위로 비교한다. 같은 합성 메모의 연결 수는 문장형 7개, 슬라이드형 8개가 됐다. 키워드 없는 메모도 언급한 개념이 원문에 있으면 연결한다. 연결되지 않은 일반 메모는 목록에 표시하지 않고 AI만 참고한다.
- [x] 퀴즈: 메모 연결 문항 상한은 max(1, 30% 내림)이다. 프롬프트로 가능한 한 이 수만큼 출제를 요청하지만 강제하지 않는다. signalHits에 서버가 확인한 메모 개념을 저장한다. 화면 표시는 없다.
- [x] 전체 비DB 169건·bootJar 통과(실패 0), JAR 금지 설정 0건. 신규 MemoReflectionTests 11건. 앞서 기존 실패로 기록한 SummaryContractTests도 이 수정으로 통과한다. "메모를 보내지 않는다"는 전제의 StreamingSummaryTests 단언은 "각 메모를 한 번 전달한다"로 바꿨다.
- [ ] 버전 변경으로 기존 요약은 조회되지 않고, 다음 요약 또는 퀴즈 생성 때 다시 만들어진다(AI 비용 발생). 메모를 고친 뒤 저장본을 다시 열면 이전 요약이 보이는 조회 동작은 코드상 판단이며 수정하지 않았다.
- [ ] signals 컬럼에 쓰는 코드는 여전히 없다. 프롬프트 전달만 연결돼 있고 새 데이터에서는 비어 있다.
- [ ] "쉽게 설명해줘"에 새 설명 문장을 쓰는 기능은 요약 검증 방식 변경이 필요해 넣지 않았다. 사용자 확인 대기.
- [ ] 다음 시작점: IDE 백엔드를 재시작하고 메모를 적은 자료로 요약과 10문항 퀴즈를 실제 생성해 메모 개념이 선택되는지 확인한다. 실제 AI·MySQL·브라우저는 실행하지 않았다.

## 2026-11-04 퀴즈 원문 기반 검증 전환

실제 실패 로그는 stage=grounding, code=QUIZ_GROUNDING, missing={mcq=6}, rejected={ANSWER_MISMATCH=2, INVALID_CHOICE=5, QUOTE_MISMATCH=3, UNKNOWN_PAGE=2}였다. 개념형 프롬프트와 글자 일치 검증이 충돌해 객관식 후보 12개가 모두 탈락했다. 사용자 결정에 따라 표현은 달라도 되고 원문과 무관한 내용만 막는 기준으로 바꿨다(quiz_v10_source_based).

- [x] 인용: 공백·문장부호 차이와 문단 경계를 허용한다. 어미가 달라도 핵심 용어 4개 이상 중 80% 이상이 한 문단에 있으면 인정한다. 인용문이 다른 제공 페이지에 있으면 그 페이지로 교정한다. 해설은 계속 실제 원문 문단만 표시한다.
- [x] 문제·보기·정답: 문제는 인용 문단과, 보기는 제공된 원문과 핵심 용어가 1개 이상 이어져야 한다. 정답은 인용 페이지에 표현이 있거나 핵심 용어의 절반 이상이 있어야 한다. 단답형은 뒷받침되는 동의어만 저장한다. OX 문제의 원문 연결 검사는 새로 추가했다.
- [x] 개수: 초과분은 버리고 부족분만 다시 요청한다. 재요청은 최대 2회이며 탈락 사유를 함께 보낸다. 끝내 부족하면 기존대로 502이며 저장하지 않는다. 이전 v9 퀴즈는 검증 강화 전 생성본으로 표시하지 않는다.
- [x] 전체 비DB 158건 중 157건 통과, 신규 시험 QuizSourceBasedTests 22건 포함. bootJar 통과, JAR 금지 설정 0건. 기존 시험 13건은 새 계약에 맞게 고쳤다. 실패 1건 SummaryContractTests.rendersSourceAndSignalsWithoutInternalIdentifiersOrTimestamps는 이번 수정 전 커밋에서도 재현된 기존 실패이며 수정하지 않았다.
- [ ] 용어 연결 검사는 내용의 참·거짓을 판정하지 못한다. 원문 용어로 틀린 주장을 만들면 통과할 수 있다. 의미 검수는 기존 선택 기능 onrelearn.ai.quiz-critic.enabled가 담당한다.
- [ ] 다음 시작점: IDE 백엔드를 재시작한 뒤 같은 자료로 실제 Gemini 10문항 생성과 문항 품질을 확인한다. 실제 AI·MySQL·브라우저는 이번에 실행하지 않았다.

## PDF 기반 개념형 퀴즈

새 퀴즈는 문제 안에 풀이 조건을 제시하고 개념 비교와 적용, 참·거짓 판단을 묻는다. 출제 프롬프트에 객관식·OX·단답형 예시를 추가했다. 서버는 인용문과 PDF 페이지의 일치, 객관식 보기와 정답 용어의 출처, 문제 중복과 문항 수를 확인한다. 개념 질문과 OX 판정은 모델이 작성하며, 채점 해설에는 검증된 원문을 표시한다.
## 퀴즈 중복 방지와 객관식 보기

서버는 원문 검증 뒤 문제 문구가 같은 문항을 제외한다. 재생성 요청에 이미 채택한 문제를 전달해 부족분을 다른 인용이나 빈칸으로 채운다. 객관식 오답은 관련 PDF 표현을 요청하고, 서버가 보완할 때는 같은 페이지의 다른 구절을 우선한다. 원문 검증 및 Gemini 회귀 시험과 bootJar를 통과했다. 2026-11-04에 5173에서 ‘2_메시지 무결성_인증’ 퀴즈 10문항을 새로 저장하고 풀이 화면에서 서로 다른 문구 10개와 같은 페이지 기반 보기를 확인했다.
## 퀴즈 인용 페이지 범위 보완

메시지 무결성 자료의 10문항 실검증에서 재생성 후 객관식 6개, OX 2개가 부족했다. 탈락한 객관식 12개와 OX 5개는 입력 원문 범위 밖 페이지를 인용했다. 서버는 문항 유형별 부족분과 탈락 사유를 집계해 기록하며, 재생성은 부족한 유형과 개수만 요청한다. 일반 생성에는 요약에 인용된 PDF 페이지를 최대 12개 추가하고 프롬프트에 선택 가능한 페이지 번호를 명시한다. 원문 일치와 메모 반영 상한은 최종 검증에서 적용한다.
객관식 보완에서는 검증된 인용구에서 빈칸 정답을 고르고, 검증에 실패한 보기는 PDF에 실제 존재하는 서로 다른 표현으로 다시 구성한다. 원문 페이지 확대 후에는 OX와 단답형이 모두 통과했고, 객관식 4개가 부족했다. 당시 객관식 탈락 집계는 정답 불일치 4개, 보기 불일치 또는 중복 4개, 인용 불일치 1개였다. 객관식 보완 적용 후 GroundingTests와 GeminiProviderTests, bootJar를 통과했다.
2026-11-04 최신 JAR를 8080에서 실행해 5173의 ‘2_메시지 무결성_인증’ 퀴즈 10문항 저장을 확인했다. 페이지를 새로고침한 후에도 새 퀴즈가 목록에 표시됐다.

## 퀴즈 원문 검증 재시도

5173에서 메시지 무결성 자료 10문항 요청은 grounding 단계 502를 반환했다. 첫 응답의 검증 통과 문항을 보존하고 재생성 응답에서 부족한 유형을 채운다. 재생성 호출 오류는 grounding_retry 단계, 최종 검증 부족은 QUIZ_GROUNDING 코드로 구분한다. 수정본을 8080에 반영한 후 같은 자료로 다시 확인한다.
## Gemini 퀴즈 502 진단

퀴즈 생성 스트림은 실패 단계와 고정 진단 코드를 반환한다. PROVIDER_HTTP는 Gemini HTTP 오류, PROVIDER_TRANSPORT는 연결 실패, PROVIDER_RESPONSE는 Gemini 응답 형식 오류, QUIZ_JSON과 QUIZ_STRUCTURE는 문항 파싱과 구조 검증 실패를 뜻한다. Gemini가 스키마를 포함한 요청에 HTTP 400을 반환하면 JSON 모드로 한 번 다시 요청하고 서버에서 문항을 검증한다. 이전 실서비스 오류 응답에는 진단 코드가 없었다. 수정본을 실행한 후 같은 자료로 10문항을 요청해 결과 또는 새 진단 코드를 확인한다.
## 메모 신호와 퀴즈 생성 검증

요약은 허용된 학습 신호를 서버에서 해석해 안내 문구와 PDF 발췌를 붙인다. 지원되는 질문에 대응할 원문을 찾지 못하면 다시 볼 질문에 표시한다. 지원 범위 밖의 메모는 생성에 사용하지 않는다. 퀴즈 프롬프트는 원문 검증 규칙과 일치시키고 검증 실패 시 메모 유무와 관계없이 한 번 더 생성한다. 스트림 실패 로그는 단계와 상태를 기록한다. 같은 자료의 실서비스 재검증은 수정된 백엔드가 실행된 후 진행한다.
## 2026-11-03 메모 의도를 반영한 요약과 퀴즈

- 요약은 메모의 쉬운 설명·비교·예시·이유 요청을 표현 규칙으로 분류한다. 같은 페이지에서 먼저, 없으면 확인된 같은 목차 절에서 PDF 발췌를 찾는다. 관련 개념 아래 읽는 안내와 발췌를 붙이고, 근거를 찾지 못한 메모는 다시 볼 질문으로 모은다. 기존 원문 검증과 스트리밍 경로를 사용한다.
- 퀴즈는 원문과 연결된 메모 개념을 10문항 중 최대 3문항에 사용한다. 다른 문항 수는 30%를 내림한다. 메모는 출제 주제 선택에 사용한다. 퀴즈 난이도와 객관식 보기 수는 메모 문구에 따라 자동 조정하지 않는다. 원문이나 메모 문항 수 검증으로 요청 수가 부족하면 한 번 다시 생성한다.
- 원문·스트리밍·퀴즈 대상 시험 54건 통과. 실제 AI와 브라우저에서 새 요약·퀴즈 생성은 미검증이다. 다음 확인은 메모를 저장한 자료로 요약을 다시 생성하고 10문항 퀴즈에서 메모 관련 문항 수와 보기를 확인하는 것이다.

## 개념 설명의 요약 분량 확대 (2026-11-03)

summary.v13-grounded는 페이지 묶음당 최대 8개, 원문 항목당 최대 360자를 사용한다. 정의뿐 아니라 작동 이유와 과정, 적용 조건과 예외를 뒷받침하는 원문을 우선 고른다. 예시와 비교는 이해에 필요한 경우만 포함한다. 근거가 적은 묶음은 적은 항목으로 끝낸다.

## 요약의 메모 근거 연결 (2026-11-03)

summary.v12-grounded는 메모 문장과 PDF 원문의 공통 용어로 근거를 찾는다. 같은 페이지를 우선하고 목차 경계가 확인되면 같은 절의 다른 페이지를 확인한다. 일치한 메모와 PDF 발췌의 페이지를 따로 표시한다. 스트리밍 중 해당 개념을 표시할 때 메모와 근거를 같이 내보내고, 요약 항목으로 선택되지 않은 연결과 미해결 질문은 절 끝에 붙인다. 표현이 다른 동의어까지 자동으로 연결하지 않으며, 목차 경계가 없으면 같은 페이지에서만 검색한다.

## 2026-11-02 요약·퀴즈 원문 근거 강제 검증

- [x] 자유 생성 문장의 인용 존재만 확인하는 방식으로는 의미 왜곡을 차단할 수 없어 보수적 발췌/원문 대조 방식으로 변경했다. SourceEvidence는 공백/NFC만 정규화하고 대소문자·숫자·부정어를 바꾸지 않는다. 모델이 선택한12자 이상 quote가 지정 PDF 페이지에 실제 포함되는지 검사한 뒤, 인용 앞뒤 부정/조건을 잃지 않도록 해당 원문 문단 전체를 서버가 복원한다. 문서 자체의 오류/OCR 오류·논리적 참/거짓을 보장하는 기능은 아니다.
- [x] 요약은 JSON Lines의 완성 문단 선택만 검사한다. 원시 AI delta, 모델의 제목/해석/추가 주장은 노출하지 않고 실제 PDF에서 복원한 문단+페이지 표시만 전달한다. 없는 인용/페이지·메모만의 주장·잘못된 JSON·중복은 제외한다. 통과 문단이0이면502이며 저장하지 않는다. 8페이지 묶음/전체 완료·기존 소유권/원본 재검사 후 저장하고 실패한 전체 결과는 기존 저장본을 보존한다. 동기/스트리밍 모두 같은 검증 제공자를 사용한다. summary.v7-grounded로 캐시 구분하며 이전 버전 요약은 보존하되 새 조회/재사용 대상에서 제외한다.
- [x] 퀴즈는 실제 선택 PDF의 전체 원문에 근거를 대조한다(기존2000자 합성 생략문자 제거). 모델의 임의 문제/해설/제목/주제는 채택하지 않고 서버가 재구성한다. 객관식/단답형은 원문 표현 빈칸형, 모든 객관식 보기/정답은 원문에 존재해야 하며 단답형의 미검증 동의어는 제외한다. OX는 실제 원문 인용과 지정 페이지의 일치 여부를 대조한다. 다른 확인 페이지가 없으면 O만 만든다. 해설/근거는 실제 원문 문단이다. 유효 문항이 요청 수/유형에 못 미치면 전체502·미저장이며 없는 문항을 채우지 않는다. 선택 검수 실패도 이 최종 강제 검증을 건너뛰지 못한다. quiz_v7_grounded로 구분하고 과목 목록에서 예전 퀴즈는 검증 강화 전 생성본으로 안내한다. 기존 풀이/기록은 삭제하거나 검증됐다고 소급 표시하지 않는다.
- [x] 전체 비DB125건·bootJar 통과(실패/건너뜀0), 추가 원문 검증 분기4건 재확인, 프론트 관련13건·전체 ESLint·Vite build 통과. 숫자/페이지/인용 위조·노트 단독 주장·조작된 해설·원문에 없는 보기/정답·부정 문맥·부분 JSON 비공개·묶음 스트리밍을 검사했다. JAR 금지 설정0건. application.properties/.env/keys.local.yml 직접 접근·실서비스 AI 요청·기존 DB/스토리지 변경 없음.
- [ ] 다음 시작점: IDE 백엔드 재시작 후 실제 Gemini JSON Lines 출력·검증 통과 문단 표시·최종 저장·근거 부족 거절 및 원문 대조 퀴즈 조작을 확인한다. 새 목록 projection의 실제 MySQL/JPA 및 브라우저 직접 조작은 미검증이다. 실제 모델이 계약을 지키지 않으면 보수적으로 거절하며 검증을 자동 완화하지 않는다. 자유로운 재서술/추론형 요약·퀴즈 대신 발췌/대조형이라는 제품 변화는 docs/ai-configuration.md 최신 절을 따른다.

## 2026-11-02 긴 자료 요약 지연·실제 제공자 진단 및 분할

- [x] 사용자 자료는 약78페이지다. 기존 스트리밍 구현도 입력 전체를 한 번에 보냈다. 현재 설정을 Spring만 로딩한 짧은 합성 Gemini 생성 요청에서도 약65초 동안 헤더 미수신으로 시간 초과했다. 인증된 모델 메타데이터는 HTTP/2 약398ms·HTTP/1.1 약230ms에200이며 generateContent 지원이 확인됐다. 두 HTTP 방식 모두 가짜 키 POST는 즉시400, 실제 선택 모델 gemini-3.8-flash의 LOW 추론 합성 요청은 각20초 제한에 시간 초과했다. 단순 키 누락/연결 불가나78페이지 분량만의 문제로 단정하지 않는다.
- [x] 동일 키/합성 입력/LOW 추론/출력 최대128토큰으로 gemini-3.5-flash-lite 스트림은200·약1940ms·텍스트3조각·STOP 완료했다. 이는 짧은 합성 요청 결과이며78페이지 실제 생성 성능은 아니다. 설정 파일·비밀값·응답 원문·사용자 자료는 직접 읽거나 출력하지 않았고 DB/스토리지를 변경하지 않았다. 합성 외부 생성 진단은 종료하고 임시 코드/클래스/개별 리포트를 제거했다.
- [x] 긴 자료를 기본8페이지/약16000문자 단위로 순차 요약하고 각 묶음의 실제 토큰을 즉시 전달하도록 수정했다. 단일 큰 페이지는 자르지 않고 보존한다. 페이지/노트 전체를 순서대로 포함하며 묶음 범위를 제목에 표시한다. 모든 묶음 완료 및 기존 원본 재검증 후에만 전체를 저장한다. 마지막 묶음 실패도 기존 요약을 보존한다. 묶음별 문맥으로 인해 주제가 반복될 수 있으며 별도 전체 재요약 호출은 추가하지 않는다.
- [x] onrelearn.ai.summary.batch-pages(기본8), batch-chars(기본16000), thinking-level(기본빈값; Gemini 스트리밍 전용 LOW/MEDIUM/HIGH/MINIMAL) 설정을 추가했다. 총5분 한도를 묶음 전체에 적용하고 남은 시간도 헤더 대기에 적용한다. 기존 용도별 timeout 무응답 제한을 유지한다. 사용자 properties를 자동 수정하거나 다른 모델로 자동 전환하지 않는다.
- [x] 전체 비DB121건·bootJar 통과(실패/건너뜀0). 모의78페이지가10묶음으로 전송되고 첫 묶음 출력이 다음 묶음 요청 전 전달되며 PDF/노트가 각1회씩 빠짐없이 포함되는 것을 확인했다. 추가 LOW 요청 계약도 별도 대상 시험2건 통과했다. 프론트 제품 코드는 이번에 변경하지 않았다.
- [ ] 사용자 조치: summary.model을 gemini-3.5-flash-lite로 직접 변경하고 summary.thinking-level=low로 맞춘 뒤 IDE 백엔드를 완전히 재시작한다. 기존 gemini-3.8-flash 설정은 그대로 있으므로 코드 재시작만으로 제공자 지연이 해결됐다고 보장하지 않는다. 이후 실제78페이지 요약의 첫 토큰/전체 완성과 브라우저 직접 조작을 확인한다.

## 2026-11-02 요약 실시간 표시·퀴즈 진행 단계/소량 생성

- [x] 과목 콘텐츠의 요약 생성 버튼이 즉시 요약 모달을 열고 POST /api/summary/summaries/stream으로 실제 AI 문장 조각을 표시한다. OpenAI Responses SSE/Gemini streamGenerateContent SSE를 지원한다. 기존 요약 조회/동기 API는 유지한다. 완성 이벤트 확인 뒤 기존 원본 fingerprint/소유권 재검사·짧은 저장 트랜잭션을 통과한 경우에만 저장한다. 실패한 미리보기는 미저장 표시하고 기존 저장 결과를 보존한다.
- [x] 스트리밍은 용도별 timeout을 첫 응답/이벤트 무응답 제한으로 사용하고 5분 전체 제한도 적용한다. 60초 동안 전체 완료를 기다리던 동기 API와 구분한다. 전송 중복/비용을 피하려 스트리밍 자동 재요청은 하지 않는다. 토큰 첫 출력 전 모델 준비 지연은 스트리밍으로 제거되지 않는다.
- [x] POST /api/quiz/quiz-sets/stream은 준비→실제 생성→형식/개수 검증→선택 검수→저장 단계를 전송한다. 퍼센트를 추측하지 않는다. 과목 퀴즈 모달/자료 퀴즈 화면은 기본 객관식2+OX1(3문항), 빠르게3문항 및 기존10문항 선택/직접 입력을 제공한다. 문항/정답/해설은 중간 이벤트에 노출하지 않고 전체 검증·저장 후 목록을 갱신한다.
- [x] 백엔드 비DB120건(두 제공자 스트리밍 조각/완료·중단 검사 포함) 및 bootJar, 프론트 관련 Node15건·전체 ESLint·Vite build 통과. 기존 번들 경고 유지. JAR 금지 설정0건. application.properties/.env/keys.local.yml 직접 접근 및 실제 AI 요청/기존 DB·스토리지 변경 없음.
- [ ] 다음 시작점: IDE 백엔드 재시작 후 사용자 브라우저에서 요약 첫 문장·완료 저장·중단 미리보기와 퀴즈3문항/단계 표시 확인. 실서비스 모델별 SSE 및 프록시 버퍼링/브라우저 직접 조작은 미검증이다. 기능 흐름과 설정 차이는 docs/ai-configuration.md 최신 절을 참고한다.

## 2026-11-02 스토리지 설정 로딩·읽기 전용 진단

- [x] 사용자 요청에 따라 임시 최소 Spring 컨텍스트가 기존 IDE와 같은 리소스 위치의 설정을 자동 로딩했다. application.properties/.env/keys.local.yml을 에이전트가 직접 읽거나 수정하지 않았다. DB 및 실제 사용자 파일 변경 없이 안전한 고정 진단 결과만 확인했다.
- [x] URL/키 모두 존재하지만 값 양끝 따옴표가 포함된 상태다. 원래 URL은 유효 URI로 처리되지 않았다. 메모리에서만 바깥 따옴표를 제거해 검사한 결과 legacy service_role 키, URL과 토큰 프로젝트 일치, 버킷 형식 정상, 버킷 메타데이터 GET200을 확인했다. 키/URL/버킷명/응답 원문은 출력하지 않았다. 업로드·삭제는 실행하지 않았다.
- [ ] 사용자가 properties의 URL 및 service-key 값에 포함된 따옴표를 직접 제거하고 IDE 백엔드를 완전 재시작한 뒤 업로드한다. 임시 진단 소스/클래스/개별 리포트는 제거해 일반 회귀 시험에 실서비스 조회가 남지 않게 했다. 영구 진단 코드·설정 자동 수정은 추가하지 않았다. 버킷 조회 성공은 실제 업로드 성공 검증과 구분한다.

## 2026-11-02 AI 제공자·용도별 모델 선택

- [x] 요약·퀴즈 생성·선택적 검수 각각 provider=openai|gemini 및 model을 설정하도록 구현했다. AiProviderConfiguration이 Spring Environment로 조립하고 AiTextClient/GeminiClient/GeminiResponse가 Gemini native generateContent 및 JSON Schema 출력을 연결한다. 기존 프롬프트/검증/보정·선택 검수 정책은 유지한다. 공통 제공자 키와 용도별 키 재정의, 제공자별 기본 endpoint, 용도별 timeout/retries 설정을 지원한다. Gemini model은 명시 필수다.
- [x] 전체 비DB 테스트119건(새 Gemini 계약8건 포함), bootJar 통과(실패/건너뜀0). 혼합 OpenAI 생성/Gemini 검수, 독립 모델/키, JSON 보정/거절·부분/차단/빈 응답 거절, 재시도·전체 본문 timeout, Spring 단일 provider 빈을 확인했다. JAR 금지 설정0건.
- [x] application.properties/.env/keys.local.yml 직접 접근 없이 구현했다. 비밀 없는 config/keys.example.yml과 docs/ai-configuration.md에 설정 예시·확장 경계·오류·회귀 절차를 기록했다. 기존 사용자 DB·스토리지·프론트 변경 없음.
- [ ] 다음 시작점: 사용자가 기존 properties의 용도별 api-key/model/endpoint를 선택 제공자에 맞게 직접 교체하고 IDE 백엔드를 재시작한 뒤 생성 확인. 실제 외부 AI/계정별 모델 지원은 미검증이며 자동 실서비스 호출하지 않았다. 설정 방식은 docs/ai-configuration.md를 따른다.

## 2026-11-02 자료 삭제 외래키 500 수정·실제 검증

- [x] DB FK CASCADE에 의존하던 자료 삭제를 명시적인 자식 우선 삭제로 수정했다. MaterialContentDeletionService와 JPA Repository가 답안(풀이/문항 양쪽 참조)→풀이→문항→퀴즈 세트→요약→노트→페이지 텍스트를 삭제하며, 기존 소유 과목/Job 잠금·삭제 기록과 같은 트랜잭션 안에서만 실행한다. 과목 삭제도 같은 경계를 사용한다. material_study_day는 삭제하지 않는다. 기존 DB 제약/스키마는 변경하지 않았다.
- [x] 실제 onrelearn HTTP의 소유권403/CSRF403/RUNNING409·자식 삭제 중 강제 DB 실패500 시 전체 원본/삭제기록 롤백·완료/진행중 풀이/답안 포함 전체 삭제·다른 자료 유지·과거 달성 유지·파일 삭제503 후 동일 DELETE 재시도/기록 소멸·하위 자료가 있는 과목 DELETE200/퀴즈 통계 기여 제거를 확인했다. 합성 계정·자료·풀이·학습량·삭제 기록은 테스트 종료 시 정리했다.
- [x] -parameters 없는 IDE 컴파일 조건에서 실제 MySQL MaterialUploadMysqlTests6건·SubjectCreateMysqlTests1건 및 SubjectDeletionServiceTests 단위4건, bootJar 통과(실패/건너뜀0). JAR 금지 설정0건. Spring만 기존 설정을 자동 로딩했고 에이전트는 직접 읽지 않았다. 외부 저장소 대역 사용, 기존 사용자 데이터 변경 없음. 임시 Gradle 스크립트 제거.
- [ ] IDE 백엔드 완전 재시작 후 사용자 브라우저 삭제 조작. 외부 실서비스 저장소 연결은 별도 미검증이다. 앞선 자료 삭제 FK500 미완료 기록은 이 수정/시험 결과로 대체한다.

## 2026-11-02 업로드·요약·퀴즈 IDE 조건 검증

사용자 요청으로 -parameters 없이 실제 컴파일했으며 MaterialController 클래스의 MethodParameters 부재를 확인했다. 전체 컨트롤러 RequestParam/PathVariable/RequestPart/CookieValue 이름 명시 회귀 검사와 과목 MockMvc 각1건 통과. 검사 중 NoteController.page 누락1건을 추가 수정했다. Spring만 기존 설정을 자동 로딩하고 실제 MySQL/HTTP에서 MaterialUploadMysqlTests 대상5건(업로드 정상/권한/CSRF/중복/저장소 실패, DB 롤백·보상, 응답 유실, PDF 추출·상태·링크 전체 흐름, 메타데이터 수정), SummaryApiMysqlTests1건, QuizGenerationApiMysqlTests1건(일반·오답·취약 주제·실패 보존), QuizReadApiMysqlTests1건 총8건 실패/건너뜀0 통과. 외부 AI/저장소는 대역이며 실서비스 연결은 미검증이다. 기존 FK 규칙으로 합성 시험 정리가 실패한 부분은 이메일·자료 소유 형식을 검증한 테스트 전용 자식 우선 정리로 보강했고 이번 잔여 합성6계정도 보호된 정리 시험으로 제거했다. 임시 검사 파일/스크립트 제거, 기존 사용자 데이터·스키마 변경 없음. 별도로 자료 DELETE 시험은 저장소 실패503 기대 대신 DB FK 연관500을 재현해 미완료 결함으로 유지한다. 다음은 IDE 재시작 후 사용자 화면의 업로드/요약/퀴즈 조작 및 자료 삭제 FK 경계 수정이다.

## 2026-11-02 IDE 과목 500 매개변수 이름 수정

실행 중인 8080에서 신규 합성 계정의 과목 조회가 빈 쿼리/gradeId= 모두 500임을 재현했다. IDE bin/main 클래스는 MethodParameters가 없고 Gradle 클래스에는 있으며, 사용자가 제공한 IllegalArgumentException(매개변수 이름 reflection 정보 없음)과 일치했다. 과목/자료/노트/요약/퀴즈/통계 컨트롤러의 RequestParam 및 자료 PathVariable/RequestPart 이름을 명시해 컴파일 옵션 의존을 제거했다. workspace 빈 gradeId/subjectId는 기본 선택으로 처리하고 프론트도 빈 선택값을 제외한다. -parameters를 제거한 컴파일 상태에서 MockMvc1건 및 실제 MySQL SubjectCreateMysqlTests1건(권한·CSRF·입력·생성·조회·수정·삭제/빈 선택) 통과. 프론트 Node2건·대상 ESLint 통과. 최초 합성 정리의 FK RESTRICT 실패로 가려진 원인을 확인하고 승인 합성 계정에 한해 자식 우선 정리 가드를 보강했으며 해당 잔여2건도 조건 검증 후 정리했다. 라이브 검사용 합성 계정은 finally에서 정리했다. Spring만 기존 설정을 자동 로딩했고 비밀값·로그 원문은 출력하지 않았으며 임시 검사/초기화 스크립트를 제거했다. 기존 사용자 데이터·스키마 변경 없음. 다음은 IDE 백엔드 완전 재시작 후 사용자 실제 화면의 과목/파일/요약·퀴즈 조회 확인이다.

## 2026-11-02 로그인 503 실제 원인 확인

사용자 승인에 따라 임시 경량 Spring 컨텍스트가 IDE와 동일한 config location으로 기존 설정을 자동 로딩했다. 에이전트는 properties를 직접 읽거나 수정하지 않았고 DB 접속도 하지 않았다. 고정된 boolean 결과는 secretPresent=true, secretValid=false, ttlValid=true, tokenWorks=false이다. 현재 적용 키가 32 UTF-8 바이트 최소 조건을 충족하지 않아 토큰 발급이 실패하는 것으로 확인했다. 파일 존재/로딩 여부를 추측하던 앞선 설명과 달리 이번에는 Spring 적용 상태를 실제 검사했다. 키값/실제 길이/오류 원문은 출력하지 않았으며 임시 검사 소스는 제거했다. 다음은 사용자가 충분한 무작위 키로 직접 수정하고 백엔드를 완전히 재시작해 로그인하는 것이다. YAML 변경/추가 운영 코드 불필요.

## 2026-11-02 표준 실행 구조로 정리

최신 사용자 지침은 Spring의 properties 자동 로딩은 허용하고 에이전트의 직접 읽기만 금지한다는 것이다. 확인 결과 루트/백엔드 부모/백엔드 프로젝트의 기존 VS Code launch.json 모두 On Re:Learn (development properties) 실행 구성에서 올바른 작업 폴더와 Spring config location을 이미 지정한다. 불필요하게 추가했던 main 기본값 메서드·JWT 진단 컴포넌트와 해당 시험을 제거하고 표준 SpringApplication.run으로 복원했다. Gradle bootRun도 같은 Spring 설정 인수를 사용한다. 별도 환경변수나 YAML 전환은 필요 없다. 기존 로그인/JWT 대상 시험 통과. properties 내용은 열거나 수정하지 않았다. 실제 사용자 설정값 및 로그인 성공은 아직 미확인이다. VS Code Run and Debug에서 기존 On Re:Learn (development properties) 구성을 선택해 실행한다. 앞선 진단 옵션/기본값 추가 설명은 이 기록으로 대체한다.

## 2026-11-02 IDE 로컬 설정 자동 로딩 수정

최신 사용자 요청에 따라 main 실행에서 Spring ConfigData 기본값을 지정했다. spring.config.name=onrelearn,application이며 백엔드 폴더 또는 L_A 루트 작업 디렉터리의 src/main/resources를 추가 검색한다. 로컬 application.properties는 Spring만 자동 로딩하고 에이전트는 직접 읽거나 수정하지 않는다. IDE에 별도 환경변수 없이 기존 파일을 사용할 수 있으며 YAML 전환은 필요 없다. bootRun의 개별 파일 경로 인수를 제거해 main과 같은 규칙을 사용한다. 명시적 실행 설정은 기본값보다 우선하므로 기존 IDE spring.config.location/name/additional-location 옵션이 있다면 확인해야 한다. 실제 파일을 사용하지 않는 임시 합성 YAML 로딩 우선순위1건·JWT 바인딩2건·bootJar 통과, JAR 금지 설정0건. 실제 사용자 IDE 재시작/로그인은 아직 미검증이다. 기존 DB/스토리지 변경 없음.

## 2026-11-02 IDE 실행 JWT 설정 진단

사용자는 IDE에서 OnReLearnApplication을 직접 실행한다. Gradle bootRun의 config location 인수는 IDE main 실행에 적용되지 않는다. application.properties는 Gradle 리소스/JAR에서 제외되며 사용자 정의 onrelearn.yml도 기본 이름이 아니므로 실제 IDE 클래스패스/실행 옵션에 따라 로딩이 달라진다. 따라서 파일 존재만으로 JWT 바인딩을 단정할 수 없다. onrelearn.auth.diagnostics.enabled=true 옵션을 추가하면 Spring이 이미 바인딩한 secretValid/ttlValid만 출력하는 진단을 추가했다. 키값·키길이·설정 경로는 출력하지 않는다. 합성 설정 바인딩/누락 진단 및 JwtTokenServiceTests 통과. 실제 사용자 IDE 프로세스의 상태는 아직 미확인이다. YAML 전환은 필수가 아니며 IDE 환경변수 ONRELEARN_AUTH_JWT_SECRET/ONRELEARN_AUTH_JWT_TTL로 해당 속성을 직접 전달할 수 있다. 금지 설정 파일은 접근하지 않았다.

## 2026-11-02 회원가입 400 입력 안내 보완

사용자의 가입 400 보고를 확인했다. 프론트 JSON email/password 및 CSRF 계약은 서버와 일치하지만 기존 noValidate 폼이 이메일 형식/254자 및 공백 비밀번호를 사전 검사하지 않았다. 가입 규칙을 바꾸지 않고 React 입력 안내와 서버 필드별 안전 400 메시지를 추가했다. 비밀번호는 trim/변환 없이 원문으로 전송한다. 회원가입 MockMvc 4건(정상·중복409·입력400·DB500), 프론트 Node2건·대상 ESLint·Vite build 통과. 기존 시험의 DB 예외 전파 기대를 현재 공통 안전500 응답 계약에 맞췄다. 실제 사용자 실패 요청의 응답 본문은 아직 없어 원인을 확정하지 않았으며 실제 DB 가입 성공은 미검증이다. 금지 설정 파일·DB에 접근하지 않았다. 다음은 재시작 후 화면의 구체 오류/Network Response로 실제 실패 원인을 확인한다.

## 2026-11-01 통합 검증 재개 시도

사용자가 금지 설정 파일에 직접 접근하지 않는 방식으로 통합 검증 재개를 요청했다. 현재 실행 프로세스의 SPRING_DATASOURCE_URL/USERNAME/PASSWORD는 값 출력 없이 존재 여부만 확인했으며 모두 미설정이다. 따라서 MySQL 시험은 실행하지 않았다. 실제 HomePage를 사용하는 scripts/home-delete-fixture.html 및 JSX 합성 화면을 추가해 과목 삭제 모달/CSRF/삭제 후 재조회를 준비했다. 대상 ESLint는 통과했지만 Vite 5182에 대한 앱 브라우저 접속이 ERR_CONNECTION_TIMED_OUT으로 실패해 3초 버튼/취소/확인 직접 검증은 미완료다. 임시 Vite 프로세스는 종료했다. 설정 파일·기존 DB·스토리지에는 접근하지 않았다. 다음은 실행 프로세스에 DB 연결값을 전달하고 대상 mysqlTest를 실행하며, 브라우저에서 로컬 서버 접근이 가능한 환경에서 합성 화면을 조작하는 것이다.

## 2026-10-29 자료 포함 과목 삭제

`DELETE /api/subjects`는 소유 과목을 잠그고 소속 자료의 추출 Job을 확인한다. RUNNING이 있으면 전체 409, 없으면 파일 삭제 기록·Job·자료·과목을 한 DB 트랜잭션으로 처리한다. 자료의 노트·요약·퀴즈·문항·풀이·답안은 DB FK cascade로 삭제되며 과거 `material_study_day` 건수는 보존한다. 커밋 뒤 PDF 삭제에 실패하면 503과 기존 미완료 파일 삭제 목록을 통해 재시도한다. 대상 단위 3건·`compileJava` 통과. 실제 MySQL/JPA validate·HTTP는 개발 완료 뒤 검증한다. 새 SQL/의존성·실제 데이터 변경 없음.

## 2026-10-28 홈 최근 퀴즈 조회

`GET /api/quiz/recent`가 로그인 사용자의 소유 자료에 연결된 최근 풀이 5건을 마지막 활동순으로 반환한다. 진행 중·제출·채점 상태와 세트/풀이 ID를 공개해 홈에서 적절한 화면으로 이동하며 `no-store`를 적용했다. `compileJava` 통과. MySQL/HTTP 권한·정렬·삭제 cascade 통합 검증은 미완료이고 SQL·의존성·기존 데이터 변경은 없다.

## 2026-10-24 퀴즈 삭제 통계 제외 검증 보강

합성 `QuizSetDeletionApiMysqlTests`에 선택 세트 삭제 전후 `GET /api/stats/material`의 완료 풀이 수 1→0 검증을 추가했다. `compileTestJava` 통과. 실제 MySQL/HTTP 실행은 연결 환경이 없어 미완료이고 기존 데이터 변경 없음. 다음은 허용된 DB 환경에서 삭제 cascade·롤백과 통계 제외, 자료 활동·노트 원자성 시험을 실행한다.

## 2026-10-24 자료 목표의 저장 날짜 기준 정정

자료 목표 집계가 `firstAt` 시각을 다시 날짜로 환산하지 않고 `material_study_day.study_date`의 서울 날짜를 그대로 사용하도록 수정했다. 저장된 학습 사실과 일·주 목표의 날짜 기준이 일치한다. 대상 단위 시험·`bootJar` 통과, JAR 금지 설정 0건. 실제 MySQL/JPA validate·HTTP와 브라우저 검증은 연결 가능한 환경에서 진행한다. DB·기존 사용자 데이터는 변경하지 않았다.

## 2026-10-23 자료 삭제 후 목표 보존 검증 보강

`MaterialStudyApiMysqlTests`에 자료 삭제 전후 `GET /api/goals/material-progress`의 오늘 학습 1건 유지 검증을 추가했다. `compileTestJava` 통과. 실제 MySQL/HTTP는 연결 환경이 없어 미실행이고 기존 데이터 변경 없음. 다음은 허용된 DB 환경에서 자료 활동·노트 원자성·퀴즈 삭제 합성 시험을 실행한다.

## 2026-10-23 노트·자료 활동 원자성 시험 보강

합성 `NoteSaveMysqlTests`에서 노트 저장과 자료 활동의 같은 날 한 행, 활동 저장소 실패 시 앞선 노트 수정 롤백을 검증하도록 보강했다. `compileTestJava` 통과. 실제 MySQL/HTTP는 연결 환경이 없어 미실행이며 기존 데이터 변경 없음. 다음은 허용된 DB 환경에서 이 시험을 실행한다.

## 2026-10-23 자료 활동 MySQL/HTTP 검증 준비

`MaterialStudyApiMysqlTests`에 합성 계정의 인증·CSRF·입력·소유권, 동시 최초 기록 한 행, 자료 삭제 뒤 활동 사실과 최근 카드의 링크 제거를 검증하는 경계를 추가했다. `compileTestJava` 통과. 연결 환경변수가 없어 실제 MySQL/HTTP 실행은 미완료이며 합성 데이터나 기존 DB는 변경하지 않았다. 다음은 허용된 환경에서 이 시험과 퀴즈 삭제 시험을 실행한다.

## 2026-10-23 선택 실행 MySQL 시험 설정

`mysqlTest` 및 보호된 합성 시드·정리 Gradle 작업은 금지 설정 파일 경로를 인수로 전달하지 않는다. Spring은 패키지의 비밀 없는 `onrelearn.yml`을 로딩하며 접속·인증 값은 실행 프로세스 환경변수로 받는다. `gradle help` 구성 확인 통과. 현재 실행 환경에 DB 연결 변수가 없어 실제 MySQL 시험은 보류했고 DB·실제 사용자 데이터는 변경하지 않았다. 다음은 허용된 환경변수에서 활동 테이블 JPA validate/HTTP와 퀴즈 삭제 cascade를 확인한다.

## 2026-10-23 최근 자료 학습 조회

`GET /api/activity/material-study/recent`는 사용자별 마지막 활동순 상위 20개를 no-store로 반환한다. 자료 소유권 조회가 성공한 행에만 과목 ID를 넣어 기록 화면 링크를 허용하고, 삭제된 자료는 `삭제된 자료`·null 과목 ID로 반환한다. 저장된 과거 학습 행은 지우지 않는다. 단위 1건·`bootJar`·JAR 금지 설정 0건 확인. 실제 MySQL/JPA validate·HTTP·자료 삭제 후 집계 확인은 후속이다.

## 2026-10-22 자료 학습 활동 기록

추가형 `material_study_day`는 사용자×자료×서울 날짜에 한 행이며 자료 FK를 두지 않아 자료 삭제 후 과거 달성 수가 유지된다. `POST /api/activity/material-study`는 PDF 페이지 표시 성공 시 호출되며 서버 시간·인증 주체와 과목 잠금/소유권 재확인으로 저장한다. 노트 저장 성공 시 같은 트랜잭션에서 기록한다. `GET /api/goals/material-progress`는 월~일 합계와 목표 진행률을 no-store로 반환한다. 단위 2건·`bootJar` 통과. 실제 MySQL/JPA validate·HTTP/CSRF·동시 기록/삭제 검증은 허용된 설정 전달 후 수행한다. 다음은 최근 활동 카드의 조회 전용 20건 제한과 삭제 자료 링크 제외다.

## 2026-10-22 자료 학습 목표 집계 계약

`MaterialGoalProgress`는 서울 날짜별 같은 자료의 여러 학습을 1건으로 세고 주간 값을 월~일의 날짜별 건수 합계로 계산한다. 목표 0은 0%, 그 외 반올림·100% 상한을 적용한다. 단위 시험 1건과 `bootJar` 통과, JAR 금지 설정 0건. 아직 자료 활동 저장 테이블/API 및 PDF 표시·노트 저장 연계는 없으므로 화면 진행률에 연결하지 않았다. 다음은 일별 행 모델과 저장 경계이며 기존 기록 이관/삭제는 하지 않는다.

## 2026-10-22 퀴즈 세트 삭제 API

`DELETE /api/quiz/quiz-sets`는 인증된 소유자의 `{quizSetId}`를 받아 과목 행 잠금과 소유권 재확인 후 선택 세트를 삭제한다. DB FK cascade가 그 세트의 문항·진행 중/완료 풀이·답안을 같은 트랜잭션에서 제거한다. 성공은 `{ok:true}`와 no-store, 잘못된 입력 400·타인/없는 세트 403·DB 실패는 공통 안전 500이다. 다른 세트와 자료/PDF/노트/요약은 대상이 아니다. 단위 시험 1건·`bootJar` 통과, 금지 설정 JAR 항목 0건. 실제 MySQL HTTP 시험은 컴파일됐으나 기존 `mysqlTest`의 금지 설정 경로 전달 방식 때문에 미실행이다. 다음은 허용된 설정 전달로 실제 cascade·권한·CSRF·롤백 검증을 완료하고 B9/D3 자료 활동을 연결한다. 새 SQL·의존성·실제 데이터 변경 없음.

## 2026-10-21 SUBMITTED 오답 조회 단위 검증

`QuizWrongReviewServiceTests`에서 SUBMITTED 풀이의 오답 문항 ID·주제 조회와 답안 저장소 실패 전파를 확인했다. 대상 Gradle test 1건 통과, DB·앱 로직·SQL·의존성·외부 호출 변경 없음. 실제 Spring·React 전체 E2E는 설정 전달 방식이 허용될 때 검증한다. 다음은 독립 미완료를 확인하며 B9/D3·세트 영구 삭제 정책은 보류한다.

## 2026-10-21 오답 복습 검증 준비

`scripts/synthetic-review-ai.mjs`에 `--wrong-review` 모드와 별도 호출 수를 추가했다. `node --check` 및 로컬 합성 HTTP에서 기본 5/3/2 문항·호출 1회를 확인했다. 실제 Spring JAR 실행 시 외부 `application.properties` 경로를 지정하는 문서상 옵션이 자동 승인 검토에서 거부되어 오답 복습 전체 E2E는 진행하지 못했다. 해당 파일 직접 접근은 없었고 합성 계정은 보호된 정리 시험으로 삭제했다. 임시 포트도 종료했다. 앱 로직·SQL·의존성 변경 없음. 다음은 허용된 실행 설정 방식 확인 후 실제 오답 복습 흐름 또는 독립 기능 전환이다.

## 2026-10-21 일반 퀴즈 실제 통합 검증

기존 `scripts/synthetic-review-ai.mjs`에 `--general` 모드를 추가해 기본 6/3/1 문항을 반환한다. 실제 Spring JAR·onrelearn 합성 계정에서 과목 자료의 GENERAL 생성, 답안 10건 저장·복원·제출·100점 채점, 목록·통계 반영을 React 브라우저로 확인했다. 로컬 AI 요약·퀴즈 요청은 각 1회이며 실제 외부 AI는 미검증이다. fixture 문법 검사와 보호된 시드·정리 시험이 통과했고 합성 계정·종속 행 및 임시 서버를 정리했다. 앱 코드·SQL·의존성 변경은 없다. 다음은 오답 복습 실제 전체 흐름 등 독립 미완료 검증이다. B9/D3와 영구 삭제 정책은 미확정이다.

## 2026-10-21 기록/PDF/노트 실제 통합 검증

합성 계정 시드에 `-Pe2eRecordPdf=true` 옵션을 추가해 유효한 사용자·자료별 PDF 경로와 2페이지 추출 텍스트만 만들었다. 로컬 `scripts/synthetic-record-storage.mjs`가 서명 응답을 제공하고, 실제 Spring JAR의 자료·서명·노트 API를 React와 연결해 페이지 이동 전 저장, 페이지별 복원, 새로고침과 링크 재발급을 확인했다. 실제 저장소 호출은 미검증이다. 기존 합성 정리 시험은 이 정확한 경로만 추가 허용한다.

`compileTestJava`와 fixture 문법 검사, 보호된 `cleanupSyntheticE2e`가 통과했고 합성 계정·종속 행을 정리했다. 임시 서버는 종료됐으며 JAR 금지 설정 항목은 0건이다. 앱 로직·SQL·의존성 변경은 없다. 다음은 일반 퀴즈 전체 통합 검증과 첫 필수 미완료 재점검이다. 활동 B9/D3와 영구 삭제 정책은 미확정이다.

## 2026-10-20 퀴즈 풀이 미저장 새로고침 보호 확인

기존 합성 Vite 퀴즈 fixture에서 저장된 첫 번째 보기를 두 번째 보기로 바꾼 뒤 새로고침을 요청했다. 미저장 입력과 화면이 그대로 유지됐고 저장 요청은 발생하지 않았다. 답안을 저장한 뒤 같은 새로고침은 정상 진행되어 fixture의 초기 보기로 돌아갔다. 이 브라우저에서는 beforeunload 확인 대화상자 자체가 노출되지 않아 문구와 사용자의 확인/취소 선택은 직접 검증하지 못했다. 앱 코드·DB·외부 서비스 변경 없이 시험 탭과 서버를 종료했다. 다음 시작점은 실제 Spring·React 기록/PDF 통합 흐름을 확인하는 것이다. 자료 활동 B9/D3와 세트 영구 삭제 정책은 계속 보류한다.

## 2026-10-20 과목→통계→취약 주제 퀴즈 실제 통합 검증

실제 Spring JAR·MySQL `onrelearn`·React/Vite를 합성 계정과 로컬 AI 응답 fixture로 연결했다. 과목 목록에서 합성 자료의 통계로 이동해 완료 풀이 2회·0점·주제 `분수`의 오답 2건을 확인했다. 화면에서 WEAK_TOPIC 퀴즈 10문항(객관식 5·OX 3·단답 2)을 생성해 답안을 저장했고, 새로고침 후 복원하여 제출했다. 결과 100점·10문항 정답, 재조회 통계의 완료 풀이 3회·채점 답안 10/12·취약 주제 정답 10/오답 2를 확인했다. 과거 오답 2건 때문에 해당 주제가 계속 취약 주제로 표시되는 것은 현재 집계 규칙과 일치한다.

로컬 fixture 호출은 요약 1회·퀴즈 1회(취약 주제 1회)였다. 전용 시드와 보호된 정리 시험이 통과했고 합성 계정과 종속 데이터를 정리했다. 브라우저 탭과 임시 서버는 종료됐다. 검증용 시드 옵션과 로컬 AI fixture만 추가했으며 앱 로직·SQL·의존성·실제 사용자 데이터·외부 서비스는 변경하지 않았다. 실제 외부 AI 연결은 미검증이다. 다음 시작점은 실제 Spring·React 일반 퀴즈/기록 PDF 흐름 중 남은 통합 검증을 확인하고 첫 필수 미완료를 진행하는 것이다. 자료 활동 B9/D3와 퀴즈 세트 영구 삭제는 정책 결정 전 보류한다.

## 2026-10-20 실제 마이페이지 통합 검증

실제 Spring JAR·Vite·onrelearn 합성 계정으로 로그인→계정 화면→새로고침→로그아웃을 확인했다. 오늘 완료 1건·8일 전 완료 1건·진행중 1건에서 총 완료 2회, 일/주 진행률의 완료 수 1회, 과목별 0%, 7일 날짜와 최근 기록의 0/100 표시·정렬이 새로고침 뒤에도 복원됐다. 채점 답안이 없는 합성 데이터이므로 0보다 큰 정확도는 이번 브라우저 시험 범위가 아니다.

실행 중 포트 충돌과 인증 설정 누락을 확인했다. 임시 포트와 프로세스 한정 합성 JWT 환경변수로 해결했고 실제 키값을 읽거나 출력하지 않았다. 보호된 시드·정리 시험이 통과했으며 합성 계정과 연관 풀이를 정리했다. 시험 탭을 닫고 임시 서버 프로세스가 남지 않았음을 확인했다. 앱 코드·SQL·의존성·실제 사용자 데이터·외부 서비스 변경 없음. 다음 시작점은 실제 Spring·React 과목 목록→자료 통계→취약 주제 생성/풀이 통합 검증이다. 자료 활동 B9/D3와 퀴즈 세트 영구 삭제는 결정 전 보류한다.

## 2026-10-19 마이페이지 조합 조회 API

`MyPageService`는 목표, 일·주 퀴즈 진행률, 퀴즈 성과를 REPEATABLE_READ 읽기 트랜잭션에서 조합한다. 인증 전용 `GET /api/mypage`는 이메일과 이 세 결과만 `no-store`로 반환한다. 자료 활동 누적은 B9/D3 결정 전 계산하지 않는다.

실제 합성 HTTP/MySQL `MyPageApiMysqlTests` 1건에서 비로그인 401, 빈 계정 기본값과 미작성 목표 행 보존, no-store, DB 실패 안전 500과 재조회를 확인하고 합성 계정을 정리했다. `bootJar` 통과, JAR 금지 설정 0건. SQL·의존성·외부 호출 변경 없음. 다음은 실제 Spring·React 합성 완료 풀이의 마이페이지 통합 확인이다.

## 2026-10-19 마이페이지 퀴즈 성과 읽기 서비스

`MyPageQuizService`는 소유 과목·완료 풀이·채점된 답안 수를 한 REPEATABLE_READ 읽기 트랜잭션에서 projection으로 조회한다. 사용자의 풀이 ID와 연결 과목 소유자를 함께 제한하고 정답/해설 엔티티는 로딩하지 않는다. 실제 `onrelearn` 합성 MySQL 1건에서 완료/진행중·타인 풀이와 답안 분리, 빈 과목, 정확도, 7일 추이, 빈 인증 ID 401을 확인했고 합성 행은 롤백됐다. `bootJar` 통과, JAR 금지 설정 0건. 다음은 인증 전용 조합 API와 React 화면이다. 자료 활동 누적 B9/D3는 보류한다.

## 2026-10-19 마이페이지 퀴즈 성과 집계 기반

`profile/MyPageQuizOverview`는 DB를 읽거나 수정하지 않고 완료 풀이·채점 답안 수·소유 과목 입력으로 총 퀴즈 수/정확도, 과목별 점수율, Asia/Seoul 최근 7일 추이, 최근 퀴즈 카드와 학습 기록을 만든다. 원본 시작 시각 상위 8건 선택과 카드 완료 시각 재정렬·학습 기록 상위 5건 규칙을 유지한다. 자료 활동 집계는 B9/D3 확정 전 포함하지 않았다. 단위 2건과 `bootJar` 통과, JAR 금지 설정 0건. 다음은 사용자별 읽기 전용 Repository/Service와 실제 MySQL 검증, 이후 조합 API/UI다. SQL·의존성·외부 호출 변경 없음.

## 2026-10-19 목표 진행률 실제 브라우저 검증용 합성 시드

`seedSyntheticGoalProgressE2e`는 명시적 `e2e-UUID@example.invalid` 계정만 생성하는 옵트인 시험이다. 오늘 완료·8일 전 완료·진행중 풀이를 만들어 실제 Spring·Vite 계정 화면에서 기본 10%/1%, 목표 수정 후 0%/100%와 새로고침 복원을 확인했다. 정리 시험은 정확한 합성 PDF 메타데이터만 자료 FK 앞에서 제거하고 같은 트랜잭션으로 계정을 삭제하도록 보강했다. 첫 정리 실행의 FK 실패 뒤 재실행 성공 및 연관 풀이 0건을 확인했다. 검증 실패 시 롤백·확인 후 커밋을 적용한 최종 정리 시험도 별도 합성 계정으로 재검증했다. 합성 계정·임시 서버는 정리됐고 실제 데이터·외부 서비스 변경은 없다. 다음은 마이페이지 조합 조회의 퀴즈 성과·최근 기록이다. 자료 활동 B9/D3는 보류한다.

## 2026-10-15 퀴즈 목표 진행률 조회 API

인증 사용자 전용 GET /api/goals/quiz-progress를 연결했다. 서버 현재 시각과 Asia/Seoul 날짜 경계로 SUBMITTED/GRADED 완료 풀이 수·일/주 목표 진행률을 계산하고 no-store로 반환한다. 자료 활동량은 아직 포함하지 않는다.

실제 onrelearn 합성 HTTP/MySQL GoalProgressApiMysqlTests 1건에서 비로그인 401, 빈 계정의 네 값 0, no-store, DB 오류의 안전한 500과 회복을 확인했다. 합성 계정은 정확한 이메일로 정리했다. bootJar 통과, JAR 금지 설정 항목 0건이다. 기존 사용자 데이터·SQL·의존성·외부 서비스 변경 없음. 다음 시작점은 React 계정 화면에 퀴즈 진행률을 연결하고 목표 수정 후 재조회·빈 계정/오류/새로고침을 검증하는 것이다. 마이페이지 조합 조회와 자료 활동 B9/D3, 실제 전체 E2E는 여전히 미완료다.
## 2026-10-15 마이페이지 퀴즈 목표 진행률 조회 기반

QuizGoalProgress와 GoalProgressService를 추가했다. 사용자별 SUBMITTED/GRADED 풀이의 gradedAt→submittedAt→startedAt 완료 시각만 조회하고 Asia/Seoul 자정·월요일 주 시작에 따라 일/주 완료 풀이 수를 센다. 원본 진행률 규칙(목표 0이면 0%, 반올림·최대 100%)을 적용하며 자료 활동량·기존 기록은 변경하지 않는다. 아직 HTTP 조합 조회와 React 진행률 화면은 미연결이다.

서울 날짜·주 경계/0 목표/100% 상한 단위 1건과 사용자 분리·완료/진행중 구분·기본 목표 합성 MySQL 1건, bootJar를 통과했다(실패/건너뜀 0). 합성 DB 행은 시험 트랜잭션으로 롤백했고 JAR 금지 설정 항목 0건이다. 새 SQL·의존성·외부 호출은 없다. 다음은 마이페이지 조합 API에서 이 조회를 사용하고 인증/빈 계정/오류·실제 집계 경계를 검증한다. 자료 활동 D3와 기존 행 삭제·이관은 확정 전 적용하지 않는다.
## 2026-10-15 목표 화면 실제 Spring·React 통합 검증

실제 onrelearn DB에 합성 계정 한 건을 만들고 Spring JAR(로컬 포트 18080)과 Vite 프록시를 연결해 회원가입→로그인→계정 목표 기본값 10/70/3/15 조회→0/2/0/5 저장→새로고침 후 값 복원→로그아웃을 브라우저에서 확인했다. 비로그인 목표 조회는 401이었다. 전용 cleanupSyntheticE2e 보호 가드로 합성 계정 한 건을 삭제했고 시험이 성공했다. 임시 탭과 서버는 종료됐으며 실제 사용자 데이터·외부 서비스·코드는 변경하지 않았다.

다음 시작점은 마이페이지 조합 조회와 일·주 퀴즈 진행률의 필수 구현이다. 자료 활동량은 원본의 최근 20건 upsert/삭제 목록을 누적 학습량으로 재사용하는 B9 문제가 있어 D3(반복 학습·이관·보존 단위) 확정 전 임의 집계하거나 기존 활동을 삭제하지 않는다. 실제 과목→통계→취약 주제 복습 전체 E2E와 퀴즈 세트 삭제 승인 항목은 계속 남아 있다.
## 2026-10-15 사용자별 목표 조회·저장 API

소유자 전용 GET/PUT /api/goals를 연결했다. 미작성 사용자는 10/70/3/15 기본값을 조회만 하고 행을 만들지 않으며, 저장은 로그인 사용자 ID만 사용한다. PUT은 네 개의 0 이상 정수 필드를 정확히 요구하고 사용자 행 잠금 아래 최초 생성·재수정을 직렬화한다. 응답은 goal 객체와 no-store이며 내부 DB 오류는 공통 안전 500으로 처리한다.

실제 onrelearn 합성 HTTP/MySQL GoalApiMysqlTests 1건에서 기본값/0/수정·타인 격리·입력400·미인증401·CSRF403·DB 읽기/쓰기 실패500 및 기존 목표 보존을 확인했다. mysqlTest 1건·bootJar 통과(실패/건너뜀0), JAR 금지 설정 0건이다. 합성 계정과 목표는 시험 종료 후 정리했고 기존 사용자 데이터·외부 서비스·새 의존성은 건드리지 않았다.

다음 시작점은 React 목표 조회·수정 API와 마이페이지 목표 화면 연결이다. 활동 기록/D3와 실제 Spring·React 통합 흐름, 퀴즈 세트 삭제 승인 대상은 계속 미완료다.

## 2026-10-15 사용자별 Goal 모델·SQL

Goal 엔티티·Repository와 추가형 JDBC goal 테이블을 연결했다. 사용자당 한 행 unique/FK와 사용자 삭제 cascade, 네 목표의 0 이상 CHECK를 적용하고 원본 기본값인 일/주 퀴즈 10/70, 자료 3/15를 유지했다. 목표 수정은 음수를 거부하고 생성 시각을 보존한다. 활동 기록·기존 목표/사용자 데이터는 변경하지 않았으며 조회·저장 API와 화면은 아직 미연결이다.

실제 onrelearn 합성 MySQL 시험 GoalMysqlTests 1건에서 JPA validate, 기본값/0·수정, 음수 거부, unique/FK/CHECK와 합성 사용자 삭제 cascade를 확인했다. 시험 변경은 트랜잭션에서 롤백했고 mysqlTest 대상 1건·bootJar 통과(실패/건너뜀0), JAR 금지 설정 0건이다. 외부 서비스 호출·새 의존성 없음.

다음 시작점은 소유자 전용 Goal 조회·저장 서비스/API와 입력·권한·DB 실패 검증이다. 활동 누적/D3 정책과 실제 Spring·React 전체 E2E는 계속 미완료이며 기존 데이터 삭제 정책은 승인 전 적용하지 않는다.

## 2026-10-13 자료 통계 GET API

소유자 전용 GET /api/stats/material?materialId=...를 MaterialStatsController에 연결했다. 공통 인증 경계와 MaterialStatsService 소유권 확인을 사용하며 성공 응답은 자료 메타데이터·집계·추이·취약 주제·추천을 직접 반환하고 Cache-Control: no-store를 지정한다. 답안 본문·정답·해설은 통계 응답에 포함하지 않는다.

실제 onrelearn 합성 HTTP/MySQL 시험 1건에서 미인증401, ID 누락·공백400, 없는 자료·타인 자료403, 빈 기록200/NONE, 완료 풀이의 0점·유형·목적·추이·비공개 필드 차단, DB 조회 실패500/내부 상세 비노출, 재조회 시 기존 기록 유지와 no-store를 검증했다. 합성 계정·자료는 시험 후 정리했다. mysqlTest --tests com.onrelearn.MaterialStatsApiMysqlTests bootJar 통과(실패/건너뜀0), JAR 금지 설정0건. SQL·새 의존성·실제 외부 호출 없음.

다음 시작점은 React 자료 통계 GET API 계약과 통계 화면/취약 주제 복습 진입 연결이다. 실제 Spring·React 전체 E2E, 활동/D3·목표·삭제 승인 항목은 남아 있다.

## 2026-10-12 자료 통계 소유권 조회 서비스

MaterialStatsService.get과 MaterialStatsRepository를 연결했다. 자료 소유권을 먼저 확인하고 동일 REPEATABLE_READ 읽기 트랜잭션에서 해당 사용자/자료의 SUBMITTED·GRADED 풀이를 조회한다. 전용 projection은 점수·목적·문항 유형/주제·채점만 가져오며 답안 본문·정답·해설은 읽지 않는다. LEFT JOIN으로 답안 없는 완료 풀이도 유지하고, 문항이 같은 세트에 속하는지 제한한다. 자료 없음/타인 자료403, 미인증401, 빈 ID400이며 조회 실패는 공통 예외 처리로 전달하고 빈 통계로 숨기지 않는다. HTTP Controller는 아직 미연결이다.

실제 onrelearn 합성 MySQL 시험1건에서 JPA validate·빈 기록·GRADED/SUBMITTED·null 채점/점수·답안 없는 완료·타인/진행중/다른 자료 제외·메타데이터·B10 최신 점수·조회 실패 주입 후 원본/재조회 일치를 확인했다. 합성 행은 시험 트랜잭션으로 롤백했다. mysqlTest --tests com.onrelearn.MaterialStatsMysqlTests bootJar 통과(실패/건너뜀0), JAR 금지 설정0건. SQL·새 의존성·실제 외부 호출 없음.

다음 시작점은 GET /api/stats/material Controller와 HTTP 입력·인증·권한·no-store·빈 통계·DB 실패 안전500 검증이다. 이후 React 통계 조회/취약 주제 복습 UI를 연결한다. 활동/D3·목표·삭제 승인·전체 E2E는 남아 있다.

## 2026-10-12 자료 통계 전체 순수 집계 및 B10 회귀

MaterialStats.build에 material/summary/understanding/typeStats/purposeStats/trend/weakTopics/recommendations 응답 집계를 추가했다. SUBMITTED/GRADED 전체 풀이의 연결된 지원 유형 답안만 집계하고 true 정답·false/null 통계 오답, null 점수/만점 0, 원점수 평균과 최고점, 유형·목적 3종 및 기존 이해도/추천 순서를 보존한다. 빈 기록 NONE과 완료되었지만 답안 없는 LOW를 구분한다. 입력/응답 컬렉션은 복사하거나 읽기 전용으로 제공하며 저장 데이터는 변경하지 않는다.

B10의 전체 제출 시각 오름차순 정렬을 적용해 세트 생성 순서와 무관하게 latestScore를 선택한다. 제출 시각이 없는 이전 기록은 날짜 있는 기록보다 먼저, 같은 시각은 attemptId 오름차순으로 정렬한다. 약한 주제 동률 순서는 기존 입력 순서를 유지한다. 신규 단위4건에서 혼합 목적/유형·모든 완료 풀이·미완료 제외·null/누락 참조·원점수/환산율 차이·추천 순서/5개 제한·85/70/50 경계·B10/동률/원본 보존을 확인했다. 연결된 MaterialTopicStats 회귀2건도 통과했다.

검증: test --tests com.onrelearn.MaterialStatsTests --tests com.onrelearn.MaterialTopicStatsTests bootJar 성공(6건, 실패/건너뜀0), JAR 금지 설정 파일0건. DB·SQL·새 의존성·외부 호출 없음. 소유권 검증/실제 완료 풀이 조회 및 API는 아직 미연결이다.

다음 시작점은 자료 소유권과 사용자별 완료 풀이를 확인하는 조회 서비스/Repository 연결 및 실제 합성 MySQL 정상·빈 기록·권한·조회 실패/원본 보존 검증이다. 이후 GET 자료 통계 API와 취약 주제 UI를 연결한다. 활동/D3·목표·삭제 승인·전체 E2E는 남아 있다.

## 2026-10-12 자료 통계 계약 분석과 취약 주제 집계

원본 features/stats/server/materialStatsService.ts·materialStatsServiceCore.ts, lib/stats.ts·types.ts, app/api/stats/material/route.ts와 통계 UI를 확인했다. 자료 소유권 검사 후 같은 자료의 사용자 SUBMITTED/GRADED 모든 풀이를 집계한다. API는 GET /api/stats/material?materialId=..., 미인증401·빈ID400·권한403이며 새 구현은 공통 advice의 안전한500/no-store를 사용한다. 기존 원본의 내부 오류 문자열500 노출은 이식하지 않는다.

응답은 material/summary/understanding/typeStats/purposeStats/trend/weakTopics/recommendations다. 완료 풀이가 없으면 숫자0·NONE·빈 trend/weakTopics·첫 풀이 안내이며 조회 실패를 빈 통계로 취급하지 않는다. 완료 풀이가 있지만 답안이 없으면 totalAttempts는 증가하고 정답률0/LOW다. 문항 수는 전체 문항 수가 아니라 문항에 연결된 mcq/tf/short 답안 수이며 true만 정답, false/null은 오답이다. 원본 문항이 없는 답안은 제외한다. 원본 점수/maxScore null은 통계에서0, 평균은 점수 자체의 산술평균(재환산 아님), 비율/평균은 소수1자리다. 유형3개·목적3개는 빈 항목도 유지하고 원본 미지 목적은 GENERAL로 분류한다. 원본 데이터는 변경하지 않는다.

취약 주제는 trim·빈 주제 '기타 개념'·대소문자 유지, 답안2개 이상이고 정답률60% 미만 또는 오답2개 이상인 주제다. 반올림한 정답률 오름차순→오답 수 내림차순→동률 입력순으로 최대5개. UI는 받은5개를 실제 오답률 순으로 다시 정렬하고 추천은 서버 최대5개 중3개를 표시한다. 취약 주제 이름을 복습 생성 API로 전달한다. 이해도 경계85/70/50, 추천 순서는 전체→short→mcq→tf→취약 주제3개, 중복 제거 후5개다. 과목 통계 버튼도 원본에서는 첫 자료 통계를 여는 모달이며 과목 전체 합계 API가 아니다.

B10: 원본 trend/latestScore는 세트 생성순/풀이 시작순 순회여서 전체 제출 시각순이 아니다. 기존 계획의 제출 시각 전체 정렬을 후속 전체 집계에 적용한다. null 제출 시각과 동률의 명시적 정렬 규칙도 구현 시 기록/검증한다. D3 활동량 집계와 이 퀴즈 답안 통계는 별도이며 삭제 정책 승인 없이 기록을 정리하지 않는다.

MaterialTopicStats.weakTopics 순수 함수를 추가했다. 소유자/완료 풀이에서 선택한 답안 행을 받는 계약이며 DB 조회는 아직 없다. 단위2건에서 공백/Unicode·null 채점·미지원 유형·대소문자·최소표본/60%·오답2개·정렬 동률/5개 제한·입력 보존/불변 결과를 검증했다. test --tests com.onrelearn.MaterialTopicStatsTests bootJar 통과, JAR 금지설정0건. DB·SQL·새 의존성·외부 호출 없음.

다음 시작점은 자료 통계 전체 순수 응답 집계(점수/유형/목적/이해도/추천/제출순 추이 및 B10 회귀), 이후 소유권 있는 완료 풀이 조회 서비스/API 연결이다. 취약 주제 UI·활동/D3·목표·삭제 승인·전체 E2E는 미완료다.

## 2026-10-12 복습 생성 HTTP API

POST /api/quiz/quiz-sets에 WRONG_REVIEW/WEAK_TOPIC을 연결했다. purpose 허용값·원본 ID 문자열·주제 배열·명세 정수를 검사하고 GENERAL의 복습 인자 혼용 및 WEAK_TOPIC의 원본 풀이 혼용을400으로 거절한다. 오답은 원본 ID가 필요하고 취약 주제는 기존 정규화 서비스가 빈 결과를400으로 거절한다. 명세 생략은 서비스의 일반6/3/1·복습5/3/2를 따른다. 응답은 기존 식별자5개 필드/no-store를 유지하며 정답을 노출하지 않는다.

QuizGenerationApiMysqlTests 실제 HTTP/MySQL 합성1건 확장 및 bootJar 통과, JAR 금지설정0건. 기본 복습5/3/2·원본ID/주제 저장·401/CSRF403·타인/다른 자료/미완료404·잘못된 입력/빈 오답400·원본 답안 조회 DB 실패500/내부 상세 비노출/기존 세트·답안 보존 및 기존 일반 생성 회귀를 확인했다. 합성 자료/계정 정리. SQL·새 의존성·실제 외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizGenerationApiMysqlTests bootJar.

다음 시작점은 React 복습 생성 API 모듈의 목적/원본/주제·CSRF·응답·오류 계약과 결과 화면 오답 복습 진입 연결이다. 취약 주제 통계/화면·삭제 승인·활동/D3·전체 E2E는 남아 있다.

## 2026-10-12 복습 퀴즈 생성과 원자적 저장

QuizGenerationService에 목적/원본 풀이/주제 인자와 focus 제공자 호출을 연결했다. 기본 명세는 일반6/3/1·복습5/3/2이며 사용자 명세를 유지한다. 과목 잠금 후 자료/원문 지문 및 오답 원본 소유권·완료·내용/문항 ID를 재확인하고 purpose/sourceAttemptId/sourceTopics/sourceItemIds를 새 세트와 함께 저장한다. 원본 변경409·제공자 실패·문항 저장 실패는 기존 세트/풀이를 변경하지 않는다. AI 호출은 DB 트랜잭션 밖이다.

QuizGenerationServiceMysqlTests 신규 복습1건/일반 회귀1건 총2건·bootJar·JAR 금지설정0건 통과. 실제 합성 MySQL에서 GRADED/SUBMITTED 원본·오답/취약 주제 메타데이터·타인/다른 자료404·빈 주제400·제공자에 focus 전달·원본 변경409·문항 삽입 실패 전체 롤백·기존 답안/풀이 보존을 확인하고 합성 자료/계정을 정리했다. SQL·새 의존성·실제 외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizGenerationServiceMysqlTests bootJar.

다음 시작점은 복습 생성 Controller 입력/purpose/sourceAttemptId/sourceTopics와 HTTP 권한·CSRF·실패 계약 검증이다. 현재 API는 GENERAL만 허용한다. 복습 기본 명세/원본 조회 DB 실패 주입 검증은 HTTP 연결 때 추가한다. 삭제 승인 대기는 유지한다.

## 2026-10-12 복습 목적별 입력 준비

QuizInputService.prepare에 목적/원본 풀이/주제 입력을 연결했다. WRONG_REVIEW는 기존 소유권 조회 서비스를 요약 생성 전에 호출하고 focus·sourceTopics·sourceItemIds·trim한 sourceAttemptId와 근거 선호 페이지를 보존한다. WEAK_TOPIC은 비어 있지 않은 정규화 주제를 요구한다. Prepared에 목적/복습 메타데이터를 추가하고 목록을 불변 복사한다. 기존 일반 생성 호출과 요약 후 원문 지문409 검사는 유지한다. AI 요약 호출은 NOT_SUPPORTED 경계에 둔다.

QuizInputServiceTests 신규2/기존3건 총5건·bootJar·JAR 금지설정0건 통과. 원본 실패/빈 주제 시 요약 미호출·호출 순서·오답 근거 페이지 선택·식별자 전달·취약 주제 정규화·일반 생성/지문/권한 회귀를 확인했다. 최초 시험 JSON 문자열 컴파일 오류 수정 후 통과. DB·SQL·외부 호출·새 의존성 없음. 회귀: gradlew.bat test --tests com.onrelearn.QuizInputServiceTests bootJar.

다음 시작점은 QuizGenerationService의 복습 제공자 호출·목적/원본 메타데이터 저장·저장 전 소유권/복습 원본 재확인 및 실제 합성 MySQL 원자성 검증이다. 현재 생성 서비스와 API는 GENERAL만 연결한다. SUBMITTED/DB 실패 검증 및 삭제 승인 대기는 유지한다.

## 2026-10-12 복습 선호 페이지와 QuizContext 연결

QuizContext.assemble이 List<? extends Number>를 받아 오답 조회의 preferredPages를 직접 사용할 수 있게 했다. 유한한 양수 정수를 검사하고 JavaScript Number 방식으로 앞뒤1 페이지를 선택한다. int 범위로 잘라내지 않으며 범위 밖 선호 페이지가 있어도 PDF 첫3페이지로 대체하지 않는다. 기존 일반 생성의 정수 페이지 호출은 유지된다.

QuizContextTests 신규1건 포함4건·bootJar 통과, JAR 금지설정0건. 오답 Source→근거 문자열5→PDF4/5/6 연결, int 최댓값 바로 다음 페이지의 이웃 선택, 매우 큰 값/잘못된 값, 기존 노트 합집합/첫3페이지/원문 처리 회귀를 확인했다. DB·SQL·외부 호출·새 의존성 없음. 회귀: gradlew.bat test --tests com.onrelearn.QuizContextTests bootJar.

다음 시작점은 복습 목적별 QuizInputService와 생성 서비스에 focus·sourceTopics·sourceItemIds·sourceAttemptId를 전달하고 소유권 재확인/원자적 저장을 검증하는 것이다. 현재 일반 생성만 연결되어 있으며 복습 생성 API는 미완료다. 삭제 승인 대기 및 SUBMITTED/DB 실패 후속 검증은 유지한다.

## 2026-10-11 복습 근거 페이지 정규화

QuizEvidencePages와 QuizWrongReviewService.Source.preferredPages를 추가했다. 원본 asEvidencePage의 Number(page) 후 유한한 양수 정수 계약, 숫자 문자열/진법/단일 배열 변환·boolean 처리, 순서/중복을 유지한다. 근거 JSON은 변경하지 않고 반환 목록은 불변이다. 큰 원본 페이지 값을 int로 잘라내지 않도록 Double로 유지한다.

QuizEvidencePagesTests 순수 계약2건 및 bootJar 통과, JAR 금지설정0건. DB·SQL·외부 호출·새 의존성 없음. 회귀: gradlew.bat test --tests com.onrelearn.QuizEvidencePagesTests bootJar.

다음 시작점은 QuizContext의 선호 페이지 입력에 원본 Number 범위를 유지하도록 연결하고 복습 목적별 QuizInputService/생성 서비스에 focus·sourceTopics·sourceItemIds·sourceAttemptId를 전달하는 것이다. 현재 QuizContext는 List<Integer>이며 생성 API에는 미연결이다. SUBMITTED 직접 조회/DB 실패 주입 검증은 후속 연결 때 수행한다. 삭제 정책은 승인 대기 유지.

## 2026-10-11 오답 복습 입력 조회 서비스

QuizWrongReviewService.load를 추가했다. 풀이/자료 소유권·동일 자료·SUBMITTED/GRADED를 확인한 뒤 isCorrect=false 답안만 QuizFocus.WRONG_REVIEW와 sourceItemIds로 구성한다. null 채점은 제외하고 빈 주제는 General concept으로 대체하며 정규화한 첫8개 주제·문항/해설/근거 원문을 보존한다. REPEATABLE_READ 읽기 트랜잭션이며 입력400·인증401·없는/타인/다른 자료/미완료 원본404·빈 오답400은 공통 advice 대상이다. DB 실패는 숨기지 않는다.

QuizWrongReviewMysqlTests 실제 onrelearn 합성1건 및 bootJar 통과. 소유권·자료/완료 상태·false만 선택·null 제외·빈 오답·주제 기본값·숫자 문자열 근거 보존·기존 답안 유지·불변 ID 목록을 확인했다. 합성 행은 시험 트랜잭션 롤백, JAR 금지설정0건. SQL·새 의존성·외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizWrongReviewMysqlTests bootJar.

다음 시작점은 근거 페이지 정규화와 preferredPages 연결, 복습 목적별 QuizInputService/생성 입력 연결이다. 현재 조회 서비스는 생성 API에 미연결이며 SUBMITTED 직접 조회/DB 실패 주입 추가 검증은 후속 연결 때 수행한다. 삭제 승인 대기는 유지하며 질문을 반복하지 않는다.

## 2026-10-11 복습 주제 입력 순수 계약

원본 features/quiz/lib/focus.ts의 asTopicList를 QuizFocusInput.topics로 이식했다. 배열 내 문자열만 JavaScript trim에 맞춰 정리하고 빈 값/중복을 제거하며 최초 순서·대소문자·내부 공백을 유지한 첫8개를 반환한다. weakTopic은 빈 정규화 결과를 공통400 서비스 예외로 거절하고 기존 QuizFocus.WEAK_TOPIC을 구성한다.

QuizFocusInputTests 신규 단위2건·bootJar 통과. 순서/8개 제한·비문자/빈 값·Unicode 공백·입력 불변/반환 목록 불변·취약 주제400을 확인했고 JAR 금지설정0건이다. DB·SQL·외부 호출·새 의존성 없음. 회귀: gradlew.bat test --tests com.onrelearn.QuizFocusInputTests bootJar.

다음 시작점은 오답 복습 입력 조회 서비스: 소유자/동일 자료/완료 풀이 및 isCorrect=false 문항 조회, 빈 오답 거절, 근거 페이지 정규화/선호 페이지 연결이다. 원본 asEvidencePage는 Number 변환 후 양수 정수를 허용하므로 숫자 문자열 등 실제 저장 근거 형태를 확인한다. 생성 API/제공자 호출에는 아직 미연결이다. 삭제 승인 대기는 유지하고 질문은 반복하지 않는다.

## 2026-10-11 퀴즈 세트 제목 변경 HTTP API

PATCH /api/quiz/quiz-sets를 QuizSetUpdateController에 연결했다. quizSetId/title 문자열만 허용하고 기존 서비스의 trim·1~100자·소유권/과목 잠금을 사용한다. 정상 응답은 {ok:true,quizSet:{id,title,createdAt}}와 no-store다. 입력400·미인증401·소유권/CSRF403·예상 밖 DB 실패500은 기존 보안 필터/공통 예외 처리를 따른다.

QuizSetUpdateApiMysqlTests 실제 HTTP/onrelearn 합성1건 및 bootJar 통과: 잘못된 JSON/필드 유형/빈 값/101자400·100자 정상·권한/CSRF·공개 응답 필드·제목 외 필드 보존·flush 후 실패500/내부 상세 비노출/원본 롤백·목록 재조회 보존. 합성 자료/계정 정리. SQL·새 의존성·외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizSetUpdateApiMysqlTests bootJar.

다음 시작점은 React 퀴즈 제목 수정 API 모듈과 입력/응답·CSRF·실패 계약 검증이며 이후 목록의 수정/취소/실패 입력 유지 UI를 연결한다. 세트 삭제 정책·복습/통계·활동/D3·전체 E2E는 미완료다.

## 2026-10-11 퀴즈 세트 제목 변경 서비스

원본 PATCH /api/quiz/quiz-sets의 제목 계약을 확인하고 QuizSetUpdateService.rename을 추가했다. JavaScript trim에 맞춘 앞뒤 공백 제거·UTF-16 1~100자 검사, 소유자 조회·과목 잠금·READ_COMMITTED 재확인 후 제목만 저장한다. 응답 DTO는 id/title/createdAt이며 입력400·인증401·없는/타인 세트403은 공통 advice로 처리할 서비스 예외다. 예상 밖의 DB 오류는 그대로 전파한다.

QuizSetUpdateMysqlTests 실제 onrelearn 합성1건·bootJar 통과: 공백/100자/이모지 경계·권한·완료 풀이가 있는 세트 변경·목록 반영·flush 후 실패 롤백·문항/답안/풀이/생성 시각/해시 보존. 최초 시험의 Repository 프록시 실패 주입 오류는 EntityManager flush 방식으로 수정 후 통과했다. 합성 자료/계정 정리, JAR 금지설정0건. SQL·새 의존성·외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizSetUpdateMysqlTests bootJar.

다음 시작점은 PATCH /api/quiz/quiz-sets Controller와 엄격한 문자열 입력·CSRF·권한·안전한 실패 응답 HTTP 검증이다. React 제목 수정 UI·세트 삭제·복습/통계·활동/D3는 미완료이며 기존 데이터 삭제 정책은 확인 전 적용하지 않는다.

## 2026-10-10 결과 조회 HTTP API

GET /api/attempt/result?attemptId=...에 QuizResultController를 연결했다. 원본 {attempt} 응답과 완료 상태/소유권 계약을 유지하고 no-store로 반환한다. 빈 ID/미제출400·미인증401·없는/타인 풀이404·DB 실패500은 기존 공통 처리를 사용한다. GET은 CSRF 토큰 없이 읽기만 수행한다.

QuizResultApiMysqlTests 실제 HTTP/onrelearn 합성1건·bootJar 통과: 입력/권한·미제출 정답/해설 비노출·GRADED 결과 공개 필드·no-store·0/null/false·SUBMITTED nullable 상태, DB 조회 실패500/내부 상세 비노출·재조회 동일 결과/시각 보존. 합성 자료/계정 정리, JAR 금지설정0건. SQL·새 의존성·외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizResultApiMysqlTests bootJar.

일반 퀴즈 생성·목록·시작/복원·답안 저장·제출·결과 조회 백엔드 연결을 완료했다. 다음 시작점은 React 퀴즈 목록/생성 API 모듈과 CSRF·취소·응답 계약 검증이다. React 퀴즈 화면·복습·통계·목표·활동/D3는 미완료이며 기존 진행 중 풀이 삭제 정책은 확인 전 미적용이다.

## 2026-10-10 소유자 전용 결과 조회 서비스

QuizResultService.get에 풀이/자료 소유권·완료 상태 검사와 원본 결과 DTO를 연결했다. SUBMITTED/GRADED만 정답/해설 조회를 허용하며 미제출400·없는/타인 풀이404·미인증401·빈 ID400이다. REPEATABLE_READ 읽기 트랜잭션으로 풀이/문항/답안을 조회하고 nullable 점수·채점·시각·피드백 및 0/false를 유지한다. 문항은 order순, DTO는 원본 필드만 반환하며 내부 소유자/제공자 메타데이터를 제외한다. 조회 실패는 전파하며 데이터 변경은 없다.

QuizResultMysqlTests 실제 onrelearn 합성1건·bootJar 통과: 400/401/404·SUBMITTED/GRADED·null/0점/false·문항 순서/보기/정답·공개 필드 제한·답안 조회 실패 전파·기존 기록/시각 보존. 합성 자료/계정 정리. SQL·새 의존성·외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizResultMysqlTests bootJar.

다음 시작점은 GET /api/attempt/result Controller와 HTTP 입력·권한·미제출 차단·완료 결과/no-store 검증이다. 결과 UI·복습·활동/D3 등은 미완료이며 진행 중 풀이 삭제 정책은 확인 전 미적용이다.

## 2026-10-10 제출 HTTP API

POST /api/attempt/submit에 QuizSubmissionController를 연결했다. attemptId 문자열을 확인하고 기존 제출 서비스를 호출한다. 성공은 원본 ok/attemptId/score/maxScore 네 필드·no-store이며 정답/엔티티는 반환하지 않는다. 공통 advice와 보안 필터로 입력400·미인증401·타인/없는 풀이403·완료409·DB 실패500을 처리한다.

QuizSubmissionApiMysqlTests 실제 HTTP/onrelearn 합성1건·bootJar 통과: 잘못된 JSON/ID·권한·CSRF, 저장 실패500/내부 메시지 비노출/미응답 행과 상태 롤백, 정상0점/100점 만점·GRADED/시각 저장·정답 비노출, 중복 제출409/완료 시각 보존 및 SUBMITTED409. 합성 자료/계정 정리, JAR 금지설정0건. SQL·새 의존성·외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizSubmissionApiMysqlTests bootJar.

다음 시작점은 원본 결과 조회 응답 계약·제출 전 정답 공개 조건 확인과 소유자 전용 결과 조회 서비스의 작은 단위다. 결과 API·복습·React UI·활동/D3는 미완료이며 기존 진행 중 풀이 삭제 정책은 확인 전 미적용이다.

## 2026-10-10 제출·채점 저장 서비스

QuizSubmissionService.submit에 소유권 확인·과목 잠금·READ_COMMITTED 재조회와 QuizGrading을 연결했다. 기존 답안 저장/자료 삭제와 잠금을 공유하며 완료 상태는409다. 모든 문항의 답안 채점 및 미저장/null 답안 {} 생성, 100점 환산/반올림, GRADED·제출/채점 시각·점수를 한 트랜잭션으로 저장한다. 기존 응답·생성 시각·피드백은 보존한다. QuizAttempt.completeGrading/QuizAnswer.recordGrade가 상태 변경을 담당한다.

QuizSubmissionMysqlTests 실제 onrelearn 합성1건·bootJar 통과: 400/401/403, 최종 저장 실패 시 기존 답안/풀이와 새 미응답 행 전체 롤백, 동시 제출 200/409, 답안 저장과 제출 경합의 결과 일관성, 완료 후 저장409, 2/6 배점의33점 환산, 미응답0점/빈 세트 maxScore0·시각 저장. 합성 자료/계정 정리, JAR 금지설정0건. SQL·새 의존성·외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizSubmissionMysqlTests bootJar.

다음 시작점은 POST /api/attempt/submit Controller와 HTTP 입력·CSRF·권한·제출/완료/실패 응답 검증이다. 결과 조회·복습·React UI·활동/D3는 미완료이며 기존 진행 중 풀이 삭제 정책은 확인 전 미적용이다.

## 2026-10-10 순수 문항 채점 규칙

QuizGrading에 객관식 숫자 비교·OX boolean 비교·단답형 공백/대소문자 정규화와 정확 일치/키워드 판정을 이식했다. 정답 후보 전체의 고유 키워드 합집합에서 60% 이상 또는 2개 이상 일치하면 전점이며 부분점수는 없다. 미응답은 원본 방식으로 처리하고 답안 JSON은 변경하지 않는다. JavaScript 공백(NBSP/BOM 포함)·UTF-16 토큰 길이를 유지한다. 새 생성 계약에 맞지 않는 저장 정답키는 내부 예외로 중단해 임의의 0점 완료로 숨기지 않는다. 과거 비정상 정답키의 JS 강제 변환은 이식하지 않았으며 기존 행 변경은 없다.

QuizGradingTests 단위3건·bootJar 통과: 0/false·숫자 문자열 거절·미선택, 단답 정확 일치·키워드 기준/중복/후보 합집합·한글/Unicode 공백·입력 불변·잘못된 키. DB·SQL·외부 API·새 의존성 변경 없음. 회귀: gradlew.bat test --tests com.onrelearn.QuizGradingTests bootJar.

다음 시작점은 제출 서비스의 소유권/과목 잠금·답안 저장과의 직렬화·채점 결과/풀이 상태 원자적 저장 및 합성 MySQL 검증이다. 100점 환산·미응답 답안 생성·완료 시각은 후속 서비스에서 연결한다. 제출 API·결과 조회·복습·React UI·활동/D3는 미완료다.

## 2026-10-10 답안 저장 HTTP API

POST /api/attempt/answer에 QuizAnswerController를 연결했다. attemptId/quizItemId 문자열·JSON 객체를 확인하고 response를 QuizAnswerService에 전달한다. 성공은 {ok:true}·no-store이며 공통 advice/보안 필터로 오류를 처리한다.

QuizAnswerApiMysqlTests 실제 HTTP/onrelearn 합성1건·bootJar 통과: 입력400·미인증401·타인/없는 풀이403·CSRF403, false 저장·같은 행 재저장, SUBMITTED/GRADED409, DB 저장 실패500·내부 상세 비노출·원본 보존. 합성 자료/계정 정리, JAR 금지설정0건. SQL·새 의존성·외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizAnswerApiMysqlTests bootJar. 이전 실행의 문서 기록은 자동 승인 검토 사용량 제한으로 지연됐으며 이번에 보완했다.

다음 시작점은 원본 제출/채점 계약과 순수 유형별 채점 규칙 확인·이식이다. 제출 트랜잭션·결과 조회·복습·React UI·활동/D3는 미완료이며 진행 중 풀이 삭제 정책은 확인 전 미적용이다.

## 2026-10-10 답안 저장 서비스

QuizAnswerService.save에 소유자/자료 소유권 확인·과목 잠금·READ_COMMITTED 이후 상태 재조회·같은 세트 문항 검사와 답안 upsert를 연결했다. QuizAnswerInput은 객관식 selectedIndex(보기 범위 정수 또는 null), OX value(boolean 또는 null), 단답형 text(문자열)를 검사한다. 원본 화면의 미선택/null·0·false·빈 문자열·공백을 보존하며 응답 원문을 임의 정규화하지 않는다. 필수 필드가 없거나 유형/범위가 다르면400, 미인증401, 타인/없는 풀이403, 완료 풀이409다. DB 실패는 전파되어 공통 advice의500 처리 대상이며 저장 트랜잭션은 롤백한다.

QuizAnswerMysqlTests 실제 onrelearn 합성1건·bootJar 통과. 권한·다른 세트/없는 문항·유형/범위 오류·동시 최초 저장 한 행·수정 시 생성 시각 유지·미선택/긴 단답 원문·SUBMITTED/GRADED 수정 거절·DB 저장 실패 원본 롤백을 검증했다. 합성 자료/계정 정리, JAR 금지설정0건. SQL·새 의존성·외부 호출 없음. 회귀: gradlew.bat mysqlTest --tests com.onrelearn.QuizAnswerMysqlTests bootJar.

다음 시작점은 POST /api/attempt/answer Controller 연결과 HTTP 입력·CSRF·권한·정상/실패 저장 검증이다. 제출/채점·복습·React 퀴즈 UI·활동/D3는 미완료이며 기존 풀이 삭제 정책은 확인 전 미적용이다.

## 2026-10-10 풀이 시작·복원 HTTP API

POST /api/attempt/start에 quizSetId 문자열 검사와 QuizAttemptStartService를 연결했다. 성공 응답은 원본 attemptId/quizSet/existingAnswers이며 no-store다. 이미 제출/채점된 풀이는 AttemptAlreadySubmittedException을 공통 ApiExceptionHandler에서 409(error/code=ALREADY_SUBMITTED/attemptId)·no-store로 변환한다. 소유권을 확인한 뒤에만 완료 풀이 ID를 반환한다. 새 실행/삭제 정책이나 외부 호출은 추가하지 않았다.

QuizAttemptStartApiMysqlTests 실제 HTTP/onrelearn 합성1건·bootJar 통과: 입력400·미인증401·타인/없는 세트403·CSRF403, 최초 시작/같은 ID 복원/false 답안, 문항 공개 필드 제한, SUBMITTED/GRADED409, 답안 조회 DB 실패500·내부 메시지 비노출·기존 답안 보존. 합성 자료/계정 정리, JAR 금지설정0건. SQL·새 의존성 없음. 회귀 명령: gradlew.bat mysqlTest --tests com.onrelearn.QuizAttemptStartApiMysqlTests bootJar.

다음 시작점은 원본 답안 저장 입출력·유형별 입력 검증 계약 확인과 소유자/진행 상태를 검사하는 저장 서비스의 작은 단위다. 제출/채점·복습·React 퀴즈 UI·활동/D3는 미완료이며 진행 중 풀이 삭제 정책은 확인 전 미적용이다.

## 2026-10-10 풀이 시작·복원 서비스

QuizAttemptStartService.start에 소유권 검사·과목 잠금·READ_COMMITTED 재조회·완료 우선/진행중 재사용/최초 생성과 문항·기존 답안 DTO를 연결했다. 기존 자료 삭제와 잠금 순서를 공유하며 두 동시 최초 시작은 같은 풀이를 재사용한다. 완료 상태는 already-submitted와 attemptId를 반환하고 문항을 공개하지 않는다. 성공 DTO는 원본의 id/order/type/question/choices/topic/points와 quizItemId/response/updatedAt를 유지하며 정답·해설·채점 필드는 제외한다. 원본의 진행 중 풀이 삭제 정책은 적용하지 않고 모든 기존 이력을 보존한다.

QuizAttemptStartMysqlTests 실제 onrelearn 합성1건·bootJar 통과: 입력400/인증401/소유권403, 동시 두 요청 동일 ID/한 행, 페이지 아닌 문항 순서·보기/null·정답/해설 제외, false/빈 답안 복원, 타인 풀이 제외, 완료 우선 및 과거 풀이/답안 보존. 합성 자료/계정 정리, JAR 금지설정0건. SQL·새 의존성·외부 호출 없음. 검증 명령: gradlew.bat mysqlTest --tests com.onrelearn.QuizAttemptStartMysqlTests bootJar.

다음 시작점은 풀이 시작/복원 Controller와 HTTP 입력·CSRF·권한·복원·완료 분기 검증이다. 활동 기록/D3 및 진행 중 풀이 삭제 정책은 별도 확인 대상으로 유지한다. 답안 저장/제출/채점·복습·React 퀴즈 UI는 미완료다.

## 2026-10-10 퀴즈 목록 풀이 이력 연결

GET /api/quiz/quiz-sets 목록에 원본 latestAttempt(id/status/score/maxScore/startedAt/submittedAt)를 연결했다. 자료 소유권 검사 후 해당 사용자·자료의 풀이 요약을 한 번에 조회하고 최신 완료(SUBMITTED/GRADED)를 우선하며 없으면 최신 진행 중 풀이를 선택한다. 시작 시각 동률은 ID 내림차순으로 고정한다. 풀이가 없으면 명시적 null이다. nullable 점수와 0점을 구분하고 정답/답안/내부 사용자 ID는 반환하지 않는다. 읽기 트랜잭션·no-store·공통 예외 처리를 유지하며 조회 중 기존 풀이를 삭제하지 않는다.

변경된 QuizReadApiMysqlTests 실제 HTTP/onrelearn 합성1건·bootJar 통과: 기존 목록 권한/정렬/문항 수 회귀, 미풀이 null·최신 진행중·GRADED/SUBMITTED 간 최신 완료 우선·타인 이력 제외·0/null 점수·공개 필드 제한·모든 과거 풀이 보존. 합성 자료/계정 정리, JAR 금지설정0건. SQL·새 의존성·실제 외부 호출 없음. 검증 명령: gradlew.bat mysqlTest --tests com.onrelearn.QuizReadApiMysqlTests bootJar.

다음 시작점은 소유자 전용 풀이 시작/복원 서비스와 정답 없는 문항·기존 답안 DTO의 작은 단위다. 동시 최초 시작 검증이 필요하며 원본의 진행 중 풀이 삭제 정책은 사용자 확인 전 적용하지 않는다. 답안 저장/제출/채점·복습·React UI·활동/D3는 미완료다.

## 2026-10-10 풀이·답안 저장 모델

QuizAttempt/QuizAnswer 엔티티·Repository 및 JDBC quiz_attempt/quiz_answer 테이블을 추가했다. 원본의 사용자 scalar ID, IN_PROGRESS/SUBMITTED/GRADED, nullable 점수/채점 시각/피드백, JSON 답안과 생성/갱신 시각을 보존한다. 동일 사용자/세트의 복수 풀이를 허용하고 답안은 attempt/item unique다. 다른 세트의 문항 연결은 생성자에서 거절한다. 실제 저장 서비스에서는 소유권·진행 상태·같은 세트 검증이 추가로 필요하다. 엔티티는 답안/연관 객체를 toString에 출력하지 않는다.

QuizAttemptModelMysqlTests 실제 onrelearn 합성1건·JPA validate·bootJar 통과: 복수 풀이/소유자 Repository 조회/정렬, JSON false·빈 문자열·긴 답안 갱신·생성 시각 유지, nullable 이전/채점 결과, unique/FK/상태 CHECK, 풀이·문항·세트 FK cascade. 합성 변경은 트랜잭션 롤백, 새 테이블은 유지. JAR 금지설정0건. 외부 호출·새 의존성 없음. 검증 명령: gradlew.bat mysqlTest --tests com.onrelearn.QuizAttemptModelMysqlTests bootJar.

다음 시작점은 목록 latestAttempt의 완료 우선 선택 계약과 소유자 전용 조회 연결이다. 풀이 시작/복원·문항 조회·답안 저장/채점·복습·UI·활동/D3는 미완료. 원본의 완료 풀이 존재 시 IN_PROGRESS 삭제 정책은 확인만 했으며 적용하지 않았다. 기존 행 삭제나 이관 정책은 사용자 확인 대상이다.

## 2026-10-10 퀴즈 목록 기본 조회

GET /api/quiz/quiz-sets?materialId=...를 QuizReadController→QuizReadService→Repository로 연결했다. 소유권 확인 후 한 읽기 트랜잭션에서 최신순 목록을 조회한다. 동시각은 ID 내림차순으로 정렬한다. DB에서 문항 수만 집계해 정답이 든 엔티티를 로딩하지 않는다. 응답 quizSets는 id/title/createdAt/itemCount를 포함하고 no-store다. 빈 자료는 빈 목록, 누락/공백 ID는400, 미인증401, 타인/없는 자료403이며 공통 예외 처리를 사용한다.

QuizReadApiMysqlTests 실제 HTTP/onrelearn 합성1건 및 bootJar 통과: 권한·빈 목록·다른 자료 제외·최신순·nullable 제목·0/2문항 집계·공개 필드 한정·AI 미호출. 합성 자료/계정 정리, JAR 금지설정0건. SQL·새 의존성·외부 서비스 변경/호출 없음. 회귀 명령: gradlew.bat mysqlTest --tests com.onrelearn.QuizReadApiMysqlTests bootJar.

목록 전체 계약은 아직 미완료다. 원본 latestAttempt는 풀이 모델 이식 뒤 연결하며 현재는 해당 필드를 생략한다(미구현을 미풀이 null로 대체하지 않는다). 원본 문항 조회는 독립 GET이 아니라 풀이 시작/복원 서비스의 일부다. 다음 시작점은 QuizAttempt/QuizAnswer 원본 모델·시작/복원 계약 확인과 저장 모델의 작은 단위다. 목록 latestAttempt·문항 조회·풀이/채점·복습·React 퀴즈 UI·활동/D3는 필수 후속으로 유지한다.

## 2026-10-10 일반 퀴즈 생성 HTTP API

POST /api/quiz/quiz-sets를 QuizGenerationService에 연결했다. materialId 문자열 및 spec의 세 정수/음수/빈 구성/합계 초과를 AI 호출 전에 검사하며 spec 생략/null은 기본6/3/1이다. 현재 GENERAL만 지원하고 복습 purpose/참조 입력은 명시적400으로 거절한다. 복습 기능은 후속 구현이며 일반 생성으로 대체하지 않는다. 성공 응답은 원본의 ok/quizSetId/createdAt/summaryId/sourceHash만 no-store로 반환하고 정답/문항 엔티티는 노출하지 않는다. 예외는 기존 공통 advice와 보안 필터에서 처리한다.

QuizGenerationApiMysqlTests 실제 HTTP/onrelearn 합성1건·bootJar 통과: 입력400·미인증401·타인403·CSRF403, 기본10문항/사용자 명세 생성, 동일해시 새 세트, 요약 재사용, 제공자502/503/504 시 기존 세트 보존. finally에서 합성 자료/계정을 정리했다. JAR 금지설정0건. SQL·새 의존성·실제 외부 AI 변경/호출 없음. 검증 명령은 gradlew.bat mysqlTest --tests com.onrelearn.QuizGenerationApiMysqlTests bootJar이다.

다음 시작점은 소유자 전용 퀴즈 목록/문항 조회의 원본 응답 계약 확인과 조회 API의 작은 단위다. 복습·풀이/채점·React 퀴즈 UI·활동 기록/D3 및 실제 외부 연결은 미완료/미검증이다.

## 2026-10-08 일반 퀴즈 생성·원자적 저장

QuizGenerationService.generate가 소유자 요약/QuizContext 확보 후 트랜잭션 밖에서 QuizProvider를 호출한다. 요청 spec(null이면 기본6/3/1)·프롬프트 버전·정답/수량을 확인하고 QuizPoints로 배점을 정규화한다. 저장은 READ_COMMITTED에서 노트 저장/자료 삭제와 공유하는 과목 잠금을 잡고 소유권·현재 입력 지문을 재검증한다. 변경은409이며 매번 새 GENERAL QuizSet과 전체 QuizItem을 한 트랜잭션에 저장한다. nullable 보기·기본 topic/근거/신호를 원본처럼 처리하고 응답은 세트ID/시각/요약ID/해시만 반환한다. 정답 엔티티를 API 응답으로 노출하지 않는다.

QuizGenerationServiceMysqlTests 실제 onrelearn 합성1건·bootJar 통과. 동일해시 복수세트, 정렬/합계100/정답JSON, 400/401/403, 제공자502 원본 유지, 생성 중 노트 변경409, 첫 문항 실제 flush 후 두 번째 저장 실패시 새 세트·첫 문항 롤백을 검증했다. 최초 롤백 대역은 Repository 프록시 callRealMethod 제약으로 실패하여 EntityManager persist/flush 대역으로 수정 후 통과했다. finally로 합성 자료·계정을 정리했다. JAR 금지설정0건. SQL·새 의존성·실제 외부 AI 변경/호출 없음.

다음 시작점은 원본 일반 퀴즈 생성 API 계약 확인 후 소유자 전용 Controller와 HTTP 입력/CSRF/권한·생성/실패 검증이다. 동시 생성 직접 시험, 복습 대상 조회/소유권·복습 생성, 퀴즈 목록/풀이/채점/API/UI·활동 기록/D3는 미완료다. 현재 서비스는 일반 생성만 제공하며 복습 요청을 일반으로 대체하지 않는다.

## 2026-10-08 요약과 퀴즈 입력 연결

QuizInputService.prepare가 MaterialSummaryService.ensure로 소유자 요약을 확보한 뒤 SummaryInputService.build로 소유권을 다시 확인하고 PDF/노트 스냅샷을 읽는다. SummaryPrompt.VERSION의 입력 지문이 요약 sourceHash와 다르면 안전한409로 거절하여 이전 요약과 새 노트를 섞지 않는다. 일반 생성용 QuizContext와 material/subject/summary ID·전체 지문을 반환한다. 외부 요약 호출을 둘러싼 호출자 트랜잭션은 NOT_SUPPORTED로 중단한다. 내부 Prepared의 toString은 내용을 숨긴다. 복습 대상 권한 조회·생성·저장을 아직 연결하지 않았다.

QuizInputServiceTests 서비스 대역 단위3건·bootJar 통과: 새 요약/재사용 요약의 canonical·노트/PDF/식별자 연결, 변경 지문409, 선행400/401/403/502/503 및 재조회403 전파. 이번 시험은 실제 MySQL 트랜잭션/HTTP 통합을 검증하지 않는다. JAR 금지설정0건 확인. DB·SQL·실제 외부 AI·새 의존성 변경 없음.

다음 시작점은 일반 퀴즈 생성 호출과 저장 직전 소유권/지문 재검증, 세트/문항 원자적 저장 및 합성 MySQL 검증이다. API/UI·복습·활동 기록/D3는 미완료다. 이전 자동화 안내 갱신은 자동 승인 검토 사용량 제한으로 실행되지 않았으나 코드·문서·시험은 완료되어 있었다.

## 2026-10-08 퀴즈 제공자 실행 흐름 연결

QuizAiConfiguration이 QuizProvider를 Spring 빈으로 등록하고 OpenAiQuizProvider에 생성/검수별 HTTP 클라이언트를 주입한다. 설정 prefix는 onrelearn.ai.quiz와 onrelearn.ai.quiz-critic이며 각각 endpoint/api-key/model/reasoning-effort/timeout/retries/retry-delay를 받는다. 기본 모델은 원본의 gpt-5.2/gpt-5-mini, endpoint는 Responses, reasoning low, timeout PT60S, retries0, retry-delay PT1S다. quiz-critic.enabled 기본 false. 키 기본값은 빈 문자열이며 설정 파일을 직접 읽지 않는다. 사용자가 Spring 실행 옵션 또는 IDE 환경변수/승인된 외부 설정에 값을 제공한다. 기존 summary 설정과 별도이며 critic 키는 enabled일 때 필요하다.

생성→JSON/필수 형식 1회 보정→문항 수/유형 수 보정→노트가 있고 note/mixed 근거40% 미만이면50% 이상 요구→최종 검증→선택 검수/수정 흐름을 연결했다. 보정 문구는 QuizPrompt에 분리했다. 구조 검증과 최종 수량 검증을 분리하되 모든 반환 결과는 기존 정답/수량 검증을 통과해야 한다. 원본의 최종 수량 누락 검사는 그대로 이식하지 않았다. HTTP 오류/거절을 JSON 보정 사유로 삼지 않는다. 선택 검수/수정의 예상502/503/504만 기존 검증된 결과로 복구하고 인터럽트·프로그래밍 오류는 숨기지 않는다. 검수 비활성 시 모델 메타데이터에 실행하지 않은 critic을 표시하지 않는다.

OpenAiQuizProviderTests 합성 HTTP5건·변경된 QuizOutputTests3건·QuizPromptTests3건·bootJar 통과. 설정 모델/추론/스키마와 생성·보정 호출 순서, 검수 모델 분리·수정 성공, 검수/수정 실패 보존, 최종 수량 오류502·HTTP 보정 금지를 확인했다. 프롬프트 책임 분리 후 최종 재검증했다. JAR 금지설정0건. DB·SQL·실제 외부 AI·새 의존성 변경 없음.

다음 시작점은 소유자 전용 요약/입력 지문과 QuizContext를 연결하는 퀴즈 생성 서비스 및 세트/문항 원자적 저장이다. 복습 대상 소유권 조회는 아직 미구현이므로 빈 복습을 성공으로 취급하지 않는다. 퀴즈 API/UI, 활동 기록/D3, 실제 외부 연결은 미완료/미검증이다.

## 2026-10-08 퀴즈 HTTP 호출 계층

OpenAiQuizClient와 OpenAiQuizResponse를 추가했다. 생성/검수 호출마다 별도 인스턴스에 endpoint/key/model/reasoning/timeout/retries/retryDelay를 전달할 수 있다. Responses 요청은 store=false, text.format에 원본 생성/검수 strict JSON schema를 받는다. 원본 QuizResponseFormat/QuizCritiqueResponseFormat을 별도 JSON 리소스로 이식했다. 공식 형식 참조: https://developers.openai.com/api/docs/guides/structured-outputs .

완료된 assistant output_text만 반환하며 거절/불완전/빈 응답은 안전한502다. 키 누락503, 본문 대기 timeout504, 명시적 일시 HTTP 오류만 지정 횟수 재시도하며 리디렉션과 모호한 I/O/timeout 재시도는 하지 않는다. 본문/키/원인 로그를 출력하지 않는다. 기존 요약 제공자는 수정하지 않았다. Spring 용도별 설정 바인딩과 QuizProvider 구현은 아직 연결하지 않았으며 이 클라이언트만으로 퀴즈 생성 기능이 실행되지는 않는다.

OpenAiQuizClientTests 합성 HTTP4건·bootJar 통과: 요청 모델/추론/스키마/store, 정상 응답, 재시도 횟수/비일시 오류/키 누락, 거절/빈/불완전, 본문 지연504. JAR 스키마2개 포함·금지설정0건 확인. 원본 TS 스키마 변환 정규식 오류를 수정한 뒤 빌드했다. DB·실제 외부 AI·새 의존성 변경 없음.

다음 시작점은 OpenAiQuizProvider에 생성/검수 설정과 보정·선택 검수 흐름 연결 및 합성 HTTP 검증이다. 요약/입력 서비스 연결·원자적 저장·퀴즈 API/UI·활동 기록/D3는 미완료다.

## 2026-10-08 퀴즈 검수·수정 계약

원본 검수/수정 프롬프트와 노트 유무 기준을 리소스4개로 추가하고 QuizPrompt.critique/repair에 연결했다. QuizCritique는 점수1~5·문항 인덱스·선택 problems/regenerate/regenerateIndexes를 검증한다. 원본처럼 items의 score<=2 또는 regenerate=true이면 수정 대상이며 regenerateIndexes만으로 수정하지 않는다. 실제 문항 범위를 벗어난 인덱스는 안전한502로 거절한다. 검증 결과를 복사하고 toString에 내용을 노출하지 않는다.

QuizCritiqueTests 3건·변경된 QuizPromptTests 회귀3건·bootJar 통과. JAR 퀴즈 프롬프트8개 포함·금지설정0건 확인. DB·SQL·외부 AI·새 의존성 변경 없음.

원본 호출 정책은 JSON 파싱/스키마 실패시1회 보정, 전체 문항수 불일치시 재생성, 노트가 있고 note/mixed 비율40% 미만이면50% 이상 요청, 선택적 검수(기본 비활성) 및 저품질 수정이다. 검수/수정 실패시 기존 생성 결과를 유지한다. HTTP 제공자·생성/검수 별도 모델 설정·위 호출 흐름은 아직 미구현이며 다음 시작점이다. 검수 실패 처리에서 예상 밖 프로그래밍 오류까지 숨기지 않도록 후속 구현한다. QuizContext/요약 서비스 연결·원자적 저장·API/UI·활동 기록/D3도 남아 있다.

## 2026-10-08 퀴즈 프롬프트·제공자 계약

원본 quiz_v6_review_focus 생성/일반/오답/취약 주제 지시문을 prompts/quiz-v6-*.txt 네 리소스로 이식했다. QuizPrompt는 문항 수·QuizContext·QuizFocus를 한 번만 삽입하므로 입력의 %s나 템플릿 문자는 재해석하지 않는다. 원본 60% 비율과 한글 지시문을 유지했다. 리소스 변경은 QuizPrompt와 build.gradle 허용 목록에서 관리한다.

QuizFocus는 WRONG_REVIEW의 topics/wrongItems(question/topic/explanation/evidence), WEAK_TOPIC의 topics를 분리한다. 이는 내부 계약이며 복습 대상 DB 조회·소유권 확인 구현이 아니다. QuizProvider.generate 경계와 QuizResult를 추가했고, 출력은 기존 QuizOutputValidator로 유형별 정답·문항 수를 검증한 뒤 복사한다. 빈 제공자/모델/버전은 공통 advice가 처리하는 안전한502다. 결과 접근 시 복사하여 검증 후 변경을 막고 toString에 정답·노트를 노출하지 않는다. 학습자용 응답 DTO는 후속 단계다.

QuizPromptTests 3건 및 bootJar 통과: 일반/두 복습 모드, 문항 수·근거·입력 문자 보존, 결과 검증/메타데이터502·복사 격리. JAR 프롬프트4개 포함·금지설정0건 확인. DB·SQL·외부 AI·새 의존성 변경 없음. HTTP 제공자와 용도별 모델 설정은 아직 연결하지 않았다.

다음 시작점은 원본 퀴즈 검수/수정 호출 계약 확인과 퀴즈 HTTP 제공자 연결이다. QuizContext와 소유자 전용 요약/입력 연결, 원자적 저장, 퀴즈 API/UI 및 활동 기록/D3는 미완료다.

## 2026-10-08 퀴즈 입력 문맥 구성

quiz.ai.QuizContext가 요약 원문·전체 노트·선택 근거 페이지를 분리한다. 정렬된 SummaryInput에서 노트/선호 페이지의 앞뒤 1페이지를 선택하고, 둘 다 없으면 내용 있는 PDF 첫 3페이지를 사용한다. 선호 페이지가 실제 자료에 없으면 빈 선택을 유지하는 원본 계약도 보존했다. PDF만 ECMAScript 공백 규칙으로 정리해 UTF-16 2,000자 뒤 ...를 붙이며 노트 내부 공백·신호·요약 원문은 유지한다. 입력 패킷을 변경하지 않고 신호 JSON은 복사하며 toString에 내용을 노출하지 않는다.

QuizContextTests 3건 및 bootJar 통과: 노트 전용/인접/선호 페이지 합집합, 중복 선호, PDF 3페이지 대체, 빈 자료/없는 선호, 양수 경계, NBSP/BOM 공백과 2,000자 경계, 원본 지문 보존. DB·SQL·외부 API·새 의존성 변경 없음. 소유권과 요약 최신성 확인은 기존 서비스에 있으며 이 순수 계약과의 서비스 연결은 아직 하지 않았다.

다음 시작점은 원본 퀴즈 프롬프트·복습 focus 입출력 및 제공자 경계 연결이다. 원자적 저장 서비스·퀴즈 제공자/API/UI와 활동 기록/D3는 미완료다.

# On Re:Learn 백엔드

## 2026-10-07 퀴즈 출력 검증·점수 정규화

QuizOutputValidator를 제공자 호출과 분리했다. 요청 QuizSpec의 총수/유형별 수, 질문·해설, 객관식 보기와 정답 인덱스 범위, OX 불리언, 단답형의 비어 있지 않은 정답 문자열 목록, 선택 점수·근거·신호 필드를 검증한다. 잘못된 출력은 정답/본문을 노출하지 않는 공통 ResponseStatusException 502로 거부한다. 원본의 범위 밖 객관식 인덱스·빈 accepted 허용은 필수 정답 무결성 검사로 보완했다. 학습자 응답 DTO와 생성 제공자 연결은 아직 없다.

QuizPoints는 원본의 최소1점·100문항 미만 합계100·나머지 동률 원래 순서·100문항 이상 각1점 배분을 이식했다. 누락/비양수/비유한 가중치는1이며 큰 유한 가중치 합산의 overflow를 피하도록 상대 비율로 계산한다. QuizOutputTests 3건·bootJar 통과: 3유형 정상/정답 오류/유형별 수/안전한502, 점수 경계·1~99문항 합계 검증. DB·SQL·새 의존성·외부 호출 없음.

다음 시작점은 요약/노트/근거 페이지를 묶는 퀴즈 입력과 원본 프롬프트·제공자 경계 연결이다. 원자적 저장 서비스·생성 API/UI·활동 기록/D3는 미완료다.

## 2026-10-07 퀴즈 세트·문항 저장 모델

QuizSet/QuizItem 엔티티와 Repository, JDBC quiz_set/quiz_item 테이블을 추가했다. 같은 자료·sourceHash의 여러 생성 세트를 허용하고 nullable summaryId/sourceAttemptId는 원본처럼 FK 없는 참조로 보존한다. 목적·생성 메타데이터·JSON choices/answerKey/evidence/signalHits/sourceTopics/sourceItemIds를 매핑했다. 원본 문항 순서 인덱스는 unique로 바꾸지 않았다. 문항 순서/점수 양수·유형/목적 CHECK와 material→quiz_set→quiz_item FK/cascade를 둔다. 정답·본문·관계는 toString에서 제외하며 API가 엔티티를 직접 반환하지 않도록 후속 DTO 경계를 유지한다.

실제 onrelearn 합성 MySQL 시험1건 및 JPA validate·bootJar 통과. 동일 해시 복수 세트, 일반/복습 목적, nullable 이전 필드, 3유형 정답 JSON·보기·근거·신호, 문항 정렬, CHECK/FK/cascade를 확인했다. CHECK 예외 분류 차이를 MySQL 오류코드3819로 검증하도록 시험을 수정한 뒤 최종 통과했다. 합성 데이터 변경은 트랜잭션 롤백, 기존 사용자 데이터 변경 없음. 추가 테이블은 유지한다. JAR 금지 설정0건, 새 의존성·외부 호출 없음.

다음 시작점은 퀴즈 출력·유형별 정답 검증 계약과 점수 정규화의 작은 단위다. 세트/문항 원자적 저장 서비스·생성 제공자·API·UI는 미완료이며 현재 모델만 구현했다.

## 2026-10-07 퀴즈 생성 계약·명세 모델

원본 quizGenerationServiceCore/Service, quizGenerationRequest, quizSpec, Prisma QuizSet/QuizItem 및 quiz-sets route를 확인했다. 요약만 sourceHash/signalsDigest로 재사용하며 퀴즈는 요청마다 새 세트·문항을 원자적으로 저장한다. 일반 기본 문항 수는 6/3/1, 복습 구성은 5/3/2(객관식/OX/단답형)다. 목적은 GENERAL/WRONG_REVIEW/WEAK_TOPIC이다.

quiz.model.QuizSpec과 QuizPurpose를 추가했다. 음수·빈 구성·정수 합계 초과를 제공자 호출 전에 거부하고 단일 유형 구성도 허용한다. API 요청 파싱과 상태 변환은 후속 Controller에서 연결한다. QuizSpecTests 2건 및 bootJar 통과. DB·SQL·외부 호출·새 의존성 변경 없음. 다음은 QuizSet/QuizItem 엔티티·Repository·SQL 및 합성 MySQL 검증이다. 생성 제공자·API·UI는 아직 퀴즈에 대해 미완료다.

## 2026-10-07 요약 조회·생성 API

소유자 전용 GET/POST /api/summary/summaries를 연결했다. GET은 materialId로 저장된 요약을 조회하고 미생성은 summary:null이며 제공자를 호출하지 않는다. POST는 문자열 materialId를 받아 기존 서비스의 재사용·생성·저장을 실행한다. 응답은 ok/summaryId/sourceHash/content/adaptive/reused이며 원본 클라이언트가 사용하지 않는 packet(원문·노트)은 반환하지 않는다. 두 응답은 no-store다. 원본 POST 경로를 유지하되 입력 타입을 엄격히 검증하고 공통 예외 처리로 HTTP 상태를 보존한다.

SummaryApiMysqlTests 실제 HTTP/MySQL 합성 시험 1건 및 bootJar 통과. 미생성 null·정상 생성·재사용·조회, 400·401·403·CSRF, 제공자 502/503/504와 기존 요약 보존을 검증했다. 시험 자료·계정은 finally에서 정리했다. SQL·의존성 변경과 실제 외부 API 호출은 없다. 다음은 React 요약 API와 과목 화면 생성·보기 UI 연결이다. 실제 외부 AI·전체 E2E, 활동 기록/D3는 미완료다.

## 2026-10-07 요약 재사용·생성·저장 서비스

MaterialSummaryService.ensure가 소유자 확인과 PDF/노트 스냅샷 조회 후 sourceHash/signalsDigest가 같은 요약을 재사용한다. 제공자 호출은 DB 트랜잭션 밖에서 실행한다. 저장은 노트 저장·자료 삭제와 같은 과목 잠금 및 READ_COMMITTED 트랜잭션을 사용하고 소유권과 입력 지문을 다시 확인한다. 생성 중 원문이 바뀌면 409로 기존 요약을 보존하며, 같은 입력의 동시 저장은 먼저 커밋된 한 행을 재사용한다. 제공자 실패는 기존 요약을 변경하지 않는다. 새 API·SQL·의존성은 없다.

MaterialSummaryServiceMysqlTests 합성 MySQL 시험 1건 통과: 최초 저장/재사용/노트 변경 재생성, 400·401·403, 제공자 실패 시 보존, 생성 중 변경 409, 트랜잭션 밖 제공자 실행, 동시 최초 저장 한 행·동일 ID 확인. 시험 대역 재설정과 Java 호환성 오류를 수정한 뒤 최종 통과했다. 합성 자료·계정은 finally에서 정리했다. bootJar 통과, JAR 금지 설정 파일 0건. 실제 외부 API는 호출하지 않았다.

다음 시작점은 원본 요약 API 계약 확인 후 소유자 전용 조회·생성 Controller와 HTTP 권한·CSRF·오류 검증이다. API/UI, 활동 기록/D3, 실제 서버 전체 E2E는 미완료다.

## 2026-10-06 요약 HTTP 제공자

OpenAiSummaryProvider를 SummaryProvider의 Spring 구현으로 연결했다. SummaryPrompt 렌더링과 OpenAiSummaryResponse 파싱은 별도 책임이며 Responses의 완료된 assistant output_text만 받아들인다. 불완전·거절·빈 출력·잘못된 JSON은 안전한 502, 설정 누락은 503, 전체 응답 대기 시간 초과는 504다. 원문 오류·키·본문은 예외에 포함하지 않는다. store=false이며 리다이렉트는 따르지 않는다. HTTPS endpoint만 허용하되 로컬 대역용 localhost/127.0.0.1 HTTP는 허용한다.

설정은 onrelearn.ai.summary 아래 endpoint/api-key/model/reasoning-effort/timeout/retries/retry-delay이며 onrelearn.yml의 ONRELEARN_SUMMARY_*와 OPENAI_API_KEY로 지정한다. 기본 모델은 원본 gpt-5-mini, reasoning low(미지원 모델은 빈 문자열로 생략), timeout PT60S, retries 0, retry-delay PT1S다. 명시적 429/500/502/503/504만 최대 설정 횟수(0~3) 재시도하며 모호한 I/O·시간 초과는 자동 재시도하지 않는다. timeout은 시도별 전체 본문 수신 제한이다. config/keys.example.yml에 비밀 없는 요약 키 항목을 추가했다. 실제 keys.local.yml은 사용자가 작성하고 Spring만 로딩한다.

OpenAiSummaryProviderTests 4건·bootJar 통과: 합성 HTTP 요청/인증/모델/추론 생략, 정상 출력, 재시도/한도, 400/401/302, 잘못된 응답, 키 없음, 본문 지연 504 확인. JAR 금지 설정 파일 0건. 신규 의존성·DB·실제 외부 API 호출 없음. 실제 OpenAI 연결·응답 품질은 미검증이며 진행 차단 조건이 아니다. 다음은 기존 요약 재사용·생성·저장 서비스 연결 및 권한/실패 시 원본 보존 검증이다. 생성 API/UI는 미완료다.

계약 참고: [OpenAI Responses 안내](https://developers.openai.com/api/docs/guides/migrate-to-responses). 실제 HTTP output 배열을 파싱하며 SDK 전용 output_text 편의 속성에 의존하지 않는다.

## 2026-10-06 요약 프롬프트·제공자 계약

원본 summary.v6의 한국어 Markdown 프롬프트를 `prompts/summary-v6.txt`로 이식하고 SummaryPrompt가 페이지 본문/노트/signals를 렌더링한다. 프롬프트 버전 불일치는 거부하며 자료/과목 ID·노트 수정 시각은 보내지 않는다. SummaryProvider 인터페이스와 SummaryResult 검증을 분리했다. 원본은 개인화된 출력 한 개를 canonical/adaptive 모두에 저장한다. null 변형은 content로 대체하고 빈 출력·누락 모델/제공자/버전은 안전한 502 예외를 throw하여 기존 공통 Advice에서 처리하도록 했다. 본문은 toString에 노출하지 않는다.

SummaryContractTests 3건과 bootJar 통과. 프롬프트 리소스 JAR 포함, 금지 설정 파일 0건 확인. Gradle 리소스 허용 목록에 프롬프트 하나만 추가했고 새 의존성·DB 변경·외부 호출 없음. 실제 HTTP 제공자 구현과 용도별 모델/endpoint/timeout/재시도 설정, 생성·재사용 저장 서비스/API/UI는 미완료다. 다음은 키 없는 대역으로 검증할 요약 HTTP 제공자와 설정 연결이다.

## 2026-10-06 소유자 전용 요약 입력 조회

SummaryInputService가 자료 소유권 확인 후 PDF 텍스트와 노트를 읽고 SummaryInput으로 결합한다. REPEATABLE_READ 읽기 트랜잭션으로 조회하며 생성 제공자는 이 트랜잭션 밖에서 후속 연결한다. 프롬프트 버전은 호출자가 전달하고 서비스에 모델·프롬프트를 하드코딩하지 않는다. NoteRepository에 자료별 페이지 정렬 조회를 추가했다.

실제 onrelearn SummaryInputMysqlTests 1건과 bootJar 통과. 본인 자료의 PDF/노트 혼합·노트 전용 페이지·signals/시각·빈 자료·원문 유지·반복 해시와 잘못된 입력 400/미인증 401/타인·없는 자료 403 서비스 예외를 확인했다. HTTP endpoint 시험은 아니며 신규 API·SQL·의존성·외부 AI 호출 없음. 합성 변경 롤백. 다음은 원본 요약 프롬프트·일반/개인화 출력 계약과 제공자 경계의 작은 단위다. 생성 API/UI와 활동/D3는 미완료다.

## 2026-10-06 요약 입력·재사용 계약

SummaryInput에 PDF/노트 페이지 합집합·오름차순·누락 본문 빈 문자열·입력 공백 제거를 구현했다. sourceHash는 자료 ID·프롬프트 버전·페이지 본문/노트/signals의 SHA-256이며 signalsDigest는 JSON이다. 두 값이 같을 때만 재사용한다. 제목·주차·과목·수정 시각은 원본과 같이 해시에서 제외한다. SummaryInputTests 3건과 bootJar 통과: 원본 형태 JSON 해시 일치, 페이지 결합/정렬, 입력 변경 무효화, 빈 입력·페이지 제약 확인. DB·외부 AI 호출·새 의존성 없음. DB 조회·소유권 서비스 연결, 요약 생성 API/UI는 미완료다. 다음은 저장된 PDF/노트를 읽는 소유자 전용 입력 서비스 연결이다.

## 2026-10-06 요약 저장 모델

`summary/entity/MaterialSummary`와 `MaterialSummaryRepository`, JDBC `material_summary` 테이블을 추가했다. 자료당 unique와 material FK/cascade를 적용하며 content, nullable canonical/adaptive, summaryType, signalsDigest, provider/model/promptVersion을 보존한다. signalsDigest는 해시가 아니라 페이지별 개인화 정보를 직렬화한 JSON 문자열이므로 LONGTEXT다. replace는 입력 검증 후 기존 ID/createdAt을 유지하고 갱신한다. 본문·개인화 정보·자료 관계는 toString에서 제외한다.

`mysqlTest --tests com.onrelearn.MaterialSummaryMysqlTests bootJar` 통과: 실제 onrelearn 합성 시험 1건에서 nullable 이전 형식·긴 JSON·일반/개인화 출력과 메타데이터 왕복·갱신·잘못된 입력 시 값 유지·unique/FK/cascade 및 JPA validate 검증. 합성 변경은 롤백한다. 새 API/의존성 없음. 다음은 요약 입력 구성과 sourceHash/signalsDigest 재사용 판정이며 생성 제공자·API·UI는 아직 없다.

## 2026-10-05 노트 저장 API

`POST /api/notes`에 `{materialId, content, page}`를 보내면 `{ok:true, note:{id,content,page,updatedAt}}`를 no-store로 반환한다. page 생략 시 1이며 양수 정수만 허용한다. content는 문자열 필수이며 빈 문자열·공백은 보존한다. 누락/null/다른 타입을 빈 내용으로 바꾸지 않고 400으로 처리한다. 소유권과 기존 CSRF 필터를 적용하며 자료 삭제와 같은 과목 잠금, READ_COMMITTED로 동시 최초 저장을 직렬화한다. 기존 노트 ID·signals는 보존하고 마지막으로 직렬화된 저장 내용이 남는다.

공통 Advice에 DataAccessException의 안전한 500 응답을 추가했다. DB 실패를 4xx나 성공으로 숨기지 않고 내부 메시지를 제외한다. `mysqlTest --tests com.onrelearn.NoteSaveMysqlTests` 최종 통과: 정상·빈 내용·잘못된 입력·권한/CSRF·동시 최초 저장·실패 롤백 검증. 실패 주입의 프록시 호출 문제를 수정했고 bootJar도 통과했다. 합성 행 정리 완료. SQL·의존성 변경 없음. 다음은 React 노트 편집과 페이지/자료 이동 전 저장 연결이며 원본 활동 기록은 후속 연계한다.

## 2026-10-05 노트 조회 API

`GET /api/notes?materialId=...&page=...`는 로그인 사용자의 자료 소유권을 확인하고 `{note: {id, content, page, updatedAt}}` 또는 미작성 페이지의 `{note: null}`을 반환한다. page 생략 시 1이며 양수 정수만 허용한다. DTO에는 노트 본문과 조회에 필요한 필드만 포함하고 signals·관계 엔티티는 제외한다. 응답은 no-store다. 잘못된 입력 400, 미인증 401, 타인/없는 자료 403을 기존 공통 예외/보안 처리기로 응답한다.

`gradlew.bat mysqlTest --tests com.onrelearn.NoteReadMysqlTests bootJar` 통과. 실제 MySQL HTTP 시험 1건에서 정상 본문·미작성 null·입력·소유권·캐시 제어를 확인하고 합성 행을 정리했다. SQL·의존성 변경 없음. 다음은 POST 저장 API의 소유권·CSRF·동시 upsert 처리이며 활동 기록과 React 연결은 후속 작업이다.

## 2026-10-05 페이지별 노트 모델

`note/entity/Note`, `note/repository/NoteRepository` 및 JDBC `note` 테이블을 추가했다. 원본의 자료/페이지당 한 건과 빈 내용 저장 의미를 유지하며 페이지는 양수 정수다. content는 LONGTEXT, 선택적 signals는 JSON, signalsVer는 LONGTEXT로 보존한다. 내용 수정은 이 메타데이터를 변경하지 않는다. 자료 FK는 명시적 자료 삭제 시 cascade하며 기존 테이블·데이터를 초기화하지 않는다. 관계·본문·signals는 toString에서 제외하고 식별자 기반 equals/hashCode를 유지한다.

검증: `gradlew.bat mysqlTest --tests com.onrelearn.NoteMysqlTests bootJar` 통과. 실제 onrelearn 합성 시험 1건에서 한글/공백·빈 내용 수정, JSON 보존, unique/FK/CHECK, 자료 삭제 cascade 및 JPA validate를 확인하고 트랜잭션을 롤백했다. 새 의존성·예외 응답 없음. 다음은 소유권·CSRF를 적용한 노트 조회/저장 API와 이후 React 페이지 이동 전 저장 흐름이다. 원본 노트 저장의 활동 기록 연계는 아직 미구현이다.

## 2026-10-05 미완료 삭제 조회 API

`GET /api/materials/deletions`는 인증된 소유자의 미완료 삭제를 요청 시각·자료 ID 순으로 반환한다. 응답은 `[{materialId, requestedAt}]`이며 `Cache-Control: no-store`를 적용한다. 사용자 ID 파라미터를 받지 않고 저장소 경로·내부 오류를 노출하지 않는다. 자료 행이 이미 삭제됐어도 이 목록의 ID로 기존 DELETE를 다시 호출할 수 있다.

실제 MySQL `MaterialUploadMysqlTests.deleteChecksOwnershipAndRunningJobAndRetriesStorageFailure`에서 저장소 실패 후 조회·타인 빈 목록·미인증 401·응답 필드/캐시·재시도 후 목록 소멸을 추가 검증했다. 시험과 bootJar 통과, 합성 행 정리. SQL·의존성 변경 없음. 프론트 삭제 확인 및 이 목록의 재시도 화면은 아직 미완료다.

## 2026-10-05 자료 삭제 API

`DELETE /api/materials/{id}`는 소유권·CSRF를 확인하고 `{ok:true}`를 반환한다. `MaterialDeletionService`는 과목 및 Job 행을 잠그고 RUNNING이면 409를 반환한다. 대기/종료 Job과 자료를 삭제하며 페이지 텍스트는 FK cascade로 정리한다. 소유자 ID·자료 ID·파일 경로를 `material_deletion`에 같은 트랜잭션으로 남긴 뒤 DB 커밋 후 저장소를 삭제한다. 이 기록은 자료 삭제 후에도 남아야 하므로 자료 FK를 두지 않았다.

저장소 실패는 공통 처리기의 503이며 자료는 이미 목록에서 사라졌어도 삭제 기록이 남는다. 동일 소유자의 동일 DELETE 요청으로 재시도하면 저장소 삭제 성공 후 기록을 제거한다. DB 실패는 공통 안전한 500이다. 자동 정리 실행기는 없으며, 미완료 삭제 조회 및 프론트 재시도 연결은 다음 단위다. 실제 Supabase 연결·응답 유실은 미검증이다.

검증: `gradlew.bat mysqlTest --tests com.onrelearn.MaterialUploadMysqlTests.deleteChecksOwnershipAndRunningJobAndRetriesStorageFailure bootJar` 통과. 권한/CSRF 403·처리 중 409·DB 실패 롤백·파일 삭제 실패 503·소유자 재시도·페이지/Job 정리를 실제 MySQL과 메모리 저장소로 확인했다. DB 실패 시나리오를 추가한 뒤 해당 시험만 재실행해 통과했다. 합성 데이터 정리 완료, 새 의존성 없음.

## 2026-10-04 자료 제목·주차 수정

`PATCH /api/materials/{id}`는 JSON의 `title`, `week` 중 제공된 필드만 수정하고 `{ok, material}`을 반환한다. 소유권·CSRF, 빈 제목/255자 초과, 양의 정수 주차를 검증하고 주차 중복은 409다. 업로드와 같은 과목 잠금으로 주차 변경을 조정하며 파일 경로는 유지한다. 실제 MySQL HTTP 시험에서 정상·400·403·409·중복 실패 후 원본 유지와 `bootJar`를 확인했다. 합성 데이터 정리, SQL·의존성 변경 없음. 다음은 React 수정 폼 연결이며 자료 삭제와 기록/PDF 화면도 남아 있다.

## 2026-10-04 키 예시와 구현 원칙

[config/keys.example.yml](config/keys.example.yml)에 DB 연결·JWT·Supabase 설정의 빈 예시를 제공한다. 사용자가 `config/keys.local.yml`로 복사해 실제 값을 입력한다. 이 실제 값 파일은 Git 제외 대상이며 에이전트 직접 접근 금지다. `src/main/resources` 밖에 있어 기본 빌드 리소스에 포함되지 않는다.

`keys.local.yml`을 사용하는 경우 IDE/JAR의 `--spring.config.location` 목록에서 `classpath:/onrelearn.yml,optional:file:./config/keys.local.yml,file:./src/main/resources/application.properties` 순서로 지정한다. properties가 마지막이므로 중복된 키는 properties 값이 우선한다. 예시 자체는 자동 로딩하지 않는다. 필요한 항목만 남기고 중복을 피한다. OS 환경변수로 주입하는 방식도 유지한다. AI 키는 AI 연동 구현 시 실제 설정 키를 추가한다.

외부 키가 필요한 사전 연결 시험은 필수가 아니다. 필수 예외·입력·권한·무결성 검증과 기능 이식에 집중하고 선택적인 운영 고도화를 선행 조건으로 만들지 않는다. 이번 변경은 문서와 예시뿐이므로 빌드·외부 연결 시험은 반복하지 않았다.

2026-10-01 기준 0차 환경 검증과 1차 인증의 회원가입·로그인·현재 사용자 조회·로그아웃 API, `/api/**` 보호 필터와 SPA CSRF 토큰 교환을 구현했다. [전체 지침](../../README.md)과 [전환 체크리스트](../../docs/migration-plan.md)를 함께 따른다. 실제 MySQL 합성 계정의 가입부터 로그아웃까지 브라우저 흐름을 검증했다. 학습 기능은 아직 이식·검증 전이다.

## 설정과 데이터 보호

사용자가 `application.properties`의 **Spring 실행 시 자동 로딩**을 허용했다. 에이전트가 이 파일을 직접 읽거나 쓰거나 복사하는 것은 금지다. 빌드/JAR 리소스에도 포함하지 않는다. `.env` 및 변형·예제는 직접 접근과 자동 로딩 모두 금지다.

Java 코드에는 설정 파일 경로를 지정하지 않는다. `bootRun`은 비밀 없는 `onrelearn.yml` 다음에 외부 `src/main/resources/application.properties`를 읽도록 실행 인수를 제공하며, 동일한 설정 키는 뒤의 properties 값이 우선한다. properties는 JAR에 포함되지 않으므로 JAR과 IDE 실행에서는 백엔드 프로젝트 루트를 작업 디렉터리로 지정하고 아래 JAR 명령의 실행 인수를 전달한다. 파일이 없거나 경로가 틀리면 시작 시 명확히 실패하도록 필수 경로로 지정했다. OS/IDE 환경변수 `SPRING_CONFIG_LOCATION`으로 같은 위치를 지정해도 된다. 개발 JDBC URL은 **onrelearn** DB를 가리켜야 한다. 비밀값을 로그·문서·채팅에 기록하지 않는다.

`JdbcSchemaConfig`가 `src/main/resources/sql/onrelearn.sql`을 JDBC DataSource로 실행하고 JPA schema validate보다 먼저 완료한다. 중복 실행을 피하기 위해 `onrelearn.yml`의 `spring.sql.init` 설정은 제거했다. SQL은 `CREATE TABLE IF NOT EXISTS`를 사용하며 원본/기존 행을 삭제하지 않는다. 이후 열이나 제약 변경은 별도로 검토된 ALTER SQL이 필요하다. SQL 파일은 app_user, grade, subject의 FK·unique·CHECK 제약을 정의한다. Flyway는 사용하지 않으며 신규 라이브러리도 없다. [Spring SQL 초기화 안내](https://docs.spring.io/spring-boot/how-to/data-initialization.html).

사용자가 개발 단계의 합성 데이터 통합 시험에 **onrelearn** DB 사용을 허용했다. 별도 시험 DB 환경변수는 요구하지 않는다. `mysqlTest`는 Spring이 실행 시 허용된 외부 설정을 읽어 개발 DB에 연결하고, DB명이 정확히 onrelearn인지 시험 initializer가 SQL 실행 전에 확인한다. 시험에서는 `ddl-auto=validate`를 강제해 실행·종료 시 스키마를 삭제하지 않는다. 합성 행은 트랜잭션 롤백 또는 시험 종료 정리로 제거한다. 사용자가 설명한 일반 실행의 `create-drop` 설정 내용은 에이전트가 금지 파일을 직접 읽지 않아 확인하지 않았다.

## 구조

React View ↔ Spring MVC Controller ↔ Service ↔ JPA Repository/Entity ↔ MySQL. 현재 Member(app_user), Grade, Subject 엔티티와 Repository, system 연결 상태 API가 있다. 세 엔티티에는 `@NoArgsConstructor`, `@AllArgsConstructor`, `@Data`가 적용됐고 검증 생성자에 `@Builder`를 둔다. 관계와 비밀번호 해시를 toString에서 제외한다. equals/hashCode는 ID 기반이다. ID/생성 시각 setter는 막았다. Entity는 직접 HTTP 응답으로 반환하지 않는다. [Lombok Builder](https://projectlombok.org/features/Builder).

`GET /api/system/health`는 local 프로필에서 DB `SELECT 1`을 수행한다. 성공 시 200 `{"status":"UP"}`, DB 접근 실패 시 503 `{"status":"DOWN"}`이다. 기본 주소는 127.0.0.1:8080이다. 상태 API는 업무 CRUD·권한 검증을 대신하지 않는다.

`POST /api/auth/register`는 `{ "email": "learner@example.com", "password": "..." }`를 받는다. 성공 200 `{ "ok": true }`, 중복 이메일 409 `{ "error": "이미 존재하는 이메일" }`, 누락·잘못된 타입·이메일 형식·JSON은 400으로 응답한다. 이메일 대소문자와 공백을 임의로 정규화하지 않는다. 회원 비밀번호는 기존과 같은 bcrypt cost 10으로 해시하고, 1~6학년의 1·2학기 12개를 함께 만들며 1학년 1학기만 현재 학기다. 신규 비밀번호 길이 정책과 기존 계정 충돌·이관 정책(D4)은 아직 확정하지 않았다.

`POST /api/auth/login`은 JSON 본문에 `email`과 `password` 문자열을 받는다. 성공하면 200 `{ "ok": true }`와 서명된 JWT를 담은 `token` 세션 쿠키(`HttpOnly`, `SameSite=Lax`, `Path=/`)를 반환한다. `ONRELEARN_AUTH_COOKIE_SECURE=true` 또는 HTTPS 요청이면 `Secure`도 설정한다. 누락·빈 값·잘못된 타입·형식이 깨진 JSON은 400, 잘못된 자격증명은 동일한 일반 메시지의 401이며 쿠키를 발급하지 않는다. 서명 키 설정이 없거나 유효하지 않으면 503을 반환한다. 로컬 HTTP를 위한 `Secure` 기본값은 false이므로 HTTPS 환경에서는 OS/IDE 변수로 true를 설정한다. JSON만 허용하고 CSRF 토큰도 요구한다.

`GET /api/auth/me`는 `token` 쿠키의 JWT 서명·만료를 확인한 뒤 회원을 MySQL에서 다시 조회한다. 유효하면 200 `{ "id": "...", "email": "..." }`만 반환한다. 쿠키가 없거나 변조·만료됐거나 회원이 삭제된 경우 401, 서명 키 설정 오류는 503이다. 비밀번호 해시는 응답에 포함하지 않는다. Spring Security 필터도 `/api/**` 요청의 JWT와 회원 존재를 확인한다. 상태·CSRF 조회·회원가입·로그인 및 쿠키 정리용 로그아웃 API는 인증 없이 접근할 수 있고 나머지 API는 인증을 요구한다. 로그아웃을 포함한 상태 변경 요청은 CSRF 토큰을 요구한다.

`GET /api/auth/csrf`는 200 `{ "headerName": "X-XSRF-TOKEN" }`와 JavaScript가 읽을 수 있는 `XSRF-TOKEN` 쿠키를 발급한다. React는 상태 변경 직전 이 GET을 호출한 뒤 **그 쿠키의 원본 값**을 `X-XSRF-TOKEN` 헤더로 보낸다. 응답 본문의 마스킹된 토큰은 SPA 헤더에 쓰지 않는다. JWT `token` 쿠키는 계속 HTTP-only다. Spring Security의 [SPA CSRF 방식](https://docs.spring.io/spring-security/reference/7.0/servlet/exploits/csrf.html#csrf-integration-javascript-spa)을 사용하며 가입·로그인을 포함한 모든 안전하지 않은 메서드에 CSRF 필터를 적용한다. CSRF 토큰 누락·오류는 403이다. 아직 보호된 업무 쓰기 API가 없어 인증된 계정의 상태 변경 E2E는 다음 단위에서 확인한다.

`POST /api/auth/logout`은 CSRF 토큰을 요구하며, JWT가 이미 만료됐어도 브라우저의 오래된 쿠키를 지울 수 있게 인증 여부와 무관하게 처리한다. 성공 200 `{ "ok": true }`와 `token`·`XSRF-TOKEN` 두 쿠키의 `Max-Age=0` 응답을 보낸다. `token`의 HttpOnly·SameSite·Path·Secure 정책은 로그인과 동일한 `AuthCookieService`에서 관리한다. JWT 자체를 서버에서 폐기하는 저장소는 아직 없으므로, 이미 유출된 토큰은 원래 만료 시각(기본 3시간)까지 재사용될 수 있다. 이 한계와 갱신/강제 폐기 정책은 인증 후속 단위에서 결정한다.

## 실행과 검증

이 디렉터리에서 실행한다.

- `.\gradlew.bat --no-daemon test bootJar`: DB 없이 단위 시험과 JAR 빌드.
- `.\gradlew.bat bootRun`: 외부 properties를 포함하는 기본 실행 인수로 개발 서버 기동.
- `java -jar build-onrelearn/libs/on-re-learn-0.0.1-SNAPSHOT.jar --spring.config.location=classpath:/onrelearn.yml,file:./src/main/resources/application.properties`: 동일한 설정 우선순위로 JAR 기동. IDE Program arguments에도 이 옵션을 사용한다.
- VS Code에서는 실행 및 디버그의 `On Re:Learn (development properties)` 구성을 선택한다. 메인 클래스 위의 기본 `Run` 버튼은 이 실행 인수를 적용하지 않는다. `L_A`, `OnReLearn_back`, `on-re-learn` 중 어느 폴더를 작업 공간으로 열어도 해당 폴더의 `.vscode/launch.json`에 같은 구성이 있다.
- `.\gradlew.bat --no-daemon mysqlTest`: 허용된 onrelearn 개발 DB에서 실제 DB 통합 시험. 실행 중인 서버와 같은 DB를 동시에 사용하지 않는다.

2026-09-30 실제 결과: Java 17.0.12 / Gradle 9.7.1 / Spring Boot 4.1.1에서 test bootJar 성공, 단위 시험 5건 통과. Spring 설정 자동 로딩 → onrelearn SQL 실행 → JPA 검증 → 기동 → 상태 API 200 UP을 확인했다. 같은 DB에서 재기동 후에도 200 UP이며 SQL 재실행 오류가 없었다. 8080은 기존 프로세스가 사용 중이어서 별도 18080에서 시험했다. React/Vite 5173 프록시를 통한 상태 API도 200 UP이었다. 시험 프로세스는 종료했다. JAR에는 sql/onrelearn.sql이 포함되고 금지 설정 파일은 포함되지 않는다.

설정 위치 하드코딩을 Java main과 Gradle bootRun에서 제거한 후 `test bootJar`를 다시 통과했다. 위 실행 인수를 사용한 JAR를 별도 18081 포트에서 기동해 개발 DB 연결 상태 API 200 UP을 확인하고 시험 프로세스를 종료했다. `bootRun --args` 명령 자체는 이번 변경 후 별도로 기동 검증하지 않았다.

2026-09-30 사용자 승인 후 `test mysqlTest` 성공: 단위 시험 5건과 실제 MySQL 통합 시험 6건(합성 데이터 저장·조회·수정, 소유자 조회, 롤백, FK·unique, SQL 재실행 후 행 보존)을 통과했다. 별도 시험 DB 설정은 더 이상 필요하지 않다. 이후 단위 시험에 연결 거부된 JDBC DataSource를 통한 실제 접속 실패 → HTTP 503 `DOWN` 검증을 추가해 단위 시험은 6건이 됐다. 실행 중인 MySQL 서비스를 중단하지 않고 고립된 실패 경로로 시험했다. 브라우저의 연결 확인 성공·백엔드 중단 오류·미정의 경로도 검증했다. 인증/권한은 아직 구현 전이므로 해당 차수에서 검증한다.

1차 회원가입 첫 단위 검증: `test mysqlTest bootJar` 통과. 이후 API 성공·중복 계약 시험을 더해 단위 시험 8건, 실제 MySQL 시험 7건이 통과했다. MySQL 시험은 합성 계정·12개 학기를 트랜잭션으로 롤백했다. 기존 결함 B2의 null·타입·잘못된 JSON 파싱 오류를 400 응답으로 고쳤다. Spring Boot 4 JSON 입력에는 Jackson 3 `tools.jackson.databind.JsonNode`를 사용한다. 새 라이브러리는 추가하지 않았고 기존 `spring-security-crypto`의 bcrypt를 사용한다. 로그인·브라우저 가입 화면·동시 요청·인증 권한은 미검증이다.

1차 로그인 자격증명 검증 첫 단위: `AuthenticationService`가 입력 누락을 거부하고 이메일로 회원을 찾아 기존 bcrypt 해시와 비밀번호를 비교해 회원 ID만 반환한다. 없는 이메일과 틀린 비밀번호는 모두 실패로 처리한다. `test mysqlTest bootJar` 성공(단위 9건, 실제 MySQL 7건). MySQL 시험에서 가입 직후 정상 비밀번호·잘못된 비밀번호·없는 이메일을 확인하고 합성 계정은 롤백했다. 새 의존성·SQL 변경은 없다. 아직 HTTP 로그인 응답, JWT 서명, 쿠키, 로그아웃, React 로그인 화면은 구현하지 않았고 인증 성공을 사용자에게 노출하지 않는다.

1차 JWT 단위: `JwtTokenService`가 회원 ID를 담은 서명 토큰을 발급하고 서명·만료를 검증한다. 비밀키와 수명은 비밀 없는 `onrelearn.yml`의 `onrelearn.auth.jwt` 설정 경계에 모았으며 기본 수명은 기존과 같은 3시간이다. 실제 키는 OS/IDE 환경변수 `JWT_SECRET`로 주입하고 최소 32 UTF-8 바이트가 필요하다. 값은 문서나 채팅에 기록하지 않는다. 키가 없어도 상태 API·기존 개발 흐름은 기동되며, 토큰 발급 시에는 명시적으로 실패한다. `test bootJar mysqlTest` 통과(단위 12건, 실제 MySQL 7건); 합성 키로 정상·만료·변조·취약 설정을 검증했다. 기존 JJWT 의존성을 사용해 새 라이브러리·SQL 변경은 없다.

1차 HTTP 로그인 단위: `test bootJar mysqlTest` 통과(단위 16건, 실제 MySQL 7건). 합성 키를 사용한 Controller 시험에서 정상 쿠키와 JWT, 잘못된 입력·자격증명 시 쿠키 미발급, 키 누락 503, `Secure` 설정과 JSON 외 요청 415를 검증했다. 빌드된 JAR의 실제 HTTP 요청으로 빈 JSON 400과 존재하지 않는 계정 401을 확인하고 프로세스를 종료했다. 실제 MySQL 계정으로 로그인에 성공하는 브라우저 E2E, 보호 API 권한, 로그아웃, CSRF는 미검증·미구현이다. 새 의존성·SQL 변경은 없다. 다음 시작점은 쿠키 검증을 사용하는 현재 사용자 API와 보호 경로다.

1차 현재 사용자 조회 단위: `test bootJar mysqlTest` 통과(단위 19건, 실제 MySQL 8건). 합성 JWT의 정상·누락·변조·삭제 회원·서명 키 누락을 검증했고, 실제 MySQL의 합성 회원을 토큰으로 조회한 후 시험 트랜잭션을 롤백했다. 새 의존성·SQL 변경은 없다. 다음 시작점은 Spring Security 기반 보호 경로와 CSRF, 이후 로그아웃 및 React 로그인 화면이다. 실제 계정 브라우저 E2E와 전역 권한 처리는 여전히 미검증이다.

1차 API 보호 필터 단위: Spring Boot 4.1.1의 BOM이 관리하는 `spring-boot-starter-security`를 신규 추가했다. JWT 쿠키 필터를 Spring Security API 체인에만 등록하고, 인증된 회원 정보만 principal로 넣는다. `test bootJar mysqlTest` 통과(단위 22건, 실제 MySQL 8건). 실제 Spring JAR에서 상태 200, 비로그인 `/api/auth/me`·미정의 API 401, 기존 가입·로그인 잘못된 입력 400, CSRF 토큰 없는 보호 POST 403을 확인했다. 단위 시험은 정상·변조·삭제 회원 쿠키와 키 설정 오류를 확인한다. 실제 계정의 보호 API 성공 경로와 SPA CSRF 토큰 교환은 미검증·미구현이다. SQL 변경은 없다. 다음 시작점은 CSRF 토큰 발급·전달과 보호된 상태 변경 요청 검증이다.

1차 SPA CSRF 단위: 새 의존성·SQL 변경 없이 Spring Security `csrf.spa()`와 `/api/auth/csrf`를 연결하고 React 가입·로그인 요청에 `X-XSRF-TOKEN`을 추가했다. Spring `test bootJar mysqlTest`(단위 22건, 실제 MySQL 8건) 및 React build/lint 통과. 실제 HTTP에서 CSRF GET 200·쿠키 발급, 로그인 누락/잘못된 토큰 403·올바른 쿠키 값과 잘못된 JSON 400, 가입 누락 토큰 403·올바른 토큰과 잘못된 입력 400을 확인했다. 브라우저에서 로그인 잘못된 계정 401과 가입 형식 오류 400을 확인해 SPA가 CSRF 검사를 통과함을 검증했다. 실제 계정의 로그인 성공·로그아웃·보호된 쓰기 E2E는 남았다. 다음 시작점은 CSRF가 적용된 로그아웃과 쿠키 해제다.

1차 로그아웃 단위: 로그인·로그아웃의 쿠키 속성을 `AuthCookieService`로 모으고 React 계정 화면에 로그아웃 버튼을 연결했다. Spring `test bootJar mysqlTest`(단위 24건, 실제 MySQL 8건) 및 React build/lint 통과. 실제 Spring HTTP에서 CSRF 없는 로그아웃 403, 올바른 CSRF 헤더가 있는 로그아웃 200과 두 쿠키 만료를 확인했다. 단위 시험에서 HTTP-only·Secure·Max-Age=0 속성을 확인했다. 새 의존성·SQL 변경은 없다. 실제 로그인된 브라우저의 로그아웃→재조회 401 흐름은 다음 E2E에서 확인한다.

1차 실제 브라우저 E2E: `onrelearn` MySQL, Spring JAR, Vite 프록시와 브라우저에서 합성 계정 가입→로그인→`/account` 조회→새로고침 후 로그인 유지→로그아웃→`/account` 재접근 시 로그인 필요 안내를 확인했다. 전용 `cleanupSyntheticE2e` Gradle 시험으로 해당 계정 한 건 삭제와 부재를 확인했고, 일반 `test`도 통과했다. `cleanupSyntheticE2e`는 자동 실행되지 않으며, 실행할 경우 PowerShell에서 `./gradlew.bat cleanupSyntheticE2e '-Pe2eEmail=e2e-00000000-0000-0000-0000-000000000000@example.invalid'`처럼 합성 주소를 인용해 전달한다. 실제 사용한 합성 주소로 교체해야 하며 JDBC URL의 DB명이 정확히 `onrelearn`인지 먼저 검사하고 JPA `ddl-auto=validate`를 강제한다. 계정 삭제 시 연관 학기 삭제는 SQL 외래 키의 `ON DELETE CASCADE`에 따른다. 신규 의존성·스키마 변경은 없다. 다음은 동시 중복 가입·만료/변조 쿠키·보호된 쓰기와 소유권·JWT 갱신/폐기 정책이다.

1차 인증 예외 단위: `RegistrationControllerTests`에서 동시 가입 상황의 unique 제약 실패를 모의해 이메일이 실제로 존재할 때만 409가 되고 다른 제약 오류는 전파되는 것을 확인했다. `JwtCookieAuthenticationFilterTests`에서 만료·변조된 서명 쿠키도 인증 주체를 만들지 않음을 확인했다. 두 대상 클래스의 Gradle 시험이 통과했다. 이는 모의 시험이므로 실제 동시 HTTP 요청의 승자 1건·409 1건은 아직 별도로 검증해야 한다. 코드 동작·의존성·SQL 변경은 없다.

1차 실제 MySQL 동시 가입 단위: `ConcurrentRegistrationMysqlTests`가 DB명을 검사하고 JPA `ddl-auto=validate`로 기동한 뒤, 같은 합성 이메일을 두 작업자에서 동시에 `RegistrationService`에 보냈다. 성공 1건·중복 실패 1건과 회원 1건·기본 학기 12건을 확인했으며, 시험의 `finally`에서 합성 계정을 삭제하고 부재를 확인했다. `mysqlTest --tests com.onrelearn.ConcurrentRegistrationMysqlTests` 통과. 새 의존성·SQL 변경은 없다.

1차 동시 HTTP 가입 단위: 같은 시험 클래스에 실제 Spring 임의 포트 HTTP 요청을 추가했다. 독립 클라이언트 두 개가 각각 `/api/auth/csrf`에서 쿠키를 받은 후 같은 합성 이메일의 `/api/auth/register`를 동시에 요청했다. 결과는 200·409 각 1건, 회원 1건·기본 학기 12건이었다. 시험은 해당 합성 계정을 삭제하고 부재를 확인했다. `mysqlTest --tests com.onrelearn.ConcurrentRegistrationMysqlTests` 통과. `application.properties`는 허용된 Spring 자동 로딩으로만 사용했으며 시험은 `ddl-auto=validate`를 강제한다. 새 의존성·SQL 변경은 없다. 다음은 비로그인·CSRF 경계의 남은 경로와 보호된 업무 쓰기·소유권 검증이다.

1차 HTTP 인증 경계 단위: `AuthBoundaryMysqlTests`가 임의 포트 Spring 서버·실제 MySQL에서 합성 회원을 만들고 정상 JWT의 `/api/auth/me` 200, 쿠키 누락·만료·변조 401을 확인했다. 공개 로그아웃도 CSRF 누락·오류 403, 올바른 쿠키와 헤더 200을 확인했다. 합성 회원은 시험 종료 시 삭제·부재를 확인했다. `mysqlTest --tests com.onrelearn.AuthBoundaryMysqlTests` 통과. 시험에만 합성 서명 키를 사용하며 `ddl-auto=validate`를 강제한다. 새 의존성·SQL 변경은 없다. 보호된 홈·업무 쓰기·소유권과 JWT 갱신/폐기 정책은 남았다.

예외 처리·주석 규칙: 가입·로그인·현재 사용자 Controller는 예상 입력/인증 실패를 `throw`하고 `shared/error/ApiExceptionHandler.java`의 `@RestControllerAdvice`가 기존 400·401·409·503 상태와 `{ "error": "..." }` 형식을 만든다. `/api/auth/me`의 인증 설정 오류 503은 이전 빈 본문 대신 안전한 JSON 오류를 돌려준다. 잘못된 JSON과 중복 이메일도 같은 파일에서 처리한다. 서명 키 설정 오류는 `AuthenticationConfigurationException`으로 구분하고, 예기치 않은 DB 오류를 중복 이메일로 숨기지 않는다. Spring Security 필터는 MVC 처리기 이전에 실행되므로 필터 자체의 503 경계를 유지한다. 중요한 메서드의 흐름은 `// *`, 보안·무결성 주의점은 `// !` 주석으로 설명한다. Better Comments의 기본 태그를 따르며 자명한 코드에는 주석을 늘리지 않는다. 백엔드 `test`·`bootJar`와 실제 MySQL의 `AuthBoundaryMysqlTests`·`ConcurrentRegistrationMysqlTests` 통과, 프론트 lint/build 통과. 새 의존성·SQL 변경은 없다. [Better Comments 공식 설명](https://marketplace.visualstudio.com/itemdetails?itemName=aaron-bond.better-comments)

보호된 홈 첫 단위: `GET /api/home`은 JWT 인증 주체의 회원 ID로 소유 학기 12개와 현재 학기 ID·학년·학기·과목 ID/이름을 반환한다. 비로그인·잘못된 쿠키는 401이다. `HomeMysqlTests`에서 실제 MySQL의 두 합성 회원을 사용해 본인 과목만 노출됨을 확인했고 두 계정 모두 정리했다. `test bootJar`, 대상 `mysqlTest`, React lint/build 통과. 실제 브라우저에서 합성 계정 가입→로그인→보호된 홈→새로고침→로그아웃 후 홈 접근 거부를 확인하고 `cleanupSyntheticE2e`로 계정을 삭제했다. 새 의존성·SQL 변경은 없다. 최근 활동·과목 이동·현재 학기 변경은 미구현이다.
# 2026-10-01 현재 학기 선택 API

`GET /api/grades`는 로그인 사용자의 학기 목록을 학년·학기 순으로 반환한다. `PATCH /api/grades`는 JSON `{"gradeId":"..."}`와 CSRF 헤더로 현재 학기를 변경한다. 같은 학기는 `{ "ok": true, "unchanged": true }`, 변경은 `{ "ok": true }`이다. `CurrentGradeService`가 회원 행 잠금 뒤 소유권을 확인하여 한 학기만 현재로 남긴다. ID 누락/형식 오류 400, 타인·존재하지 않는 ID 404, 미인증 401, CSRF 누락 403이다. 예상 가능한 예외는 `ApiExceptionHandler`에서 안전한 JSON으로 변환한다. 실제 MySQL 합성 데이터 통합 시험 `CurrentGradeMysqlTests`와 `test bootJar`를 통과했다. SQL·의존성 변경은 없다. 과목 CRUD는 다음 단계다.

## 2026-10-01 과목 목록·생성 API

`GET /api/subjects?gradeId=...`는 소유 학기의 과목을 생성 역순으로 반환하며 타인 학기는 기존 동작대로 빈 배열이다. 아직 자료 도메인이 없으므로 각 과목의 `materials`는 빈 배열이다. `POST /api/subjects`는 JSON `{"gradeId":"...","name":"..."}`와 CSRF 헤더로 과목을 만들고 201 `{ "ok": true, "subject": { ... } }`를 반환한다. 과목명은 공백 제거 후 1~50자다. 타인 학기 생성 403, 누락·잘못된 입력 400, 미인증 401, CSRF 누락 403. `SubjectService`가 학기 소유권을 확인하고 `ApiExceptionHandler`가 예상 오류를 안전한 JSON으로 변환한다. 실제 MySQL HTTP `SubjectCreateMysqlTests`, `test bootJar` 통과; 시험 행 정리 확인. SQL·의존성 추가는 없다. 수정·삭제는 다음 단계다.

## 2026-10-02 과목 수정·삭제 API

`PATCH /api/subjects`는 `{"subjectId":"...","name":"..."}`를 받아 `{ "ok": true, "subject": {"id":"...","name":"..."} }`를 반환한다. `DELETE /api/subjects`는 `{"subjectId":"..."}`를 받아 `{ "ok": true }`를 반환한다. 두 요청 모두 CSRF가 필요하고 Service가 과목의 회원 소유권을 확인한다. 빈 ID/과목명·50자 초과 이름은 400, 타인·없는 과목은 기존 계약대로 403이다. 예상 오류는 공통 `ApiExceptionHandler`에서 처리한다. 실제 MySQL HTTP `SubjectCreateMysqlTests`의 수정·삭제·권한·입력·CSRF 검증과 `test bootJar`가 통과했다. 현재 자료 테이블·업로드 기능이 없으므로 자료 연결 과목 삭제는 시험하지 못했다. 3차에서 자료 관계를 도입할 때 파일 정리 전 삭제를 제한해야 한다. SQL·의존성 추가는 없다.

## 2026-10-02 과목 workspace 조회

`GET /api/subjects/workspace?gradeId=...&subjectId=...`는 로그인 사용자의 학기·과목 목록과 선택 ID를 반환한다. 선택값이 없으면 현재 학기와 그 학기의 첫 과목을 고른다. 명시한 타인 학기·과목 또는 다른 학기의 과목 조합은 404로 차단한다. 자료 조회 전에 부모 관계를 검증해 기존 소유권 누락 경로(B1)를 개선한다. 실제 MySQL HTTP 통합 시험에서 정상·401·404와 부모 관계를 확인했으며 `test bootJar`가 통과했다. SQL·의존성 추가는 없다. 자료·요약·퀴즈 DTO는 후속 단계에서 연결한다.


## 2026-10-02 자료 모델 기반

`Material` 엔티티와 Repository를 추가하고 `src/main/resources/sql/onrelearn.sql`에 `material` 테이블을 추가했다. 과목 FK는 `ON DELETE RESTRICT`, 과목별 주차는 unique, 주차는 양수 CHECK다. 자료가 있는 과목의 `DELETE /api/subjects`는 공통 예외 응답으로 HTTP 409를 반환한다. 파일 삭제 구현 전 자료 유실을 막기 위한 정책이다.

실제 MySQL `onrelearn`에서 `MaterialMysqlTests`가 합성 자료의 저장·조회·수정·rollback, 소유자 범위, unique/FK/CHECK, 과목 삭제 제한, SQL 재실행을 확인했다. `SubjectCreateMysqlTests`, `test bootJar`도 통과했다. 입력·권한 API 자체는 이번 단위에 추가하지 않았고 업로드·스토리지·PDF 추출·화면은 아직 미구현이다. 새 의존성은 없다. 다음은 저장소 경계와 업로드 API다.

## 2026-10-02 자료 목록 API

`GET /api/materials?subjectId=...`는 JWT 인증 후 과목 소유권을 확인하고 주차순 자료 목록을 반환한다. 응답 필드는 기존과 같은 `id`, `week`, `title`, `storagePath`다. `subjectId` 누락은 400, 미인증은 401, 타인·없는 과목은 403이며 공통 예외 처리에서 안전한 JSON 오류로 변환한다. 실제 MySQL `MaterialListMysqlTests`가 정상·입력·권한을 검증했고 합성 행을 정리했다. `test bootJar` 통과. SQL·의존성 변경은 없다. 저장소와 업로드·PDF 추출·화면은 아직 미구현이다.

## 2026-10-02 PDF 저장소 경계

`PdfObjectStorage`와 `SupabasePdfObjectStorage`를 추가했다. 실행 시 OS/IDE 환경변수 `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `ONRELEARN_MATERIAL_BUCKET`(기본 `materials`), `ONRELEARN_STORAGE_TIMEOUT`(기본 `PT10S`)을 설정한다. 비밀값은 소스에 두지 않는다. 업로드는 `POST /storage/v1/object/{bucket}/{path}`로 PDF 바이트와 `x-upsert: false`를 전송한다. 보상 삭제는 `DELETE /storage/v1/object/{bucket}`에 지정 경로만 전송한다. 저장소 실패는 공통 503 오류 응답이며 내부 응답 내용을 노출하지 않는다.

로컬 HTTP 대역을 사용하는 `SupabasePdfObjectStorageTests`와 `test bootJar` 통과. 실제 Supabase 연결·권한은 미검증이며 업로드 API는 아직 이 경계를 호출하지 않는다. SQL·새 의존성은 없다. 다음은 자료 업로드의 소유권·입력·중복·DB/객체 정리 검증이다. API 응답의 정상·입력·권한 회귀도 그 단위에서 갱신한다.

## 2026-10-02 PDF 업로드 API

`POST /api/materials`에 multipart `subjectId`, `week`, `title`, `file`을 보낸다. JWT 쿠키와 CSRF 토큰이 필요하다. 과목 소유권, 양의 정수 주차, 1~255자 제목, `application/pdf`와 `%PDF-` 헤더를 확인한다. 기본 파일 한도 20 MiB는 `ONRELEARN_MATERIAL_MAX_BYTES`, multipart 요청 한도는 `ONRELEARN_MATERIAL_MAX_REQUEST_BYTES`로 설정한다. 정상 200은 `id`, `subjectId`, `week`, `title`, `storagePath`; 입력 오류 400, 타인 과목 403, 주차 중복 409, 저장소 오류 503, 업로드 크기 초과 413은 공통 예외 응답이다.

업로드는 과목 행 잠금과 MySQL 트랜잭션 내에서 새 Storage 객체를 만든 뒤 Material을 저장한다. 저장·커밋 실패 시 그 객체만 보상 삭제한다. 같은 잠금이 자료 있는 과목 삭제와 충돌을 줄인다. 실제 MySQL `MaterialUploadMysqlTests`에서 합성 계정·저장소 대역으로 정상·오류·권한·CSRF·중복을 검증하고 생성 행을 정리했다. `MaterialUploadCompensationTests`, 기존 `SubjectCreateMysqlTests`, `test bootJar` 통과. SQL·새 의존성 없음. 실제 Supabase는 미연결·미검증이며 PDF 추출 job·서명 URL·React 화면은 미구현이다. 외부 응답 유실이나 보상 삭제 실패는 후속 복구 정책이 필요하다.

## 2026-10-02 자료 서명 URL

`GET /api/materials/file_url?materialId=...`는 JWT 인증 후 자료 소유권을 확인하고 `{ "signedUrl": "..." }`을 반환한다. 기본 600초 TTL은 `ONRELEARN_SIGNED_URL_TTL_SECONDS`로 설정한다. 응답에 `Cache-Control: no-store`를 적용한다. 자료 ID 누락 400, 미인증 401, 타인·없는 자료 403, 저장소 실패 503이다. `PdfObjectStorage` 계약에 서명 기능을 추가했고 Supabase 상대 서명 경로가 요청한 버킷·객체에 속하는지 검증한다.

로컬 HTTP 대역의 서명 요청·악성/잘못된 응답 시험과 실제 MySQL `MaterialListMysqlTests` 정상·입력·권한·실패 HTTP 시험을 통과했다. 합성 행을 정리했고 `test bootJar`도 통과했다. SQL·새 의존성 없음. 실제 Supabase 서명·다운로드, React 자료 화면, PDF 추출 job은 아직 미검증/미구현이다.

## 2026-10-02 PDF 페이지 텍스트 저장 기반

`MaterialPageText`와 JPA Repository를 추가하고 JDBC 초기화 SQL에 `material_page_text` 테이블을 추가했다. 자료 FK, 자료·페이지 unique, 양수 페이지와 문자 수 제약을 둔다. 추출 텍스트가 없는 이미지 PDF도 빈 문자열로 저장할 수 있다. 기존 자료 행과 테이블을 삭제·초기화하지 않는다. 새 의존성·API 변경은 없다.

실제 `onrelearn` MySQL에서 합성 자료의 페이지 텍스트 저장·조회·수정, 중복/FK/CHECK 거부와 JPA `validate`를 검증했다. 시험 행은 롤백했다. `mysqlTest --tests com.onrelearn.MaterialPageTextMysqlTests`, `test bootJar`, 최종 주석·Lombok 변경 `compileJava` 통과. Job 큐·PDFBox 추출·처리 상태 API는 아직 없다. 다음은 Job 상태와 claim·재시도·잠금 회수이며 원본 worker의 B5/B6 결함을 수정해야 한다.

## 2026-10-03 추출 Job 상태 저장 기반

`ExtractionJob` 엔티티·Repository와 JDBC 초기화 SQL에 `extraction_job` 테이블을 추가했다. 자료 FK는 RESTRICT이고, 시도 횟수 CHECK와 조회 인덱스를 적용한다. 새 Job의 기본 상태는 PENDING이며 claim 한 번에 attempts를 정확히 한 번 올린다. 실패 시 실제 시도 횟수가 maxAttempts에 도달했을 때 FAILED가 된다. 오래된 RUNNING lease의 재시도/종료 상태 전이와 작업자 소유권 검사도 모델에 둔다. 기존 테이블/자료 삭제·초기화는 없다.

실제 `onrelearn` MySQL의 합성 Job 상태·FK·JPA `validate`를 `mysqlTest --tests com.onrelearn.ExtractionJobMysqlTests` 2건으로 확인하고 합성 행을 롤백했다. `test bootJar` 통과. 새 의존성·API 변경 없음. 동시 claim을 위한 MySQL 행 잠금, 업로드 후 Job 생성, PDF 다운로드·추출 및 상태 API는 아직 미구현이다. 다음은 `SKIP LOCKED` claim과 만료 잠금 회수를 트랜잭션 경계에서 검증한다.

## 2026-10-03 작업 선점·만료 회수 서비스

`ExtractionJobService.claimNext(workerId)`는 JPA Repository의 MySQL `FOR UPDATE SKIP LOCKED`로 대기 작업 하나를 선택하고 RUNNING 상태를 커밋한다. 잠긴 행은 건너뛰며 우선순위 내에서 생성순·ID순으로 선택한다. `recoverExpired()`는 만료된 RUNNING을 최대 100개씩 PENDING 또는 FAILED로 돌린다. [MySQL 잠금 읽기 공식 문서](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)의 큐 용례를 따른다.

시간은 DB의 UTC를 사용한다. `ONRELEARN_EXTRACTION_LEASE`는 ISO-8601 기간이며 기본 `PT5M`이다. `complete`/`fail`은 작업 행을 잠근 뒤 작업자, 시도 번호, 만료 여부를 확인한다. 같은 worker ID가 재선점했더라도 이전 시도는 `ExtractionLeaseLostException`으로 중단한다. 이 예외는 worker 내부 제어용이며 새 HTTP 응답은 추가하지 않았다. DB에는 지정된 실패 코드만 저장하고 외부 예외 원문은 저장하지 않는다.

선점·회수는 파싱을 포함하는 외부 트랜잭션으로 감싸지 않는다. worker는 다운로드·파싱 후 **페이지 저장과 소유권 재검사·완료를 하나의 트랜잭션으로 묶어야 한다**. 현재는 큐 서비스만 준비됐으며 스케줄러, 업로드 후 Job 생성, 결과 저장 연결은 아직 없다. 처리 시간이 lease를 넘으면 완료를 거부하므로 향후 worker에서 적절한 처리 제한 또는 lease 갱신을 검증해야 한다.

검증: `gradlew.bat mysqlTest --tests com.onrelearn.ExtractionJobClaimMysqlTests --tests com.onrelearn.ExtractionJobMysqlTests` 7건 및 `gradlew.bat test bootJar` 일반 시험 29건/빌드 통과. 신규 시험은 동시 선점 1회, 잠긴 상위 작업 건너뛰기, 만료/같은 worker 재선점 후 이전 시도 거부, 마지막 시도 만료, 롤백·잘못된 worker를 실제 MySQL로 확인한다. 기존 활성 작업이 있으면 변경 없이 중단하고, 동시성 시험 때문에 커밋한 합성 계정·자료·Job만 정확한 ID로 정리한다. 새 의존성·SQL 스키마·외부 API 변경은 없다.

## 2026-10-03 업로드 후 추출 작업 생성

`POST /api/materials`가 저장소 업로드 후 같은 DB 트랜잭션에서 Material과 ExtractionJob을 flush/커밋한다. 새 작업은 `EXTRACT_PDF`, `PENDING`, priority=0, attempts=0, maxAttempts=3이다. 정상 응답은 기존 자료 객체/200을 유지한다. 작업 저장/커밋 실패도 기존 업로드 보상 경계를 통과한다. 주차 unique 제약만 409이며, 그 외 `DataAccessException`은 `MaterialPersistenceException`으로 던져 공통 `ApiExceptionHandler`가 안전한 오류 문구와 500을 반환한다. SQL·예외 원문은 응답에 포함하지 않는다.

`gradlew.bat mysqlTest --tests com.onrelearn.MaterialUploadMysqlTests` 2건에서 새 작업 한 건의 생성, 중복/잘못된 입력/타인/CSRF/저장소 실패, 실제 MySQL 자료·Job flush 직후 강제 실패 시 두 행 롤백 및 업로드 경로 보상 삭제를 확인했다. 합성 데이터는 정확한 이메일/관계로 정리했다. 테스트 대역 오류로 중단된 합성 계정은 실행 시각·시험용 과목·자료 없음 조건으로 식별해 별도 정리했으며 일회성 정리 코드는 제거했다. 일반 Spring 시험 29건과 `bootJar` 통과. 새 의존성·SQL 변경은 없다.

worker는 아직 연결하지 않았으므로 실제 업로드 작업은 PENDING 상태로 대기한다. 다음 단계는 PDF 다운로드·PDFBox 추출이며, 추출 결과 저장과 lease 재검사/완료를 한 트랜잭션으로 묶어야 한다. 실제 Supabase 연동, 장시간 처리와 복구·상태 화면도 미검증이다. 큐 통합 시험은 실제 활성 작업이 있으면 실행을 중단하는 기존 가드를 유지한다.


## 2026-10-03 PDF 다운로드 경계 완료

- [x] `PdfObjectStorage.download(path, maxBytes)`와 Supabase 서버 인증 다운로드를 구현했다. 경로를 검증하고 Content-Length 유무와 무관하게 수신 크기를 제한하며, 본문 지연도 전체 timeout으로 취소한다. 리다이렉트/부분 응답/저장소 실패는 기존 안전한 저장소 예외로 전달한다.
- [x] 로컬 HTTP 대역 신규 시험 4건(정상 바이트·인증 헤더·잘못된 입력, 길이 헤더 유무별 한도 초과, 403/404/503/302/206, 본문 지연)과 일반 Spring 총 33건, `gradlew.bat test bootJar` 통과. SQL·외부 API·의존성 변경 없음. DB 시험은 변경이 없어 반복하지 않았다.
- [ ] 다음 시작점은 PDFBox 버전·Java 17/Boot 4.1.1 호환성 확인과 텍스트 추출 경계다. 실제 Supabase 다운로드와 브라우저 정상 파일 흐름은 미검증이다. worker는 아직 없어 업로드 작업은 대기하며 페이지 저장/lease 확인/완료의 단일 트랜잭션과 상태 API/UI도 남아 있다.

기존 저장소 URL·키·버킷·timeout 설정을 사용하고 호출자가 양수 `maxBytes`를 전달한다. 새 설정은 없다. PDF 구조 검증은 후속 파서의 책임이다. [Supabase 공식 다운로드 계약](https://supabase.com/docs/guides/storage/serving/downloads)을 참고했다.

## 2026-10-03 PDFBox 텍스트 추출 경계

`PdfTextExtractor`가 PDF 바이트를 페이지 순서대로 읽고 페이지 번호·텍스트 목록을 반환한다. 비어 있는 페이지도 보존한다. `onrelearn.material.extraction.max-pages`(기본 300)와 `onrelearn.material.extraction.max-chars-per-page`(기본 100000)는 OS/IDE 설정에서 바꿀 수 있다. 잘못된 PDF·초과 결과는 내부 `PdfExtractionException`으로 분류하며 원문은 사용자 응답에 내보내지 않는다. 이미지 OCR은 범위에 포함되지 않는다.

신규 의존성 `org.apache.pdfbox:pdfbox:3.0.8`을 Gradle에 이유 주석과 함께 추가했다. [Apache PDFBox 3.0.8 공식 배포](https://pdfbox.apache.org/download.html)는 Java 8 이상을 요구하며 프로젝트의 Java 17에서 실제 빌드·실행 시험을 통과했다. 신규 시험 2건은 3페이지의 순서·빈 페이지, 깨진 PDF·페이지/문자 한도·설정 오류를 검증했다. `gradlew.bat test bootJar` 전체 35건 통과. DB·SQL·HTTP API 변경은 없어 MySQL 시험은 반복하지 않았다. 다음은 Spring 내부 예약 실행기로 추출 작업을 처리하고, 페이지 저장과 lease 검사/완료를 한 트랜잭션으로 묶는 단계다. 실제 Supabase 다운로드·브라우저 정상 파일 흐름은 미검증이다.

## 2026-10-03 추출 결과와 작업 완료의 원자적 저장

`ExtractionJobService.completeWithPages`는 잠긴 Job의 작업자·시도 번호·lease를 확인한 뒤 같은 트랜잭션에서 페이지 결과를 저장하고 DONE으로 바꾼다. 페이지는 1부터 연속되어야 하며 기존 결과를 덮어쓰지 않는다. 만료·이전 시도·잘못된 결과는 예외를 던져 페이지와 Job 변경을 함께 롤백한다. 외부 API·SQL·의존성 추가는 없다.

실제 `onrelearn` MySQL의 `ExtractionJobClaimMysqlTests` 6건에서 신규 결과 저장·명시 롤백·만료 후 이전 시도 차단을 포함한 선점 회귀를 통과했다. 시험 합성 페이지·Job·자료·계정을 정확한 ID로 정리했다. `gradlew.bat bootJar`도 통과했다. 독립 Spring 내부 예약 실행기는 아직 연결하지 않아 실제 업로드 작업은 PENDING이며, 다운로드/파싱 후 이 완료 메서드를 호출하는 흐름과 처리 상태 API/UI가 다음 단계다.

## 2026-10-03 Spring 내부 PDF 추출 실행기

`ExtractionWorker.runOnce()`는 만료 작업을 회수하고 대기 Job 하나를 선점한 뒤, DB 잠금 밖에서 저장소 다운로드와 PDFBox 파싱을 수행한다. 결과는 기존 `completeWithPages`의 단일 트랜잭션에 저장한다. 저장소 실패·잘못된 PDF·기타 오류는 안전한 실패 코드로 분류하고, lease가 만료된 이전 시도는 새 작업의 결과를 바꾸지 않는다. 별도 worker 서버는 필요 없다.

실행 설정은 OS/IDE 환경변수 `ONRELEARN_MATERIAL_EXTRACTION_ENABLED=true` 또는 동등한 Spring 설정 키 `onrelearn.material.extraction.enabled=true`다. 기본값은 꺼짐이며, 실제 저장소 연결을 검증한 뒤 켠다. 폴링 간격은 `ONRELEARN_MATERIAL_EXTRACTION_POLL_DELAY_MS`(기본 5000ms), Job lease는 기존 `ONRELEARN_EXTRACTION_LEASE`(기본 PT5M)를 사용한다. 실행기가 활성화되면 기존 PENDING 작업도 처리 대상이다. 여러 앱 인스턴스는 DB 선점으로 중복 작업을 막지만 장시간 파싱의 lease 갱신은 아직 없다. 외부 응답 원문·비밀값은 실행기 로그에 기록하지 않는다.

`ExtractionWorkerTests` 신규 4건에서 빈 큐, 정상 다운로드→파싱→완료, 저장소 실패 분류, 만료 시도의 실패 보고 방지를 확인했다. `gradlew.bat test bootJar` 일반 시험 39건 통과. 이 실행에서 실제 MySQL과 저장소를 연결한 전체 자동 처리, 실제 Supabase, 브라우저 상태 화면은 검증하지 않았다. 기존 데이터·SQL·API·의존성 변경 없음. 다음은 합성 자료로 내부 실행기와 MySQL의 전체 흐름을 검증하고 상태 API/UI를 연결한다.

## 2026-10-03 실제 MySQL 내부 실행기 통합 시험

`ExtractionJobClaimMysqlTests`에 PDFBox로 만든 합성 PDF와 저장소 대역을 연결했다. `runOnce`의 Job 선점→다운로드→페이지 텍스트 저장→DONE을 실제 `onrelearn` MySQL에서 확인했다. 다운로드 실패 때 PENDING·안전한 오류 코드·페이지 없음으로 돌아가고 다음 시도에서 DONE/페이지 한 건이 되는 것도 확인했다. 기존 활성 Job이 있으면 시험이 DB를 바꾸기 전에 중단하는 가드를 유지한다. 합성 자료·페이지·Job·계정은 정확한 ID로 정리한다.

`gradlew.bat mysqlTest --tests com.onrelearn.ExtractionJobClaimMysqlTests` 8건 통과. 이번 변경은 시험 코드만이며 실행기·SQL·API·의존성은 바뀌지 않았다. 예약 폴링 자체의 실시간 기동, 실제 Supabase와 브라우저 정상 파일 흐름은 아직 미검증이다. 다음 시작점은 자료별 처리 상태 조회 API와 React 표시다.

## 2026-10-03 자료 처리 상태 조회 API

로그인한 소유자는 `GET /api/materials/status?materialId={id}`로 PDF 추출 상태를 조회한다. 응답은 `materialId`, `status`, `attempts`, `maxAttempts`이며 작업이 없는 기존 자료는 `NOT_SCHEDULED`, 0, 0을 돌려준다. 정상 업로드의 작업은 `PENDING`에서 시작해 `RUNNING`·`DONE` 또는 `FAILED`로 바뀐다. 오류 원문이나 저장소 경로는 상태 응답에 포함하지 않고 `Cache-Control: no-store`를 적용한다. materialId 누락은 400, 타인·존재하지 않는 자료는 403, 비로그인은 401이다.

실제 `onrelearn` MySQL의 `MaterialListMysqlTests`에서 기존 자료의 NOT_SCHEDULED, 새 작업의 PENDING/시도 한도, 400·401·403·캐시 정책을 검증했다. `gradlew.bat mysqlTest --tests com.onrelearn.MaterialListMysqlTests bootJar` 통과. 합성 자료·Job·계정은 정확한 ID로 정리했다. SQL·의존성 변경은 없고 새 읽기 전용 API만 추가했다. 다음은 React 자료 패널의 처리 상태 표시다.

## 2026-10-04 저장소 대역 PDF 전체 흐름 검증

합성 PDF를 실제 HTTP multipart로 업로드하고 저장소 대역의 바이트를 Spring 내부 실행기가 내려받아 `onrelearn` MySQL에 페이지 텍스트와 DONE 상태를 기록했다. 업로드 직후 PENDING, 처리 뒤 DONE·시도 1회, 소유자 서명 URL과 타인의 상태/URL 403을 검증했다. 기존 활성 Job이 있으면 worker 실행 전에 시험을 중단한다.

`gradlew.bat mysqlTest --tests com.onrelearn.MaterialUploadMysqlTests` 3건 통과. 합성 페이지·Job·자료·계정과 대역 객체를 정리했다. 시험 코드만 변경했으며 실제 Supabase, 예약 폴링 실시간 동작, 브라우저 파일 선택·상태 변화는 미검증이다. 다음은 브라우저 흐름 또는 예약 폴링 기동 검증이다.

## 2026-10-04 내부 예약 폴링 기동 검증

Spring 경량 컨텍스트에서 추출 예약을 활성화하면 설정된 50ms 간격으로 `ExtractionWorker.runOnce()`가 실제 호출되고, 기본 비활성 상태에서는 예약 빈이 생성되지 않아 worker가 호출되지 않는 것을 확인했다. `gradlew.bat test --tests com.onrelearn.ExtractionScheduleTests` 신규 2건 통과. 모의 worker만 사용해 개발 DB·기존 대기 작업·스토리지에 접근하지 않았다. 시험 코드만 변경했고 새 의존성·SQL·API 변경은 없다. 실제 Supabase와 브라우저 파일 흐름은 여전히 미검증이다.

## 2026-10-04 업로드 응답 유실 보상

`MaterialService.upload`는 원격 저장소 호출 전에 새 자료 객체 경로를 보상 대상으로 기록한다. 객체는 생성됐지만 응답이 유실되어 저장소 호출이 예외를 던져도 DB 트랜잭션을 롤백하고 해당 경로 삭제를 시도한다. 실제 `onrelearn` MySQL·합성 저장소 HTTP 회귀 시험에서 503, 자료/Job 없음, 객체 삭제를 확인했다. `MaterialUploadMysqlTests` 4건과 `bootJar` 통과; 합성 행 정리. 의존성·SQL·API 변경 없음. 실제 Supabase의 응답 유실과 보상 삭제 실패에 대한 사후 복구는 아직 검증·구현되지 않았다.

## 2026-10-04 추출 lease 갱신 경계

`ExtractionJobService.renew(claim)`은 현재 시도의 Job 행을 잠그고 DB UTC 기준으로 작업자·시도 번호·유효 lease를 확인한 후 잠금 시각을 연장한다. `ExtractionJob.renewLease`는 RUNNING과 소유권을 재확인하며 시도 횟수와 상태를 바꾸지 않는다. 실제 MySQL 합성 시험에서 정상 갱신·만료/이전 시도 차단을 확인했다. `ExtractionJobClaimMysqlTests` 9건과 `bootJar` 통과, 합성 행 정리. SQL·API·의존성 변경 없음. 긴 다운로드·파싱 중 주기 호출은 아직 연결하지 않았다.

## 2026-10-04 PDF 단계 사이 lease 갱신

`ExtractionWorker`가 저장소 다운로드 직후와 PDFBox 파싱 직후 `renew`를 호출한다. 소유권을 잃은 시도는 다음 처리·페이지 저장을 하지 않는다. `ExtractionWorkerTests` 5건, 실제 MySQL `ExtractionJobClaimMysqlTests` 9건, `bootJar` 통과; 합성 행 정리. 의존성·SQL·API 변경 없음. 다운로드나 파싱 한 단계가 lease보다 길어질 때의 주기 갱신·시간 제한은 남아 있다.

## 2026-10-04 PDF 처리 중 주기 갱신

`ExtractionWorker`는 한 Job 처리 중 daemon 스레드에서 `ExtractionJobService.renew`를 lease의 1/3 간격으로 호출한다. 갱신 실패·소유권 상실 시 뒤늦은 결과 저장을 막고 실행기를 닫을 때 갱신 스레드를 중단한다. `ONRELEARN_MATERIAL_EXTRACTION_MAX_PROCESSING` 기본 PT15M 이후에는 갱신하지 않고 늦은 결과를 거부한다. 긴 다운로드·처리 시간 초과를 포함한 단위 시험 7건, 실제 MySQL 시험 9건, `bootJar` 통과; 합성 행 정리. 허용 YAML 설정 외 SQL·API·의존성 변경 없음. 블로킹 I/O·PDF 파싱 자체의 즉시 취소와 상한 후 Job 상태 전이·재시도 대기, 실제 Supabase 장시간 동작은 다음 검증 대상이다.

## 2026-10-04 처리 상한 후 안전한 Job 실패

`ExtractionProcessingTimeoutException`을 소유권 상실과 구분한다. 상한 뒤 블로킹 단계가 반환되면 결과를 저장하지 않고 현재 소유 시도에만 `PROCESSING_TIMEOUT`을 기록한다. `ExtractionJob.fail`의 기존 시도 한도에 따라 PENDING 또는 FAILED가 된다. 실제 MySQL에서 PENDING·attempts=1·페이지 없음·안전 코드를 확인했다. `ExtractionWorkerTests` 7건, `ExtractionJobClaimMysqlTests` 10건, `bootJar` 통과; 합성 행 정리. 새 SQL·API·의존성 없음. 실행 중인 블로킹 호출을 즉시 취소하는 기능과 실제 Supabase 장시간 동작은 아직 없다.
## 2026-10-21 자료 수정 프론트 직접 검증

프론트의 로컬 합성 API만으로 자료 수정 취소·실패 입력 유지·성공 후 목록 갱신/새로고침 복원을 확인했다. 백엔드 코드·SQL·DB·외부 서비스 변경은 없다. 실제 Spring 오답 복습 통합 검증은 앞선 설정 전달 자동 승인 거부로 미완료이며, 독립 작업을 계속한다.


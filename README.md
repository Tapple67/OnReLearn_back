# On Re:Learn 백엔드

On Re:Learn은 강의 PDF를 올리고 쪽마다 메모를 남기면, 그 메모를 반영한 요약과 퀴즈를 만들어 주는 학습 서비스다. 이 저장소는 Spring Boot API 서버이며 화면은 프론트엔드 저장소(`OnReLearn_front`)에 있다.

## 작업 기간

2025.09 ~

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| 언어·프레임워크 | Java 17, Spring Boot 4.1 (Spring MVC), Gradle |
| 데이터 | MySQL, Spring Data JPA, JDBC 스키마 스크립트 |
| 인증·보안 | Spring Security, JWT(HTTP-only 쿠키), CSRF 토큰, bcrypt |
| 파일·문서 | Supabase Storage, Apache PDFBox |
| AI | OpenAI API, Gemini API (HttpClient 호출, SSE 스트리밍) |

## 팀 구성

Re:Boot팀 [2인]


## 주요 기능

- **계정과 학습 공간**: 가입·로그인, 학기(기본 12개)·과목·자료 관리
- **PDF 자료**: 업로드 후 저장소에 보관하고, 백그라운드 작업이 쪽별 텍스트를 추출한다. 자료를 지우면 하위 데이터와 파일을 함께 정리한다.
- **메모**: PDF 쪽마다 메모를 저장한다.
- **AI 요약**: 쪽 묶음 단위로 요약을 만들어 실시간으로 보여 준다. 학습 목표, 비교표, 원문 쪽 표시, 글자가 적은 쪽 안내가 들어간다.
- **AI 퀴즈**: 객관식·OX·단답 문항을 만들고 풀이·저장·제출·채점을 처리한다. 결과에서 오답 복습과 취약 주제 복습 퀴즈를 만들 수 있다.
- **학습 기록**: 날짜별 자료 학습 기록, 자료 통계, 학습 목표, 마이페이지

## 핵심 설계

- **AI 출력 검증**: 모델이 쓴 문장과 문항은 그대로 저장하지 않는다. 모델이 고른 쪽과 인용문을 실제 PDF 텍스트와 대조하고(`SourceEvidence`), 용어·숫자·부정 표현이 원문과 맞는 내용만 통과시킨다(`GroundedSummary`, `GroundedQuiz`). 근거가 부족하면 기존 저장본을 보존하고 오류를 돌려준다.
- **메모 반영**: 쪽에 연결된 메모를 프롬프트에 넣어 원문 선택과 출제에 쓰고, 메모와 원문을 연결해 요약 안내와 퀴즈의 메모 문항을 만든다. 메모는 사실 근거로 쓰지 않는다.
- **제공자 분리**: 요약·퀴즈 생성·검수마다 제공자(OpenAI/Gemini), 모델, timeout, 재시도를 따로 설정한다. 프롬프트는 `resources/prompts/`에 있고 버전으로 이전 결과와 구분한다.
- **스트리밍**: 요약과 퀴즈 생성은 SSE로 진행 상황을 보낸다. 응답이 시작된 뒤의 오류는 안전한 `error` 이벤트로 보낸다.
- **데이터 보호**: 모든 조회·변경은 소유자를 확인한다. 상태 변경 요청은 CSRF를 검사하고, 예상 가능한 오류는 공통 처리기에서 내부 정보 없이 응답한다.

## 구조

```
on-re-learn/src/main/java/com/onrelearn/
├── auth       가입·로그인·JWT·CSRF
├── semester   학기
├── subject    과목
├── material   PDF 업로드·추출·삭제
├── note       쪽별 메모
├── summary    AI 요약
├── quiz       AI 퀴즈 생성·풀이·채점
├── ai         제공자 설정, 원문 대조
├── activity   학습 기록
├── stats·goal·profile·home   통계, 목표, 마이페이지, 홈
└── shared     공통 오류 처리
```

도메인마다 `controller → service → repository → entity` 순서로 나눈다. 데이터는 계정 → 학기 → 과목 → 자료로 이어지고, 쪽 텍스트·메모·요약·퀴즈 세트가 자료에, 문항·풀이·답안이 퀴즈 세트에 연결된다. 스키마 원본은 `src/main/resources/sql/onrelearn.sql`이다.

## 실행 방법

`on-re-learn` 폴더에서 실행한다. Windows는 `./gradlew.bat`을 쓴다.

```bash
./gradlew bootRun    # 127.0.0.1:8080
./gradlew test       # DB 없이 도는 시험
./gradlew bootJar    # 산출물: build-onrelearn/libs
```

- 기본 설정은 `src/main/resources/onrelearn.yml`에 있다.
- DB 접속, JWT 비밀, AI·저장소 키는 `config/keys.example.yml`을 참고해 로컬 설정 파일이나 환경변수로 넣는다. 비밀 값은 저장소에 올리지 않는다.
- MySQL에 `onrelearn` 데이터베이스가 있어야 한다. 테이블은 기동할 때 스키마 스크립트로 만든다.


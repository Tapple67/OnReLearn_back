# On Re:Learn 백엔드 전환 안내

현재 Spring Boot 프로젝트는 [on-re-learn](on-re-learn/README.md) 내부에 있다. 2026-09-30에 개발 MySQL onrelearn에서 JDBC SQL 초기화·JPA 검증·Spring 재기동과 상태 API 200 UP을 확인했다. 사용자가 같은 개발 DB의 합성 데이터 시험을 허용한 뒤 CRUD·제약·rollback 통합 시험 6건도 통과했다.

1차 인증에서 회원가입 API와 기본 12개 학기 생성, 로그인 HTTP API까지 구현했다. 권한과 프론트 로그인 화면은 이어서 구현한다. 실제 검증 내용은 [내부 프로젝트 README](on-re-learn/README.md)를 따른다.

로그인 자격증명 비교 서비스도 추가해 기존 bcrypt 계정의 정상·오류 비밀번호를 실제 MySQL에서 검증했다.

JWT 발급·서명·만료 검증 서비스와 `POST /api/auth/login`의 HTTP-only 쿠키 발급을 추가했다. 키는 OS/IDE의 `JWT_SECRET`로 주입한다. 로그인 API의 입력 오류 400·자격증명 오류 401·설정 오류 503을 검증했다.

`GET /api/auth/me`에서 쿠키 서명과 회원 존재를 검사해 ID·이메일만 반환한다. 정상·누락·변조·삭제 계정 경로와 실제 MySQL 조회를 검증했다.

Spring Security의 `/api/**` 보호 필터를 추가했다. 상태·CSRF 조회·가입·로그인과 쿠키 정리용 로그아웃은 인증 없이 접근할 수 있고, 다른 API는 JWT 쿠키 인증이 필요하다. 로그아웃을 포함한 상태 변경 요청은 CSRF 토큰을 요구한다. SPA는 `/api/auth/csrf`의 읽기 가능한 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보낸다. 신규 의존성은 Boot BOM 관리 `spring-boot-starter-security`이며 [내부 README](on-re-learn/README.md)에 검증 결과를 기록했다.

실제 `onrelearn` MySQL의 합성 계정으로 Spring↔Vite↔브라우저 가입·로그인·새로고침·로그아웃·비로그인 재접근을 확인했다. 합성 계정은 별도 `cleanupSyntheticE2e` 시험으로 삭제 확인했다. 실행법과 보호 조건은 [내부 README](on-re-learn/README.md)에 기록했다.

같은 합성 이메일의 실제 MySQL 서비스 동시 가입 시험에서 성공 1건·중복 실패 1건과 기본 학기 12건을 확인하고 합성 계정을 정리했다.

추가로 두 독립 CSRF 세션의 동시 HTTP 가입도 실제 Spring·MySQL에서 200·409 각 1건을 확인했다. 합성 계정은 시험 안에서 삭제·부재를 확인했다.

실제 HTTP에서 정상 JWT 200, 쿠키 누락·만료·변조 401과 로그아웃 CSRF 누락·오류 403/정상 200을 확인했다. 시험용 합성 회원은 삭제 확인했다.

가입·로그인·현재 사용자 조회의 예상 오류는 `shared/error/ApiExceptionHandler.java`가 공통으로 응답한다. 중요한 인증 흐름과 보안 제약에는 Better Comments `// *`·`// !` 주석을 달았다. 기존 상태 코드를 유지했고 `/api/auth/me`의 인증 설정 오류 503은 안전한 JSON 오류 본문을 반환한다. 세부 검증은 [내부 README](on-re-learn/README.md)에 있다.

보호된 `GET /api/home`은 로그인 회원의 현재 학기·과목을 조회한다. 실제 MySQL에서 비로그인 401과 타 회원 과목 비노출을 검증했다. 실행·시험 결과는 [내부 README](on-re-learn/README.md)에 기록했다.

CSRF를 확인하는 `POST /api/auth/logout`은 만료된 JWT가 있어도 쿠키를 지울 수 있으며 `token`·`XSRF-TOKEN`을 만료한다. 쿠키 정책은 `AuthCookieService`에서 공유한다. 서버 측 JWT 폐기 목록은 아직 없으므로 유출 토큰의 재사용 가능성은 남는다.

[전체 지침](../README.md), [기존 분석](../docs/legacy-analysis.md), [차수별 계획](../docs/migration-plan.md)을 따른다. application.properties의 Spring 자동 로딩만 허용됐으며 에이전트 직접 읽기·쓰기·복사는 금지다. .env 파일군은 자동 로딩까지 금지다.

설정 경로는 Java 코드가 아닌 `bootRun` 기본 실행 인수 또는 IDE/OS의 `SPRING_CONFIG_LOCATION`으로 전달한다. 외부 properties를 마지막에 로딩해 같은 키에서는 이 값이 우선한다. 자세한 명령은 [내부 프로젝트 README](on-re-learn/README.md)를 따른다.

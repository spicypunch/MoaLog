# Environment Setup

## 앱

- JDK 17
- Android SDK 36
- Xcode 및 iOS Simulator
- iOS deployment target 17.2
- `local.properties.example`을 복사해 `local.properties`를 만들고 실제 SDK 경로만 입력한다.

## 서버

- Docker Desktop 또는 Docker Engine/Compose
- PostgreSQL 17, 데이터베이스와 애플리케이션 스키마 이름은 각각 `moalog`
- `.env.example`을 `.env`로 복사하고 로컬 전용 비밀번호를 설정한다. `.env`는 Git에서 제외된다.

```bash
cp .env.example .env
docker compose up -d postgres
set -a
source .env
set +a
./gradlew :server:bootRun --args='--spring.profiles.active=local'
```

서버 확인 주소는 `http://localhost:8080/actuator/health`다. 종료는 `docker compose down`, DB 볼륨까지 초기화하려면 데이터 삭제를 이해한 상태에서 `docker compose down -v`를 사용한다.

기본 설정에는 DB 접속 기본값이 없다. 로컬 프로필은 URL과 사용자 이름에만 개발 기본값을 제공하고 비밀번호는 `SPRING_DATASOURCE_PASSWORD` 또는 `POSTGRES_PASSWORD`가 반드시 있어야 한다. 운영 환경은 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`를 비밀 저장소에서 직접 주입하고 `local` 프로필을 사용하지 않는다.

인증 서버는 `MOALOG_AUTH_ACCESS_TOKEN_ISSUER`, `MOALOG_AUTH_ACCESS_TOKEN_AUDIENCE`, `MOALOG_AUTH_ACCESS_TOKEN_SECRET_BASE64`, `MOALOG_AUTH_GOOGLE_AUDIENCE`, `MOALOG_AUTH_APPLE_AUDIENCE`가 필요하다. 액세스 토큰 서명 키는 `openssl rand -base64 32`로 환경별로 생성하고 비밀 저장소에서 주입한다. 실제 OAuth client ID와 서명 키는 `.env.example`의 자리표시자를 그대로 사용하지 않는다.

iOS Signing Team, 운영 API 주소, OAuth 키는 로컬/CI 비밀 저장소에서 주입한다. 저장소에는 실제 값을 넣지 않는다.

# MoaLog · 모아로그

부부가 함께 수입, 지출, 저축과 자산을 기록하고 연간 재무 흐름을 관리하는 가계부 앱입니다. Google Sheets로 관리하던 부부 가계부의 계산과 기록 흐름을 Android와 iOS 앱으로 옮긴 프로젝트입니다.

[![Verification](https://github.com/spicypunch/MoaLog/actions/workflows/verification.yml/badge.svg)](https://github.com/spicypunch/MoaLog/actions/workflows/verification.yml)

## 주요 기능

- 부부 공동 가계부 생성 및 초대 코드로 구성원 연결
- 월별 수입·고정지출·변동지출·저축/투자 기록
- 연간 목표, 저축률, 달성률과 지출 구성 요약
- 월급 배분, 고정비 점검과 여러 달 일괄 적용
- 과소비 표시, 카테고리 필터와 거래 내역 관리
- 월별 자산 추이, 목적통장과 관리비 관리
- 오프라인 우선 저장과 로그인 후 기기 간 동기화

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| 공통 로직 | Kotlin Multiplatform, Coroutines, Flow, Koin |
| Android | Jetpack Compose, Navigation 3, Android Credential Manager |
| iOS | SwiftUI, SharedKit KMP Framework |
| 네트워크 | Ktor Client, Kotlinx Serialization |
| 로컬 데이터 | Room KMP, SQLite |
| 백엔드 | Kotlin, Spring Boot MVC, Spring Security, Spring Data JPA |
| 데이터베이스 | PostgreSQL, Flyway |
| 테스트 | Kotlin Test, JUnit 5, Spring Boot Test, Testcontainers, Android Instrumentation |
| CI | GitHub Actions |

## 구조

```text
MoaLog
├── composeApp/          # Android 앱과 Jetpack Compose UI
├── iosApp/              # iOS 앱과 SwiftUI UI
├── shared/              # 인증, 가계부 세션, 동기화 및 DI
├── app-shell/           # 앱 내비게이션과 화면 상태 연결
├── core/
│   ├── contracts/       # 앱·서버 공용 API 계약
│   ├── database/        # Room KMP 로컬 데이터베이스
│   ├── designsystem/    # Android 디자인 시스템
│   ├── model/           # 공통 도메인 모델과 계산 규칙
│   └── network/         # Ktor Client 기반 API 클라이언트
├── feature/             # 홈, 계획, 기록, 자산, 관리, 초기 설정
└── server/              # Kotlin Spring Boot API 서버
```

공통 도메인·데이터·동기화 로직은 KMP로 공유하고, 화면은 Android의 Jetpack Compose와 iOS의 SwiftUI로 각각 구현합니다. 앱은 Room에 먼저 저장한 뒤 변경 내역을 서버와 동기화하며, 서버는 버전 기반 충돌 처리와 삭제 이력을 관리합니다.

## 로컬 실행

### 준비 사항

- JDK 17
- Android Studio와 Android SDK
- Xcode
- Docker Desktop 또는 호환되는 Docker 환경

### 서버

```bash
cp .env.example .env
docker compose up -d postgres
set -a && source .env && set +a
./gradlew :server:bootRun --args='--spring.profiles.active=local'
```

### Android

`local.properties.example`을 참고해 Android SDK 경로를 설정한 뒤 실행합니다.

```bash
./gradlew :composeApp:assembleDebug
```

### iOS

`iosApp/iosApp.xcodeproj`를 Xcode에서 열어 `iosApp` 스킴을 실행합니다.

## 테스트

```bash
# 앱 공통 로직과 앱 셸
./gradlew :shared:allTests :app-shell:testDebugUnitTest

# Spring Boot 기본 통합 테스트
./gradlew :server:test

# Docker 기반 PostgreSQL·Flyway 통합 테스트
./gradlew :server:postgresIntegrationTest

# Android 에뮬레이터 기반 Room 테스트
./gradlew :core:database:connectedDebugAndroidTest
```

Pull Request와 `main` 브랜치 푸시 시 GitHub Actions에서 KMP, Android, iOS 시뮬레이터, Spring Boot, PostgreSQL 테스트를 실행합니다.

## 문서

- [아키텍처](ARCHITECTURE.md)
- [개발 환경 설정](docs/environment-setup.md)
- [검증 방법](docs/validation.md)
- [제품 기능 명세](docs/product/moalog-spec.md)

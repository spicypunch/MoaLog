# Validation

가장 작은 범위부터 실행한다.

```bash
./gradlew :core:model:allTests
./gradlew :feature:home:allTests
./gradlew :feature:setup:allTests
./gradlew :feature:records:allTests
./gradlew :feature:plan:allTests :feature:assets:allTests :feature:maintenance:allTests
./gradlew :app-shell:testDebugUnitTest
./gradlew :core:database:kspDebugKotlinAndroid :core:database:kspKotlinIosSimulatorArm64
./gradlew :server:test
./gradlew :shared:compileKotlinIosSimulatorArm64
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :composeApp:compileDebugKotlin
./gradlew :composeApp:assembleDebug
```

서버의 일반 테스트는 Docker 없이 H2 격리 설정으로 실행된다. PostgreSQL과 Flyway 실제 호환성은 Docker가 실행 중일 때 별도 태스크로 확인한다.

```bash
./gradlew :server:postgresIntegrationTest
```

이 태스크는 Testcontainers로 PostgreSQL 17을 시작한다. Docker가 없는 환경에서는 일반 `:server:test` 결과와 구분해 미실행으로 보고한다.

로컬 서버와 DB를 함께 확인할 때는 `docs/environment-setup.md` 순서로 PostgreSQL을 실행한 뒤 다음을 확인한다.

```bash
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8080/api/system/info
```

iOS Swift 호스트를 변경한 경우 다음처럼 generic simulator 대상으로 `xcodebuild`를 실행한다.

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

컴파일 성공은 에뮬레이터·시뮬레이터·실기기 QA를 대신하지 않는다. `NO-SOURCE`는 테스트 성공 증거가 아니다.

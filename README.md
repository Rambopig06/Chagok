# 차곡 (Chagok)

> 한 대의 차를 여러 사람이 함께 쓸 때, 차에 남긴 말과 할 일을 **다음 운전자에게, 도착한 장소에서** 음성으로 전해 주는 Pleos Connect 차량용 앱

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. Android Automotive OS 기반 Pleos Connect에서 동작합니다.

## 왜 필요한가

- 가족 차, 카셰어링, 법인 업무용 차처럼 **한 대를 여러 사람이 번갈아 탈 때** "타이어 경고등이 켜졌어요", "뒷좌석에 우산 두고 내렸어요" 같은 말이 다음 사람에게 전해지지 않습니다.
- 기존 카셰어링·법인차 관리 앱은 관리자가 차 상태를 기록하는 구조이고, **운전자끼리 말을 주고받는 기능**은 없습니다.
- 휴대폰의 "위치 도착 알림"은 차 화면·차량 데이터와 연결되지 않습니다.

차곡은 **차를 바꿔 타는 순간(주차 → 다음 출발)**과 **내비 도착 순간**을 차가 직접 알아차리고, 그때 음성으로 전해 줍니다.

## 주요 기능

| 장면 | 동작 | 사용 기술 |
|---|---|---|
| 주차(P)하면 | "다음 운전자께 남길 말씀이 있으신가요?" 묻고 바로 듣기 → 메모 저장 (받는 사람 선택) | 기어 상태, TTS, STT |
| 다음 사람이 출발(P → D)하면 | 안 들은 메모를 화면과 **음성으로 전달**, 그사이 주행 거리·도착 할 일 개수도 안내 | 기어·주행 거리, TTS |
| 도착 할 일 등록 | "마트 도착하면 우유 사라고 알려 줘" → **장소: 마트 / 할 일: 우유 사기** 자동 분리, 내비 목적지로 장소 지정 가능 | STT, NaviHelper |
| 주행 중 | 큰 글씨 최소 화면(다음 할 일, 남은 거리) + "말로 남기기" 버튼 하나 | CarUxRestrictions, 주행 상태 판단 |
| 목적지 도착 | "이마트 성수점에 도착했습니다. 우유 사기, 잊지 마세요." + 도착 알림 화면 | NaviHelper 도착 이벤트, TTS |
| 메모 관리 | 남긴 말을 터치 → **삭제**(전달된 메모·내가 쓴 메모만) 또는 **AI 도움**: 글레오가 메모를 보고 정비·점검 등 해결책을 제안하고 읽어 줌 | Pleos LLM SDK (서버 → 기기 안 순서), TTS |
| **자동 차량 상태 인수인계** | 주차(P)할 때 문·트렁크·보닛·창문 열림, 충전구, 배터리 잔량을 차곡이 스스로 기록 → 다른 운전자가 출발할 때 "지난 주차 때 트렁크가 열린 채 주차됐어요"처럼 전달 | Vehicle SDK (Door, Window, EvBattery/Fuel), TTS |
| **하차 안전 체크** | 운행 중 뒷좌석 문이 열렸었다면 주차할 때 "뒷좌석에 두고 내린 물건이나 아이는 없는지 확인해 주세요" 안내. 열린 창문·트렁크·충전구도 알림. 주차 후 뒷좌석 문을 열면 확인 처리 | Vehicle SDK (Door 실시간 감지, Window) |
| **운전자별 운행일지** | 출발(D)~주차(P)마다 운전자별 주행 거리·시간·배터리 사용량 자동 기록, 최근 7일 합계와 비율, 충전·주유비를 주행 거리 비율로 나누기 | 주행 거리·속도, EvBattery/Fuel |
| 운전자 전환 | 왼쪽 메뉴의 드롭다운에서 지금 운전자 선택·**+ 운전자 추가**. 바꾸면 새 운전자가 안 들은 메모를 "오늘 오후 3시 20분에 아빠님이 남긴 메모입니다"처럼 언제·누가 남겼는지부터 읽어 줌 | TTS |
| 상태 복원 | 메모·할 일·운전자 목록·운행일지·주차 기록을 기기에 저장, 앱을 다시 켜도 유지 | SharedPreferences |

### 운전자 주의 분산 대응

- 메모 목록, 할 일 입력 같은 복잡한 화면은 **주차 중에만** 보입니다.
- 주행 중에는 큰 글씨 2~3줄과 버튼 하나만 표시하고, 전달은 **음성**으로 합니다.
- 앱은 Distraction Optimized로 선언하고, 플랫폼의 CarUxRestrictions(콘텐츠 제한)를 따라 화면을 자동 전환합니다.

## APK 위치

`apk/ScienceCrew_Chagok_release.apk`

팀명과 앱 이름은 `gradle.properties`의 `pleos.teamName`, `pleos.appName`만 바꾸면 파일 이름에 반영됩니다.

## 테스트 환경

- macOS (Apple Silicon), Android Studio 2026.2.2
- **Pleos Connect Emulator v2.1.0** (시스템 이미지: Pleos Connect v2.1.0 APIs arm64, API 34)
  - AVD 부팅 옵션: **Cold boot** (Quick boot이면 차량 데이터 VHAL이 동작하지 않음)
  - RAM 4GB, 화면 2560×1440
- **CRN 주입**: 에뮬레이터 2.1.0부터는 필요 없음 (공식 문서 "프로젝트 생성 및 SDK 사용 신청" 기준)
- **Pleos Connect Virtual Console**: 에뮬레이터에 내장된 차량 상태 조작 도구
- 앱 설정: minSdk 28, targetSdk 34, Kotlin + Jetpack Compose

## 실행 방법

### 1. APK로 바로 설치

```bash
adb install -r apk/ScienceCrew_Chagok_release.apk
adb shell am start -n com.sciencecrew.chagok/.MainActivity
```

처음 실행하면 "위치 정보 허용" 팝업이 뜹니다. 차량 속도 권한(CAR_SPEED)이 안드로이드에서 위치 그룹에 묶여 있어서 그렇습니다. **허용**을 누르세요.

### 2. 소스에서 빌드

```bash
./gradlew installDebug          # 디버그판 빌드 + 에뮬레이터 설치 (시연용 신호 포함)
./gradlew exportReleaseApk      # 릴리스 APK → apk/ 폴더에 제출용 이름으로 저장
```

- `./gradlew`는 JAVA_HOME이 없으면 Android Studio에 들어 있는 Java를 자동으로 씁니다.
- 디버그판과 릴리스판은 서명이 달라서, 바꿔 설치할 때는 먼저 `adb uninstall com.sciencecrew.chagok`가 필요합니다.
- 릴리스 빌드에는 저장소 루트의 `keystore.properties`와 `keystore/release.jks`가 필요합니다. 보안상 git에 올리지 않습니다.
- Android Studio 실행 목록에 "제출용 APK 만들기", "릴리스 APK 설치", "Virtual Console 연결"이 등록되어 있습니다(`.run/`).

## 시연 순서 (Virtual Console)

```bash
adb forward tcp:8088 tcp:8088     # Virtual Console 연결 (에뮬레이터를 재시작할 때마다)
adb emu avd hostmicon             # 맥 마이크를 에뮬레이터에 연결 (재시작하면 꺼지므로 매번)
```

Android Studio 실행 목록의 **"Virtual Console 연결 + 마이크 켜기"**를 누르면 두 가지를 한 번에 합니다.

브라우저에서 http://127.0.0.1:8088/ 을 엽니다. 트렁크·충전구가 열려 있으면 출발할 때 시스템 경고가 뜹니다. 자동 차량 상태 인수인계를 보여 줄 장면이 아니면 Convenience·Energy 메뉴에서 닫아 두세요.

| 순서 | 조작 | 앱 반응 |
|---|---|---|
| 1 | (주차 중) 홈에서 **남김말 녹음** → 말하기 → **남기기** | 메모 저장, "남겨 두었어요" 음성 |
| 2 | **도착 할 일 추가** → "마트 도착하면 우유 사라고 알려 줘" → **저장** | 장소·할 일 자동 분리 후 저장 |
| 3 | Emulation Controls → **Door Position**에서 Rear left **Open → Close** (아이·짐을 태운 장면) | 뒷좌석 문 사용 기록 |
| 4 | 왼쪽 메뉴 **지금 운전자** 드롭다운에서 엄마님 선택 | "엄마님으로 운전자를 바꿨어요. … 남긴 메모입니다" 음성 |
| 5 | Gear Selection **D** | 출발 전 화면 + 이전 운전자 메모와 **차곡이 확인한 지난 주차 상태** 음성 전달 |
| 6 | 메인 화면 **ACCEL** 길게 누르기 | 주행 중 단순 화면 |
| 7 | 도착 신호 보내기 (아래 시연용 신호) | 도착 알림 화면 + "우유 사기, 잊지 마세요" 음성 |
| 8 | **BRAKE**로 0 km/h → Gear Selection **P** | **SAFETY 안내**("뒷좌석에 두고 내린 물건이나 아이는 없는지…", 열린 트렁크·충전구) + "다음 운전자께 남길 말씀이 있으신가요?" |
| 9 | 왼쪽 메뉴 **운행일지** | 운전자별 주행 거리·시간·비율, 충전·주유비 나누기 |

### 시연용 신호 (debug 빌드에만 포함, release APK에는 없음)

에뮬레이터 내비는 실제로 목적지에 도착하지 않고, 에뮬레이터 마이크는 환경에 따라 동작하지 않을 수 있어서 시연용 신호를 둡니다.

```bash
# 목적지 도착 흉내
adb shell am broadcast -a com.sciencecrew.chagok.DEMO_ARRIVE -p com.sciencecrew.chagok --es place "'이마트 성수점'"

# 마이크 대신 "이 문장을 말했다"고 처리 (지금 열린 화면에 맞게 들어감)
adb shell am broadcast -a com.sciencecrew.chagok.DEMO_SAY -p com.sciencecrew.chagok --es text "'뒷좌석에 우산 두고 내렸어요'"
```

### 실제 음성 입력

`adb emu avd hostmicon`을 실행하거나, 에뮬레이터 창 툴바 **⋯ (Extended Controls) → Microphone → "Virtual microphone uses host audio input"**을 켠 뒤 말합니다. 이 스위치는 에뮬레이터를 다시 켜면 꺼집니다. macOS 시스템 설정 → 개인정보 보호 및 보안 → 마이크에서 에뮬레이터(qemu) 접근도 허용되어 있어야 합니다. 말이 잠깐 끊겨도 이어서 받아 적고, **말을 마친 뒤 2초 조용하면** 자동으로 끝납니다(아무 말이 없으면 8초 뒤 종료). 안내 음성이 끝나고 0.7초 뒤에 듣기 시작하니, 맥 스피커 대신 이어폰을 쓰면 안내 음성이 마이크로 다시 들어가지 않아 더 정확합니다. 주차 중에는 화면의 **직접 입력**으로 글자를 쳐서 남길 수도 있습니다.

### 로그로 확인

```bash
adb logcat -s Chagok Condition DrivingState VoiceGuide SpeechInput NaviRepo Gleo
```

## 사용 권한

모두 읽기 전용입니다. 차량 제어 권한(HVAC, Seat, Window, Door 등)과 마이크 권한(RECORD_AUDIO)은 쓰지 않습니다.

| 권한 | 종류 | 용도 |
|---|---|---|
| `pleos.car.permission.CAR_DRIVE` | 일반 | 기어, 주행 거리 (Pleos Vehicle SDK) |
| `pleos.car.permission.CAR_DOORS` | 일반 | 문·트렁크·보닛 열림, 뒷좌석 문 열림 감지 (읽기만) |
| `pleos.car.permission.CAR_WINDOWS` | 일반 | 창문 열림 (읽기만) |
| `pleos.car.permission.CAR_SAFETY` | 일반 | 경고등 조회 (주차 시 1회). `PRIVATE_CAR_SAFETY`는 사용하지 않음 |
| `pleos.car.permission.CAR_ENERGY` | 일반 | 배터리 잔량·충전구 상태 (운행일지 배터리 사용량, 충전 필요 안내) |
| `android.car.permission.CAR_POWERTRAIN` | 일반 | 주차 브레이크 (AAOS) |
| `android.car.permission.CAR_SPEED` | 위험 (실행 중 허락) | 차량 속도 → 주행/정차 판단 (AAOS) |
| `pleos.car.permission.TTS_SERVICE` | 일반 | 음성 안내 |
| `pleos.car.permission.STT_SERVICE` | 일반 | 음성 입력 (마이크는 Pleos 서비스가 직접 켬) |
| `pleos.car.permission.NAVI_ROUTE` | 일반 | 목적지·남은 거리·시간·도착 이벤트 |
| `pleos.car.permission.LLM_SERVICE` | 일반 | 'AI 도움'을 누를 때만 메모 내용을 기기 안 LLM(글레오)에 보냄 |
| `android.permission.INTERNET` | 일반 | Pleos 음성 SDK가 자동으로 추가 (ON_DEVICE 모드라 실제로 쓰지 않음) |

## 사용 SDK

| SDK | 버전 |
|---|---|
| `ai.pleos.playground:Vehicle` | 2.3.2 |
| `ai.pleos.playground:TextToSpeech` | 3.3.1 (ON_DEVICE) |
| `ai.pleos.playground:SpeechToText` | 3.3.1 (ON_DEVICE) |
| `ai.pleos.playground:NaviHelper` | 3.0.5 |
| `ai.pleos.playground:LLM` | 2.1.3.2 (서버 모드 → 기기 안 모드 순서. 공식 문서 페이지 없음 → SDK 파일 기준으로 연동) |

저장소: `https://nexus-playground.pleos.ai/repository/maven-releases/`

## 디자인

- 배경 #F6F6F6, 대표색 검정 #111111, 버튼 글씨 흰색, 가는 구분선 중심의 미니멀 레이아웃
- 서체: **IBM Plex Sans KR** (SIL Open Font License 1.1, 라이선스 전문 `licenses/OFL-IBMPlexSansKR.txt`)
  - 시안에 쓴 열린고딕은 앱 임베딩에 윤디자인의 별도 허락이 필요해 앱에는 넣지 않았습니다. 허락을 받으면 `app/src/main/res/font`의 파일 두 개만 교체하면 됩니다.

## 폴더 구조

```
app/src/main/java/com/sciencecrew/chagok/
├── PleosApplication.kt          앱 전체가 공유하는 모듈 생성
├── MainActivity.kt              시작 화면 (속도 권한 요청)
├── chagok/                      차곡 흐름(주차·출발·도착), 메모·할 일·운행일지 저장, 차량 상태 점검(VehicleCondition)
├── ui/                          차곡 화면(홈·녹음·할 일·전달·주행·도착·메모 상세·운행일지)과 디자인 토큰
├── vehicle/                     차량 상태 읽기 (Vehicle SDK + AAOS Car API)
├── driving/                     주행 상태 판단 + 화면 규칙 (CarUxRestrictions)
├── voice/                       음성 안내(TTS), 음성 입력(STT), 글레오(LLM)
└── navi/                        내비 정보·도착 이벤트 (NaviHelper)
app/src/debug/                   시연용 신호 (debug 빌드 전용)
```

## 알려진 제한

- 누가 운전 중인지는 차가 알 수 없어서, 데모에서는 왼쪽 메뉴의 **지금 운전자** 드롭다운으로 고릅니다. 실제 서비스에서는 Pleos 프로필·디지털 키와 연동하는 것을 목표로 합니다.
- 에뮬레이터 내비는 차량 속도에 맞춰 이동하지 않아 실제 도착 이벤트가 오지 않습니다. 시연은 debug 빌드의 도착 신호로 합니다.
- 주행 중 화면의 남은 거리 표시는 내비가 안내 상태를 보낼 때만 나타납니다.
- Pleos Connect Emulator v2.1.0은 기기 안 LLM이 꺼져 있어(`on-device LLM is disabled`) 글레오가 응답하지 않습니다. 10초 동안 응답이 없으면 메모 단어에 맞춘 **시연용 예시 답변**을 보여 주며, 화면에 "시연용 예시 답변 · 실제 글레오 응답 아님"으로 표시합니다.
- **경고등(타이어 공기압 TPMS 등)은 읽히지 않습니다.** SDK에 표시된 권한(`CAR_SAFETY`)은 허용되어 있지만, 경고등 정보(TELLTALES)는 SDK 내부에서 일반 앱이 받을 수 없는 권한을 요구해 "need Permission"으로 막힙니다. 이 경우 조용히 건너뛰고 문·창문·충전구·배터리만으로 기록합니다. Pleos 권한 승인 시 경고등까지 확장할 예정입니다.
- 차종에 없는 부위(예: 선루프)는 조회가 실패하면 건너뜁니다.
- 글레오 서버 모드는 Playground 클라이언트 ID·시크릿(`local.properties`의 `pleos.clientId`/`pleos.clientSecret`)이 필요하고, 현재 Playground SDK 목록에 LLM이 없어 실제 응답은 확인하지 못했습니다.
- TextToSpeech / SpeechToText는 Pleos Playground에서 아직 "준비 중"입니다. 앱 등록이 필요 없는 ON_DEVICE 모드로 사용합니다.

## 향후 계획: 글레오 음성 호출 (설계)

Pleos의 Gleo Interface로 차곡 기능을 글레오가 직접 부르도록 연동할 계획입니다. 차곡이 **App Schema(JSON)**로 기능을 등록하면, 글레오가 **Gleo Action**으로 차곡을 호출하고 **Callback**으로 결과를 받아 답합니다.

| 기능 (App Schema) | 사용자 말 예시 | 차곡 동작 |
|---|---|---|
| `leave_note(받는 사람, 내용)` | "글레오, 엄마한테 우산 챙기라고 남겨 줘" | 남김말 저장 → "엄마님께 남겨 두었어요" |
| `read_notes(운전자)` | "글레오, 남긴 말 읽어 줘" | 안 들은 메모를 언제·누가 남겼는지와 함께 읽기 |
| `add_arrival_todo(장소, 할 일)` | "글레오, 마트 도착하면 우유 사라고 알려 줘" | 도착 할 일 등록 |
| `switch_driver(이름)` | "글레오, 지금 운전자는 엄마야" | 운전자 전환 + 메모 읽기 |
| `get_trip_summary(운전자, 기간)` | "이번 주 엄마는 얼마나 탔어?" | 운행일지 요약 |

주행 중에는 화면 없이 음성 응답만 돌려주어 주의 분산을 줄이고, 메모 내용은 차량 내부에만 저장합니다.

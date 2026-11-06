# ClickModa (옛 이름 Vitrine AI): 이어서 작업하는 사람을 위한 안내

> 앱 이름은 **ClickModa**(주야 회사 브랜드 Modas.click과 연결). 저장소 이름과 패키지 ID(`com.juya.vitrine`)는 그대로 둠 — 이미 설치된 테스트 앱과 저장 사진·모델을 지키기 위해. 아이콘: 아이보리 바탕 + 골드 원 + 검정 디돈 C.

주인: 주야 (한국어 사용, 브라질 상파울루 봉헤치루 여성복 도매 대표, 코딩은 하지 않음 → 쉬운 한국어로, 한 번에 할 일 하나씩, 복사할 글자는 코드 블록으로)

## 1. 이 앱이 뭔가
- **ClickModa**: 옷가게·도매 사장님이 옷 사진만으로 AI 모델 착용샷·동영상·판매 자료를 만드는 안드로이드 앱
- 목표: Google Play 출시 → 구독 판매 (무료 체험 5장, 무료 사진엔 작은 "ClickModa" 로고)
- `main`에 push하면 GitHub Actions가 테스트 APK를 Releases에 `ClickModa-번호.apk`로 올림 (버전 = 100 + 빌드 번호)
- 구조: `app/src/main/assets/index.html`(화면 전부, 한 파일) + `MainActivity.kt`(사진·저장·공유·음성·알림 다리) + `Premium.kt`(Play 결제) + `WorkService.kt`(앱 나가도 계속 만들기 알림) + `worker/`(출시용 AI 서버)

## 2. 기능
**만들기 (AI)**
1. 옷 → 모델 착용샷: 옷 1~3장(같이 입히기) 또는 색깔별 최대 6장, **옷 뒷면 사진**(선택), 색 이름으로 다른 색, 모델(프리셋 또는 저장 모델), 포즈 18개(여러 개 = 장당 1), 배경 23개, 비율, 🎤 추가 요청
2. 신상 한꺼번에: 옷 최대 10벌 → 같은 모델·포즈·배경
3. 모델 옷 바꾸기 / 4. 배경 바꾸기 / 5. 가게 모델 만들기(✕로 삭제)
6. 🎬 동영상(Gemini Omni Flash `gemini-omni-1.1-flash`, Interactions API, 이미지→영상, 지시어 칩: 움직임·카메라·비율·화질·소리) — 1개 = 사진 10장 값
- 같은 모델 유지: 저장 모델이 없으면 첫 장을 먼저 만들고 그 얼굴을 나머지에 사용, 나머지는 **3장씩 동시에**
- 앱을 나가도 계속: `WorkService`(foreground, dataSync) 알림 "만드는 중 n/m", 끝나면 "✅ 완성" 알림, 앱 안은 진행 알약 + NEW 표시

**완성 사진에서 (보기 화면 아래 도구 줄)**
- ✏️ 부분 고치기(🎤 가능, 카탈로그면 모델 사진만 고치고 다시 그림) · ⬜ 흰 배경(마켓 대표 사진) · 🛒 마켓 규격 저장(Mercado Livre·Shopee·TikTok Shop·Amazon/Magalu·Instagram·Stories) · 📝 설명 글(AI, 포르투갈어 기본, 도매/마켓/인스타용, 복사·왓츠앱) · 🎬 동영상 · 📇 카탈로그 1장 · 🏙️ 배경 · 👩 모델 저장 · 🗑
- **AI 품질 검사**: 착용샷이 나오면 글 AI가 원래 옷과 비교 → 점수(✓80 이상 / ⚠) + 틀린 곳, 누르면 부분 고치기에 자동 입력
- 확대(두 손가락·두 번 톡톡)

**만든 사진 목록 (선택 모드)**
- 여러 장 선택 → 📕 PDF 카탈로그(표지·상품 1/2/4개씩·주문 안내, 손님용 포르투갈어, 다운로드 폴더) · 🎞 신상 릴스(사진 이어 붙인 세로 영상 + 직접 만든 배경음, AI 안 씀, MediaRecorder라 만드는 동안 화면 켜 둬야 함) · 💾 저장 · 🗑 지우기, 사용 용량 표시

**기타**: 첫 화면 고급 디자인, 언어 124개(기본 ko/pt/es/en, 나머지는 처음 고를 때 AI 번역 → localStorage 저장, 아랍어 등 RTL), 사진첩에서 여러 장 한꺼번에 고르기, HEIF 등은 휴대폰이 JPG로 바꿔서 넘김

## 3. 결제 (Google Play 구독, `Premium.kt`, Billing 8.0.0)
Play Console > 수익 창출 > 구독에서 **ID를 똑같이** 만들어야 함:
| 상품 ID | 기본 요금제 ID | 가격(안) | 내용 |
|---|---|---|---|
| `clickmoda_basic` | `monthly` | R$ 49,90/월 | 한 달 100장 |
| `clickmoda_pro` | `monthly` | R$ 99,90/월 | 한 달 300장 |
| `clickmoda_pro` | `yearly` | R$ 899,90/년 | 한 달 300장 |
- `monthly`에 7일 무료 체험 혜택을 넣으면 앱이 알아서 체험부터 보여줌
- 앱의 사용량은 휴대폰에 기록(월별) + 서버를 켜면 서버가 진짜로 막음
- 심사용 코드: 첫 화면 맨 아래 버전 글자 5번 누르기 → `CLICKMODA2026` → 프로 열림
- 테스트 APK(debug)에서만 구독 화면에 "테스트용: 프리미엄 켜기" 스위치

## 4. AI 키 / 서버 (출시 전 꼭)
- 지금 테스트 APK: GitHub 비밀값 `GEMINI_API_KEY`가 앱 안에 들어감 → **남에게 배포 금지**
- 출시: Cloudflare Workers에 `worker/` 올리기 (Actions > "AI 서버 올리기" 직접 실행)
  1. Cloudflare 가입 → API 토큰·계정 ID → GitHub 비밀값 `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`
  2. 비밀값 `APP_KEY`(아무 긴 글자) 넣기
  3. (사용량 지키기) Workers KV "clickmoda-usage" 만들고 `wrangler.toml`의 `[[kv_namespaces]]` 주석 풀기 + id 넣기
  4. (구독 확인) Play Console > API 액세스 > 서비스 계정 만들고 JSON 키 → 비밀값 `PLAY_SA_JSON`
  5. 서버 주소를 비밀값 `AI_URL`에 넣고 다시 빌드 → **앱에서 Gemini 키가 자동으로 빠짐**(build.gradle)
- 서버 길: `/generate`(사진 1), `/video`(10), `/text`(번역·품질 검사·설명 글, 무료). KV 없으면 제한 없이 동작(테스트용)

## 5. 남은 일
1. Play Console에 구독 상품 3개 만들기 → 비공개 테스트 트랙에 올려서 실제 결제 테스트
2. 서버 켜기(위 4번) → `AI_URL`로 빌드
3. 출시용 서명 키·AAB(Actions 직접 실행 시 `bundleRelease`), 개인정보 처리방침(사진을 Google AI로 보냄), Play 데이터 보안 양식
4. 실제 폰에서 확인할 것: 동영상(Omni 응답 모양), 릴스 영상이 H.264로 녹화되는지(헤드리스 테스트는 VP9였음 — 왓츠앱 호환), 알림 권한, 결제

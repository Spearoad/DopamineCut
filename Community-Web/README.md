# DopamineCut 웹 커뮤니티

Firebase 프로젝트: **[dopaminecut-community](https://console.firebase.google.com/project/dopaminecut-community/overview)**

| 항목 | 값 |
|------|-----|
| Hosting (배포) | https://dopaminecut-community.web.app |
| Realtime DB | `https://dopaminecut-community-default-rtdb.firebaseio.com` |
| 데이터 경로 | `community_posts` |

## 기능

- 다크 테마 피드 (도파민 점수 랭킹)
- 1~3위: 글자 크기·좌측 테두리 강조
- Firebase Realtime Database 실시간 동기화
- 앱 URL 파라미터: `?nickname=…&score=…&uid=…`

## 로컬 실행

```bash
npm install
npm run dev
```

http://localhost:5173

## Firebase 설정

웹 앱 설정은 `index.html` 하단 `firebaseConfig`에 이미 반영되어 있습니다.

Realtime Database 규칙은 `../firebase/database.rules.json`을 참고해 콘솔에 배포하세요.

전체 연동(안드로이드 `google-services.json` 포함)은 저장소 루트 **[FIREBASE_SETUP.md](../FIREBASE_SETUP.md)** 를 보세요.

## 배포

```bash
npm run build
cd ../firebase
npx firebase-tools deploy --only hosting
```

## 앱 연동 URL

```
https://dopaminecut-community.web.app/?uid=USER_ID&nickname=닉네임&score=85
```

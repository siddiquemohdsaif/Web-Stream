# WebStream Node Server

Minimal relay used by the Android SDK for join signaling, prototype JPEG video frame forwarding, and media state controls.

```bash
npm install
npm start
```

The server listens on `ws://0.0.0.0:8080` by default. Android emulators can reach it through the SDK's internal `ws://10.0.2.2:8080` endpoint.

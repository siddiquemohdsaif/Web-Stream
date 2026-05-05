# WebStream Node Server

Minimal relay used by the Android SDK for join signaling, prototype image frame forwarding, and media state controls.

Binary video packets support image format id `1` for JPEG and `2` for JPEG XL (JXL).
The server advertises JXL as the default image format and keeps JPEG available for older or fallback clients.

```bash
npm install
npm start
```

The server listens on `ws://0.0.0.0:8080` by default. Android emulators can reach it through the SDK's internal `ws://10.0.2.2:8080` endpoint.

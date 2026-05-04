const WebSocket = require('ws');

const port = Number(process.env.PORT || 8080);
const wss = new WebSocket.Server({ port, host: '0.0.0.0' });
const calls = new Map();

function send(ws, message) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(message));
  }
}

function getCallParticipants(callId) {
  if (!calls.has(callId)) {
    calls.set(callId, new Set());
  }
  return calls.get(callId);
}

function publicParticipant(ws) {
  return {
    userId: ws.webStreamUserId,
    displayName: ws.webStreamDisplayName || null,
    microphoneMuted: Boolean(ws.webStreamMicrophoneMuted),
    cameraEnabled: ws.webStreamCameraEnabled !== false,
  };
}

function leaveCall(ws, notifyClient) {
  const callId = ws.webStreamCallId;
  if (!callId || !calls.has(callId)) {
    return;
  }

  const participants = calls.get(callId);
  participants.delete(ws);

  if (notifyClient) {
    send(ws, {
      type: 'server.left',
      callId,
      userId: ws.webStreamUserId,
    });
  }

  for (const peer of participants) {
    send(peer, {
      type: 'server.participant_left',
      callId,
      participant: publicParticipant(ws),
    });
  }

  if (participants.size === 0) {
    calls.delete(callId);
  }

  ws.webStreamCallId = null;
}

function handleJoin(ws, message) {
  const callId = String(message.callId || '').trim();
  const userId = String(message.userId || '').trim();

  if (!callId || !userId) {
    send(ws, {
      type: 'server.error',
      code: 'INVALID_CONFIG',
      message: 'callId and userId are required.',
    });
    return;
  }

  leaveCall(ws, false);
  ws.webStreamCallId = callId;
  ws.webStreamUserId = userId;
  ws.webStreamDisplayName = String(message.displayName || '').trim();
  ws.webStreamMicrophoneMuted = false;
  ws.webStreamCameraEnabled = true;

  const participants = getCallParticipants(callId);
  const existingParticipants = Array.from(participants).map(publicParticipant);
  participants.add(ws);

  send(ws, {
    type: 'server.joined',
    callId,
    userId,
    participants: existingParticipants,
  });

  for (const peer of participants) {
    if (peer === ws) {
      continue;
    }
    send(peer, {
      type: 'server.participant_joined',
      callId,
      participant: publicParticipant(ws),
    });
  }
}

function relayMediaState(ws, message) {
  const callId = ws.webStreamCallId;
  const userId = ws.webStreamUserId;

  if (!callId || !userId || !calls.has(callId)) {
    send(ws, {
      type: 'server.error',
      code: 'NOT_IN_CALL',
      message: 'Join a call before sending media state.',
    });
    return;
  }

  ws.webStreamMicrophoneMuted = Boolean(message.microphoneMuted);
  ws.webStreamCameraEnabled = message.cameraEnabled !== false;

  const relayedMessage = {
    type: 'server.media_state',
    callId,
    userId,
    microphoneMuted: ws.webStreamMicrophoneMuted,
    cameraEnabled: ws.webStreamCameraEnabled,
  };

  for (const peer of calls.get(callId)) {
    if (peer !== ws) {
      send(peer, relayedMessage);
    }
  }
}

function relayVideoFrame(ws, message) {
  const callId = ws.webStreamCallId;
  const userId = ws.webStreamUserId;

  if (!callId || !userId || !calls.has(callId)) {
    send(ws, {
      type: 'server.error',
      code: 'NOT_IN_CALL',
      message: 'Join a call before sending video.',
    });
    return;
  }

  const relayedMessage = {
    type: 'server.media.video',
    callId,
    userId,
    timestampMs: Number(message.timestampMs || Date.now()),
    format: 'jpeg',
    width: Number(message.width || 0),
    height: Number(message.height || 0),
    frameRateFps: Number(message.frameRateFps || 0),
    bitrateKbps: Number(message.bitrateKbps || 0),
    sequence: Number(message.sequence || 0),
    data: String(message.data || ''),
  };

  if (!relayedMessage.data) {
    return;
  }

  for (const peer of calls.get(callId)) {
    if (peer !== ws) {
      send(peer, relayedMessage);
    }
  }
}

wss.on('connection', (ws) => {
  ws.on('message', (data) => {
    let message;
    try {
      message = JSON.parse(data.toString());
    } catch (error) {
      send(ws, {
        type: 'server.error',
        code: 'INVALID_JSON',
        message: 'Message must be valid JSON.',
      });
      return;
    }

    if (message.type === 'client.join') {
      handleJoin(ws, message);
    } else if (message.type === 'client.leave') {
      leaveCall(ws, true);
    } else if (message.type === 'client.media_state') {
      relayMediaState(ws, message);
    } else if (message.type === 'client.media.video') {
      relayVideoFrame(ws, message);
    } else {
      send(ws, {
        type: 'server.error',
        code: 'UNSUPPORTED_MESSAGE',
        message: `Unsupported message type: ${message.type || 'unknown'}`,
      });
    }
  });

  ws.on('close', () => {
    leaveCall(ws, false);
  });
});

wss.on('listening', () => {
  console.log(`WebStream server listening on ws://0.0.0.0:${port}`);
});

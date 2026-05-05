const crypto = require('crypto');
const WebSocket = require('ws');

const port = Number(process.env.PORT || 8080);
const wss = new WebSocket.Server({ port, host: '0.0.0.0' });
const calls = new Map();
const VIDEO_PACKET_TYPE = 1;
const FORMAT_JPEG = 1;
const FORMAT_JXL = 2;
const VIDEO_PACKET_HEADER_BYTES = 33;
const SUPPORTED_IMAGE_FORMATS = ['jpeg', 'jxl'];
const DEFAULT_IMAGE_FORMAT = 'jxl';

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
    cUuid: ws.webStreamCUuid,
    displayName: ws.webStreamDisplayName || null,
    microphoneMuted: Boolean(ws.webStreamMicrophoneMuted),
    cameraEnabled: ws.webStreamCameraEnabled !== false,
    imageFormats: ws.webStreamImageFormats || [],
    preferredImageFormat: ws.webStreamPreferredImageFormat || null,
  };
}

function publicMediaCapabilities() {
  return {
    imageFormats: SUPPORTED_IMAGE_FORMATS,
    defaultImageFormat: DEFAULT_IMAGE_FORMAT,
  };
}

function normalizeImageFormat(value) {
  const format = String(value || '').trim().toLowerCase();
  return SUPPORTED_IMAGE_FORMATS.includes(format) ? format : null;
}

function normalizeImageFormats(values) {
  if (!Array.isArray(values)) {
    return ['jpeg'];
  }
  const formats = [];
  for (const value of values) {
    const format = normalizeImageFormat(value);
    if (format && !formats.includes(format)) {
      formats.push(format);
    }
  }
  return formats.length > 0 ? formats : ['jpeg'];
}

function isSupportedBinaryImageFormat(format) {
  return format === FORMAT_JPEG || format === FORMAT_JXL;
}

function generateCUuid(callId, userId, participants) {
  const hash = crypto.createHash('sha256').update(`${callId}:${userId}`).digest();
  let cUuid = hash.readUInt32BE(0) & 0x7fffffff;
  if (cUuid === 0) {
    cUuid = 1;
  }

  const used = new Set(Array.from(participants).map((participant) => participant.webStreamCUuid));
  while (used.has(cUuid)) {
    cUuid += 1;
    if (cUuid > 0x7fffffff) {
      cUuid = 1;
    }
  }
  return cUuid;
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
  ws.webStreamCUuid = null;
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

  const participants = getCallParticipants(callId);
  leaveCall(ws, false);
  const activeParticipants = getCallParticipants(callId);

  ws.webStreamCallId = callId;
  ws.webStreamUserId = userId;
  ws.webStreamCUuid = generateCUuid(callId, userId, activeParticipants);
  ws.webStreamDisplayName = String(message.displayName || '').trim();
  ws.webStreamMicrophoneMuted = false;
  ws.webStreamCameraEnabled = true;
  ws.webStreamImageFormats = normalizeImageFormats(
    message.mediaCapabilities && message.mediaCapabilities.imageFormats
  );
  ws.webStreamPreferredImageFormat =
    normalizeImageFormat(message.mediaCapabilities && message.mediaCapabilities.preferredImageFormat)
    || ws.webStreamImageFormats[0];

  const existingParticipants = Array.from(participants).map(publicParticipant);
  activeParticipants.add(ws);

  send(ws, {
    type: 'server.joined',
    callId,
    userId,
    cUuid: ws.webStreamCUuid,
    mediaCapabilities: publicMediaCapabilities(),
    participants: existingParticipants,
  });

  for (const peer of activeParticipants) {
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

function isValidBinaryVideoPacket(ws, packet) {
  if (!packet || packet.length < VIDEO_PACKET_HEADER_BYTES) {
    return false;
  }

  let offset = 0;
  while (offset < packet.length) {
    if (packet.length - offset < VIDEO_PACKET_HEADER_BYTES) {
      return false;
    }

    const type = packet.readUInt16BE(offset);
    const cUuid = packet.readUInt32BE(offset + 2);
    const format = packet.readUInt8(offset + 14);
    const payloadLength = packet.readUInt32BE(offset + 29);
    const nextOffset = offset + VIDEO_PACKET_HEADER_BYTES + payloadLength;

    if (
      type !== VIDEO_PACKET_TYPE ||
      cUuid !== ws.webStreamCUuid ||
      !isSupportedBinaryImageFormat(format) ||
      payloadLength === 0 ||
      nextOffset > packet.length
    ) {
      return false;
    }

    offset = nextOffset;
  }

  return offset === packet.length;
}

function relayBinaryVideoFrame(ws, packet) {
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

  if (!isValidBinaryVideoPacket(ws, packet)) {
    send(ws, {
      type: 'server.error',
      code: 'INVALID_BINARY_VIDEO',
      message: 'Binary video packet is invalid.',
    });
    return;
  }

  for (const peer of calls.get(callId)) {
    if (peer !== ws && peer.readyState === WebSocket.OPEN) {
      peer.send(packet, { binary: true });
    }
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
    format: normalizeImageFormat(message.format) || 'jpeg',
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
  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      relayBinaryVideoFrame(ws, Buffer.isBuffer(data) ? data : Buffer.from(data));
      return;
    }

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

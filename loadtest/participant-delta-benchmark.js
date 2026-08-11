const { performance } = require('node:perf_hooks');

const DEFAULT_SIZES = [10, 50, 100, 500, 1000];
const ITERATIONS = 10000;

const createParticipant = (index) => ({
  id: `user-${index}`,
  name: `participant-${index}`,
  email: `participant-${index}@example.com`,
  profileImage: '',
});

const parseSizes = () => {
  const sizesArgument = process.argv.find((argument) => argument.startsWith('--sizes='));
  if (!sizesArgument) {
    return DEFAULT_SIZES;
  }

  const sizes = sizesArgument
    .slice('--sizes='.length)
    .split(',')
    .map(Number)
    .filter((size) => Number.isInteger(size) && size > 0);

  return sizes.length > 0 ? sizes : DEFAULT_SIZES;
};

const byteLength = (value) => Buffer.byteLength(JSON.stringify(value), 'utf8');

const measureSerialization = (payloadFactory) => {
  const startedAt = performance.now();
  let bytes = 0;

  for (let iteration = 0; iteration < ITERATIONS; iteration += 1) {
    bytes += byteLength(payloadFactory());
  }

  return {
    bytes,
    elapsedMs: performance.now() - startedAt,
  };
};

const formatBytes = (bytes) => {
  if (bytes < 1024) return `${bytes.toFixed(0)} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(2)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`;
};

const formatPercent = (value) => `${value.toFixed(2)}%`;

const printResult = (size, operation, legacy, improved) => {
  const reduction = legacy.totalBytes === 0
    ? 0
    : ((legacy.totalBytes - improved.totalBytes) / legacy.totalBytes) * 100;
  const serializationReduction = legacy.elapsedMs === 0
    ? 0
    : ((legacy.elapsedMs - improved.elapsedMs) / legacy.elapsedMs) * 100;

  console.log([
    `participants=${size}`,
    `operation=${operation}`,
    `legacy=${formatBytes(legacy.totalBytes)}`,
    `delta=${formatBytes(improved.totalBytes)}`,
    `wireReduction=${formatPercent(reduction)}`,
    `legacySerialize=${legacy.elapsedMs.toFixed(2)}ms`,
    `deltaSerialize=${improved.elapsedMs.toFixed(2)}ms`,
    `serializeReduction=${formatPercent(serializationReduction)}`,
  ].join(' | '));
};

const benchmarkJoin = (participants) => {
  const joined = participants[participants.length - 1];
  const existingParticipants = participants.slice(0, -1);

  // Previous implementation: full list broadcast to every client in the room.
  const legacyPayload = () => participants;
  const legacyMeasurement = measureSerialization(legacyPayload);
  const legacy = {
    totalBytes: legacyMeasurement.bytes * participants.length,
    elapsedMs: legacyMeasurement.elapsedMs,
  };

  // Improved implementation: full list only to the joiner, one delta to others.
  const initialListMeasurement = measureSerialization(() => participants);
  const deltaMeasurement = measureSerialization(() => ({
    type: 'joined',
    participant: joined,
  }));
  const improved = {
    totalBytes: initialListMeasurement.bytes + (deltaMeasurement.bytes * existingParticipants.length),
    elapsedMs: initialListMeasurement.elapsedMs + deltaMeasurement.elapsedMs,
  };

  printResult(participants.length, 'join', legacy, improved);
};

const benchmarkLeave = (participants) => {
  const leaving = participants[participants.length - 1];
  const remainingParticipants = participants.slice(0, -1);

  // Previous implementation: remaining full list to every remaining client.
  const legacyMeasurement = measureSerialization(() => remainingParticipants);
  const legacy = {
    totalBytes: legacyMeasurement.bytes * remainingParticipants.length,
    elapsedMs: legacyMeasurement.elapsedMs,
  };

  // Improved implementation: one leave delta to every remaining client.
  const deltaMeasurement = measureSerialization(() => ({
    type: 'left',
    participant: leaving,
  }));
  const improved = {
    totalBytes: deltaMeasurement.bytes * remainingParticipants.length,
    elapsedMs: deltaMeasurement.elapsedMs,
  };

  printResult(participants.length, 'leave', legacy, improved);
};

console.log(`iterations=${ITERATIONS}`);
console.log('Note: wireReduction includes JSON payload bytes multiplied by recipient count.');

for (const size of parseSizes()) {
  const participants = Array.from({ length: size }, (_, index) => createParticipant(index));
  benchmarkJoin(participants);
  benchmarkLeave(participants);
}

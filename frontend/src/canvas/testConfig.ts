import type { ClientConfig } from '../shared/pageConfig';

// The server defaults (ARCHITECTURE.md §6) as tests see them; production code reads them from the API.
export const TEST_CONFIG: ClientConfig = {
  width: 7680,
  height: 4320,
  chunkSize: 480,
  cellSize: 48,
  zoomMin: 0.5,
  zoomMax: 4,
  palette: ['#000000', '#FFFFFF', '#E53935', '#FB8C00', '#FDD835', '#43A047', '#29B6F6', '#1E88E5', '#8E24AA', '#EC407A', '#795548', '#9E9E9E'],
  strokeWidthMin: 2,
  strokeWidthMax: 16,
  defaultStrokeWidth: 4,
  fontSizes: [10, 11, 12, 13, 14, 15, 16, 20, 24, 32, 36, 40, 48, 64, 96],
  defaultFontSize: 24,
  textMaxLength: 50,
  maxBbox: 1024,
  maxChunks: 60,
  postCooldownSec: 1800,
  countdownWarningSec: 300,
  draftIdleTimeoutSec: 600,
  cursorIntervalMs: 100,
  draftIntervalMs: 100,
  heartbeatMs: 10000,
  countdownThresholdPermille: 700,
  countdownDurationSec: 21600,
};

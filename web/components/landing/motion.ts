export type Pose = {
  px: number;
  py: number;
  pz: number;
  rx: number;
  ry: number;
  rz: number;
  scale: number;
  tx: number;
  ty: number;
  tz: number;
  dist: number;
  az: number;
  el: number;
  sx: number;
};

/** Rest poses. The card rotation is Euler YXZ, degrees. Upright is rx 90. */
const POSES: Pose[] = [
  { px: 0, py: 0, pz: 0, rx: 62, ry: -28, rz: 14, scale: 1, tx: 0, ty: 0, tz: 0, dist: 0.235, az: 18, el: 14, sx: 0.2 },
  { px: -0.03, py: 0, pz: 0, rx: 74, ry: -16, rz: 8, scale: 1, tx: 0.025, ty: 0.025, tz: 0, dist: 0.36, az: 8, el: 10, sx: 0.17 },
  { px: 0.17, py: 0.2, pz: 0.064, rx: 90, ry: -12, rz: 0, scale: 1, tx: 0.085, ty: 0.13, tz: 0.06, dist: 0.7, az: 0, el: 2, sx: 0.22 },
  { px: -0.126, py: -0.024, pz: 0.026, rx: 90, ry: -4, rz: -9, scale: 0.806, tx: 0, ty: 0.01, tz: 0, dist: 0.64, az: -12, el: 6, sx: 0.22 },
  { px: -0.05, py: 0.03, pz: 0.03, rx: 90, ry: 34, rz: 6, scale: 0.8, tx: 0.03, ty: 0.03, tz: 0, dist: 0.52, az: 6, el: 6, sx: 0.2 },
  { px: -0.03, py: 0.002, pz: 0.06, rx: 90, ry: 24, rz: -6, scale: 1, tx: 0.03, ty: 0.05, tz: 0, dist: 0.55, az: 10, el: 8, sx: 0.2 },
];

/**
 * s where each rest pose has fully arrived, then holds.
 * Section 2 lists 2.55 and 3.5 for beats 3 and 4. Those sits are at or past
 * the end of the sticky window: a 200vh section only pins its 100vh copy while
 * local progress is under 0.5, so s = k + 0.5 is the last frame where the copy
 * still fills the viewport. Arriving at 2.4 and 3.4 settles the pose while that
 * copy is still stuck, which is the storyboard frame.
 */
const ARRIVE = [0, 1.3, 2.4, 3.4, 4.3, 5];

export type Sample = {
  pose: Pose;
  from: number;
  to: number;
  u: number;
  rowWon: boolean;
};

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function smoother(t: number): number {
  const x = clamp(t, 0, 1);
  return x * x * x * (x * (x * 6 - 15) + 10);
}

/** Hero copy is on screen at s = 0. Later beats arrive over [k − 0.36, k − 0.14]. */
export function reveal(s: number, beat: number, item: number): number {
  const start = beat - 0.36 + item * 0.03;
  const end = beat - 0.14 + item * 0.03;
  return smoother((s - start) / Math.max(end - start, 1e-4));
}

export function beatFade(beat: number, from: number, to: number, u: number): number {
  if (from === to) return beat === from ? 1 : 0;
  if (beat === from) return 1 - u;
  if (beat === to) return u;
  return 0;
}

export function beatEnter(beat: number, from: number, to: number, u: number): number {
  if (to === beat && from !== to) return u;
  if (from === beat) return 1;
  return 0;
}

function heroTurn(s: number): number {
  if (s <= 0 || s >= 0.9) return 0;
  const up = clamp(s / 0.45, 0, 1);
  const down = clamp((0.9 - s) / 0.45, 0, 1);
  return 6 * Math.min(up, down);
}

function blend(s: number): { from: number; to: number; u: number } {
  if (s >= 5) return { from: 5, to: 5, u: 1 };
  let to = 1;
  while (to < ARRIVE.length && s >= ARRIVE[to]) to += 1;
  if (to >= ARRIVE.length) return { from: 5, to: 5, u: 1 };
  const from = to - 1;
  const end = ARRIVE[to];
  const start = Math.max(ARRIVE[from], end - 0.4);
  const u = smoother((s - start) / (end - start));
  return { from, to, u };
}

function lerpPose(a: Pose, b: Pose, u: number): Pose {
  const out = { ...a };
  (Object.keys(a) as (keyof Pose)[]).forEach((key) => {
    out[key] = a[key] + (b[key] - a[key]) * u;
  });
  return out;
}

export function sampleMotion(s: number): Sample {
  const x = clamp(s, 0, 5);
  const { from, to, u } = blend(x);
  const pose = lerpPose(POSES[from], POSES[to], u);
  if (from === 0) pose.ry += heroTurn(x) * (1 - u);
  if (to === 1 && u > 0) pose.pz += -0.004 * (1 - u);
  if (to === 3 && u > 0) {
    const endY = POSES[3].py;
    const startY = POSES[from].py;
    if (u < 0.85) pose.py = startY + (endY + 0.006 - startY) * (u / 0.85);
    else pose.py = endY + 0.006 * (1 - smoother((u - 0.85) / 0.15));
  }
  const rowWon = (from === 3 && to !== from) || (to === 3 && u >= 0.98);
  return { pose, from, to, u, rowWon };
}

import {
  ACESFilmicToneMapping,
  CanvasTexture,
  DirectionalLight,
  Group,
  HemisphereLight,
  Mesh,
  MeshBasicMaterial,
  MeshPhysicalMaterial,
  MeshStandardMaterial,
  Object3D,
  PerspectiveCamera,
  PlaneGeometry,
  PMREMGenerator,
  Scene,
  SRGBColorSpace,
  Vector3,
  WebGLRenderer,
  type Material,
  type Texture,
} from "three";
import { DRACOLoader } from "three/examples/jsm/loaders/DRACOLoader.js";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import { RoomEnvironment } from "three/examples/jsm/environments/RoomEnvironment.js";
import { beatEnter, beatFade, clamp, sampleMotion, smoother, type Pose } from "@/components/landing/motion";

const C = {
  paper: "#f6f4ee",
  ink: "#1f242c",
  muted: "#5d6570",
  ui: "#262b33",
  uiLine: "#3a414c",
  fg: "#eef1f5",
  fg2: "#aab3be",
  mint: "#6fd6a3",
  coral: "#f09584",
};

export type SceneHandle = {
  update: (sd: number) => boolean;
  resize: () => void;
  dispose: () => void;
};

type Draw = {
  f?: string;
  w?: number;
  size?: number;
  col?: string;
  al?: CanvasTextAlign;
};

function deg(value: number): number {
  return (value * Math.PI) / 180;
}

function isMesh(object: Object3D): object is Mesh {
  return (object as Mesh).isMesh === true;
}

function materials(object: Object3D): Material[] {
  const found: Material[] = [];
  object.traverse((child) => {
    if (!isMesh(child)) return;
    const material = child.material;
    if (Array.isArray(material)) found.push(...material);
    else found.push(material);
  });
  return found;
}

function fadeObject(object: Object3D, opacity: number) {
  object.visible = opacity > 0.02;
  materials(object).forEach((material) => {
    const base = typeof material.userData.base === "number" ? material.userData.base : material.opacity;
    material.userData.base = base;
    material.opacity = base * opacity;
    material.transparent = true;
    material.depthWrite = material.opacity > 0.98;
  });
}

export async function mountScene(canvas: HTMLCanvasElement): Promise<SceneHandle> {
  const root = canvas.closest("[data-landing]");
  const styles = root ? getComputedStyle(root) : null;
  const sans = styles?.getPropertyValue("--font-landing-sans").trim() || '"Instrument Sans", sans-serif';
  const mono = styles?.getPropertyValue("--font-landing-mono").trim() || '"JetBrains Mono", monospace';
  const primary = (stack: string) => stack.split(",")[0]?.trim().replace(/^["']|["']$/g, "") || stack;
  try {
    await Promise.all([
      document.fonts.load(`600 40px ${primary(sans)}`),
      document.fonts.load(`500 24px ${primary(mono)}`),
      document.fonts.ready,
    ]);
  } catch {
    // The canvas still draws with the fallback face.
  }

  const renderer = new WebGLRenderer({ canvas, antialias: true, alpha: true });
  if (!renderer.getContext()) throw new Error("webgl");
  renderer.outputColorSpace = SRGBColorSpace;
  renderer.toneMapping = ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.05;
  renderer.setClearColor(0x000000, 0);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
  const aniso = Math.min(8, renderer.capabilities.getMaxAnisotropy());

  const scene = new Scene();
  const pmrem = new PMREMGenerator(renderer);
  const room = new RoomEnvironment();
  scene.environment = pmrem.fromScene(room, 0.04).texture;
  scene.environmentIntensity = 0.6;
  pmrem.dispose();

  const key = new DirectionalLight("#dfe7f2", 1.4);
  key.position.set(-0.7, 1, 0.7);
  const rim = new DirectionalLight("#cfe0ff", 6);
  rim.position.set(0.6, 0.55, -1);
  const fill = new DirectionalLight("#dfe7f2", 0.5);
  fill.position.set(0.4, 0.2, 1);
  scene.add(key, rim, fill, new HemisphereLight("#9aa8bd", "#0b0d10", 0.3));

  const camera = new PerspectiveCamera(30, 1, 0.01, 10);
  const draco = new DRACOLoader();
  (draco.setDecoderPath as (path: string | { js: string; wasm: string }) => void)({
    js: "/draco/draco_wasm_wrapper.js",
    wasm: "/draco/draco_decoder.wasm",
  });
  const loader = new GLTFLoader().setDRACOLoader(draco);
  const [cardGltf, receiptGltf] = await Promise.all([
    loader.loadAsync("/models/card.glb"),
    loader.loadAsync("/models/receipts.glb"),
  ]);
  draco.dispose();

  const canvasOf = (w: number, h: number) => {
    const el = document.createElement("canvas");
    el.width = w;
    el.height = h;
    const ctx = el.getContext("2d");
    if (!ctx) throw new Error("2d");
    return [el, ctx] as const;
  };
  const texture = (source: HTMLCanvasElement, flip = true) => {
    const map = new CanvasTexture(source);
    map.colorSpace = SRGBColorSpace;
    map.flipY = flip;
    map.anisotropy = aniso;
    return map;
  };
  const text = (ctx: CanvasRenderingContext2D, value: string, x: number, y: number, options: Draw = {}) => {
    ctx.font = `${options.w ?? 500} ${options.size ?? 40}px ${options.f ?? sans}`;
    ctx.fillStyle = options.col ?? C.fg;
    ctx.textAlign = options.al ?? "left";
    ctx.fillText(value, x, y);
  };
  const panel = (
    wm: number,
    hm: number,
    draw: (ctx: CanvasRenderingContext2D, width: number, height: number) => void,
    options: { r?: number; bg?: string; line?: string | null; px?: number } = {},
  ) => {
    const px = options.px ?? 5600;
    const [el, ctx] = canvasOf(Math.max(2, Math.round(wm * px)), Math.max(2, Math.round(hm * px)));
    ctx.fillStyle = options.bg ?? C.ui;
    ctx.beginPath();
    ctx.roundRect(2, 2, el.width - 4, el.height - 4, options.r ?? 28);
    ctx.fill();
    if (options.line !== null) {
      ctx.strokeStyle = options.line ?? C.uiLine;
      ctx.lineWidth = 3;
      ctx.stroke();
    }
    draw(ctx, el.width, el.height);
    return new Mesh(
      new PlaneGeometry(wm, hm),
      new MeshBasicMaterial({ map: texture(el), transparent: true, toneMapped: false }),
    );
  };

  const card = cardGltf.scene.clone(true);
  card.traverse((object) => {
    if (!isMesh(object)) return;
    const name = Array.isArray(object.material) ? object.material[0]?.name : object.material.name;
    object.material =
      name === "chip"
        ? new MeshStandardMaterial({ color: "#b9b4aa", metalness: 1, roughness: 0.3 })
        : new MeshPhysicalMaterial({
            color: "#3c434e",
            metalness: 0.6,
            roughness: 0.38,
            clearcoat: 0.7,
            clearcoatRoughness: 0.2,
          });
  });
  const [faceCanvas, faceCtx] = canvasOf(856, 540);
  text(faceCtx, "HokWallet", 56, 120, { w: 600, size: 76, col: "#eef1f5" });
  text(faceCtx, "•• 4417", 56, 470, { f: mono, w: 500, size: 66, col: "#eef1f5" });
  const face = new Mesh(
    new PlaneGeometry(0.0856, 0.054),
    new MeshBasicMaterial({ map: texture(faceCanvas), transparent: true, toneMapped: false, opacity: 0.92 }),
  );
  face.rotation.x = -Math.PI / 2;
  face.position.y = 0.00045;
  card.add(face);
  scene.add(card);

  const receiptSource = receiptGltf.scene.getObjectByName("receipt_02");
  if (!receiptSource) throw new Error("receipt");
  const receiptCanvas = receiptTexture();
  const receipt = paper(receiptSource, receiptCanvas.canvas);
  const printed = receipt.userData.mesh as Object3D;
  printed.scale.x = 0.92;
  const lineY = uvY(receipt, receiptCanvas.lineFrac);
  const Y = 0.15;
  const Z = 0.06;
  receipt.position.set(0.075, Y - lineY, Z + 0.01);

  const tag = panel(0.1, 0.02, (ctx, width) => {
    text(ctx, "Card", 22, 76, { size: 46, col: C.fg2 });
    text(ctx, "54.80", width - 22, 78, { f: mono, w: 700, size: 58, al: "right" });
  }, { r: 12 });
  tag.position.set(0.17, Y + 0.012, Z + 0.006);

  const candidates = (
    [
      ["Cafe · AUD 8.00", "2026-01-11 · out of window"],
      ["Officeworks · AUD 128.00", "2026-01-21 · out of window"],
    ] as const
  ).map(([title, detail], index) => {
    const mesh = panel(0.1, 0.03, (ctx) => {
      text(ctx, title, 30, 70, { f: mono, w: 600, size: 34, col: C.fg2 });
      text(ctx, detail, 30, 128, { f: mono, size: 28, col: C.fg2 });
    }, { r: 14, bg: "#24282f" });
    mesh.position.set(-0.045, Y + 0.035 - index * 0.042, Z - 0.004);
    const material = mesh.material as MeshBasicMaterial;
    material.opacity = 0.85;
    material.userData.base = 0.85;
    return mesh;
  });

  const rule = new Mesh(
    new PlaneGeometry(0.2, 0.0014),
    new MeshBasicMaterial({ color: "#5BBF8E", toneMapped: false, transparent: true }),
  );
  rule.position.set(0.125, Y - 0.001, Z + 0.03);

  const score = panel(0.13, 0.022, (ctx, width) => {
    text(ctx, "score 1.000 ≥ 0.850", 30, 80, { f: mono, w: 600, size: 40, col: C.mint });
    text(ctx, "no tie", width - 30, 80, { f: mono, size: 34, col: C.fg2, al: "right" });
  }, { r: 14, bg: "#22332c", line: "#3f7a5e" });
  score.position.set(0.125, Y - 0.03, Z + 0.03);

  const stampCanvas = stamp("MATCHED", "#6fd6a3");
  const stampMesh = new Mesh(
    new PlaneGeometry(0.1, 0.0268),
    new MeshBasicMaterial({ map: texture(stampCanvas), transparent: true, toneMapped: false }),
  );
  stampMesh.position.set(0.125, Y - 0.07, Z + 0.03);
  stampMesh.rotation.z = deg(-3);

  const note = panel(0.13, 0.058, (ctx, width) => {
    ctx.fillStyle = "#3c434e";
    ctx.beginPath();
    ctx.roundRect(36, 36, 64, 64, 14);
    ctx.fill();
    text(ctx, "H", 68, 82, { w: 700, size: 40, al: "center" });
    text(ctx, "HokWallet", 120, 64, { w: 600, size: 28 });
    text(ctx, "now", width - 36, 64, { size: 24, col: C.fg2, al: "right" });
    text(ctx, "Card •• 4417 · AUD 54.80", 120, 104, { w: 600, size: 32 });
    text(ctx, "Woolworths · tax 4.98 · 2026-01-06", 120, 146, { f: mono, size: 23, col: C.fg2 });
    text(ctx, "POST /transactions → 201", 120, 190, { f: mono, size: 24 });
    text(ctx, "status UNMATCHED", 120, 228, { f: mono, w: 600, size: 24, col: C.fg2 });
  }, { r: 36, bg: "#2e343d" });
  note.position.set(0.05, 0.045, 0.03);
  note.rotation.y = deg(-10);

  const header = panel(0.26, 0.02, (ctx) => {
    text(ctx, "Northwind Coffee · AUD 18.40 · SUGGESTED 0.700 · version 0", 28, 70, { f: mono, size: 28, col: C.fg2 });
  }, { r: 14, px: 4000 });
  header.position.set(-0.04, 0.124, 0);

  const loseMaps = Array.from({ length: 10 }, (_, index) => rowTexture(index, false));
  const winMap = rowTexture(6, true);
  const rows = Array.from({ length: 10 }, (_, index) => {
    const win = index === 6;
    const mesh = new Mesh(
      new PlaneGeometry(0.26, 0.0185),
      new MeshBasicMaterial({ map: loseMaps[index], transparent: true, toneMapped: false }),
    );
    mesh.position.set(win ? -0.028 : -0.04, 0.1 - index * 0.021, win ? 0.012 : -index * 0.001);
    return mesh;
  });
  const ring = panel(0.27, 0.026, () => undefined, { r: 18, bg: "rgba(111,214,163,0.10)", line: "#6fd6a3" });
  ring.position.set(-0.028, 0.1 - 6 * 0.021, 0.008);

  const flag = panel(0.15, 0.09, (ctx, width) => {
    text(ctx, "Receipt R-2 · Cafe · same day as R-1", 44, 70, { f: mono, size: 26, col: C.fg2 });
    text(ctx, "AUD 12.34", 44, 140, { f: mono, w: 600, size: 60 });
    text(ctx, "same merchant, amount and date", 44, 192, { f: mono, size: 26, col: C.fg2 });
    ctx.fillStyle = "rgba(240,149,132,0.14)";
    ctx.beginPath();
    ctx.roundRect(44, 220, width - 88, 104, 10);
    ctx.fill();
    text(ctx, "EXCEPTION · POSSIBLE_DUPLICATE", 64, 260, { f: mono, w: 600, size: 28, col: C.coral });
    text(ctx, "rules R1–R6 + R7_DUPLICATE", 64, 302, { f: mono, size: 26, col: C.coral });
    text(ctx, "→ A person approves, with a reason", 44, 384, { w: 600, size: 32 });
    text(ctx, "no reason: 422", 44, 430, { f: mono, size: 24, col: C.fg2 });
  }, { bg: "#2c2a2e", line: "#c0584a" });
  flag.position.set(0.05, 0.02, 0);
  flag.rotation.y = deg(-8);

  const approved = panel(0.15, 0.02, (ctx, width) => {
    text(ctx, "✓ APPROVED · demo-reviewer", 30, 70, { f: mono, w: 600, size: 32, col: C.mint });
    text(ctx, "+ reason", width - 30, 70, { f: mono, size: 26, col: C.fg2, al: "right" });
  }, { r: 16, bg: "#22332c", line: "#3f7a5e" });
  approved.position.set(0.055, -0.04, 0.004);
  approved.rotation.y = deg(-8);

  const duplicate = panel(0.15, 0.02, (ctx) => {
    text(ctx, "INV-100 twice → 409 Duplicate document", 30, 70, { f: mono, size: 28, col: C.fg2 });
  }, { r: 16 });
  duplicate.position.set(0.07, 0.088, -0.02);

  const book = panel(0.165, 0.12, (ctx, width, height) => {
    text(ctx, "SEEDED BOOK · ada.ng", 50, 76, { f: mono, size: 28, col: C.fg2 });
    text(ctx, "Ready for month-end", 50, 132, { w: 600, size: 44 });
    (
      [
        ["Woolworths 54.80", "APPROVED 1.000", C.mint],
        ["Dwayne 6.50", "MATCHED", C.mint],
        ["Cafe 8.00", "SUGGESTED 0.700", C.fg],
        ["Officeworks 120.00", "AMOUNT_MISMATCH", C.coral],
        ["Uber 24.30", "NO_CANDIDATE", C.coral],
      ] as const
    ).forEach(([left, right, color], index) => {
      const y = 210 + index * 66;
      ctx.fillStyle = C.uiLine;
      ctx.fillRect(50, y - 44, width - 100, 2);
      text(ctx, left, 50, y, { f: mono, size: 28, col: C.fg2 });
      text(ctx, right, width - 50, y, { f: mono, size: 28, col: color, al: "right" });
    });
    text(ctx, "APPROVED → approved.csv", width - 50, height - 40, { f: mono, size: 26, col: C.fg2, al: "right" });
  });
  book.position.set(0.055, 0.06, 0.02);
  book.rotation.y = deg(-12);

  const matchGroup = new Group();
  matchGroup.add(receipt, tag, rule, score, stampMesh, ...candidates);
  const chargeGroup = new Group();
  chargeGroup.add(note);
  const raceGroup = new Group();
  raceGroup.add(header, ring, ...rows);
  const flagGroup = new Group();
  flagGroup.add(flag, approved, duplicate);
  const bookGroup = new Group();
  bookGroup.add(book);
  scene.add(matchGroup, chargeGroup, raceGroup, flagGroup, bookGroup);

  const groups = [new Group(), chargeGroup, matchGroup, raceGroup, flagGroup, bookGroup];
  let width = 1;
  let height = 1;
  let lastSd = Number.NaN;
  let dirty = true;
  let painted = false;
  let rowState = false;
  let disposed = false;

  const resize = () => {
    width = canvas.clientWidth || window.innerWidth;
    height = canvas.clientHeight || window.innerHeight;
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
    renderer.setSize(width, height, false);
    dirty = true;
  };

  const placeCamera = (pose: Pose) => {
    const azimuth = deg(pose.az);
    const elevation = deg(pose.el);
    camera.aspect = width / Math.max(height, 1);
    camera.position.set(
      pose.tx + pose.dist * Math.cos(elevation) * Math.sin(azimuth),
      pose.ty + pose.dist * Math.sin(elevation),
      pose.tz + pose.dist * Math.cos(elevation) * Math.cos(azimuth),
    );
    camera.lookAt(pose.tx, pose.ty, pose.tz);
    camera.setViewOffset(width, height, -pose.sx * width, 0, width, height);
    camera.updateProjectionMatrix();
  };

  const apply = (sd: number) => {
    const sample = sampleMotion(sd);
    card.position.set(sample.pose.px, sample.pose.py, sample.pose.pz);
    card.rotation.set(deg(sample.pose.rx), deg(sample.pose.ry), deg(sample.pose.rz));
    card.rotation.order = "YXZ";
    card.scale.setScalar(sample.pose.scale);
    placeCamera(sample.pose);

    groups.forEach((group, beat) => {
      const fade = beatFade(beat, sample.from, sample.to, sample.u);
      const enter = beatEnter(beat, sample.from, sample.to, sample.u);
      group.visible = fade > 0.02;
      if (beat === 1) {
        const slide = smoother(clamp(enter / 0.7, 0, 1));
        note.position.x = 0.05 + 0.024 * (1 - slide);
        fadeObject(note, fade * slide);
      } else if (beat === 2) {
        const dim = smoother(clamp(enter / 0.28, 0, 1));
        const drop = smoother(clamp((enter - 0.12) / 0.32, 0, 1));
        const line = smoother(clamp((enter - 0.42) / 0.22, 0, 1));
        const chip = smoother(clamp((enter - 0.58) / 0.2, 0, 1));
        const landed = smoother(clamp((enter - 0.74) / 0.26, 0, 1));
        receipt.position.y = Y - lineY + (1 - drop) * 0.025;
        fadeObject(receipt, fade * drop);
        fadeObject(tag, fade * drop);
        candidates.forEach((mesh) => fadeObject(mesh, fade * dim));
        rule.scale.x = Math.max(line, 0.001);
        fadeObject(rule, fade * (line > 0.02 ? 1 : 0));
        score.position.y = Y - 0.03 + (1 - chip) * 0.01;
        fadeObject(score, fade * chip);
        const stampScale = 0.96 + 0.04 * landed;
        stampMesh.scale.set(stampScale, stampScale, stampScale);
        fadeObject(stampMesh, fade * landed);
      } else if (beat === 3) {
        const head = smoother(clamp(enter / 0.15, 0, 1));
        header.position.y = 0.124 + (1 - head) * 0.008;
        fadeObject(header, fade * head);
        if (rowState !== sample.rowWon) {
          rowState = sample.rowWon;
          rows.forEach((mesh, index) => {
            const material = mesh.material as MeshBasicMaterial;
            material.map = index === 6 && sample.rowWon ? winMap : loseMaps[index];
            material.needsUpdate = true;
          });
        }
        rows.forEach((mesh, index) => {
          const shown = smoother(clamp((enter - 0.12 - index * 0.05) / 0.12, 0, 1));
          mesh.position.y = 0.1 - index * 0.021 + (1 - shown) * 0.006;
          fadeObject(mesh, fade * shown);
        });
        fadeObject(ring, sample.rowWon ? fade : 0);
      } else if (beat === 4) {
        const pull = smoother(clamp((enter - 0.05) / 0.55, 0, 1));
        const chip = smoother(clamp((enter - 0.62) / 0.38, 0, 1));
        flag.position.z = -0.02 * (1 - pull);
        fadeObject(flag, fade * pull);
        fadeObject(duplicate, fade * smoother(clamp(enter / 0.4, 0, 1)));
        fadeObject(approved, fade * chip);
      } else if (beat === 5) {
        const settle = smoother(clamp(enter / 0.8, 0, 1));
        book.position.y = 0.06 + (1 - settle) * 0.016;
        fadeObject(book, fade * settle);
      }
    });
  };

  const update = (sd: number) => {
    if (disposed) return painted;
    if (!dirty && Math.abs(sd - lastSd) < 0.00005) return painted;
    lastSd = sd;
    dirty = false;
    apply(sd);
    renderer.render(scene, camera);
    if (!painted) {
      painted = true;
      const draws = renderer.info.render.calls;
      const triangles = renderer.info.render.triangles;
      canvas.dataset.glReady = "1";
      canvas.dataset.draws = String(draws);
      canvas.dataset.tris = String(triangles);
    }
    return painted;
  };

  const dispose = () => {
    if (disposed) return;
    disposed = true;
    scene.environment?.dispose();
    scene.traverse((object) => {
      if (!isMesh(object)) return;
      object.geometry.dispose();
      materials(object).forEach((material) => {
        const map = (material as MeshBasicMaterial).map;
        if (map) map.dispose();
        material.dispose();
      });
    });
    renderer.dispose();
    room.removeFromParent();
  };

  function receiptTexture() {
    const px = 8;
    const [el, ctx] = canvasOf(58 * px, 120 * px);
    ctx.fillStyle = C.paper;
    ctx.fillRect(0, 0, el.width, el.height);
    let y = Math.round(el.height * 0.3) + 64;
    const left = 24;
    const right = el.width - 24;
    const line = (
      leftText: string,
      rightText: string | null,
      options: { w?: number; s?: number; col?: string; c?: boolean; gap?: number; bw?: number; bs?: number },
    ) => {
      ctx.font = `${options.w ?? 500} ${options.s ?? 22}px ${mono}`;
      ctx.fillStyle = options.col ?? C.ink;
      ctx.textAlign = options.c ? "center" : "left";
      ctx.fillText(leftText, options.c ? el.width / 2 : left, y);
      if (rightText) {
        ctx.font = `${options.bw ?? options.w ?? 500} ${options.bs ?? options.s ?? 22}px ${mono}`;
        ctx.textAlign = "right";
        ctx.fillText(rightText, right, y);
      }
      y += options.gap ?? 30;
    };
    line("WOOLWORTHS", null, { s: 58, w: 800, c: true, gap: 72 });
    line("2026-01-06", null, { s: 42, w: 600, c: true, col: C.muted, gap: 80 });
    line("TAX", "4.98", { s: 44, w: 600, col: C.muted, gap: 96 });
    const base = y;
    line("AUD", "54.80", { s: 44, w: 600, bs: 72, bw: 800, gap: 34 });
    return { canvas: el, lineFrac: (base + 16) / el.height };
  }

  function stamp(label: string, color: string) {
    const [el, ctx] = canvasOf(560, 150);
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = 8;
    ctx.beginPath();
    ctx.roundRect(14, 14, 532, 122, 14);
    ctx.stroke();
    ctx.textAlign = "center";
    ctx.font = `800 76px ${mono}`;
    ctx.fillText(label, 280, 102);
    return el;
  }

  function rowTexture(index: number, win: boolean) {
    const label = `#${String(index + 1).padStart(2, "0")}`;
    const mesh = panel(0.26, 0.0185, (ctx) => {
      text(ctx, label, 28, 48, { f: mono, size: 26, col: C.fg2 });
      ctx.fillStyle = "rgba(170,179,190,0.16)";
      ctx.beginPath();
      ctx.roundRect(96, 14, 400, 46, 8);
      ctx.fill();
      text(ctx, "approve · version 0", 112, 48, { f: mono, w: 600, size: 28 });
      const color = win ? C.mint : C.coral;
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(534, 38, 8, 0, Math.PI * 2);
      ctx.fill();
      text(ctx, win ? "200 · APPROVED · version 1" : "409 · stale version, reload", 554, 48, {
        f: mono,
        w: 600,
        size: 26,
        col: color,
      });
    }, { r: 14, bg: win ? "#22332c" : "#2a2b30", line: win ? "#3f7a5e" : "#4a3634", px: 4000 });
    const map = (mesh.material as MeshBasicMaterial).map as Texture;
    mesh.geometry.dispose();
    (mesh.material as Material).dispose();
    return map;
  }

  function paper(source: Object3D, print: HTMLCanvasElement) {
    const group = new Group();
    const meshRoot = source.clone(true);
    meshRoot.position.set(0, 0, 0);
    meshRoot.rotation.set(deg(90), 0, 0);
    const printMap = texture(print, false);
    const printMaterial = new MeshStandardMaterial({
      map: printMap,
      roughness: 0.9,
      emissive: "#ffffff",
      emissiveMap: printMap,
      emissiveIntensity: 0.22,
    });
    const paperMaterial = new MeshStandardMaterial({ color: C.paper, roughness: 0.9 });
    meshRoot.traverse((object) => {
      if (!isMesh(object)) return;
      const name = Array.isArray(object.material) ? object.material[0]?.name : object.material.name;
      object.material = name && /print/.test(name) ? printMaterial : paperMaterial;
    });
    group.add(meshRoot);
    group.userData.mesh = meshRoot;
    group.userData.print = printMaterial;
    return group;
  }

  function uvY(group: Group, frac: number) {
    let best = 1e9;
    let out = 0;
    const meshRoot = group.userData.mesh as Object3D;
    const printMaterial = group.userData.print as Material;
    meshRoot.updateMatrix();
    const point = new Vector3();
    meshRoot.traverse((object) => {
      if (!isMesh(object) || object.material !== printMaterial) return;
      object.updateMatrix();
      const uv = object.geometry.attributes.uv;
      const position = object.geometry.attributes.position;
      if (!uv || !position) return;
      for (let index = 0; index < uv.count; index += 1) {
        const distance = Math.abs(uv.getY(index) - frac) + 0.2 * Math.abs(uv.getX(index) - 0.5);
        if (distance < best) {
          best = distance;
          point.fromBufferAttribute(position, index).applyMatrix4(object.matrix).applyMatrix4(meshRoot.matrix);
          out = point.y;
        }
      }
    });
    return out;
  }

  resize();
  return { update, resize, dispose };
}

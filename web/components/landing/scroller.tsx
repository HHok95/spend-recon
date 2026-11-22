"use client";

import { useEffect } from "react";
import { clamp, reveal } from "@/components/landing/motion";
import type { SceneHandle } from "@/components/landing/scene";

type Box = { top: number; height: number };

export function Scroller() {
  useEffect(() => {
    const root = document.querySelector<HTMLElement>("[data-landing]");
    if (!root) return;
    const canvas = root.querySelector<HTMLCanvasElement>("canvas");
    const sections = [...root.querySelectorAll<HTMLElement>("[data-beat]")];
    const frames = [...root.querySelectorAll<HTMLImageElement>("[data-frame]")];
    const fine = window.matchMedia("(min-width: 768px)");
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    let scene: SceneHandle | null = null;
    let disposed = false;
    let starting = false;
    let raf = 0;
    let running = false;
    let s = 0;
    let sd = 0;
    let last = 0;
    let boxes: Box[] = [];

    const measure = () => {
      const scroll = window.scrollY;
      boxes = sections.map((section) => ({
        top: section.getBoundingClientRect().top + scroll,
        height: section.offsetHeight,
      }));
    };

    const progress = () => {
      const y = window.scrollY;
      const lastIndex = boxes.length - 1;
      for (let beat = 0; beat < boxes.length; beat += 1) {
        const box = boxes[beat];
        const nextTop = beat === lastIndex ? box.top + box.height : boxes[beat + 1].top;
        if (y < nextTop || beat === lastIndex) {
          return beat + clamp((y - box.top) / Math.max(box.height, 1), 0, 1);
        }
      }
      return lastIndex;
    };

    const activeBeat = () => {
      const mark = window.scrollY + window.innerHeight * 0.35;
      let beat = 0;
      boxes.forEach((box, index) => {
        if (mark >= box.top) beat = index;
      });
      return beat;
    };

    const motion = () => fine.matches && !reduce.matches && document.documentElement.dataset.webgl !== "off";

    const applyText = (value: number) => {
      const animate = motion();
      sections.forEach((section, beat) => {
        section.querySelectorAll<HTMLElement>("[data-reveal]").forEach((node) => {
          if (!animate) {
            node.style.opacity = "";
            node.style.transform = "";
            return;
          }
          const shown = reveal(value, beat, Number(node.dataset.reveal || 0));
          node.style.opacity = shown.toFixed(3);
          node.style.transform = `translateY(${((1 - shown) * 14).toFixed(2)}px)`;
        });
      });
    };

    const showFrame = (beat: number) => {
      frames.forEach((frame) => {
        frame.dataset.on = frame.dataset.frame === String(beat) ? "1" : "0";
      });
    };

    const loop = (now: number) => {
      if (disposed) return;
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      s = progress();
      sd += (s - sd) * (1 - Math.exp(-9 * dt));
      if (Math.abs(s - sd) < 0.0004) sd = s;
      applyText(sd);
      if (scene?.update(sd)) root.classList.add("is-live");
      root.dataset.settled = Math.abs(s - sd) < 0.001 ? "1" : "0";
      if (Math.abs(s - sd) >= 0.0004) raf = window.requestAnimationFrame(loop);
      else running = false;
    };

    const kick = () => {
      if (!motion()) {
        showFrame(activeBeat());
        applyText(progress());
        root.dataset.settled = "1";
        return;
      }
      if (!running) {
        running = true;
        last = performance.now();
        raf = window.requestAnimationFrame(loop);
      }
    };

    const begin = () => {
      if (!canvas || starting || scene || disposed || !motion()) return;
      starting = true;
      void import("@/components/landing/scene")
        .then((mod) => {
          if (disposed || !motion()) return null;
          return mod.mountScene(canvas);
        })
        .then((mounted) => {
          starting = false;
          if (!mounted) return;
          if (disposed || !motion()) {
            mounted.dispose();
            return;
          }
          scene = mounted;
          scene.resize();
          kick();
        })
        .catch(() => {
          starting = false;
          document.documentElement.dataset.webgl = "off";
          showFrame(activeBeat());
          applyText(progress());
        });
    };

    const schedule = () => {
      const idle = window.requestIdleCallback;
      if (idle) idle(() => begin(), { timeout: 1500 });
      else window.setTimeout(begin, 1);
    };

    const onResize = () => {
      measure();
      if (!motion()) {
        scene?.dispose();
        scene = null;
        root.classList.remove("is-live");
        kick();
        return;
      }
      scene?.resize();
      if (!scene && document.readyState === "complete") schedule();
      kick();
    };

    measure();
    kick();
    if (document.readyState === "complete") schedule();
    else window.addEventListener("load", schedule, { once: true });
    window.addEventListener("scroll", kick, { passive: true });
    window.addEventListener("resize", onResize);
    fine.addEventListener("change", onResize);
    reduce.addEventListener("change", onResize);
    const observer = new ResizeObserver(() => {
      measure();
      kick();
    });
    observer.observe(root);

    return () => {
      disposed = true;
      window.cancelAnimationFrame(raf);
      window.removeEventListener("scroll", kick);
      window.removeEventListener("resize", onResize);
      window.removeEventListener("load", schedule);
      fine.removeEventListener("change", onResize);
      reduce.removeEventListener("change", onResize);
      observer.disconnect();
      scene?.dispose();
    };
  }, []);

  return null;
}

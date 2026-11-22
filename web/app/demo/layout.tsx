import { Geist, Geist_Mono } from "next/font/google";
import type { ReactNode } from "react";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export default function DemoLayout({ children }: { children: ReactNode }) {
  return <div className={`${geistSans.variable} ${geistMono.variable} ${geistSans.className}`}>{children}</div>;
}

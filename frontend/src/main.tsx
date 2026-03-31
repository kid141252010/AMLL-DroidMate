import React, { useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import {
  LyricPlayer,
  BackgroundRender,
  MeshGradientRenderer,
  PixiRenderer,
} from '@applemusic-like-lyrics/react'
import type { LyricPlayerRef } from '@applemusic-like-lyrics/react'
import { DomSlimLyricPlayer } from '@applemusic-like-lyrics/core'
import '@applemusic-like-lyrics/core/style.css'
import '@applemusic-like-lyrics/react-full/style.css'
import { useAtom, useSetAtom } from 'jotai'
import {
  musicLyricLinesAtom,
  musicPlayingPositionAtom,
  musicCoverAtom,
  musicPlayingAtom,
  lowFreqVolumeAtom,
} from '@applemusic-like-lyrics/react-full'
import { parseTTML } from '@applemusic-like-lyrics/ttml'

// Minimal Android-specific adaptations
const PLAYER_BACKGROUND = 'transparent'
const ACTIVE_LINE_ALIGN_ANCHOR = 'top' as const
const ACTIVE_LINE_ALIGN_POSITION = 0.3
const demoAlbumArt = 'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNDAwIiBoZWlnaHQ9IjQwMCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48cmVjdCB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiBmaWxsPSJyZ2JhKDAsMCwwLDAuMSkiLz48L3N2Zz4='
type BackgroundRendererKey = 'pixi' | 'mesh'

interface BackgroundConfig {
  renderer: BackgroundRendererKey
  fps: number
  flowSpeed: number
  renderScale: number
  staticMode: boolean
  lowFreqVolume: number
}

const DEFAULT_BACKGROUND_CONFIG: BackgroundConfig = {
  renderer: 'pixi',
  fps: 30,
  flowSpeed: 2.35,
  renderScale: 0.9,
  staticMode: false,
  lowFreqVolume: 1.0,
}

interface WordEntry {
  word: string
  startTime: number
  endTime: number
}

interface LyricLine {
  words: WordEntry[]
  translatedLyric: string
  romanLyric: string
  startTime: number
  endTime: number
  isBG: boolean
  isDuet: boolean
}

interface LyricsPayload {
  lines?: Array<{
    words?: Array<{ word?: string; startTime?: number; endTime?: number }>
    text?: string
    translatedLyric?: string
    romanLyric?: string
    startTime?: number
    endTime?: number
    isBG?: boolean
    isDuet?: boolean
  }>
}

type ParsedTtmlLyric = ReturnType<typeof parseTTML>

type LegacyTtmlLyricShape = ParsedTtmlLyric & {
  lines?: ParsedTtmlLyric['lyricLines']
}

interface RawLyricLineLike {
  words?: Array<{
    word?: unknown
    startTime?: unknown
    endTime?: unknown
  }>
  text?: unknown
  translatedLyric?: unknown
  romanLyric?: unknown
  startTime?: unknown
  endTime?: unknown
  isBG?: unknown
  isDuet?: unknown
}

interface NormalizedTtmlResult {
  normalizedLines: LyricLine[]
  parsedKeys: string[]
}

type RenderMode = 'dom' | 'dom-lite'

interface LyricMotionConfig {
  enableSpring: boolean
  enableBlur: boolean
  enableScale: boolean
  hidePassedLines: boolean
  wordFadeWidth: number
}

interface RGBColor {
  r: number
  g: number
  b: number
}

interface FlowGlowPalette {
  primary: RGBColor
  secondary: RGBColor
  shadow: RGBColor
}

const DEFAULT_MOTION_CONFIG: LyricMotionConfig = {
  enableSpring: true,
  enableBlur: true,
  enableScale: true,
  hidePassedLines: false,
  wordFadeWidth: 0.5,
}

const DEFAULT_FLOW_GLOW_PALETTE: FlowGlowPalette = {
  primary: { r: 126, g: 170, b: 255 },
  secondary: { r: 255, g: 136, b: 208 },
  shadow: { r: 88, g: 118, b: 192 },
}

let player: LyricPlayerRef | null = null
let backgroundRender: any = null
let lastAlbumArt = ''

// Global state for Android bridge
interface AMLLGlobal {
  player: any
  backgroundRender: any
  state: any
}

// Downstream override for AMLL mask variable initialization.
function applyAMLLPatch() {
  logToAndroid('Applying AMLL patch for generateFadeGradient', 'info')

  if (document.getElementById('amll-runtime-patch')) return

  // Ensure mask-related CSS variables always have safe defaults.
  const style = document.createElement('style')
  style.id = 'amll-runtime-patch'
  style.textContent = `
    /* Keep mask-image CSS variables initialized. */
    :root {
      --bright-mask-alpha: 1.0;
      --dark-mask-alpha: 0.2;
      --amll-glow-primary: 126, 170, 255;
      --amll-glow-secondary: 255, 136, 208;
      --amll-glow-shadow: 88, 118, 192;
    }

    .amll-flow-outline-layer {
      position: absolute;
      inset: clamp(10px, 2.2vw, 24px);
      border-radius: clamp(22px, 5vw, 40px);
      pointer-events: none;
      z-index: 1;
      overflow: hidden;
    }

    .amll-flow-outline-core,
    .amll-flow-outline-halo {
      position: absolute;
      inset: 0;
      border-radius: inherit;
    }

    .amll-flow-outline-core {
      padding: clamp(1.5px, 0.35vw, 3px);
      background:
        conic-gradient(
          from 0deg at 50% 50%,
          rgba(var(--amll-glow-primary), 0.03) 0deg,
          rgba(var(--amll-glow-secondary), 0.78) 76deg,
          rgba(var(--amll-glow-primary), 0.25) 168deg,
          rgba(var(--amll-glow-secondary), 0.66) 248deg,
          rgba(var(--amll-glow-primary), 0.1) 320deg,
          rgba(var(--amll-glow-primary), 0.03) 360deg
        );
      -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
      -webkit-mask-composite: xor;
      mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
      mask-composite: exclude;
      transform-origin: center;
      animation: amll-flow-outline-spin 9.5s linear infinite;
    }

    .amll-flow-outline-halo {
      inset: -12px;
      background:
        radial-gradient(circle at 24% 18%, rgba(var(--amll-glow-primary), 0.3), transparent 58%),
        radial-gradient(circle at 82% 84%, rgba(var(--amll-glow-secondary), 0.28), transparent 62%),
        radial-gradient(circle at 50% 50%, rgba(var(--amll-glow-shadow), 0.2), transparent 72%);
      filter: blur(16px) saturate(1.18);
      opacity: 0.86;
      animation: amll-flow-halo-drift 12s ease-in-out infinite alternate;
    }

    @keyframes amll-flow-outline-spin {
      to {
        transform: rotate(360deg);
      }
    }

    @keyframes amll-flow-halo-drift {
      0% {
        transform: translate3d(-2%, -1.5%, 0) scale(0.98);
        opacity: 0.78;
      }
      100% {
        transform: translate3d(2%, 1.5%, 0) scale(1.03);
        opacity: 0.95;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .amll-flow-outline-core,
      .amll-flow-outline-halo {
        animation: none !important;
      }
    }
  `
  document.head.appendChild(style)

  logToAndroid('AMLL patch applied successfully', 'debug')
}

declare global {
  interface Window {
    __amll?: AMLLGlobal
    updateLyrics?: (payload: LyricsPayload) => void
    updateTtmlLyrics?: (ttml: string) => void
    updateTime?: (timeMs: number) => void
    updateAlbumArt?: (uri: string) => Promise<void>
    setPaused?: (paused: boolean) => void
    setSeeking?: (seeking: boolean) => void
    callPlayer?: (method: string, ...args: any[]) => void
    configureLyricMotion?: (options: any) => void
    configureBackgroundEffect?: (options: any) => void
    setRenderMode?: (mode: string) => void
    Android?: {
      log?: (message: string, level: string) => void
      isPlaying?: () => boolean
      onLineClick?: (index: number, startTime: number) => void
      onFrontendReady?: () => void
    }
  }
}

function logToAndroid(message: string, level: string = 'debug') {
  if (window.Android?.log) {
    try {
      window.Android.log(message, level)
    } catch (e) {
      console.log(`[ANDROID] ${message}`)
    }
  } else {
    console.log(`[${level.toUpperCase()}] ${message}`)
  }
}

function getMonotonicTime(): number {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return performance.now()
  }
  return Date.now()
}

function toFiniteNumber(value: unknown): number | undefined {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return undefined
  return parsed
}

function parseBackgroundRenderer(value: unknown): BackgroundRendererKey | undefined {
  if (value === undefined || value === null) return undefined
  const rawValue = String(value).trim().toLowerCase()
  if (rawValue === 'pixi') return 'pixi'
  if (rawValue === 'mesh' || rawValue === 'mesh-gradient' || rawValue === 'meshgradient') {
    return 'mesh'
  }
  return undefined
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function roundRgb(value: number): number {
  return Math.trunc(clamp(value, 0, 255))
}

function toCssColorChannels(color: RGBColor): string {
  return `${roundRgb(color.r)}, ${roundRgb(color.g)}, ${roundRgb(color.b)}`
}

function rgbToHsl(color: RGBColor): { h: number; s: number; l: number } {
  const r = clamp(color.r / 255, 0, 1)
  const g = clamp(color.g / 255, 0, 1)
  const b = clamp(color.b / 255, 0, 1)
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const delta = max - min
  let h = 0
  const l = (max + min) / 2
  const s = delta === 0 ? 0 : delta / (1 - Math.abs(2 * l - 1))

  if (delta !== 0) {
    if (max === r) {
      h = ((g - b) / delta) % 6
    } else if (max === g) {
      h = (b - r) / delta + 2
    } else {
      h = (r - g) / delta + 4
    }
    h /= 6
    if (h < 0) h += 1
  }

  return { h, s: clamp(s, 0, 1), l: clamp(l, 0, 1) }
}

function hueToRgb(p: number, q: number, t: number): number {
  let normalized = t
  if (normalized < 0) normalized += 1
  if (normalized > 1) normalized -= 1
  if (normalized < 1 / 6) return p + (q - p) * 6 * normalized
  if (normalized < 1 / 2) return q
  if (normalized < 2 / 3) return p + (q - p) * (2 / 3 - normalized) * 6
  return p
}

function hslToRgb(h: number, s: number, l: number): RGBColor {
  const hue = ((h % 1) + 1) % 1
  const sat = clamp(s, 0, 1)
  const light = clamp(l, 0, 1)
  if (sat === 0) {
    const gray = roundRgb(light * 255)
    return { r: gray, g: gray, b: gray }
  }

  const q = light < 0.5 ? light * (1 + sat) : light + sat - light * sat
  const p = 2 * light - q

  return {
    r: roundRgb(hueToRgb(p, q, hue + 1 / 3) * 255),
    g: roundRgb(hueToRgb(p, q, hue) * 255),
    b: roundRgb(hueToRgb(p, q, hue - 1 / 3) * 255),
  }
}

function blendColor(from: RGBColor, to: RGBColor, ratio: number): RGBColor {
  const t = clamp(ratio, 0, 1)
  const inv = 1 - t
  return {
    r: roundRgb(from.r * inv + to.r * t),
    g: roundRgb(from.g * inv + to.g * t),
    b: roundRgb(from.b * inv + to.b * t),
  }
}

function applyBackgroundLikeToneMap(color: RGBColor): RGBColor {
  // Keep the glow palette close to AMLL background color processing.
  let r = color.r
  let g = color.g
  let b = color.b

  r = (r - 128) * 0.4 + 128
  g = (g - 128) * 0.4 + 128
  b = (b - 128) * 0.4 + 128

  const gray = r * 0.3 + g * 0.59 + b * 0.11
  r = gray * -2.0 + r * 3.0
  g = gray * -2.0 + g * 3.0
  b = gray * -2.0 + b * 3.0

  r = (r - 128) * 1.7 + 128
  g = (g - 128) * 1.7 + 128
  b = (b - 128) * 1.7 + 128

  return {
    r: roundRgb(r * 0.75),
    g: roundRgb(g * 0.75),
    b: roundRgb(b * 0.75),
  }
}

function loadImage(source: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.decoding = 'async'
    img.crossOrigin = 'anonymous'
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('Failed to load album art image'))
    img.src = source
  })
}

async function extractFlowGlowPalette(source: string): Promise<FlowGlowPalette> {
  const albumSource = String(source ?? '').trim()
  if (!albumSource) return DEFAULT_FLOW_GLOW_PALETTE

  const image = await loadImage(albumSource)
  const canvas = document.createElement('canvas')
  const sampleSize = 56
  canvas.width = sampleSize
  canvas.height = sampleSize
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) return DEFAULT_FLOW_GLOW_PALETTE

  const width = image.naturalWidth || image.width
  const height = image.naturalHeight || image.height
  if (width <= 0 || height <= 0) return DEFAULT_FLOW_GLOW_PALETTE

  ctx.clearRect(0, 0, sampleSize, sampleSize)
  ctx.drawImage(image, 0, 0, width, height, 0, 0, sampleSize, sampleSize)

  const imageData = ctx.getImageData(0, 0, sampleSize, sampleSize)
  const pixels = imageData.data
  let totalWeight = 0
  let sumR = 0
  let sumG = 0
  let sumB = 0
  let bestAccentScore = -Infinity
  let accentCandidate: RGBColor | null = null

  for (let i = 0; i < pixels.length; i += 4) {
    const alpha = pixels[i + 3] / 255
    if (alpha < 0.18) continue

    const toned = applyBackgroundLikeToneMap({
      r: pixels[i],
      g: pixels[i + 1],
      b: pixels[i + 2],
    })
    const { s, l } = rgbToHsl(toned)
    if (l < 0.08 || l > 0.95) continue

    const vibrantWeight = Math.max(0, s - 0.12) * 1.45
    const luminanceWeight = Math.max(0, 1 - Math.abs(l - 0.55) * 1.8)
    const weight = alpha * (0.22 + vibrantWeight + luminanceWeight * 0.9)

    if (weight <= 0) continue

    totalWeight += weight
    sumR += toned.r * weight
    sumG += toned.g * weight
    sumB += toned.b * weight

    const accentScore = s * 0.72 + luminanceWeight * 0.48
    if (accentScore > bestAccentScore) {
      bestAccentScore = accentScore
      accentCandidate = toned
    }
  }

  if (totalWeight <= 0.0001) return DEFAULT_FLOW_GLOW_PALETTE

  const averageColor: RGBColor = {
    r: roundRgb(sumR / totalWeight),
    g: roundRgb(sumG / totalWeight),
    b: roundRgb(sumB / totalWeight),
  }

  const primary = accentCandidate ? blendColor(averageColor, accentCandidate, 0.35) : averageColor
  const primaryHsl = rgbToHsl(primary)

  const secondary = hslToRgb(
    primaryHsl.h + 0.11 + (1 - primaryHsl.s) * 0.07,
    clamp(primaryHsl.s * 1.08 + 0.1, 0.34, 0.95),
    clamp(primaryHsl.l * 1.07 + 0.06, 0.42, 0.8),
  )

  const shadow = hslToRgb(
    primaryHsl.h - 0.08,
    clamp(primaryHsl.s * 0.72 + 0.12, 0.22, 0.88),
    clamp(primaryHsl.l * 0.48, 0.16, 0.44),
  )

  return {
    primary,
    secondary,
    shadow,
  }
}

const FALLBACK_WORD_DURATION_MS = 180
const FALLBACK_LINE_DURATION_MS = 600

function toFiniteOr(value: unknown, fallback: number): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function sanitizeLyricTimings(lines: LyricLine[], source: string): LyricLine[] {
  if (!Array.isArray(lines) || lines.length === 0) return []

  const indexed = lines
    .map((rawLine, originalIndex) => ({
      originalIndex,
      line: {
        ...rawLine,
        startTime: toFiniteOr(rawLine.startTime, 0),
        endTime: toFiniteOr(rawLine.endTime, 0),
        words: (Array.isArray(rawLine.words) ? rawLine.words : []).map((rawWord) => ({
          word: String(rawWord.word ?? ''),
          startTime: toFiniteOr(rawWord.startTime, toFiniteOr(rawLine.startTime, 0)),
          endTime: toFiniteOr(rawWord.endTime, toFiniteOr(rawLine.endTime, toFiniteOr(rawLine.startTime, 0))),
        })),
      },
    }))
    .sort((a, b) => {
      if (a.line.startTime === b.line.startTime) {
        return a.originalIndex - b.originalIndex
      }
      return a.line.startTime - b.line.startTime
    })

  indexed.forEach(({ line }, lineIndex) => {
    const nextLineStart = indexed[lineIndex + 1]?.line.startTime

    line.words.forEach((word, wordIndex) => {
      if (word.endTime > word.startTime) return

      const nextWordStart = line.words[wordIndex + 1]?.startTime
      let repairedEnd = word.startTime + FALLBACK_WORD_DURATION_MS
      if (nextWordStart !== undefined && nextWordStart > word.startTime) {
        repairedEnd = nextWordStart
      } else if (nextLineStart !== undefined && nextLineStart > word.startTime) {
        repairedEnd = nextLineStart
      }

      logToAndroid(
        `[TimingSanitizer][${source}] fixed word timing line=${lineIndex} word=${wordIndex} ${word.startTime}-${word.endTime} -> ${word.startTime}-${repairedEnd}`,
        'debug',
      )
      word.endTime = repairedEnd
    })

    if (line.words.length > 0) {
      let minStart = Number.POSITIVE_INFINITY
      let maxEnd = 0
      line.words.forEach((word) => {
        minStart = Math.min(minStart, word.startTime)
        maxEnd = Math.max(maxEnd, word.endTime)
      })
      if (Number.isFinite(minStart)) {
        line.startTime = minStart
      }
      line.endTime = maxEnd
    }

    if (line.endTime > line.startTime) return

    let repairedLineEnd = line.startTime + FALLBACK_LINE_DURATION_MS
    if (nextLineStart !== undefined && nextLineStart > line.startTime) {
      repairedLineEnd = nextLineStart
    }

    const linePreview = line.words
      .map((word) => word.word)
      .join('')
      .slice(0, 32)
    logToAndroid(
      `[TimingSanitizer][${source}] fixed line timing line=${lineIndex} ${line.startTime}-${line.endTime} -> ${line.startTime}-${repairedLineEnd}, text="${linePreview}"`,
      'debug',
    )
    line.endTime = repairedLineEnd
  })

  for (let i = 0; i < indexed.length - 1; i++) {
    const cur = indexed[i].line
    const next = indexed[i + 1].line
    if (cur.endTime <= next.startTime) continue

    const overlappedEnd = next.startTime
    logToAndroid(
      `[TimingSanitizer][${source}] trimmed overlap line=${i} ${cur.startTime}-${cur.endTime} -> ${cur.startTime}-${overlappedEnd}`,
      'debug',
    )
    cur.endTime = overlappedEnd
    if (cur.words.length > 0) {
      const lastWord = cur.words[cur.words.length - 1]
      if (lastWord.endTime > overlappedEnd) {
        lastWord.endTime = overlappedEnd
      }
    }
  }

  return indexed.map(({ line }) => line)
}

function normalizeLyricLines(lines: any[]): LyricLine[] {
  if (!Array.isArray(lines)) return []
  
  const normalized = lines.map((line) => {
    const words = line.words?.map((w: any) => ({
      word: String(w.word ?? ''),
      startTime: Number(w.startTime ?? line.startTime ?? 0),
      endTime: Number(w.endTime ?? line.endTime ?? line.startTime ?? 0),
    })) || []
    
    if (words.length === 0 && line.text) {
      words.push({
        word: line.text,
        startTime: Number(line.startTime ?? 0),
        endTime: Number(line.endTime ?? line.startTime ?? 0),
      })
    }
    
    return {
      words,
      translatedLyric: String(line.translatedLyric ?? ''),
      romanLyric: String(line.romanLyric ?? ''),
      startTime: Number(line.startTime ?? 0),
      endTime: Number(line.endTime ?? 0),
      isBG: !!line.isBG,
      isDuet: !!line.isDuet,
    }
  })
  return sanitizeLyricTimings(normalized, 'bridge')
}

function normalizeTtmlLyricLines(ttml: string): NormalizedTtmlResult {
  const parsed = parseTTML(ttml) as LegacyTtmlLyricShape
  const rawLines = Array.isArray(parsed.lyricLines)
    ? parsed.lyricLines
    : Array.isArray(parsed.lines)
      ? parsed.lines
      : []

  const normalizedLines = rawLines.map((rawLine) => {
    const line = (rawLine ?? {}) as RawLyricLineLike
    const words = Array.isArray(line.words)
      ? line.words.map((word) => ({
          word: String(word?.word ?? ''),
          startTime: Number(word?.startTime ?? line.startTime ?? 0),
          endTime: Number(word?.endTime ?? line.endTime ?? line.startTime ?? 0),
        }))
      : []

    if (words.length === 0) {
      words.push({
        word: String(line.text ?? ''),
        startTime: Number(line.startTime ?? 0),
        endTime: Number(line.endTime ?? line.startTime ?? 0),
      })
    }

    return {
      words,
      translatedLyric: String(line.translatedLyric ?? ''),
      romanLyric: String(line.romanLyric ?? ''),
      startTime: Number(line.startTime ?? 0),
      endTime: Number(line.endTime ?? 0),
      isBG: !!line.isBG,
      isDuet: !!line.isDuet,
    }
  })

  return {
    normalizedLines: sanitizeLyricTimings(normalizedLines, 'ttml'),
    parsedKeys: Object.keys(parsed as unknown as Record<string, unknown>),
  }
}

function App() {
  const playerRef = useRef<LyricPlayerRef>(null)
  const currentTimeRef = useRef(0)
  const authorityTimeRef = useRef(0)
  const authorityAtRef = useRef(0)
  const isPlayingRef = useRef(false)
  const isSeekingRef = useRef(false)
  const timeRafRef = useRef<number | null>(null)
  const [lyricLines, setLyricLines] = useAtom(musicLyricLinesAtom)
  const [currentTime, setCurrentTime] = useAtom(musicPlayingPositionAtom)
  const [albumUri, setAlbumUri] = useAtom(musicCoverAtom)
  const [musicIsPlaying, setIsPlaying] = useAtom(musicPlayingAtom)
  const [isSeeking, setIsSeeking] = useState(false)
  const [renderMode, setRenderModeState] = useState<RenderMode>('dom')
  const [backgroundConfig, setBackgroundConfig] = useState<BackgroundConfig>(DEFAULT_BACKGROUND_CONFIG)
  const [motionConfig, setMotionConfig] = useState(DEFAULT_MOTION_CONFIG)
  const [flowGlowPalette, setFlowGlowPalette] = useState<FlowGlowPalette>(DEFAULT_FLOW_GLOW_PALETTE)
  const setLowFreqVolume = useSetAtom(lowFreqVolumeAtom)
  const flowPaletteJobRef = useRef(0)

  // Initialize global state and Android bridge
  useEffect(() => {
    // Mount global references
    if (window.__amll) {
      window.__amll.player = playerRef.current
      window.__amll.backgroundRender = backgroundRender
    }


    // Global API functions
    window.updateLyrics = function (payload: LyricsPayload) {
      try {
        const rawLines = Array.isArray(payload?.lines) ? payload.lines : []
        const normalizedLines = normalizeLyricLines(rawLines)
        authorityTimeRef.current = currentTimeRef.current
        authorityAtRef.current = getMonotonicTime()
        
        logToAndroid(`updateLyrics called with ${rawLines.length} raw lines, ${normalizedLines.length} normalized`, 'debug')

        // Log the first few lines to validate normalization.
        normalizedLines.slice(0, 3).forEach((line, idx) => {
          logToAndroid(`Line ${idx}: text="${line.words.map(w => w.word).join('')}", words=${line.words.length}, startTime=${line.startTime}, endTime=${line.endTime}`, 'debug')
          line.words.slice(0, 2).forEach((word, wIdx) => {
            logToAndroid(`  Word ${wIdx}: "${word.word}" ${word.startTime}-${word.endTime}ms`, 'debug')
          })
        })

        setLyricLines(normalizedLines)
        
        logToAndroid(`Updated lyrics (${normalizedLines.length} lines)`, 'debug')
        
        // Re-apply current time after lyrics land so the player can snap to the active line.
        if (playerRef.current?.lyricPlayer && currentTimeRef.current > 0) {
          logToAndroid(`Force update LyricPlayer time to ${currentTimeRef.current} after setting lyrics`, 'info')
          playerRef.current.lyricPlayer.setIsSeeking(isSeekingRef.current)
          playerRef.current.lyricPlayer.setCurrentTime(
            Math.trunc(currentTimeRef.current),
            isSeekingRef.current,
          )
        }
      } catch (error) {
        logToAndroid(`updateLyrics error: ${(error as Error).message}`, 'error')
      }
    }

    window.updateTtmlLyrics = function (ttml: string) {
      try {
        const ttmlText = String(ttml ?? '')
        const { normalizedLines, parsedKeys } = normalizeTtmlLyricLines(ttmlText)
        authorityTimeRef.current = currentTimeRef.current
        authorityAtRef.current = getMonotonicTime()

        logToAndroid(`updateTtmlLyrics called, parsed ${normalizedLines.length} lines`, 'debug')
        if (ttmlText.trim().length > 0 && normalizedLines.length === 0) {
          const ttmlPreview = ttmlText
            .slice(0, 160)
            .replace(/\s+/g, ' ')
            .trim()
          logToAndroid(
            `TTML parse produced 0 lines. keys=[${parsedKeys.join(',')}], length=${ttmlText.length}, preview="${ttmlPreview}"`,
            'warn',
          )
        }
        normalizedLines.slice(0, 3).forEach((line, idx) => {
          logToAndroid(
            `TTML Line ${idx}: text="${line.words.map(w => w.word).join('')}", words=${line.words.length}, startTime=${line.startTime}, endTime=${line.endTime}`,
            'debug',
          )
        })

        setLyricLines(normalizedLines)
        logToAndroid(`Updated TTML lyrics (${normalizedLines.length} lines)`, 'debug')

        if (playerRef.current?.lyricPlayer && currentTimeRef.current > 0) {
          playerRef.current.lyricPlayer.setIsSeeking(isSeekingRef.current)
          playerRef.current.lyricPlayer.setCurrentTime(
            Math.trunc(currentTimeRef.current),
            isSeekingRef.current,
          )
        }
      } catch (error) {
        logToAndroid(`updateTtmlLyrics error: ${(error as Error).message}`, 'error')
      }
    }

    window.updateTime = function (timeMs: number) {
      const parsedTime = Number(timeMs)
      if (!Number.isFinite(parsedTime)) return

      authorityTimeRef.current = parsedTime
      authorityAtRef.current = getMonotonicTime()
      currentTimeRef.current = parsedTime
      setCurrentTime(parsedTime)
      const seekNow = isSeekingRef.current
      // Keep the core player in sync immediately, not just through React state.
      if (playerRef.current?.lyricPlayer) {
        playerRef.current.lyricPlayer.setIsSeeking(seekNow)
        playerRef.current.lyricPlayer.setCurrentTime(Math.trunc(parsedTime), seekNow)
      }
    }

    window.updateAlbumArt = async function (uri: string) {
      setAlbumUri(uri || demoAlbumArt)
      lastAlbumArt = uri
      logToAndroid(`Album art updated: ${uri ? 'present' : 'empty'}`, 'debug')
      
      // Also update BackgroundRender directly if available
      if (window.__amll?.backgroundRender) {
        const bgRender = window.__amll.backgroundRender
        if (bgRender.setAlbum) {
          bgRender.setAlbum(uri || '')
          logToAndroid('BackgroundRender album updated directly', 'debug')
        }
      }
    }

    window.setPaused = function (paused: boolean) {
      const nextPlaying = !paused
      isPlayingRef.current = nextPlaying
      authorityTimeRef.current = currentTimeRef.current
      authorityAtRef.current = getMonotonicTime()
      setIsPlaying(nextPlaying)
      logToAndroid(`Playback ${paused ? 'paused' : 'resumed'}`, 'debug')
    }

    window.setSeeking = function (seeking: boolean) {
      const nextSeeking = !!seeking
      isSeekingRef.current = nextSeeking
      playerRef.current?.lyricPlayer?.setIsSeeking(nextSeeking)
      setIsSeeking(nextSeeking)
      logToAndroid(`Seeking state updated: ${nextSeeking}`, 'debug')
    }

    window.callPlayer = function (method: string, ...args: any[]) {
      if (method === 'setIsSeeking') {
        window.setSeeking?.(Boolean(args[0]))
        return
      }

      const corePlayer = playerRef.current?.lyricPlayer as Record<string, any> | undefined
      const methodRef = corePlayer?.[method]
      if (typeof methodRef === 'function') {
        methodRef.apply(corePlayer, args)
      } else {
        logToAndroid(`callPlayer ignored unknown method: ${method}`, 'warn')
      }
    }

    window.configureLyricMotion = function (options: any) {
      logToAndroid(`configureLyricMotion: ${JSON.stringify(options)}`, 'debug')
      setMotionConfig((prev) => ({
        enableSpring: typeof options?.enableSpring === 'boolean' ? options.enableSpring : prev.enableSpring,
        enableBlur: typeof options?.enableBlur === 'boolean' ? options.enableBlur : prev.enableBlur,
        enableScale: typeof options?.enableScale === 'boolean' ? options.enableScale : prev.enableScale,
        hidePassedLines: typeof options?.hidePassedLines === 'boolean' ? options.hidePassedLines : prev.hidePassedLines,
        wordFadeWidth: Number.isFinite(Number(options?.wordFadeWidth))
          ? Number(options.wordFadeWidth)
          : prev.wordFadeWidth,
      }))
    }

    window.configureBackgroundEffect = function (options: any) {
      logToAndroid(`configureBackgroundEffect: ${JSON.stringify(options)}`, 'debug')
      const renderer = parseBackgroundRenderer(options?.renderer)
      const fps = toFiniteNumber(options?.fps)
      const flowSpeed = toFiniteNumber(options?.flowSpeed)
      const renderScale = toFiniteNumber(options?.renderScale)
      const lowFreqVolume = toFiniteNumber(options?.lowFreqVolume)
      const staticMode = typeof options?.staticMode === 'boolean' ? options.staticMode : undefined

      setBackgroundConfig((prev) => ({
        renderer: renderer ?? prev.renderer,
        fps: fps ?? prev.fps,
        flowSpeed: flowSpeed ?? prev.flowSpeed,
        renderScale: renderScale ?? prev.renderScale,
        staticMode: staticMode ?? prev.staticMode,
        lowFreqVolume: lowFreqVolume ?? prev.lowFreqVolume,
      }))

      if (lowFreqVolume !== undefined) {
        setLowFreqVolume(lowFreqVolume)
      }
    }

    window.setRenderMode = function (mode: string) {
      logToAndroid(`setRenderMode: ${mode}`, 'debug')
      setRenderModeState(mode === 'dom-lite' ? 'dom-lite' : 'dom')
    }

    if (window.Android?.onFrontendReady) {
      try {
        window.Android.onFrontendReady()
        logToAndroid('Frontend bridge ready', 'info')
      } catch (error) {
        logToAndroid(`Failed to notify frontend ready: ${(error as Error).message}`, 'warn')
      }
    }

    return () => {
      delete window.updateLyrics
      delete window.updateTtmlLyrics
      delete window.updateTime
      delete window.updateAlbumArt
      delete window.setPaused
      delete window.setSeeking
      delete window.callPlayer
      delete window.configureLyricMotion
      delete window.configureBackgroundEffect
      delete window.setRenderMode
    }
  }, [setLyricLines, setCurrentTime, setAlbumUri, setIsPlaying, setLowFreqVolume])

  useEffect(() => {
    const paletteJobId = ++flowPaletteJobRef.current
    const nextAlbum = typeof albumUri === 'string' && albumUri.trim().length > 0
      ? albumUri.trim()
      : demoAlbumArt
    if (!nextAlbum) {
      setFlowGlowPalette(DEFAULT_FLOW_GLOW_PALETTE)
      return
    }

    void extractFlowGlowPalette(nextAlbum)
      .then((nextPalette) => {
        if (flowPaletteJobRef.current !== paletteJobId) return
        setFlowGlowPalette(nextPalette)
      })
      .catch((error) => {
        if (flowPaletteJobRef.current !== paletteJobId) return
        setFlowGlowPalette(DEFAULT_FLOW_GLOW_PALETTE)
        logToAndroid(`Flow glow palette fallback: ${(error as Error).message}`, 'warn')
      })
  }, [albumUri])

  useEffect(() => {
    authorityTimeRef.current = currentTime
    authorityAtRef.current = getMonotonicTime()
    currentTimeRef.current = currentTime
  }, [currentTime])

  useEffect(() => {
    isPlayingRef.current = musicIsPlaying
    authorityTimeRef.current = currentTimeRef.current
    authorityAtRef.current = getMonotonicTime()
  }, [musicIsPlaying])

  useEffect(() => {
    isSeekingRef.current = isSeeking
  }, [isSeeking])

  useEffect(() => {
    const tick = () => {
      const now = getMonotonicTime()
      const base = authorityTimeRef.current
      const progressed = isPlayingRef.current ? base + (now - authorityAtRef.current) : base
      currentTimeRef.current = progressed

      if (playerRef.current?.lyricPlayer) {
        playerRef.current.lyricPlayer.setCurrentTime(Math.trunc(progressed), false)
      }

      timeRafRef.current = requestAnimationFrame(tick)
    }

    authorityAtRef.current = getMonotonicTime()
    timeRafRef.current = requestAnimationFrame(tick)

    return () => {
      if (timeRafRef.current !== null) {
        cancelAnimationFrame(timeRafRef.current)
        timeRafRef.current = null
      }
    }
  }, [])

  // Sync playing state with Android
  useEffect(() => {
    if (window.Android?.isPlaying) {
      try {
        const isPlaying = window.Android.isPlaying()
        if (isPlaying !== musicIsPlaying) {
          setIsPlaying(isPlaying)
        }
      } catch (_err) {
        // Ignore
      }
    }
  }, [musicIsPlaying, setIsPlaying])

  const handleLineClick = (event: any) => {
    try {
      const lineData = event?.line?.getLine?.()
      const startTime = Math.trunc(Number(lineData?.startTime ?? 0))
      const lineIndex = Number.isFinite(Number(event?.lineIndex))
        ? Math.trunc(Number(event.lineIndex))
        : -1
      
      if (window.Android?.onLineClick) {
        window.Android.onLineClick(lineIndex, startTime)
        logToAndroid(`Called Android.onLineClick(${lineIndex}, ${startTime})`, 'info')
      }
    } catch (error) {
      logToAndroid(`line-click handler error: ${(error as Error).message}`, 'error')
    }
  }

  const backgroundRenderer = backgroundConfig.renderer === 'mesh'
    ? MeshGradientRenderer
    : PixiRenderer

  const appStyle = {
    position: 'relative',
    width: '100%',
    height: '100vh',
    '--amll-glow-primary': toCssColorChannels(flowGlowPalette.primary),
    '--amll-glow-secondary': toCssColorChannels(flowGlowPalette.secondary),
    '--amll-glow-shadow': toCssColorChannels(flowGlowPalette.shadow),
  } as React.CSSProperties

  return (
    <div id="app" style={appStyle}>
      <BackgroundRender
        ref={(ref) => {
          if (ref?.bgRender) {
            backgroundRender = ref.bgRender
            logToAndroid('BackgroundRender instance attached', 'debug')
          }
        }}
        album={albumUri || demoAlbumArt}
        renderer={backgroundRenderer}
        fps={backgroundConfig.fps}
        playing={musicIsPlaying}
        flowSpeed={backgroundConfig.flowSpeed}
        renderScale={backgroundConfig.renderScale}
        staticMode={backgroundConfig.staticMode}
        lowFreqVolume={backgroundConfig.lowFreqVolume}
        hasLyric={lyricLines.length > 0}
        style={{ position: 'absolute', inset: 0, zIndex: 0 }}
      />

      <div className="amll-flow-outline-layer" aria-hidden="true">
        <div className="amll-flow-outline-core" />
        <div className="amll-flow-outline-halo" />
      </div>

      <LyricPlayer
        ref={playerRef}
        lyricPlayer={renderMode === 'dom-lite' ? DomSlimLyricPlayer : undefined}
        lyricLines={lyricLines}
        playing={musicIsPlaying}
        disabled={false}
        enableSpring={motionConfig.enableSpring}
        enableBlur={motionConfig.enableBlur}
        enableScale={motionConfig.enableScale}
        hidePassedLines={motionConfig.hidePassedLines}
        wordFadeWidth={motionConfig.wordFadeWidth}
        alignAnchor={ACTIVE_LINE_ALIGN_ANCHOR}
        alignPosition={ACTIVE_LINE_ALIGN_POSITION}
        linePosYSpringParams={{ mass: 0.9, damping: 15, stiffness: 90 }}
        lineScaleSpringParams={{ mass: 2, damping: 25, stiffness: 100 }}
        onLyricLineClick={handleLineClick}
        style={{
          position: 'absolute',
          inset: 0,
          zIndex: 2,
          width: '100%',
          height: '100%',
          fontFamily: 'var(--amll-lp-font-family, system-ui)',
          background: PLAYER_BACKGROUND,
        }}
      />
    </div>
  )
}

if (typeof window !== 'undefined') {
  window.addEventListener('DOMContentLoaded', () => {
    try {
      document.documentElement.style.background = 'transparent'
      document.body.style.background = 'transparent'
      
      // Apply local AMLL compatibility patch.
      applyAMLLPatch()
      
      const root = document.getElementById('app') || document.createElement('div')
      if (!document.getElementById('app')) {
        root.id = 'app'
        document.body?.appendChild(root)
      }

      if (root) {
        createRoot(root).render(<App />)
      }

      logToAndroid('AMLL WebView initialized', 'info')
    } catch (error) {
      logToAndroid(`Initialization error: ${(error as Error).message}`, 'error')
    }
  })
}

export default App

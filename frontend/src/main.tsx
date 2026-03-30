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

const DEFAULT_MOTION_CONFIG: LyricMotionConfig = {
  enableSpring: true,
  enableBlur: true,
  enableScale: true,
  hidePassedLines: false,
  wordFadeWidth: 0.5,
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
  
  // Ensure mask-related CSS variables always have safe defaults.
  const style = document.createElement('style')
  style.textContent = `
    /* Keep mask-image CSS variables initialized. */
    :root {
      --bright-mask-alpha: 1.0;
      --dark-mask-alpha: 0.2;
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

function normalizeLyricLines(lines: any[]): LyricLine[] {
  if (!Array.isArray(lines)) return []
  
  return lines.map((line) => {
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
    normalizedLines,
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
  const setLowFreqVolume = useSetAtom(lowFreqVolumeAtom)

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
          playerRef.current.lyricPlayer.setCurrentTime(
            Math.trunc(currentTimeRef.current),
            isSeekingRef.current,
          )
              
          // Trigger one extra layout tick so mask-based word highlighting recalculates.
          setTimeout(() => {
            if (playerRef.current?.lyricPlayer) {
              logToAndroid('Triggering mask-image recalculation', 'debug')
              playerRef.current.lyricPlayer.setCurrentTime(Math.trunc(currentTimeRef.current), true)
            }
          }, 100)
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
      // Keep the core player in sync immediately, not just through React state.
      if (playerRef.current?.lyricPlayer) {
        playerRef.current.lyricPlayer.setCurrentTime(Math.trunc(parsedTime), isSeekingRef.current)
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
        playerRef.current.lyricPlayer.setCurrentTime(Math.trunc(progressed), isSeekingRef.current)
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

  return (
    <div id="app" style={{ position: 'relative', width: '100%', height: '100vh' }}>
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

      <LyricPlayer
        ref={playerRef}
        lyricPlayer={renderMode === 'dom-lite' ? DomSlimLyricPlayer : undefined}
        lyricLines={lyricLines}
        currentTime={currentTime}
        playing={musicIsPlaying}
        isSeeking={isSeeking}
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
          zIndex: 1,
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
